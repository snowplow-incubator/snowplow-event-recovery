/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and
 * limitations there under.
 */
package com.snowplowanalytics.snowplow.event.recovery

import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import cats.syntax.either._
import org.apache.flink.api.common.{JobExecutionResult, JobID}
import org.apache.flink.core.execution.{JobClient, JobListener}
import org.apache.flink.configuration.{Configuration, RestartStrategyOptions}
import org.apache.flink.core.fs.Path
import org.apache.flink.api.scala._
import org.apache.flink.api.common.serialization.SerializationSchema
import org.apache.flink.streaming.api.scala.{DataStream, OutputTag, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup
import org.apache.flink.connector.kinesis.sink.{KinesisStreamsConfigConstants, KinesisStreamsSink}
import org.apache.flink.connector.aws.util.{AWSClientUtil, AWSGeneralUtil}
import software.amazon.awssdk.services.kinesis.{KinesisAsyncClient, KinesisAsyncClientBuilder}
import software.amazon.awssdk.services.kinesis.model.DescribeStreamSummaryRequest
import org.apache.flink.connector.file.sink.FileSink
import org.apache.flink.streaming.api.functions.sink.filesystem.OutputFileConfig
import org.apache.flink.connector.file.src.FileSource
import org.apache.flink.connector.file.src.reader.TextLineInputFormat
import org.apache.flink.connector.file.src.enumerate.{
  DefaultFileFilter,
  FileEnumerator,
  NonSplittingRecursiveEnumerator
}
import java.util.function.Predicate
import org.apache.flink.api.common.eventtime.WatermarkStrategy
import io.circe.syntax._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.circe.implicits._
import config._
import domain._
import util.paths._
import org.apache.flink.connector.aws.config.AWSConfigConstants
import org.apache.flink.api.common.serialization.SimpleStringEncoder

object RecoveryJob extends RecoveryJob {

  /** Operator name of the Kinesis sink. Its writer reports metrics as "kinesis: Writer". */
  val KinesisSinkName = "kinesis"

  /** Operator name of the sink writing recovered events to files */
  val DirectorySinkName = "directory"
}

/** Event totals of a successful run. Only produced when every recovered event was written to Kinesis.
  *
  * Each input event is counted once even if the job restarted, and `restarts` says how often it did. A run that resumed
  * from another run's checkpoint (`resumed`) reports the totals of both runs.
  */
final case class RecoverySummary(
  recovered: Long,
  failed: Long,
  unrecoverable: Long,
  restarts: Int = 0,
  resumed: Boolean = false
)

trait RecoveryJob {

  /** Flink job running the event recovery process on AWS. It will:
    *   - read the input data from an S3 location
    *   - decode the bad row jsons
    *   - mutate the collector payloads contained in the concerned bad rows according to the specified recovery
    *     scenarios
    *   - write out the fixed payloads to Kinesis
    *   - write failed recoveries to an S3 location
    *   - write unrecoverable bad rows to an S3 location
    * @param input
    *   S3 location to read the bad rows from
    * @param output
    *   Kinesis stream to write the fixed collector payloads to
    * @param failedOutput
    *   S3 location to write the failed recoveries
    * @param unrecoverableOutput
    *   S3 location to write unrecoverble bad rows
    * @param cfg
    *   configuration object containing mappings and recovery flow configurations
    * @param kinesisConfig
    *   configuration for Kinesis sink
    */
  def run(
    env: StreamExecutionEnvironment,
    input: String,
    output: Option[String],
    failedOutput: String,
    unrecoverableOutput: String,
    cfg: Config,
    kinesisConfig: KinesisConfig,
    directoryOutput: Option[String] = None
  ): RecoverySummary = {
    require(
      output.isDefined || directoryOutput.isDefined,
      "Recovered events need a Kinesis stream, a directory or both"
    )
    val destinations = (output.map(stream => s"Kinesis stream $stream").toList ++ directoryOutput).mkString(" and ")
    output.foreach(checkStream(_, kinesisConfig))

    val failed        = OutputTag[RecoveryError]("failed")
    val unrecoverable = OutputTag[RecoveryError]("unrecoverable")

    // Stateful operators get fixed uids so a run can resume from another run's checkpoint
    // The outputs may be under the input (the failed and unrecoverable defaults are), and the input is read
    // recursively: without skipping them, a rerun would recover the previous run's output again.
    val ownOutputs =
      List(failedOutput, unrecoverableOutput) ++ directoryOutput ++ kinesisConfig.checkpointing.map(_.directory)
    val recovering = load(env, input, ownOutputs)
      .map(execute(cfg)(_))
      .uid("recover")
      .process(new SplitByStatus(failed, unrecoverable))
      .uid("split-by-status")

    output.foreach { stream =>
      recovering
        .sinkTo(kinesisSink(stream, kinesisConfig))
        .name(RecoveryJob.KinesisSinkName)
        .uid(RecoveryJob.KinesisSinkName)
    }
    directoryOutput.foreach { directory =>
      recovering
        .map(RecoveredJson.fromThrift _)
        .uid("recovered-json")
        .sinkTo(jsonLines(directory))
        .name(RecoveryJob.DirectorySinkName)
        .uid(RecoveryJob.DirectorySinkName)
    }

    sinkErr(recovering, failed, failedOutput, "failed")
    sinkErr(recovering, unrecoverable, unrecoverableOutput, "unrecoverable")

    kinesisConfig.checkpointing.foreach { ch =>
      env.enableCheckpointing(ch.interval.toMillis, CheckpointingMode.EXACTLY_ONCE)

      val checkpointing = env.getCheckpointConfig
      // Kept when the job fails too, so a later run can resume from it
      checkpointing.setExternalizedCheckpointCleanup(ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION)
      checkpointing.setCheckpointStorage(ch.directory)

      // With checkpointing on, Flink restarts a failed job forever by default, so a permanent error such as a
      // missing stream would never end the run. Cap it, unless the cluster's Flink configuration sets a strategy.
      if (!env.getJavaEnv.getConfiguration.getOptional(RestartStrategyOptions.RESTART_STRATEGY).isPresent) {
        val restarts = new Configuration()
        restarts.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay")
        restarts.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, Int.box(ch.restartAttempts))
        restarts.set(
          RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY,
          java.time.Duration.ofMillis(ch.restartDelay.toMillis)
        )
        env.getJavaEnv.configure(restarts)
      }
    }

    // execute(), not executeAsync(): only execute() honours -sae (cancel the job when the client stops).
    // The listener captures the job id, to find the job's checkpoints if it fails.
    val jobId = new AtomicReference[Option[JobID]](None)
    env.registerJobListener(new JobIdListener(jobId))
    val result = Either.catchNonFatal(env.execute("event-recovery")).valueOr { err =>
      val rerun = "a rerun on the same input sends those events again."
      val resume = (kinesisConfig.checkpointing, jobId.get) match {
        case (None, _) => s"Checkpointing was off, so $rerun"
        case (Some(ch), None) =>
          s"The job was not submitted, so no checkpoint under ${ch.directory} belongs to it and $rerun"
        case (Some(ch), Some(job)) =>
          lastCheckpoint(ch.directory, job.toString) match {
            case Some(checkpoint) =>
              s"To resume from the last checkpoint, rerun on the same input with `flink run -s $checkpoint`. " +
                "Events sent after that checkpoint are sent again, so the stream may hold some of them twice."
            case None =>
              s"No checkpoint under ${ch.directory}/$job was completed, so $rerun"
          }
      }
      log.error(s"Recovery failed. Recovered events written before the failure stay in $destinations. $resume", err)
      throw err
    }

    val summary = RecoverySummary(
      recovered = accumulator(result, SplitByStatus.Recovered),
      failed = accumulator(result, SplitByStatus.Failed),
      unrecoverable = accumulator(result, SplitByStatus.Unrecoverable),
      restarts = intAccumulator(result, SplitByStatus.Attempt),
      resumed = intAccumulator(result, SplitByStatus.Resumed) > 0
    )
    // execute() only returns once the sinks have written every record they were given: the Kinesis sink retries
    // throttled records until they land and fails the job on a non-retryable error. So every recovered event is in
    // the destinations.
    // The counts are kept in checkpoints, so each input event is counted once however often the job restarted.
    log.info(
      s"Recovery finished: ${summary.recovered} recovered and written to $destinations, " +
        s"${summary.failed} failed (written to $failedOutput), " +
        s"${summary.unrecoverable} unrecoverable (written to $unrecoverableOutput)" +
        (if (summary.resumed) ". These totals include the run this one resumed from" else "") +
        (if (summary.restarts > 0) s". The job restarted ${summary.restarts} time(s) from a checkpoint" else "") +
        (if (summary.resumed || summary.restarts > 0)
           ": events sent after the checkpoint it continued from were sent again and may be in the stream twice"
         else "")
    )
    summary
  }

  /** Client properties shared by the Kinesis sink and the check of the stream before the run */
  private[this] def kinesisProperties(kinesisConfig: KinesisConfig): Properties = {
    val properties = new Properties()
    properties.put(AWSConfigConstants.AWS_REGION, kinesisConfig.region)
    kinesisConfig.credentials.foreach { case Credentials(accessKey, secretKey) =>
      properties.put(AWSConfigConstants.AWS_ACCESS_KEY_ID, accessKey)
      properties.put(AWSConfigConstants.AWS_SECRET_ACCESS_KEY, secretKey)
    }
    kinesisConfig.endpoint.foreach { endpoint =>
      properties.put(AWSConfigConstants.AWS_ENDPOINT, endpoint)
    }
    properties
  }

  /** Fails before the run if the stream cannot be described with the credentials, region and endpoint the sink will
    * use. The sink treats few request errors as fatal: rejected credentials or a denied request are retried forever, so
    * without this check such a run would never end. It cannot tell whether the credentials may write
    * (kinesis:PutRecords), only that they are accepted and can see the stream.
    */
  protected def checkStream(stream: String, kinesisConfig: KinesisConfig): Unit = {
    val properties = kinesisProperties(kinesisConfig)
    val http       = AWSGeneralUtil.createAsyncHttpClient(properties)
    try {
      val client = AWSClientUtil.createAwsAsyncClient[KinesisAsyncClient, KinesisAsyncClientBuilder](
        properties,
        http,
        KinesisAsyncClient.builder(),
        KinesisStreamsConfigConstants.BASE_KINESIS_USER_AGENT_PREFIX_FORMAT,
        KinesisStreamsConfigConstants.KINESIS_CLIENT_USER_AGENT_PREFIX
      )
      try {
        val status = client
          .describeStreamSummary(DescribeStreamSummaryRequest.builder().streamName(stream).build())
          .get(2, MINUTES)
          .streamDescriptionSummary()
          .streamStatusAsString()
        log.info(s"Kinesis stream $stream in ${kinesisConfig.region} is $status")
      } catch {
        case scala.util.control.NonFatal(err) =>
          val cause = Option(err.getCause).getOrElse(err)
          throw new IllegalStateException(
            s"Cannot describe Kinesis stream $stream in ${kinesisConfig.region} with the job's credentials, so " +
              s"the recovery did not start: $cause",
            cause
          )
      } finally client.close()
    } finally http.close()
  }

  private[this] def kinesisSink(stream: String, kinesisConfig: KinesisConfig): KinesisStreamsSink[Array[Byte]] = {
    val kinesisBuilder = KinesisStreamsSink
      .builder()
      .setKinesisClientProperties(kinesisProperties(kinesisConfig))
      .setSerializationSchema(new BytesSerializationSchema())
      .setPartitionKeyGenerator(new Partitioner())
      .setStreamName(stream)
      .setFailOnError(false)

    kinesisConfig.batchSize.foreach(kinesisBuilder.setMaxBatchSize)
    kinesisConfig.maxBuffered.foreach(kinesisBuilder.setMaxBufferedRequests)

    kinesisBuilder.build()
  }

  /** One recovered event per line as JSON, in files ending .json under hourly directories of `directory` */
  private[this] def jsonLines(directory: String): FileSink[String] =
    FileSink
      .forRowFormat(new Path(directory), new SimpleStringEncoder[String]("UTF-8"))
      .withOutputFileConfig(OutputFileConfig.builder().withPartSuffix(".json").build())
      .build()

  private[this] def accumulator(result: JobExecutionResult, name: String): Long =
    Option(result.getAccumulatorResult[java.lang.Long](name)).fold(0L)(_.longValue)

  private[this] def intAccumulator(result: JobExecutionResult, name: String): Int =
    Option(result.getAccumulatorResult[Integer](name)).fold(0)(_.intValue)

  /** The newest completed checkpoint of a job: the highest `<directory>/<job id>/chk-<n>` holding a `_metadata` file.
    * Higher ones may be unfinished. None if there is none or the directory cannot be read.
    */
  private[recovery] def lastCheckpoint(directory: String, jobId: String): Option[String] =
    Either
      .catchNonFatal {
        val jobDir = new Path(directory, jobId)
        val fs     = jobDir.getFileSystem
        Option(fs.listStatus(jobDir))
          .toList
          .flatten
          .map(_.getPath)
          .flatMap(p => CheckpointDir.unapplySeq(p.getName).flatMap(_.headOption).map(n => (n.toLong, p)))
          .filter { case (_, p) => fs.exists(new Path(p, "_metadata")) }
          .sortBy(-_._1)
          .headOption
          .map(_._2.toString)
      }
      .leftMap(err => log.warn(s"Cannot look for checkpoints under $directory/$jobId", err))
      .toOption
      .flatten

  private[this] val CheckpointDir = "chk-(\\d+)".r

  /** Reads every file under `input` once, except under the `exclude` paths. FileSource records its position within a
    * file in each checkpoint, so a restored job resumes where the checkpoint was taken. The legacy readTextFile source
    * did not let a checkpoint complete until all input had been read.
    */
  def load(env: StreamExecutionEnvironment, input: String, exclude: List[String]): DataStream[String] =
    env
      .fromSource(
        FileSource
          .forRecordStreamFormat(new TextLineInputFormat("UTF-8"), new Path(input))
          .setFileEnumerator(new ExcludingEnumerator(exclude))
          .build(),
        WatermarkStrategy.noWatermarks[String](),
        "bad rows"
      )
      .uid("bad-rows")

  private[this] def sinkErr(
    recovering: DataStream[Array[Byte]],
    sideTag: OutputTag[RecoveryError],
    output: String,
    name: String
  ) =
    recovering
      .getSideOutput[RecoveryError](sideTag)
      .map(_.badRow.selfDescribingData.asJson.noSpaces)
      .sinkTo(file(output))
      .name(name)
      .uid(name)

  private[this] def file(filePath: String) =
    FileSink
      .forRowFormat(new Path(path(filePath, Schemas.RecoveryError)), new SimpleStringEncoder[String]("UTF-8"))
      .build()

  private[this] val log = LoggerFactory.getLogger(getClass())
}

final case class KinesisConfig(
  region: String,
  endpoint: Option[String] = None,
  credentials: Option[Credentials] = None,
  batchSize: Option[Int] = None,
  maxBuffered: Option[Int] = None,
  checkpointing: Option[Checkpointing] = None
)
final case class Credentials(accessKey: String, secretKey: String)

/** Lists input files recursively like FileSource's default, skipping hidden files and anything under `exclude` */
final class ExcludingEnumerator(exclude: List[String]) extends FileEnumerator.Provider {
  override def create(): FileEnumerator = new NonSplittingRecursiveEnumerator(new ExcludingFilter(exclude))
}

final class ExcludingFilter(exclude: List[String]) extends Predicate[Path] with Serializable {
  private val hidden   = new DefaultFileFilter()
  private val prefixes = exclude.map(ExcludingFilter.normalise)

  override def test(path: Path): Boolean = {
    val p = ExcludingFilter.normalise(path.toString)
    hidden.test(path) && !prefixes.exists(prefix => p == prefix || p.startsWith(prefix + "/"))
  }
}

object ExcludingFilter {

  /** Same form for `file:/a`, `file:///a/` and `/a`, and for s3, s3a and s3n */
  private[recovery] def normalise(path: String): String = {
    val uri    = new Path(path).toUri
    val scheme = Option(uri.getScheme).map(_.replaceAll("^s3[an]$", "s3")).getOrElse("file")
    s"$scheme://${Option(uri.getAuthority).getOrElse("")}${uri.getPath.stripSuffix("/")}"
  }
}

/** Records the id of the submitted job */
final class JobIdListener(jobId: AtomicReference[Option[JobID]]) extends JobListener {
  override def onJobSubmitted(client: JobClient, err: Throwable): Unit =
    Option(client).foreach(c => jobId.set(Some(c.getJobID)))
  override def onJobExecuted(result: JobExecutionResult, err: Throwable): Unit = ()
}
final case class Checkpointing(
  interval: FiniteDuration,
  directory: String,
  restartAttempts: Int = 3,
  restartDelay: FiniteDuration = 10.seconds
)

class BytesSerializationSchema extends SerializationSchema[Array[Byte]] {
  override def serialize(element: Array[Byte]): Array[Byte] = element
}
