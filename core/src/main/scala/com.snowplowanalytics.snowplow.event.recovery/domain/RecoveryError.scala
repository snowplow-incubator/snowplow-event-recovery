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
package domain

import java.time.Instant
import cats.implicits._
import io.circe.parser._
import io.circe.syntax._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.circe.instances._
import config._
import json._

final case class RecoveryError(status: RecoveryStatus, row: String, configName: Option[String] = None) {
  def badRow: BadRow = status match {
    case InvalidJsonFormat(_) =>
      BadRow.CPFormatViolation(
        processor = Processor("snowplow-event-recovery", "0.2.0"),
        failure = Failure.CPFormatViolation(
          timestamp = Instant.now(),
          loader = "",
          message =
            FailureDetails.CPFormatViolationMessage.Fallback(status.message)
        ),
        payload = Payload.RawPayload(row)
      )
    case _ =>
      parse(row)
        .flatMap(_.as[SelfDescribingBadRow])
        .map { typedRow =>
          BadRow.RecoveryError(
            processor = Processor("snowplow-event-recovery", "0.2.0"),
            failure = Failure.RecoveryFailure(status.message, configName),
            payload = untyped
              .payload(typedRow.data)
              .map {
                case b: BadRow.RecoveryError => b.payload
                case _                       => typedRow.data
              }
              .get,
            recoveries =
              untyped.recoveries(typedRow.data).map(_ + 1).getOrElse(1)
          )
        }
        .leftMap(
          s =>
            BadRow.CPFormatViolation(
              processor = Processor("snowplow-event-recovery", "0.2.0"),
              failure = Failure.CPFormatViolation(
                timestamp = Instant.now(),
                loader = "",
                message =
                  FailureDetails.CPFormatViolationMessage.Fallback(s.getMessage)
              ),
              payload = Payload.RawPayload(row)
            )
        )
        .merge
  }

  val json = badRow.selfDescribingData.asJson.noSpaces
}
