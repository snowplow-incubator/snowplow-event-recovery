/*
 * Copyright (c) 2023-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */

package com.snowplowanalytics.snowplow.event.recovery

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import scala.concurrent.duration._

import cats.{Applicative, Id}
import cats.data._
import cats.effect.Clock
import cats.syntax.either._
import cats.syntax.show._
import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.iglu.client.resolver.registries.JavaNetRegistryLookup.idLookupInstance
import com.snowplowanalytics.iglu.core.circe.implicits._
import com.snowplowanalytics.snowplow.badrows.BadRow
import json._

package object config {

  type Flow                 = String
  type Config               = Map[Flow, List[FlowConfig]]
  type Regexp               = String
  type Path                 = String
  type Conf                 = SelfDescribingData[Config]
  type SelfDescribingBadRow = SelfDescribingData[BadRow]

  /** Load recovery configuration from configuration json file
    * @param configuration
    *   json file contents as a String
    * @return
    *   either an error message or a loaded configuration
    */
  def load(cfg: String): Either[String, config.Config] =
    parseJson(cfg).flatMap(_.as[config.Conf]).map(_.data).leftMap(_.show)

  /** Validate that a configuration conforms to its schema. Fetches the schema with blocking HTTP calls.
    * @param configuration
    *   in json form
    * @return
    *   a failure if the json didn't validate against its schema or a success
    */
  def validateSchema(config: String, resolverConfig: String): Either[String, Unit] = {
    val parse: String => EitherT[Id, String, Json] = str => EitherT.fromEither(parseJson(str).leftMap(_.show))
    (for {
      resolver <- parse(resolverConfig)
      recovery <- parse(config)
      c        <- Client.parseDefault[Id](resolver).leftMap(_.show)
      i        <- EitherT.fromEither[Id](recovery.as[SelfDescribingData[Json]]).leftMap(_.show)
      _        <- c.check(i).leftMap(_.show)
    } yield ()).value
  }

  implicit private val idClock: Clock[Id] = new Clock[Id] {
    def applicative: Applicative[Id] = Applicative[Id]
    def monotonic: FiniteDuration    = System.nanoTime().nanos
    def realTime: FiniteDuration     = System.currentTimeMillis().millis
  }

}
