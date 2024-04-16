package com.snowplowanalytics.snowplow.event.recovery

import cats._, cats.implicits._
import io.circe._, io.circe.syntax._, io.circe.parser._

import com.snowplowanalytics.snowplow.event.recovery._, config._, json._
import com.snowplowanalytics.snowplow.badrows.{BadRow, Processor}
import java.util.concurrent.TimeUnit
import cats.effect.Clock

import mainargs.{Flag, ParserForMethods, arg}
import os./
import pprint.pprintln
import org.joda.time.DateTime

import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.ThriftLoader
import com.snowplowanalytics.snowplow.enrich.common.utils.BlockerF
import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload
import com.snowplowanalytics.snowplow.event.recovery.domain.RecoveryError

import data._

object Main {
// disable downstream library logging
  org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.OFF)

  @mainargs.main
  def validate(
    @arg(short = 'c', doc = "Absolute path for config file to validate")
    config: String,
    @arg(short = 'r', doc = "Absolute path for resolver config file")
    resolver: Option[String] = None
  ) = {
    val cfg         = os.read(os.Path(config))
    val resolverCfg = resolver.getOrElse(data.resolverConfig)
    validateSchema[Id](cfg, resolverCfg)
      .value
      .bimap(err => pprintln(s"ERROR! Invalid config\n\n$err"), _ => pprintln("OK! Config valid"))
  }

  @mainargs.main
  def run(
    @arg(short = 'i', doc = "Absolute path for bad events files directory")
    input: String,
    @arg(short = 'g', doc = "Bad event filename extension (default: txt)")
    glob: String = "txt",
    @arg(short = 'c', doc = "Absolute path for config file to validate")
    config: String,
    @arg(short = 'r', doc = "Absolute path for resolver config file")
    resolver: Option[String] = None,
    @arg(short = 'o', doc = "Abslute path for recovery output directory")
    output: Option[String] = None,
    @arg(short = 'e', doc = "Whether to run the bad rows through enrichment process")
    enrich: Boolean = false
  ) = {
    val cfg         = os.read(os.Path(config))
    val resolverCfg = resolver.getOrElse(data.resolverConfig)
    val inputLines =
      os.walk(os.Path(input)).filter(os.isFile(_, followLinks = false)).filter(_.ext == glob).flatMap(os.read.lines)

    val enrichmentsConfig =
      """{"schema": "iglu:com.snowplowanalytics.snowplow/enrichments/jsonschema/1-0-0", "data": []}"""

    val client = Client
      .parseDefault[Id](parse(resolverConfig).right.get)
      .leftMap(_.toString)
      .value
      .fold(
        e => throw new RuntimeException(e),
        r => r
      )

    val enrichmentsRes = EnrichmentRegistry.parse[Id](
      parse(enrichmentsConfig).right.get,
      client,
      true
    )
    val enrichments     = enrichmentsRes.toEither.right.get
    val registry        = EnrichmentRegistry.build[Id](enrichments, BlockerF.noop[Id]).value.right.get
    val adapterRegistry = new AdapterRegistry()

    import cats.syntax.validated._
    val conf = load(cfg)
    if (conf.isLeft) {
      pprintln(s"ERROR! Invalid config: " + conf)
      System.exit(1)
    }
    val res =
      inputLines
        .toList
        .map(execute(conf.right.get)(_))
        .map(_.leftMap(_.badRow))
        .map(_.flatMap(ThriftLoader.toCollectorPayload(_, Processor("recovery-cli", "0.0.0")).toEither.leftMap(_.head)))
        .flatMap { e =>
          EtlPipeline
            .processEvents[Id](
              adapterRegistry,
              registry,
              client,
              Processor("recovery-cli", "0.0.0"),
              new DateTime(1500000000L),
              e.toValidatedNel,
              EtlPipeline.FeatureFlags(acceptInvalid = true, legacyEnrichmentOrder = true),
              ()
            )
            .map(_.toEither)
        }

    output.foreach { o =>
      val good = res.filter(_.isRight).map(_.right.get)
      val bad  = res.filter(_.isLeft).map(_.left.get).map(_.asInstanceOf[BadRow].compact)
      os.write.over(os.Path(o) / "good.txt", good.mkString("\n"))
      os.write.over(os.Path(o) / "bad.txt", bad.mkString("\n"))
    }

    println(s"Total Lines: ${inputLines.size}, Recovered: ${res.filter(_.isRight).size}")
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}

object data {
  val resolverConfig =
    """{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":0,"repositories":[{"name": "Iglu Central","priority": 0,"vendorPrefixes": [ "com.snowplowanalytics" ],"connection": {"http":{"uri":"http://iglucentral.com"}}}]}}"""

  implicit val idClock: Clock[Id] = new Clock[Id] {
    final def realTime(unit: TimeUnit): Id[Long] =
      unit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS)

    final def monotonic(unit: TimeUnit): Id[Long] =
      unit.convert(System.nanoTime(), TimeUnit.NANOSECONDS)
  }
}
