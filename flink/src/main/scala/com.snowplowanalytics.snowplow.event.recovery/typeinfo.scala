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

import org.apache.flink.api.common.typeinfo.TypeInformation
import io.circe.Json
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import config.BadRowWithConfig
import domain._

object typeinfo {
  implicit val recoveryErrorT: TypeInformation[RecoveryError] =
    TypeInformation.of(classOf[RecoveryError])
  implicit val recoveryStatusT: TypeInformation[RecoveryStatus] =
    TypeInformation.of(classOf[RecoveryStatus])
  implicit val badRowT: TypeInformation[BadRow] =
    TypeInformation.of(classOf[BadRow])
  implicit val eitherRsForAFT: TypeInformation[Recovering[Json]] =
    TypeInformation.of(classOf[Recovering[Json]])
  implicit val eitherAForAFT: TypeInformation[Either[BadRow, BadRow]] =
    TypeInformation.of(classOf[Either[BadRow, BadRow]])
  implicit val eitherBBT: TypeInformation[Either[BadRow, BadRow.AdapterFailures]] =
    TypeInformation.of(classOf[Either[BadRow, BadRow.AdapterFailures]])
  implicit val payloadT: TypeInformation[Payload] =
    TypeInformation.of(classOf[Payload])
  implicit val payloadCPT: TypeInformation[Payload.CollectorPayload] =
    TypeInformation.of(classOf[Payload.CollectorPayload])
  implicit val eitherPPT: TypeInformation[Either[BadRow, Payload]] =
    TypeInformation.of(classOf[Either[BadRow, Payload]])
  implicit val eitherRPT: TypeInformation[Either[RecoveryError, Payload]] =
    TypeInformation.of(classOf[Either[RecoveryError, Payload]])
  implicit val eitherRST: TypeInformation[Either[RecoveryError, String]] =
    TypeInformation.of(classOf[Either[RecoveryError, String]])
  implicit val collectorPayloadT: TypeInformation[CollectorPayload] =
    TypeInformation.of(classOf[CollectorPayload])
  implicit val optionCollectorPayloadT: TypeInformation[Option[CollectorPayload]] =
    TypeInformation.of(classOf[Option[CollectorPayload]])
  implicit val stringT: TypeInformation[String] =
    TypeInformation.of(classOf[String])
  implicit val jsonT: TypeInformation[Json] = TypeInformation.of(classOf[Json])
  implicit val badRowStepsT: TypeInformation[BadRowWithConfig] =
    TypeInformation.of(classOf[BadRowWithConfig])
}
