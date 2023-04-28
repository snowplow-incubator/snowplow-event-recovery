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
import org.slf4j.LoggerFactory
import cats.syntax.either._
import org.apache.flink.core.fs.Path
import org.apache.flink.api.scala._
import org.apache.flink.api.common.serialization.SerializationSchema
import org.apache.flink.streaming.api.scala.{DataStream, OutputTag, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup
import org.apache.flink.connector.kinesis.sink.KinesisStreamsSink
import org.apache.flink.connector.file.sink.FileSink
import io.circe.syntax._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.circe.implicits._
import config._
import domain._
import util.paths._
import org.apache.flink.connector.aws.config.AWSConfigConstants
import org.apache.flink.api.common.serialization.SimpleStringEncoder

object RecoveryJob extends RecoveryJob

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
  ): Unit = {
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

    val recovering = load(env, input).map(execute(cfg)(_)).process(new SplitByStatus(failed, unrecoverable))

    recovering.sinkTo(kinesis)

    sinkErr(recovering, failed, failedOutput)
    sinkErr(recovering, unrecoverable, unrecoverableOutput)

    kinesisConfig.checkpointing.foreach { ch =>
      env.enableCheckpointing(ch.interval, CheckpointingMode.EXACTLY_ONCE)

      val checkpointing = env.getCheckpointConfig
      checkpointing.setExternalizedCheckpointCleanup(ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION)
      checkpointing.setCheckpointStorage(ch.directory)
    }
    env.execute("event-recovery")
    ()
  }

  def load(env: StreamExecutionEnvironment, input: String): DataStream[String] =
    env.readTextFile(input, "UTF-8")

  private[this] def sinkErr(recovering: DataStream[Array[Byte]], sideTag: OutputTag[RecoveryError], output: String) =
    Either
      .catchNonFatal {
        val side: DataStream[RecoveryError] = recovering.getSideOutput[RecoveryError](sideTag)
        val res: DataStream[String]         = side.map(_.badRow.selfDescribingData.asJson.noSpaces)
        res.sinkTo(file(output))
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
final case class Checkpointing(interval: Long, directory: String)

class BytesSerializationSchema extends SerializationSchema[Array[Byte]] {
  override def serialize(element: Array[Byte]): Array[Byte] = element
}
