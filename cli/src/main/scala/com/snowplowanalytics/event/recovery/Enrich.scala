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

import java.time.Instant

import cats.data.EitherT
import cats.effect.IO
import cats.implicits._
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import io.circe.Json
import io.circe.generic.auto._
import org.joda.time.DateTime

import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.{JavaNetRegistryLookup, RegistryLookup}
import com.snowplowanalytics.snowplow.badrows.{BadRow, Processor}
import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.adapters.{AdapterRegistry, AdaptersSchemas}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.{AtomicFields, EnrichmentRegistry}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf._
import com.snowplowanalytics.snowplow.enrich.common.loaders.{CollectorPayload, ThriftLoader}
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.utils.OptionIor

/** Runs recovered payloads through Snowplow enrich */
object Enrich {

  final case class Pipeline(
    client: IgluCirceClient[IO],
    enrichments: EnrichmentRegistry[IO],
    adapters: AdapterRegistry[IO],
    defaults: Defaults
  )

  /** The defaults snowplow-common-enrich ships in its reference.conf */
  final case class Defaults(adaptersSchemas: AdaptersSchemas, maxJsonDepth: Int, validation: Validation)
  final case class Validation(atomicFieldsLimits: Map[String, Int])

  val processor = Processor("recovery-cli", "0.0.0")

  // The JavaScript enrichment wraps scripts in ES6. Enrich sets the same as a JVM option.
  sys.props.getOrElseUpdate("nashorn.args", "--language=es6")

  implicit private val registryLookup: RegistryLookup[IO] = JavaNetRegistryLookup.ioLookupInstance[IO]

  private def loadDefaults: Either[String, Defaults] =
    Either
      .catchNonFatal(ConfigFactory.parseResources("reference.conf").root.render(ConfigRenderOptions.concise))
      .leftMap(_.getMessage)
      .flatMap(io.circe.parser.decode[Defaults](_).leftMap(e => s"Cannot read enrich defaults: $e"))

  /** @param enrichmentsConfig
    *   enrichments/jsonschema/1-0-0 with the enrichments to run. Only those that need no external resources.
    */
  def pipeline(resolverConfig: Json, enrichmentsConfig: Json): IO[Either[String, Pipeline]] =
    (for {
      defaults <- EitherT.fromEither[IO](loadDefaults)
      client   <- IgluCirceClient.parseDefault[IO](resolverConfig, defaults.maxJsonDepth).leftMap(_.show)
      confs <- EitherT(
        EnrichmentRegistry
          .parse[IO](enrichmentsConfig, client, localMode = false, registryLookup)
          .map(_.toEither.leftMap(_.toList.mkString("\n")))
      )
      enrichments <- EitherT.fromEither[IO](confs.foldM(EnrichmentRegistry[IO]())(withEnrichment))
    } yield Pipeline(client, enrichments, new AdapterRegistry[IO](Map.empty, defaults.adaptersSchemas), defaults)).value

  private def withEnrichment(
    registry: EnrichmentRegistry[IO],
    conf: EnrichmentConf
  ): Either[String, EnrichmentRegistry[IO]] =
    conf match {
      case c: AnonIpConf              => registry.copy(anonIp = Some(c.enrichment)).asRight
      case c: BotDetectionConf        => registry.copy(botDetection = Some(c.enrichment)).asRight
      case c: CampaignAttributionConf => registry.copy(campaignAttribution = Some(c.enrichment)).asRight
      case c: CookieExtractorConf     => registry.copy(cookieExtractor = Some(c.enrichment)).asRight
      case c: CrossNavigationConf     => registry.copy(crossNavigation = Some(c.enrichment)).asRight
      case c: EventFingerprintConf    => registry.copy(eventFingerprint = Some(c.enrichment)).asRight
      case c: HttpHeaderExtractorConf => registry.copy(httpHeaderExtractor = Some(c.enrichment)).asRight
      case c: PiiPseudonymizerConf    => registry.copy(piiPseudonymizer = Some(c.enrichment)).asRight
      case c: UserAgentUtilsConf      => registry.copy(userAgentUtils = Some(c.enrichment)).asRight
      case c: YauaaConf               => registry.copy(yauaa = Some(c.enrichment)).asRight
      case c: JavascriptScriptConf =>
        c.enrichment(exitOnJsCompileError = true, allowedJavaClasses = Set.empty)
          .map(js => registry.copy(javascriptScript = registry.javascriptScript :+ js))
      case c =>
        s"Enrichment ${c.schemaKey.toSchemaUri} needs external resources, which the recovery CLI does not support".asLeft
    }

  def toCollectorPayload(bytes: Array[Byte]): Either[BadRow, CollectorPayload] =
    ThriftLoader.toCollectorPayload(bytes, processor, Instant.now()).toEither.leftMap(_.head)

  def enrich(pipeline: Pipeline)(payload: Either[BadRow, CollectorPayload]): IO[List[Either[BadRow, EnrichedEvent]]] =
    EtlPipeline
      .processEvents[IO](
        pipeline.adapters,
        pipeline.enrichments,
        pipeline.client,
        processor,
        new DateTime(1500000000L),
        payload.toValidatedNel,
        EtlPipeline.FeatureFlags(acceptInvalid = true),
        IO.unit,
        registryLookup,
        AtomicFields.from(pipeline.defaults.validation.atomicFieldsLimits),
        emitFailed = false,
        pipeline.defaults.maxJsonDepth
      )
      .map(_.flatMap {
        case OptionIor.Right(event)    => List(Right(event))
        case OptionIor.Left(badRow)    => List(Left(badRow))
        case OptionIor.Both(badRow, _) => List(Left(badRow))
        case OptionIor.None            => Nil
      })
}
