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
package com.snowplowanalytics
package snowplow
package event.recovery

import java.util.Base64

import cats.syntax.either._
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import org.apache.thrift.{TDeserializer, TSerializer}

import CollectorPayload.thrift.model1.CollectorPayload
import iglu.client.Resolver
import iglu.client.repositories.{HttpRepositoryRef, RepositoryRefConfig}
import iglu.client.validation.ValidatableJValue.validateAndIdentifySchema

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
    implicit val genConfig: Configuration =
      Configuration.default.withDiscriminator("name")
    val result = for {
      parsed <- io.circe.parser.parse(json)
      scenarios <- parsed.hcursor.get[List[RecoveryScenario]]("data")
    } yield scenarios
    result.leftMap(e => s"Configuration is not properly formatted: ${e.getMessage}")
  }

  private val registryUri = "http://iglucentral-dev.com.s3-website-us-east-1.amazonaws.com/recovery"
  /**
   * Validate that a configuration conforms to its schema.
   * @param configuration in json form
   * @return a failure if the json didn't validate against its schema or a success
   */
  def validateConfiguration(json: String): Either[String, Unit] = {
    val resolver = Resolver(repos = List(HttpRepositoryRef(
      config = RepositoryRefConfig(name = "Iglu central", 0, List("com.snowplowanalytics")),
      uri = registryUri
    )))
    for {
      jvalue <- Either.catchNonFatal(org.json4s.jackson.JsonMethods.parse(json))
        .leftMap(_.getMessage)
      _ <- validateAndIdentifySchema(jvalue, dataOnly = true)(resolver)
        .fold(errors => errors.list.mkString("\n").asLeft, _.asRight)
    } yield ()
  }

}
