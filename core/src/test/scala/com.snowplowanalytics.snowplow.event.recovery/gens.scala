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

import java.util.{Base64, UUID}

import cats.Id
import cats.syntax.option._
import com.snowplowanalytics.iglu.client.resolver.Resolver
import com.snowplowanalytics.iglu.client.resolver.registries.Registry
import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}
import com.snowplowanalytics.iglu.schemaddl.scalacheck.{IgluSchemas, JsonGenSchema}
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.{CollectorPayload => CP}
import com.snowplowanalytics.snowplow.badrows.NVP
import com.snowplowanalytics.snowplow.badrows.Payload.CollectorPayload
import io.circe.Json
import org.scalacheck.{Arbitrary, Gen}

import model._
import utils._

object gens {
  val qs = (json: Json) => {
    val str = json.noSpaces
    val unstruct = s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-0-1","data":$str}}"""
    val encoded = Base64.getEncoder.encodeToString(unstruct.getBytes)
    s"e=ue&tv=js&ue_px=$encoded"
  }
  val qs2 = (json: Json) => {
    val str = json.noSpaces
    val unstruct = s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-0-1","data":$str}}"""
    val encoded = Base64.getEncoder.encodeToString(unstruct.getBytes)
    List(NVP("e", "ue".some), NVP("tv", "js".some), NVP("ue_px", encoded.some))
  }
  val body = (json: Json) => {
    val str = json.noSpaces
    val unstruct = s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-0-1","data":$str}}"""
    val encoded = Base64.getEncoder.encodeToString(unstruct.getBytes)
    s"""{"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4","data":[{"e":"ue","p":"web","tv":"js","ue_px":"$encoded"}]}"""
  }

  private val schemaKey =
    SchemaKey("com.snowplowanalytics.snowplow", "client_session", "jsonschema",
      SchemaVer.Full(1, 0, 1))
  private val resolver = Resolver.init[Id](0, None, Registry.IgluCentral)
  private val schemaJson = IgluSchemas.lookup[Id](resolver, schemaKey)
    .toOption
    .getOrElse(throw new RuntimeException("invalid schema coordinates"))
  private val schemaObject = IgluSchemas.parseSchema(schemaJson)
    .toOption
    .getOrElse(throw new RuntimeException("invalid schema"))

  implicit val cpArb: Arbitrary[CP] = Arbitrary {
    for {
      ts <- Gen.choose(1, Long.MaxValue)
      path = "/com.snowplowanalytics.snowplow/v1"
      post <- Gen.oneOf(true, false)
      contentType = "application/json; charset=UTF-8"
      json <- JsonGenSchema.json(schemaObject)
    } yield {
      val collectorPayload = new CP()
      collectorPayload.timestamp = ts
      collectorPayload.path = path
      if (post) collectorPayload.body = body(json)
      else collectorPayload.querystring = qs(json)
      collectorPayload
    }
  }

  implicit val collectorPayloadArb: Arbitrary[CollectorPayload] = Arbitrary {
    for {
      ts <- Gen.choose(1, Long.MaxValue)
      post <- Gen.oneOf(true, false)
      json <- JsonGenSchema.json(schemaObject)
    } yield {
      val cp = CollectorPayload(
        vendor = "com.snowplowanalytics.snowplow",
        version = "v1",
        querystring = Nil,
        contentType = Some("application/json; charset=UTF-8"),
        body = None,
        collector = "",
        encoding = "",
        hostname = None,
        timestamp = Some(ts.toString()),
        ipAddress = None,
        useragent = None,
        refererUri = None,
        headers = Nil,
        networkUserId = None
      )
      if (post) cp.copy(body = Some(body(json)))
      else cp.copy(querystring = qs2(json))
    }
  }

  implicit val uuidGen: Gen[UUID] = Gen.uuid

  private val errorGen: Gen[Error] = for {
    level <- Gen.alphaStr
    message <- Gen.alphaStr
  } yield Error(level, message)

  val badRowGen: Gen[BadRow] = for {
    payload <- cpArb.arbitrary
    line = thriftSer(payload)
    errors <- Gen.listOf(errorGen)
    failureTstamp <- Gen.alphaStr
  } yield BadRow(line, errors, failureTstamp)
}
