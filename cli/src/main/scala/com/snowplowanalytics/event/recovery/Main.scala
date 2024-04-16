package com.snowplowanalytics.snowplow.event.recovery

import cats._
import cats.effect.Clock
import cats.implicits._
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.snowplow.badrows.BadRow
import com.snowplowanalytics.snowplow.badrows.Processor
import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload
import com.snowplowanalytics.snowplow.enrich.common.loaders.ThriftLoader
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.utils.BlockerF
import io.circe.Json
import io.circe.parser._
import mainargs.ParserForMethods
import mainargs.arg
import org.joda.time.DateTime
import pprint.pprintln

import java.util.concurrent.TimeUnit

import config._
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
    @arg(short = 'e', doc = "Absolute path to enrichments config directory")
    enrichments: Option[String] = None
  ) = {
    val cfg = os.read(os.Path(config))
    val inputLines =
      os.walk(os.Path(input))
        .filter(os.isFile(_, followLinks = false))
        .filter(_.ext == glob)
        .flatMap(os.read.lines)
        .toList

    (for {
      resolverCfg <- Right(resolver.map(r => os.read(os.Path(r))).getOrElse(data.resolverConfig))
      client      <- Client.parseDefault[Id](parse(resolverCfg).right.get).leftMap(_.toString).value
      registry <- configureEnrichments(enrichments, client).leftMap(err =>
        new IllegalArgumentException(s"Invalid enrichment setup: $err")
      )
      c <- load(cfg).leftMap(err => new IllegalArgumentException(s"Invalid recovery configuration: $err"))
      enriched = inputLines
        .map(execute(c))
        .map(_.leftMap(_.badRow))
        .map(_.flatMap(ThriftLoader.toCollectorPayload(_, Processor("recovery-cli", "0.0.0")).toEither.leftMap(_.head)))
        .flatMap(e => enrich(new AdapterRegistry(), registry, client, e))
    } yield {
      sink(output, enriched)
      summary(inputLines, enriched)
    }).bimap(err => pprintln(s"ERROR! Failed to run recovery $err"), _ => pprintln(s"OK! Successfully ran recovery."))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)

  private[this] def configureEnrichments(path: Option[String], client: Client[Id, Json]) = {
    val enrichmentsConfigString = path
      .map(e =>
        os.walk(os.Path(e))
          .filter(os.isFile(_, followLinks = false))
          .filter(_.ext == "json")
          .map(os.read)
          .mkString(",\n")
      )
      .map(data.enrichmentsConfig)
      .getOrElse(data.enrichmentsConfig(""))

    parse(enrichmentsConfigString)
      .flatMap(e => EnrichmentRegistry.parse[Id](e, client, true).toEither)
      .flatMap(er => EnrichmentRegistry.build[Id](er, BlockerF.noop[Id]).value)
  }

  private[this] def enrich(
    adapterRegistry: AdapterRegistry,
    enrichmentRegistry: EnrichmentRegistry[Id],
    client: Client[Id, Json],
    event: Either[BadRow, Option[CollectorPayload]]
  ) = EtlPipeline
    .processEvents[Id](
      adapterRegistry,
      enrichmentRegistry,
      client,
      Processor("recovery-cli", "0.0.0"),
      new DateTime(1500000000L),
      event.toValidatedNel,
      EtlPipeline.FeatureFlags(acceptInvalid = true, legacyEnrichmentOrder = true),
      ()
    )
    .map(_.toEither)

  private[this] def sink(outputDir: Option[String], events: List[Either[BadRow, EnrichedEvent]]) = outputDir.foreach {
    o =>
      val good = events.filter(_.isRight).map(_.right.get).map(EnrichedEvent.toPartiallyEnrichedEvent)
      val bad  = events.filter(_.isLeft).map(_.left.get).map(_.asInstanceOf[BadRow].compact)
      os.write.over(os.Path(o) / "good.txt", good.mkString("\n"))
      os.write.over(os.Path(o) / "bad.txt", bad.mkString("\n"))
  }

  private[this] def summary(inputLines: Iterable[String], events: List[Either[BadRow, EnrichedEvent]]) =
    pprintln(s"Total Lines: ${inputLines.size}, Recovered: ${events.filter(_.isRight).size}")
}

object data {
  val resolverConfig =
    """{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":0,"repositories":[{"name": "Iglu Central","priority": 0,"vendorPrefixes": [ "com.snowplowanalytics" ],"connection": {"http":{"uri":"http://iglucentral.com"}}}]}}"""

  val enrichmentsConfig = (data: String) =>
    s"""{"schema": "iglu:com.snowplowanalytics.snowplow/enrichments/jsonschema/1-0-0", "data": [$data]}"""

  implicit val idClock: Clock[Id] = new Clock[Id] {
    final def realTime(unit: TimeUnit): Id[Long] =
      unit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS)

    final def monotonic(unit: TimeUnit): Id[Long] =
      unit.convert(System.nanoTime(), TimeUnit.NANOSECONDS)
  }
}
