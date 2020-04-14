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

package com.snowplowanalytics.snowplow
package event.recovery
package config

import org.slf4j.LoggerFactory
import cats.implicits._
import badrows.BadRow
import io.circe._
import io.circe.syntax._
import config.conditions.Condition
import json._
import domain._
import domain.MatchableSchemaKey.matchSchema
import com.snowplowanalytics.snowplow.badrows.{BadRow, Schemas}

/**
  * Defines a bad row and its corresponding, resolved recovery steps.
  */
case class BadRowWithConfig(badRow: BadRow, steps: List[StepConfig])

object BadRowWithConfig {

  /**
    * Extract Bad Row with applicable config from string.
    * @param config: recovery configuration
    * @param line: string of a self describing json
    * @return either a successfully decoded string or a failure
    */
  def extract(config: Config)(line: Json): Either[RecoveryError, BadRowWithConfig] =
    (for {
      decoded <- line.as[SelfDescribingBadRow].leftMap(err => InvalidDataFormat(line.some, err.getMessage))
      body <- if (decoded.schema == Schemas.RecoveryError)
        decoded
          .data
          .selfDescribingData
          .data
          .as[SelfDescribingBadRow]
          .leftMap(err => InvalidDataFormat(line.some, err.getMessage))
      else Right(decoded)
      config <- config
        .find(v => matchSchema(v._1, body.schema))
        .flatMap(_._2.find(v => check(v.conditions, body.data)))
        .toRight(FailedToMatchConfiguration(body.schema))
    } yield {
      logger.debug(
        s"Recovering ${body.data.schemaKey} (in ${decoded.schema}) with config: ${config.name}"
      )
      BadRowWithConfig(decoded.data, config.steps)
    }).leftMap(_.withRow(line.noSpaces))

  private[this] def check(conditions: List[Condition], badRow: BadRow): Boolean =
    conditions.foldLeft(true) { (acc, cur) =>
      acc && cur.check(badRow.asJson)
    }

  private[this] val logger = LoggerFactory.getLogger(this.getClass)
}
