/*
 * Copyright (c) 2024-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.event.recovery

import scala.Console.{BOLD, GREEN, RED, RESET}
import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.snowplowanalytics.snowplow.badrows.BadRow
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import io.circe.parser._
import mainargs.ParserForMethods
import mainargs.arg

import config._

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
    val resolverCfg = resolver.map(r => os.read(os.Path(r))).getOrElse(data.resolverConfig)
    validateSchema(cfg, resolverCfg).bimap(
      err => Console.println(s"${RESET}${RED}ERROR! Invalid config\n\n$err${RESET}"),
      _ => println(s"${RESET}${GREEN}OK! Config valid${RESET}")
    )
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
    @arg(
      short = 'e',
      doc = "Absolute path to enrichments config directory. Only enrichments that need no external resources."
    )
    enrichments: Option[String] = None
  ): Unit =
    if (Runtime.version.feature < 21)
      println(s"${RESET}${RED}${BOLD}ERROR!${RESET}${RED} run needs Java 21 or newer${RESET}")
    else recover(input, glob, config, resolver, output, enrichments)

  private[this] def recover(
    input: String,
    glob: String,
    config: String,
    resolver: Option[String],
    output: Option[String],
    enrichments: Option[String]
  ): Unit = {
    val cfg = os.read(os.Path(config))
    val inputLines =
      os.walk(os.Path(input))
        .filter(os.isFile(_, followLinks = false))
        .filter(_.ext == glob)
        .flatMap(os.read.lines)
        .toList

    (for {
      resolverJson <- EitherT.fromEither[IO](
        parse(resolver.map(r => os.read(os.Path(r))).getOrElse(data.resolverConfig)).leftMap(_.show)
      )
      enrichmentsJson <- EitherT.fromEither[IO](parse(enrichmentsConfig(enrichments)).leftMap(_.show))
      pipeline <- EitherT(Enrich.pipeline(resolverJson, enrichmentsJson)).leftMap(err =>
        s"Invalid resolver or enrichment setup: $err"
      )
      _ <- lift(IO.println("Resolver and enrichments configured..."))
      c <- EitherT.fromEither[IO](load(cfg)).leftMap(err => s"Invalid recovery configuration: $err")
      _ <- lift(IO.println("Config loaded..."))
      enriched <- lift(
        inputLines.flatTraverse { line =>
          val payload = execute(c)(line).leftMap(_.badRow).flatMap(Enrich.toCollectorPayload)
          IO.println("Enriching event...") >>
            Enrich
              .enrich(pipeline)(payload)
              .flatTap(_.traverse_ {
                case Right(e) => IO.println(s"Enriched event ${e.event_id}")
                case Left(_)  => IO.unit
              })
        }
      )
      _ <- lift(IO.println("Sinking events..."))
      _ <- lift(IO.blocking(sink(output, enriched)))
    } yield summary(inputLines, enriched))
      .value
      .unsafeRunSync()
      .bimap(
        err => println(s"${RESET}${RED}${BOLD}ERROR!${RESET}${RED} Failed to run recovery $err${RESET}"),
        _ => println(s"${RESET}${BOLD}${GREEN}OK!${RESET}${GREEN} Successfully ran recovery.${RESET}")
      )
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)

  private[this] def lift[A](io: IO[A]): EitherT[IO, String, A] = EitherT.liftF(io)

  private[this] def enrichmentsConfig(path: Option[String]): String =
    data.enrichmentsConfig(
      path
        .map(e =>
          os.walk(os.Path(e))
            .filter(os.isFile(_, followLinks = false))
            .filter(_.ext == "json")
            .map(os.read)
            .mkString(",\n")
        )
        .getOrElse("")
    )

  private[this] def sink(outputDir: Option[String], events: List[Either[BadRow, EnrichedEvent]]) = outputDir.foreach {
    o =>
      val good = events.collect { case Right(e) => EnrichedEvent.toPartiallyEnrichedEvent(e) }
      val bad  = events.collect { case Left(b) => b.compact }
      os.write.over(os.Path(o) / "good.txt", good.mkString("\n"))
      os.write.over(os.Path(o) / "bad.txt", bad.mkString("\n"))
  }

  private[this] def summary(inputLines: Iterable[String], events: List[Either[BadRow, EnrichedEvent]]) =
    println(
      s"${RESET}${BOLD}Total Lines:${RESET} ${inputLines.size}, ${BOLD}Recovered:${RESET} ${GREEN}${events
        .count(_.isRight)}${RESET}"
    )
}

object data {
  val resolverConfig =
    """{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":0,"repositories":[{"name": "Iglu Central","priority": 0,"vendorPrefixes": [ "com.snowplowanalytics" ],"connection": {"http":{"uri":"http://iglucentral.com"}}}]}}"""

  val enrichmentsConfig = (data: String) =>
    s"""{"schema": "iglu:com.snowplowanalytics.snowplow/enrichments/jsonschema/1-0-0", "data": [$data]}"""
}
