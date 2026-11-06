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

package com.snowplowanalytics.snowplow
package event.recovery
package config

import cats.implicits._
import badrows.{BadRow, Schemas}
import io.circe._
import io.circe.syntax._
import config.conditions.Condition
import json._
import domain._
import domain.MatchableSchemaKey.matchSchema

/** Defines a bad row and its corresponding, resolved recovery steps.
  */
case class BadRowWithConfig(badRow: BadRow, steps: List[StepConfig])

object BadRowWithConfig {

  /** Extract Bad Row with applicable config from string.
    * @param config:
    *   recovery configuration
    * @param line:
    *   string of a self describing json
    * @return
    *   either a successfully decoded string or a failure
    */
  def extract(config: Config)(line: Json): Either[RecoveryError, BadRowWithConfig] =
    (for {
      decoded <- decode(line)
      body    <- body(decoded, line)
      config  <- find(config, body)
    } yield BadRowWithConfig(decoded.data, config.flatMap(_.steps))).leftMap(_.withRow(line.noSpaces))

  private[this] val decode = (line: Json) =>
    line.as[SelfDescribingBadRow].leftMap(err => InvalidDataFormat(line.some, err.getMessage))

  private[this] val body = (decoded: SelfDescribingBadRow, line: Json) =>
    if (decoded.schema == Schemas.RecoveryError)
      decoded
        .data
        .selfDescribingData
        .data
        .as[SelfDescribingBadRow]
        .leftMap(err => InvalidDataFormat(line.some, err.getMessage))
    else Right(decoded)

  private[config] val find = (config: Config, body: SelfDescribingBadRow) => {
    val res = config
      .filter(v => matchSchema(v._1, body.schema.toSchemaUri))
      .values.toList.flatten.filter(v => check(v.conditions, body.data))
    if (res.isEmpty) Left(FailedToMatchConfiguration(body.schema.toSchemaUri))
    else
      Right(res.toList)
  }

  private[this] def check(conditions: List[Condition], badRow: BadRow): Boolean =
    conditions.foldLeft(true) { (acc, cur) =>
      acc && cur.check(badRow.asJson)
    }

}
