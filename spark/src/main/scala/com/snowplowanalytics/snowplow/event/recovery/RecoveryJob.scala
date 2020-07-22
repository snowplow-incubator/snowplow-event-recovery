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

import com.hadoop.compression.lzo.{LzoCodec, LzopCodec}
import org.apache.spark.{SparkConf, SparkContext, SparkEnv}
import org.apache.spark.metrics.source.Metrics
import org.apache.spark.sql.{Dataset, Encoder, Encoders, SaveMode, SparkSession}
import org.apache.spark.util.LongAccumulator
import com.amazonaws.regions.Regions
import jp.co.bizreach.kinesis.spark._
import com.snowplowanalytics.snowplow.badrows._
import config._
import util.paths._
import util.base64.byteToString
import domain._

object RecoveryJob extends RecoveryJob

trait RecoveryJob {

  /**
    * Spark job running the event recovery process on AWS.
    * It will:
    * - read the input data from an S3 location
    * - decode the bad row jsons
    * - mutate the collector payloads contained in the concerned bad rows according to the specified
    * recovery scenarios
    * - write out the fixed payloads to Kinesis
    * - write failed recoveries to an S3 location
    * - write unrecoverable bad rows to an S3 location
    * @param input S3 location to read the bad rows from
    * @param output Kinesis stream to write the fixed collector payloads to
    * @param failedOutput S3 location to write the failed recoveries
    * @param unrecoverableOutput S3 location to write unrecoverble bad rows
    * @param region Kinesis deployment region
    * @param batchSize size of event batches sent to Kinesis
    * @param cfg configuration object containing mappings and recovery flow configurations
    * @param debugOutput optionally output successful recoveries into a file
    */
  def run(
    input: String,
    output: String,
    failedOutput: String,
    unrecoverableOutput: String,
    debugOutput: Option[String],
    region: Regions,
    batchSize: Int,
    cfg: Config,
  ): Unit = {
    implicit val spark: SparkSession = init()

    val metrics = new Metrics()
    SparkEnv.get.metricsSystem.registerSource(metrics)

    implicit val resultE: Encoder[Result] = Encoders.kryo
    implicit val stringResultE: Encoder[(String, Result)] = Encoders.kryo

    import spark.implicits._
    val recovered: Dataset[(String, Result)] = load(input).map(execute(cfg)) map {
      case Right(r) =>
        (byteToString(r), Recovered)
      case e @ Left(RecoveryError(UnrecoverableBadRowType(_), _, _)) =>
        (e.left.get.json, Unrecoverable)
      case Left(e) =>
        (e.json, Failed)
    }

    val summary =
      sink(output, failedOutput, unrecoverableOutput, debugOutput, region, batchSize, recovered, new Summary(spark.sparkContext))

    metrics.recovered.inc(summary.successful.value)
    metrics.unrecoverable.inc(summary.unrecoverable.value)
    metrics.failed.inc(summary.failed.value)

    println(summary)

    SparkEnv.get.metricsSystem.report
  }

  def load(input: String)(implicit spark: SparkSession): Dataset[String] = spark.read.textFile(input)

  private[this] def init(): SparkSession = {
    val conf = new SparkConf().setIfMissing("spark.master", "local[*]").setAppName("recovery")
    val session = SparkSession
      .builder()
      .config(conf)
      .config("*.source.metrics.class", "org.apache.spark.metrics.source.Metrics")
      .config("spark.metrics.namespace", "event-recovery")
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

  def sink(
    output: String,
    failedOutput: String,
    unrecoverableOutput: String,
    debugOutput: Option[String],
    region: Regions,
    batchSize: Int,
    v: Dataset[(String, Result)],
    summary: Summary
  )(implicit encoder: Encoder[String], resEncoder: Encoder[(String, Result)]): Summary = {
    val successful    = v.filter(_._2 == Recovered).map(_._1)
    val unrecoverable = v.filter(_._2 == Unrecoverable).map(_._1)
    val failed        = v.filter(_._2 == Failed).map(_._1)

    successful
      .map { x =>
        summary.successful.add(1)
        x
      }
      .rdd
      .saveToKinesis(streamName = output, region = region, chunk = batchSize)

    if (debugOutput.isDefined) {
      successful.write.mode(SaveMode.Append).text(debugOutput.get)
    }

    if (!failed.isEmpty) {
      failed
        .map { x =>
          summary.failed.add(1)
          x
        }
        .write
        .mode(SaveMode.Append)
        .text(path(failedOutput, Schemas.RecoveryError))
    }

    if (!unrecoverable.isEmpty) {
      unrecoverable
        .map { x =>
          summary.unrecoverable.add(1)
          x
        }
        .write
        .mode(SaveMode.Append)
        .text(path(unrecoverableOutput, Schemas.RecoveryError))
    }

    summary
  }
}

case class Summary(successful: LongAccumulator, unrecoverable: LongAccumulator, failed: LongAccumulator) {
  def this(sc: SparkContext) =
    this(sc.longAccumulator("recovered"), sc.longAccumulator("unrecoverable"), sc.longAccumulator("failed"))
}
