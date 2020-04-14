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

import io.circe.Json
import io.circe.parser.{parse => parseJson}

import cats.effect.Clock
import cats._
import cats.data._
import cats.syntax.either._
import cats.syntax.show._
import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.client.resolver.{InitListCache, InitSchemaCache}
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.iglu.core.circe.instances._
import com.snowplowanalytics.snowplow.badrows.BadRow
import json._

package object config {

  type Flow                 = String
  type Config               = Map[Flow, List[FlowConfig]]
  type Regexp               = String
  type Path                 = String
  type Conf                 = SelfDescribingData[Config]
  type SelfDescribingBadRow = SelfDescribingData[BadRow]

  /**
    * Load recovery configuration from configuration json file
    * @param configuration json file contents as a String
    * @return either an error message or a loaded configuration
    */
  def load(cfg: String): Either[String, config.Config] =
    parseJson(cfg).flatMap(_.as[config.Conf]).map(_.data).leftMap(_.show)

  /**
    * Validate that a configuration conforms to its schema.
    * @param configuration in json form
    * @return a failure if the json didn't validate against its schema or a success
    */
  def validateSchema[F[_]: Monad: InitSchemaCache: InitListCache: Clock: RegistryLookup](
    config: String,
    resolverConfig: String
  ): EitherT[F, String, Unit] = {
    val parse: String => EitherT[F, String, Json] = str => EitherT.fromEither(parseJson(str).leftMap(_.show))
    for {
      resolver <- parse(resolverConfig)
      recovery <- parse(config)
      c        <- Client.parseDefault[F](resolver).leftMap(_.show)
      i        <- EitherT.fromEither[F](recovery.as[SelfDescribingData[Json]]).leftMap(_.show)
      _        <- c.check(i).leftMap(_.show)
    } yield ()
  }

}
