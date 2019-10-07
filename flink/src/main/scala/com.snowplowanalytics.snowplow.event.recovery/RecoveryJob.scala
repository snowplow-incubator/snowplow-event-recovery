/*
 * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
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

import java.time.{Clock => JClock}
import java.util.Properties
import org.apache.flink.core.fs.Path
import org.apache.flink.api.common.serialization.{
  SimpleStringEncoder,
  SimpleStringSchema
}
import org.apache.flink.streaming.api.scala.{
  OutputTag,
  StreamExecutionEnvironment
}
import org.apache.flink.streaming.connectors.kinesis._
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink
import io.circe.syntax._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.circe.instances._
import config._
import domain._
import typeinfo._
import util.paths._

object RecoveryJob {
  def run(
      input: String,
      output: String,
      unrecoveredOutput: String,
      cfg: Config
  ): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val kinesis = {
      val producer =
        new FlinkKinesisProducer[String](new SimpleStringSchema, new Properties())
      producer.setFailOnError(true)
      producer.setDefaultStream(output)
      producer.setDefaultPartition("0")
      producer
    }

    val file = StreamingFileSink
      .forRowFormat(
        new Path(
          path(unrecoveredOutput, Schemas.RecoveryError, JClock.systemUTC)
        ),
        new SimpleStringEncoder[String]("UTF-8")
      )
      .build()

    val failed = OutputTag[RecoveryError]("failed")
    val unrecoverable = OutputTag[RecoveryError]("unrecoverable")
    def lines =
      env
        .readFileStream(s"s3://$input")
        .map(execute(cfg)(_))
        .process(new SplitByStatus(failed, unrecoverable))

    lines
      .addSink(kinesis)

    lines
      .getSideOutput(failed)
      .map(_.badRow.selfDescribingData.asJson.noSpaces)
      .addSink(file)

    env.execute("Event recovery job started.")
    ()
  }
}
