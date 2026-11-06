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
package domain

import java.time.Instant
import cats.implicits._
import io.circe.parser._
import io.circe.syntax._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.circe.implicits._
import config._
import json._
import buildinfo.BuildInfo

final case class RecoveryError(
  status: RecoveryStatus,
  row: String,
  configName: Option[String] = None
) {
  def badRow: BadRow = status match {
    case InvalidJsonFormat(_) => toCPFormatViolation(row, status.message)
    case _ =>
      parse(row)
        .flatMap(_.as[SelfDescribingBadRow])
        .map(toBadRow(configName))
        .leftMap(s => toCPFormatViolation(row, s.getMessage))
        .merge
  }

  val json = badRow.selfDescribingData.asJson.noSpaces

  private[this] def toBadRow(configName: Option[String])(typedRow: SelfDescribingBadRow) =
    BadRow.RecoveryError(
      processor = Processor("snowplow-event-recovery", BuildInfo.version),
      failure = Failure.RecoveryFailure(status.message, configName),
      payload = untyped
        .payload(typedRow.data)
        .map {
          case b: BadRow.RecoveryError => b.payload
          case _                       => typedRow.data
        }
        .getOrElse(typedRow.data),
      recoveries = untyped.recoveries(typedRow.data).map(_ + 1).getOrElse(1)
    )
  private[this] def toCPFormatViolation(row: String, message: String) =
    BadRow.CPFormatViolation(
      processor = Processor("snowplow-event-recovery", BuildInfo.version),
      failure = Failure.CPFormatViolation(
        timestamp = Instant.now(),
        loader = "",
        message = FailureDetails.CPFormatViolationMessage.Fallback(message)
      ),
      payload = Payload.RawPayload(row)
    )
}
