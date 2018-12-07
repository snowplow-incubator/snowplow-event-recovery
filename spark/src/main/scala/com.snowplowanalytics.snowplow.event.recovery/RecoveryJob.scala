package com.snowplowanalytics.snowplow.event.recovery

import java.util.Base64

import cats.data.{NonEmptyList, Validated}
import cats.syntax.apply._
import cats.syntax.either._
import com.hadoop.compression.lzo.{LzoCodec, LzopCodec}
import com.monovore.decline._
import com.twitter.elephantbird.mapreduce.output.LzoThriftBlockOutputFormat
import com.twitter.elephantbird.mapreduce.io.ThriftWritable
import frameless.TypedDataset
import frameless.functions.lit
import frameless.syntax._
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import io.circe.parser._
import org.apache.hadoop.io.LongWritable
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import ste.StructTypeEncoder
import ste.StructTypeEncoder._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import model._

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
      .mapValidated(utils.parseRecoveryScenarios(_).toValidatedNel)
    (input, output, recoveryScenarios).mapN { (i, o, rss) => RecoveryJob.run(i, o, rss) }
  }
)

object RecoveryJob {
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
