package com.snowplowanalytics
package snowplow
package event.recovery

import java.net.URLEncoder
import java.util.{Base64, UUID}

import org.apache.thrift.TSerializer

import org.json4s.JValue
import org.json4s.jackson.JsonMethods._
import org.scalacheck.{Arbitrary, Gen}

import CollectorPayload.thrift.model1.CollectorPayload
import iglu.core.{SchemaKey, SchemaVer}
import iglu.schemaddl.scalacheck.{IgluSchemas, JsonGenSchema}

import model._
import utils._

object gens {
  val qs = (json: JValue) => {
    val str = compact(render(json))
    val unstruct = s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":$str}"""
    val encoded = Base64.getEncoder.encodeToString(unstruct.getBytes)
    s"e=ue&tv=js&ue_px=$encoded"
  }
  val body = (json: JValue) => {
    val str = compact(render(json))
    val unstruct = s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":$str}"""
    val encoded = Base64.getEncoder.encodeToString(unstruct.getBytes)
    s"""{"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4","data":[{"e":"ue","tv":"js",ue_px":"$encoded"}]}"""
  }

  private val schemaKey =
    SchemaKey("com.snowplowanalytics.snowplow", "client_session", "jsonschema", SchemaVer(1, 0, 1))
  private val schemaJson = IgluSchemas.lookup(None)(schemaKey)
    .right
    .toOption
    .getOrElse(throw new RuntimeException("invalid schema coordinates"))
  private val schemaObject = IgluSchemas.parseSchema(schemaJson)
    .right
    .toOption
    .getOrElse(throw new RuntimeException("invalid schema"))

  implicit val collectorPayloadArb: Arbitrary[CollectorPayload] = Arbitrary {
    for {
      ts <- Gen.choose(1, Long.MaxValue)
      path = "/com.snowplowanalytics.snowplow/v1"
      post <- Gen.oneOf(true, false)
      contentType = "application/json; charset=UTF-8"
      json <- JsonGenSchema.json(schemaObject)
    } yield {
      val collectorPayload = new CollectorPayload()
      collectorPayload.timestamp = ts
      collectorPayload.path = path
      if (post) collectorPayload.body = body(json)
      else collectorPayload.querystring = qs(json)
      collectorPayload
    }
  }

  implicit val uuidGen: Gen[UUID] = Gen.uuid

  private val errorGen: Gen[Error] = for {
    level <- Gen.alphaStr
    message <- Gen.alphaStr
  } yield Error(level, message)

  val badRowGen: Gen[BadRow] = for {
    payload <- collectorPayloadArb.arbitrary
    line = thriftSer(payload)
    errors <- Gen.listOf(errorGen)
    failureTstamp <- Gen.alphaStr
  } yield BadRow(line, errors, failureTstamp)
}
