package com.snowplowanalytics.snowplow
package event.recovery

import java.util.Base64

import cats.syntax.either._
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import org.apache.thrift.{TDeserializer, TSerializer}

import CollectorPayload.thrift.model1.CollectorPayload
import model._

object utils {
  val thriftDeser: String => CollectorPayload = { s =>
    val decoded = Base64.getDecoder.decode(s)
    val thriftDeserializer = new TDeserializer
    val payload = new CollectorPayload
    thriftDeserializer.deserialize(payload, decoded)
    payload
  }

  val thriftSer: CollectorPayload => String = { cp =>
    val thriftSerializer = new TSerializer
    val bytes = thriftSerializer.serialize(cp)
    Base64.getEncoder.encodeToString(bytes)
  }

  def decodeBase64(encoded: String): Either[String, String] =
    Either.catchNonFatal(new String(Base64.getDecoder.decode(encoded)))
      .leftMap(e => s"Configuration is not properly base64-encoded: ${e.getMessage}")

  def parseRecoveryScenarios(json: String): Either[String, List[RecoveryScenario]] = {
    implicit val genConfig: Configuration =
      Configuration.default.withDiscriminator("name")
    val result = for {
      parsed <- io.circe.parser.parse(json)
      scenarios <- parsed.hcursor.get[List[RecoveryScenario]]("data")
    } yield scenarios
    result.leftMap(e => s"Configuration is not properly formatted: ${e.getMessage}")
  }

}
