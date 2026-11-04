/*
 * Copyright (c) 2026 Snowplow Analytics Ltd. All rights reserved.
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
