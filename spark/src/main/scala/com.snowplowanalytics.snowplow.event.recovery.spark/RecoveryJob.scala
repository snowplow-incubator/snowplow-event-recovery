package com.snowplowanalytics.snowplow.event.recovery
package spark

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
import org.apache.spark.sql.SparkSession
import ste.StructTypeEncoder
import ste.StructTypeEncoder._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import model._

object Main extends CommandApp(
  name = "snowplow-event-recovery-job",
  header = "Snowplow event recovery job",
  main = {
    val input = Opts.option[String]("input", help = "Input path")
    val output = Opts.option[String]("output", help = "Output path")
    val recoveryScenarios = Opts.option[String](
      "config",
      help = "Base64 config with schema com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0"
    ).mapValidated { encoded =>
      Either.catchNonFatal(new String(Base64.getDecoder.decode(encoded))).toValidated.leftMap(e =>
        NonEmptyList.one(s"Configuration is not properly base64-encoded: ${e.getMessage}"))
    }.mapValidated { decoded =>
      implicit val genConfig: Configuration =
        Configuration.default.withDiscriminator("name")
      val result = for {
        json <- parse(decoded)
        scenarios <- json.hcursor.get[List[RecoveryScenario]]("data")
      } yield scenarios
      result.toValidated.leftMap(e =>
        NonEmptyList.one(s"Configuration is not properly formatted: ${e.getMessage}"))
    }
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

    val filterUdf = badRows.makeUDF { errors: List[Error] =>
      recoveryScenarios
        .map(_.filter(errors))
        .reduce(_ || _)
    }
    val filteredBadRows = badRows.filter(filterUdf(badRows('errors)))

    LzoThriftBlockOutputFormat
      .setClassConf(classOf[CollectorPayload], spark.sparkContext.hadoopConfiguration)
    filteredBadRows
      .rdd
      .map { br =>
        (utils.thriftDeser andThen
          recoveryScenarios
            .filter(_.filter(br.errors))
            .map(_.mutate _)
            .reduce(_ andThen _)
        )(br.line)
      }
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
}
