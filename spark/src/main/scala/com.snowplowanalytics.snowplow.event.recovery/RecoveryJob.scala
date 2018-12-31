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

import cats.syntax.apply._
import cats.syntax.either._
import com.hadoop.compression.lzo.{LzoCodec, LzopCodec}
import com.monovore.decline._
import com.twitter.elephantbird.mapreduce.output.LzoThriftBlockOutputFormat
import com.twitter.elephantbird.mapreduce.io.ThriftWritable
import frameless.TypedDataset
import frameless.syntax._
import org.apache.hadoop.io.LongWritable
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import ste.StructTypeEncoder
import ste.StructTypeEncoder._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import model._

/** Entry point for the Spark recovery job */
object Main extends CommandApp(
  name = "snowplow-event-recovery-job",
  header = "Snowplow event recovery job",
  main = {
    val input = Opts.option[String]("input", help = "Input S3 path")
    val output = Opts.option[String]("output", help = "Output S3 path")
    val recoveryScenarios = Opts.option[String](
      "config",
      help = "Base64 config with schema com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0"
    ).mapValidated(utils.decodeBase64(_).toValidatedNel)
      .mapValidated(json => utils.validateConfiguration(json).toValidatedNel.map(_ => json))
      .mapValidated(utils.parseRecoveryScenarios(_).toValidatedNel)
    (input, output, recoveryScenarios).mapN { (i, o, rss) => RecoveryJob.run(i, o, rss) }
  }
)

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
   * @param output S3 location to write the fixed collector payloads to
   * @param recoveryScenarios list of recovery scenarios to apply on the bad rows
   */
  def run(input: String, output: String, recoveryScenarios: List[RecoveryScenario]): Unit = {
    implicit val spark = {
      val conf = new SparkConf()
        .setIfMissing("spark.master", "local[*]")
        .setAppName("recovery")
      val session = SparkSession.builder()
        .config(conf)
        .getOrCreate()
      val sparkContext = session.sparkContext
      sparkContext.setLogLevel("WARN")
      val hadoopConfiguration = sparkContext.hadoopConfiguration
      hadoopConfiguration.set("io.compression.codecs", classOf[LzopCodec].getName)
      hadoopConfiguration.set("io.compression.codec.lzo.class", classOf[LzoCodec].getName)
      session
    }
    import spark.implicits._

    val badRows = spark
      .read
      .schema(StructTypeEncoder[BadRow].encode)
      .json(input)
      .as[BadRow]
      .typed

    val filteredBadRows = filter(badRows, recoveryScenarios)

    val mutated = filteredBadRows.rdd.map(_.mutateCollectorPayload(recoveryScenarios))

    LzoThriftBlockOutputFormat
      .setClassConf(classOf[CollectorPayload], spark.sparkContext.hadoopConfiguration)
    mutated
      .map { cp =>
        val thriftWritable = ThriftWritable.newInstance(classOf[CollectorPayload])
        thriftWritable.set(cp)
        new LongWritable(0L) -> thriftWritable
      }
      .saveAsNewAPIHadoopFile(
        output,
        classOf[LongWritable],
        classOf[ThriftWritable[CollectorPayload]],
        classOf[LzoThriftBlockOutputFormat[CollectorPayload]],
        spark.sparkContext.hadoopConfiguration
      )

    spark.stop()
  }

  /**
   * Filter a [[TypedDataset]] of [[BadRow]]s according to the specified [[RecoveryScenario]]s.
   * @param badRows incoming [[TypedDataset]] of [[BadRow]]s to filter
   * @param recoveryScenarios to filter with
   * @return a filtered [[TypedDataset]] of [[BadRow]]s
   */
  def filter(
    badRows: TypedDataset[BadRow],
    recoveryScenarios: List[RecoveryScenario]
  ): TypedDataset[BadRow] = {
    val filterUdf = badRows.makeUDF { errors: List[Error] =>
      recoveryScenarios
        .map(_.filter(errors))
        .fold(false)(_ || _)
    }
    badRows.filter(filterUdf(badRows('errors)))
  }
}
