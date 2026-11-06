/*
 * Copyright (c) 2026-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.event.recovery

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

class RecoveredJsonSpec extends AnyWordSpec with EitherValues {
  "RecoveredJson.fromThrift" should {
    "write a collector payload as one line of JSON keyed by field name" in {
      val payload = new CollectorPayload(
        "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0",
        "10.0.0.1",
        1700000000000L,
        "UTF-8",
        "ssc"
      )
      payload.path = "/com.snowplowanalytics.snowplow/tp2"
      payload.querystring = "e=pv&page=Pàge ünïcode"
      payload.body = """{"schema":"iglu:x/y/jsonschema/1-0-0","data":[{"e":"pv"}]}"""
      payload.headers = java.util.Arrays.asList("Host: example.com", "User-Agent: test")

      val line = RecoveredJson.fromThrift(util.thrift.serializeNoB64(payload).value)
      val json = parse(line).value

      (line should not).include("\n")
      json.hcursor.get[String]("path").value shouldBe "/com.snowplowanalytics.snowplow/tp2"
      json.hcursor.get[String]("querystring").value shouldBe "e=pv&page=Pàge ünïcode"
      json.hcursor.get[String]("body").value shouldBe payload.body
      json.hcursor.get[List[String]]("headers").value shouldBe List("Host: example.com", "User-Agent: test")
      json.hcursor.get[Long]("timestamp").value shouldBe 1700000000000L
      json.hcursor.downField("userAgent").focus shouldBe None // not set on the payload, so left out
      json shouldBe a[Json]
    }
    "fail on bytes that are not a collector payload" in {
      an[IllegalStateException] should be thrownBy RecoveredJson.fromThrift("not thrift".getBytes)
    }
  }
}
