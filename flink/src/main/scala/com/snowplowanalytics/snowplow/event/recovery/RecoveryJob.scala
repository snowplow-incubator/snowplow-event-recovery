/*
 * Copyright (c) 2018-2020 Snowplow Analytics Ltd. All rights reserved.
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
import org.apache.flink.api.common.serialization.{SerializationSchema, SimpleStringEncoder}
import org.apache.flink.streaming.api.scala.{DataStream, OutputTag, StreamExecutionEnvironment}
import org.apache.flink.streaming.connectors.kinesis._
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink
import io.circe.syntax._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.circe.implicits._
import config._
import domain._
import typeinfo._
import util.paths._

object RecoveryJob {

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
    */
  def run(
    input: String,
    output: String,
    failedOutput: String,
    unrecoverableOutput: String,
    cfg: Config
  ): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val kinesis = {
      val producer =
        new FlinkKinesisProducer[Array[Byte]](BytesSerializationSchema, new Properties())
      producer.setFailOnError(true)
      producer.setDefaultStream(output)
      producer.setDefaultPartition("0")
      producer
    }

    val failed        = OutputTag[RecoveryError]("failed")
    val unrecoverable = OutputTag[RecoveryError]("unrecoverable")
    def lines =
      env.readFileStream(s"s3://$input").map(execute(cfg)(_)).process(new SplitByStatus(failed, unrecoverable))

    lines.addSink(kinesis)
    sinkErr(lines, failed, failedOutput)
    sinkErr(lines, unrecoverable, unrecoverableOutput)

    env.execute("Event recovery job started.")
    ()
  }

  private[this] def sinkErr(lines: DataStream[Array[Byte]], sideTag: OutputTag[RecoveryError], output: String) =
    Either
      .catchNonFatal(
        lines.getSideOutput(sideTag).map(_.badRow.selfDescribingData.asJson.noSpaces).addSink(file(output))
      )
      .leftMap { err =>
        log.warn(s"Unable to write side output ${sideTag}.", err)
      }

  private[this] def file(filePath: String) =
    StreamingFileSink
      .forRowFormat(
        new Path(
          path(filePath, Schemas.RecoveryError)
        ),
        new SimpleStringEncoder[String]("UTF-8")
      )
      .build()

  private[this] val log = LoggerFactory.getLogger(getClass())
}

object BytesSerializationSchema extends SerializationSchema[Array[Byte]] {
  override def serialize(element: Array[Byte]): Array[Byte] =
    element
}
