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
package inspect

import cats.implicits._
import org.scalatest._
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import io.circe.parser.parse
import io.circe.Json
import com.snowplowanalytics.snowplow.event.recovery.domain.InvalidJsonFormat
import domain._

class TransformSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {

  "transform" should {

    val rec = Table(
      ("name", "json", "path", "output"),
      ("array case", """{"field": 1}""", Seq("field"), Json.fromInt(1)),
      ("base", """{"field": ["a","b","c"]}""", Seq("field", "[1]"), Json.fromString("b")),
      ("b64", """{"cx": "eyJuZXN0ZWQiOiAidmFsdWUifQ=="}""", Seq("cx", "nested"), Json.fromString("InZhbHVlIg==")),
      ("url", """{"co": "{\"name\":\"a\"}" }""", Seq("co", "name"), Json.fromString("\"a\"")),
      (
        "nvp",
        """{"parameters": [{"name": "lorem", "value": "data"}]}""",
        Seq("parameters", "lorem"),
        Json.fromString("data")
      ),
      (
        "b64-encoded-nvp",
        """{"cx": "eyJwYXJhbWV0ZXJzIjogW3sibmFtZSI6ICJsb3JlbSIsICJ2YWx1ZSI6ICJkYXRhIn1dfQ=="}""",
        Seq("cx", "parameters", "lorem"),
        Json.fromString("ImRhdGEi")
      ),
      (
        "url-encoded-nvp",
        """{"co": "{\"parameters\":[{\"name\":\"lorem\",\"value\":\"data\"}]}" }""",
        Seq("co", "parameters", "lorem"),
        Json.fromString("\"data\"")
      ),
      (
        "filter",
        """{"raw": {"parameters": [{"name":"lorem","value":"data"}] } }""",
        Seq("raw", "parameters", "[?(@.name=~lorem)]", "value"),
        Json.fromString("data")
      ),
      (
        "nested-filter",
        """{"parameters": [{"name": "ue_pr", "value": "{\"data\": {\"data\": {\"formId\": 123}}}"}]}""",
        Seq("parameters", "[?(@.name=~ue_pr)]", "value", "data", "data", "formId"),
        Json.fromString("123")
      )
    )
    "recurse on json transformations" in forAll(rec) { (_, jsonStr, path, outputStr) =>
      for {
        json   <- parse(jsonStr)
        output <- outputStr.asRight
        res <- transform(
          _.asRight,
          _ => _.focus.toRight(InvalidJsonFormat("focus")),
          msg => InvalidJsonFormat(msg),
          Seq.empty
        )(
          path
        )(json)
      } yield res should equal(output)
    }

    val special = Table(
      ("specialFlags", "currentElement", "previousElements", "isAnInstance"),
      (Seq("cx", "ue_pe"), "cx", Seq.empty, true),
      (Seq("cx", "ue_cx"), "aid", Seq("qs", "ue_cx"), true),
      (Seq("special1", "special2"), "normal", Seq("normal", "special2"), true),
      (Seq.empty, "current", Seq("previous1", "previous2"), false),
      (Seq("special1"), "current", Seq("previous1", "previous2"), false)
    )
    "check for special formats" in forAll(special) { (specialFlags, currentElement, previousElements, isAnInstance) =>
      transform.isSpecial(specialFlags)(currentElement, previousElements) should equal(isAnInstance)
    }

    val filters = Table(
      ("example", "isAnInstance"),
      ("[?(@.price=~10)]", true),
      ("[?(@.schema=~iglu:de.heyjobs/job_search_result_context/jsonschema/1-0-3)]", true),
      // unsupported operators
      ("[?(@.price > 10)]", false),
      // unsupported filter all
      ("[?(@.isbn)]", false)
    )
    "check filter strings" in forAll(filters) { (example, isAnInstance) =>
      transform.isFilter(example) should equal(isAnInstance)
    }

    val items = Table(
      ("example", "isAnInstance"),
      ("[1]", true),
      ("[]", false),
      // unsupported operators
      ("[:1]", false),
      // unsupported filter all
      ("[abc]", false)
    )
    "check array select strings" in forAll(items) { (example, isAnInstance) =>
      transform.isArrayItem(example) should equal(isAnInstance)
    }

    val nvps = Table(
      ("current", "previous", "isAnInstance"),
      ("lorem", Seq("ipsum", "dolor"), false),
      ("parameters", Seq("ipsum", "dolor"), true),
      ("lorem", Seq("parameters", "dolor"), true),
      ("querystring", Seq("ipsum", "dolor"), true),
      ("lorem", Seq("querystring", "dolor"), true)
    )
    "check nvp fields" in forAll(nvps) { (current, prev, isAnInstance) =>
      transform.isNVPs(current, prev) should equal(isAnInstance)
    }

    val fir = Table(
      ("jsonStr", "path", "value", "result"),
      ("""{"field": {"nested": [{"name":"a"},{"name":"b"}]}}""", Seq("field", "nested"), "a", true)
    )
    "find in array" in forAll(fir) { (jsonStr, path, value, result) =>
      val json = parse(jsonStr).right.get.hcursor
      transform.findInArray(json, path, value.r) should equal(result)
    }

    "zip json tree" in {
      val json     = """{"field": {"nested": [{"name":"a"},{"name":"b"}]}}"""
      val expected = """{"field": {"nested": [{"name":"a"}]}}"""
      val zipper   = parse(json).map(_.hcursor.downField("field").downField("nested").downN(1).delete).right.get
      val res      = transform.top(err => InvalidJsonFormat(err))(zipper)(zipper)
      res should equal(parse(expected))
    }

    "apply a function to url-encoded data" in {
      val escapedJsonString = Json.fromString("""{"nested": "{\"name\":\"a\",\"id\":123}" }""")
      val expected          = Json.fromString("""{"nested":"replaced"}""")
      val fn: Seq[String] => Json => Recovering[Json] =
        (path: Seq[String]) =>
          (j: Json) =>
            j.hcursor.downField(path.head).set(Json.fromString("replaced")).top.toRight(InvalidJsonFormat("boom"))
      transform.urlFn(fn)(Seq("nested"))(escapedJsonString).right.value should equal(expected)
    }

    "apply a function to b64-encoded data" in {
      val escapedJsonString = Json.fromString("""eyJuZXN0ZWQiOiB7Im5hbWUiOiJhIiwiaWQiOjEyM30gfQ==""")
      val expected          = Json.fromString("""eyJuZXN0ZWQiOiJyZXBsYWNlZCJ9""")
      val fn: Seq[String] => Json => Recovering[Json] =
        (path: Seq[String]) =>
          (j: Json) =>
            j.hcursor.downField(path.head).set(Json.fromString("replaced")).top.toRight(InvalidJsonFormat("boom"))
      transform.b64Fn(fn)(Seq("nested"))(escapedJsonString).right.value should equal(expected)
    }
  }

}
