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
import org.apache.hadoop.io.LongWritable
import org.apache.spark.{SparkConf, SparkContext, SparkEnv}
import org.apache.spark.metrics.source.Metrics
import org.apache.spark.sql.{Dataset, Encoder, Encoders, SaveMode, SparkSession}
import org.apache.spark.util.LongAccumulator
import com.amazonaws.regions.Regions
import com.twitter.elephantbird.mapreduce.output.LzoThriftBlockOutputFormat
import com.twitter.elephantbird.mapreduce.io.ThriftWritable
import jp.co.bizreach.kinesis.recordsMaxCount
import jp.co.bizreach.kinesis.spark.{DefaultCredentials, KinesisRDDWriter, SparkAWSCredentials}
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.snowplowanalytics.snowplow.badrows._
import config._
import util.paths._
import util.base64
import domain._
import kinesis._
import cats.effect.SyncIO

object RecoveryJob extends RecoveryJob

trait RecoveryJob {

  /** Spark job running the event recovery process on AWS. It will:
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
    * @param region
    *   Kinesis deployment region
    * @param batchSize
    *   size of event batches sent to Kinesis
    * @param cfg
    *   configuration object containing mappings and recovery flow configurations
    * @param directoryOutput
    *   optionally output successful recoveries into a file
    * @param cloudwatch
    *   a CloudWatch reporter instance. Relies upon specific type because we need to do impure things in Spark runtime
    */
  def run(
    input: String,
    output: Option[String],
    failedOutput: String,
    unrecoverableOutput: String,
    directoryOutput: Option[String],
    region: Regions,
    batchSize: Int,
    cfg: Config,
    cloudwatch: Cloudwatch[SyncIO]
  ): Unit = {
    implicit val spark: SparkSession = init()

    val metrics = new Metrics()
    SparkEnv.get.metricsSystem.registerSource(metrics)

    implicit val resultE: Encoder[Result]                      = Encoders.kryo
    implicit val stringResultE: Encoder[(Array[Byte], Result)] = Encoders.kryo

    import spark.implicits._
    val recovered: Dataset[(Array[Byte], Result)] = load(input).map(execute(cfg)).map {
      case Right(r) =>
        (r, Recovered)
      case e @ Left(RecoveryError(UnrecoverableBadRowType(_), _, _)) =>
        (e.left.get.json.getBytes, Unrecoverable)
      case Left(e) =>
        (e.json.getBytes, Failed)
    }

    val summary =
      sink(
        output,
        failedOutput,
        unrecoverableOutput,
        directoryOutput,
        region,
        batchSize,
        recovered,
        new Summary(spark.sparkContext),
        spark
      )

    metrics.recovered.inc(summary.successful.value)
    metrics.unrecoverable.inc(summary.unrecoverable.value)
    metrics.failed.inc(summary.failed.value)

    (for {
      r1 <- cloudwatch.report(summary.successful.name.getOrElse("recovered"), summary.successful.value)
      r2 <- cloudwatch.report(summary.unrecoverable.name.getOrElse("failed"), summary.unrecoverable.value)
      r3 <- cloudwatch.report(summary.failed.name.getOrElse("failed"), summary.failed.value)
    } yield (r1, r2, r3)).use(SyncIO.pure).attempt.unsafeRunSync() match {
      case Left(err) => println(s"Couldn't report metric values. Error: ${err.getMessage()}")
      case Right(_)  => println("Metric values successfully reported.")
    }

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
    output: Option[String],
    failedOutput: String,
    unrecoverableOutput: String,
    directoryOutput: Option[String],
    region: Regions,
    batchSize: Int,
    v: Dataset[(Array[Byte], Result)],
    summary: Summary,
    spark: SparkSession
  )(implicit
    encoder: Encoder[Array[Byte]],
    resEncoder: Encoder[(Array[Byte], Result)],
    strEncoder: Encoder[String]
  ): Summary = {
    val successful    = v.filter(_._2 == Recovered).map(_._1)
    val unrecoverable = v.filter(_._2 == Unrecoverable).map(_._1)
    val failed        = v.filter(_._2 == Failed).map(_._1)

    if (output.isDefined) {
      successful
        .map { x =>
          summary.successful.add(1)
          x
        }
        .rdd
        .sinkToKinesis(streamName = output.get, region = region, chunk = batchSize)

    }

    if (directoryOutput.isDefined) {
      LzoThriftBlockOutputFormat.setClassConf(classOf[CollectorPayload], spark.sparkContext.hadoopConfiguration)
      successful
        .rdd
        .map { x =>
          util
            .thrift
            .deser(x)
            .map { cp =>
              if (!output.isDefined) {
                summary.successful.add(1)
              }
              val thriftWritable = ThriftWritable.newInstance(classOf[CollectorPayload])
              thriftWritable.set(cp)
              new LongWritable(0L) -> thriftWritable
            }
            .toOption
        }
        .filter(_.isDefined)
        .map(_.get)
        .saveAsNewAPIHadoopFile(
          directoryOutput.get,
          classOf[LongWritable],
          classOf[ThriftWritable[CollectorPayload]],
          classOf[LzoThriftBlockOutputFormat[CollectorPayload]],
          spark.sparkContext.hadoopConfiguration
        )
    }

    if (!failed.isEmpty) {
      failed
        .map { x =>
          summary.failed.add(1)
          base64.byteToString(x)
        }
        .write
        .mode(SaveMode.Append)
        .text(path(failedOutput, Schemas.RecoveryError))
    }

    if (!unrecoverable.isEmpty) {
      unrecoverable
        .map { x =>
          summary.unrecoverable.add(1)
          base64.byteToString(x)
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

  override def toString() =
    s"SUMMARY | RECOVERED: ${successful.value} | FAILED : ${failed.value} | UNRECOVERABLE: ${unrecoverable.value}"
}

import org.apache.spark.rdd.RDD
import org.json4s.{DefaultFormats, Formats}
object kinesis {
  implicit class KinesisRDD[A <: AnyRef](rdd: RDD[A]) {
    def sinkToKinesis(
      streamName: String,
      region: Regions,
      credentials: SparkAWSCredentials = DefaultCredentials,
      chunk: Int = recordsMaxCount,
      endpoint: Option[String] = None
    ): Unit =
      if (!rdd.isEmpty)
        rdd
          .sparkContext
          .runJob(rdd, new PlainKinesisRDDWriter(streamName, region, credentials, chunk, endpoint).write _)
  }
  class PlainKinesisRDDWriter[A <: AnyRef](
    streamName: String,
    region: Regions,
    credentials: SparkAWSCredentials,
    chunk: Int,
    endpoint: Option[String]
  ) extends KinesisRDDWriter[A](streamName, region, credentials, chunk, endpoint) {

    override def serialize(a: A)(implicit formats: Formats = DefaultFormats): Array[Byte] =
      a.asInstanceOf[Array[Byte]]

  }

}
