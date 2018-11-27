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
    val encoded = URLEncoder.encode(str, "UTF-8")
    s"e=pv&page=Title&refr=Referrer&url=Url&co=%7B%22schema%22%3A%22iglu%3Acom.snowplowanalytics.snowplow%5C%2Fcontexts%5C%2Fjsonschema%5C%2F1-0-1%22%2C%22data%22%3A%5B$encoded%5D%7D"
  }
  val body = (json: JValue) => {
    val str = compact(render(json))
    s"""{"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4","data":[{"e":"pv","page":"Title","refr":"Referrer","url":"Url","co":{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1","data":[$str]}}]}"""
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

  private val thriftSer: CollectorPayload => String = { cp =>
    val thriftSerializer = new TSerializer
    val bytes = thriftSerializer.serialize(cp)
    Base64.getEncoder.encodeToString(bytes)
  }
}
