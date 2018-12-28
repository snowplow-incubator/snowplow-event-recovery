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

  def validateConfiguration(json: String): Either[String, Unit] = {
    val resolver = Resolver(repos = List(HttpRepositoryRef(
      config = RepositoryRefConfig(name = "Iglu central", 0, List("com.snowplowanalytics")),
      uri = "http://iglucentral.com"
    )))
    for {
      jvalue <- Either.catchNonFatal(org.json4s.jackson.JsonMethods.parse(json))
        .leftMap(_.getMessage)
      _ <- validateAndIdentifySchema(jvalue, dataOnly = true)(resolver)
        .fold(errors => errors.list.mkString("\n").asLeft, _.asRight)
    } yield ()
  }

}
