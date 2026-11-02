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

import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import cats.syntax.either._
import org.apache.flink.api.common.JobExecutionResult
import org.apache.flink.configuration.{Configuration, RestartStrategyOptions}
import org.apache.flink.core.fs.Path
import org.apache.flink.api.scala._
import org.apache.flink.api.common.serialization.SerializationSchema
import org.apache.flink.streaming.api.scala.{DataStream, OutputTag, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup
import org.apache.flink.connector.kinesis.sink.KinesisStreamsSink
import org.apache.flink.connector.file.sink.FileSink
import org.apache.flink.connector.file.src.FileSource
import org.apache.flink.connector.file.src.reader.TextLineInputFormat
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
}

/** Event totals of a successful run. Only produced when every recovered event was written to Kinesis.
  *
  * Each input event is counted once even if the job restarted, and `restarts` says how often it did.
  */
final case class RecoverySummary(recovered: Long, failed: Long, unrecoverable: Long, restarts: Int = 0)

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
    output: String,
    failedOutput: String,
    unrecoverableOutput: String,
    cfg: Config,
    kinesisConfig: KinesisConfig
  ): RecoverySummary = {
    val properties = new Properties()
    properties.put(AWSConfigConstants.AWS_REGION, kinesisConfig.region)
    kinesisConfig.credentials.foreach { case Credentials(accessKey, secretKey) =>
      properties.put(AWSConfigConstants.AWS_ACCESS_KEY_ID, accessKey)
      properties.put(AWSConfigConstants.AWS_SECRET_ACCESS_KEY, secretKey)
    }
    kinesisConfig.endpoint.foreach { endpoint =>
      properties.put(AWSConfigConstants.AWS_ENDPOINT, endpoint)
    }

    val kinesisBuilder = KinesisStreamsSink
      .builder()
      .setKinesisClientProperties(properties)
      .setSerializationSchema(new BytesSerializationSchema())
      .setPartitionKeyGenerator(new Partitioner())
      .setStreamName(output)
      .setFailOnError(false)

    kinesisConfig.batchSize.foreach(kinesisBuilder.setMaxBatchSize)
    kinesisConfig.maxBuffered.foreach(kinesisBuilder.setMaxBufferedRequests)

    val kinesis = kinesisBuilder.build()

    val failed        = OutputTag[RecoveryError]("failed")
    val unrecoverable = OutputTag[RecoveryError]("unrecoverable")

    // Stateful operators get fixed uids so a run can resume from another run's checkpoint
    val recovering = load(env, input)
      .map(execute(cfg)(_))
      .uid("recover")
      .process(new SplitByStatus(failed, unrecoverable))
      .uid("split-by-status")

    recovering.sinkTo(kinesis).name(RecoveryJob.KinesisSinkName).uid(RecoveryJob.KinesisSinkName)

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

    val result = Either.catchNonFatal(env.execute("event-recovery")).valueOr { err =>
      log.error(
        s"Recovery failed. Recovered events written before the failure stay in Kinesis stream $output. " +
          kinesisConfig
            .checkpointing
            .fold(
              "Checkpointing was off, so a rerun on the same input sends those events again."
            )(ch =>
              s"Checkpoints are kept under ${ch.directory}/<job id>/chk-<n>. To resume without sending them again, " +
                "rerun on the same input with `flink run -s <the highest chk-<n> directory>`."
            ),
        err
      )
      throw err
    }

    val summary = RecoverySummary(
      recovered = accumulator(result, SplitByStatus.Recovered),
      failed = accumulator(result, SplitByStatus.Failed),
      unrecoverable = accumulator(result, SplitByStatus.Unrecoverable),
      restarts = Option(result.getAccumulatorResult[Integer](SplitByStatus.Attempt)).fold(0)(_.intValue)
    )
    // execute() only returns once the Kinesis sink has written every record it was given: it retries throttled
    // records until they land and fails the job on a non-retryable error. So every recovered event is in Kinesis.
    // The counts are kept in checkpoints, so each input event is counted once however often the job restarted.
    log.info(
      s"Recovery finished: ${summary.recovered} recovered and written to Kinesis stream $output, " +
        s"${summary.failed} failed (written to $failedOutput), " +
        s"${summary.unrecoverable} unrecoverable (written to $unrecoverableOutput)" +
        (if (summary.restarts > 0)
           s". The job restarted ${summary.restarts} time(s) from a checkpoint: events sent to Kinesis after the " +
             "last checkpoint before a restart were sent again and may be in the stream twice"
         else "")
    )
    summary
  }

  private[this] def accumulator(result: JobExecutionResult, name: String): Long =
    Option(result.getAccumulatorResult[java.lang.Long](name)).fold(0L)(_.longValue)

  /** Reads every file under `input` once. FileSource records its position within a file in each checkpoint, so a
    * restored job resumes where the checkpoint was taken. The legacy readTextFile source did not let a checkpoint
    * complete until all input had been read.
    */
  def load(env: StreamExecutionEnvironment, input: String): DataStream[String] =
    env
      .fromSource(
        FileSource.forRecordStreamFormat(new TextLineInputFormat("UTF-8"), new Path(input)).build(),
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
    Either
      .catchNonFatal {
        val side: DataStream[RecoveryError] = recovering.getSideOutput[RecoveryError](sideTag)
        val res: DataStream[String]         = side.map(_.badRow.selfDescribingData.asJson.noSpaces)
        res.sinkTo(file(output)).name(name).uid(name)
      }
      .leftMap { err =>
        log.warn(s"Unable to write side output ${sideTag}.")
      }

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
final case class Checkpointing(
  interval: FiniteDuration,
  directory: String,
  restartAttempts: Int = 3,
  restartDelay: FiniteDuration = 10.seconds
)

class BytesSerializationSchema extends SerializationSchema[Array[Byte]] {
  override def serialize(element: Array[Byte]): Array[Byte] = element
}
