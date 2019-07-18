/*
 * Copyright (c) 2018-2018 Snowplow Analytics Ltd. All rights reserved.
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

import java.util.Base64
import java.util.concurrent.TimeUnit

import cats.Id
import cats.effect.Clock
import cats.syntax.either._
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.iglu.client.resolver.Resolver
import com.snowplowanalytics.iglu.client.resolver.registries.Registry
import com.snowplowanalytics.iglu.client.validator.CirceValidator
import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.core.circe.instances._
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import io.circe.parser._
import org.apache.thrift.{TDeserializer, TSerializer}

object utils {
  /** Deserialize a String into a CollectorPayload after having base64-decoded it. */
  val thriftDeser: String => CollectorPayload = { s =>
    val decoded = Base64.getDecoder.decode(s)
    val thriftDeserializer = new TDeserializer
    val payload = new CollectorPayload
    thriftDeserializer.deserialize(payload, decoded)
    payload
  }

  /** Serialize a CollectorPayload into a byte array and base64-encode it. */
  val thriftSer: CollectorPayload => String = { cp =>
    val thriftSerializer = new TSerializer
    val bytes = thriftSerializer.serialize(cp)
    Base64.getEncoder.encodeToString(bytes)
  }

  /**
   * Decode a base64-encoded string.
   * @param encoded base64-encoded string
   * @return either a successfully decoded string or a failure
   */
  def decodeBase64(encoded: String): Either[String, String] =
    Either.catchNonFatal(new String(Base64.getDecoder.decode(encoded)))
      .leftMap(e => s"Configuration is not properly base64-encoded: ${e.getMessage}")

  /**
   * Parse a json containing a list of recovery scenarios.
   * @param json to be parsed
   * @return either a successfully parsed list of [[RecoveryScenario]] or a failure
   */
  def parseRecoveryScenarios(json: String): Either[String, List[RecoveryScenario]] = {
    implicit val _: Configuration = Configuration.default.withDiscriminator("name")
    val result = for {
      parsed <- io.circe.parser.parse(json)
      scenarios <- parsed.hcursor.get[List[RecoveryScenario]]("data")
    } yield scenarios
    result.leftMap(e => s"Configuration is not properly formatted: ${e.getMessage}")
  }

  /**
   * Validate that a configuration conforms to its schema.
   * @param configuration in json form
   * @return a failure if the json didn't validate against its schema or a success
   */
  def validateConfiguration(json: String): Either[String, Unit] = {
    val client = Client(Resolver.init[Id](500, None, Registry.IgluCentral), CirceValidator)
    for {
      js <- parse(json).leftMap(_.message)
      sd <- SelfDescribingData.parse(js).leftMap(_.code)
      _ <- client.check(sd).leftMap(_.getMessage).value
    } yield ()
  }


  implicit val idClock: Clock[Id] = new Clock[Id] {
    final def realTime(unit: TimeUnit): Id[Long] =
      unit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
    final def monotonic(unit: TimeUnit): Id[Long] =
      unit.convert(System.nanoTime(), TimeUnit.NANOSECONDS)
  }
}
