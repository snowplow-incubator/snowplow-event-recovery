/*
 * Copyright (c) 2018-2018 Snowplow Analytics Ltd. All rights reserved.
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
import com.hadoop.compression.lzo.{LzoCodec, LzopCodec}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{Dataset, Encoder, Encoders, SaveMode, SparkSession}
import com.amazonaws.regions.Regions
import jp.co.bizreach.kinesis.spark._
import com.snowplowanalytics.snowplow.badrows._
import config._
import util.paths._
import domain._

object RecoveryJob {

  /**
    * Spark job running the event recovery process on AWS.
    * It will:
    * - read the input data from an S3 location
    * - decode the bad row jsons
    * - filter out those that are not covered by the specified recovery scenarios
    * - mutate the collector payloads contained in the concerned bad rows according to the specified
    * recovery scenarios
    * - write out the fixed payloads to PubSub
    * @param input S3 location to read the bad rows from
    * @param output Kinesis stream to write the fixed collector payloads to
    *  @param region Kinesis deployment region
    * @param recoveryScenarios list of recovery scenarios to apply on the bad rows
    */
  def run(
      input: String,
      output: String,
      failedOutput: String,
      unrecoverableOutput: String,
      region: Regions,
      cfg: Config
  ): Unit = {
    implicit val spark: SparkSession = init()
    import spark.implicits._

    val recovered: Dataset[Either[RecoveryError, String]] = spark.read
      .textFile(input)
      .map(execute(cfg))

    partition(recovered)
      .foreach {
        case (k, v) =>
          sink(output, failedOutput, unrecoverableOutput, region)(k, v)
      }
  }

  implicit val stringE: Encoder[String] = Encoders.kryo

  private[this] def init(): SparkSession = {
    val conf = new SparkConf()
      .setIfMissing("spark.master", "local[*]")
      .setAppName("recovery")
    val session = SparkSession
      .builder()
      .config(conf)
      .getOrCreate()
    val sparkContext = session.sparkContext
    sparkContext.setLogLevel("WARN")
    val hadoopConfiguration = sparkContext.hadoopConfiguration
    hadoopConfiguration.set(
      "io.compression.codecs",
      classOf[LzopCodec].getName
    )
    hadoopConfiguration.set(
      "io.compression.codec.lzo.class",
      classOf[LzoCodec].getName
    )
    session
  }

  private[this] def sink(
      output: String,
      failedOutput: String,
      unrecoverableOutput: String,
      region: Regions
  )(k: Result, v: Dataset[Either[RecoveryError, String]]) = k match {
    case Recovered =>
      v.map(_.right.get)
        .rdd
        .saveToKinesis(streamName = output, region = region)
    case Failed =>
      v.map(_.left.get.json)
        .write
        .mode(SaveMode.Overwrite)
        .text(path(failedOutput, Schemas.RecoveryError, JClock.systemUTC))
    case Unrecoverable =>
      v.map(_.left.get.json)
        .write
        .mode(SaveMode.Overwrite)
        .text(
          path(unrecoverableOutput, Schemas.RecoveryError, JClock.systemUTC)
        )
  }

  private[this] def partition(dataset: Dataset[Either[RecoveryError, String]]) =
    Result.partitions
      .foldLeft(Map.empty[Result, Dataset[Either[RecoveryError, String]]]) {
        (acc, cur) =>
          acc ++ Map(cur -> dataset.filter(Result.byKey(_) == cur))
      }
}