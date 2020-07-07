/*
 * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
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

import org.scalatest.{Inspectors, WordSpec}
import org.scalatest.Matchers._
import org.scalatest.EitherValues._
import org.scalatestplus.scalacheck._
import io.circe.parser.decode

import config._
import json._
import org.scalacheck._
import gens._

class ConfigSpec extends WordSpec with Inspectors with ScalaCheckPropertyChecks {

  val data = (badRowType: String, conditions: String, steps: String) =>
    s"""{"iglu:com.snowplowanalytics.snowplow.badrows/$badRowType/jsonschema/1-0-0":[{"name":"lorem-ipsum","conditions": $conditions,"steps":$steps}]}"""

  val schema = (schemaKey: String, data: String) => s"""{"schema": "$schemaKey", "data": $data}"""

  val badRowType = "tracker_protocol_violations"
  val schemaKey  = "iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/2-0-1"

  val matchlessReplace =
    s"""{ "op": "Replace", "path": "root.payload.body", "value": "aaa" }"""
  val replace =
    s"""{ "op": "Replace", "path": "root.payload.body", "match": ".*", "value": "aaa" }"""
  val regex =
    s"""{ "op": "Test", "path": "root.payload.body", "value": { "regex": ".*" } }"""
  val sizeGt =
    s"""{ "op": "Test", "path": "root.payload.body", "value": { "size": { "gt": 3 } }  }"""
  val compare =
    s"""{ "op": "Test", "path": "root.payload.body", "value": { "value": { "error": "RuntimeException" }  } }"""

  val conditions = Seq(regex, sizeGt, compare)
  val steps      = Seq(matchlessReplace, replace)
  val arr        = (s: Seq[String]) => s"""[${s.mkString(", ")}]"""

  val configGen = for {
    stepsN      <- Gen.chooseNum(1, 10)
    conditionsN <- Gen.chooseNum(0, 10)
    steps       <- Gen.listOfN(stepsN, Gen.oneOf(steps))
    conditions  <- Gen.listOfN(conditionsN, Gen.oneOf(conditions))
  } yield data(badRowType, arr(conditions), arr(steps))

  "Config" should {
    "follow a known JSON structure" in {
      forAll(configGen) { config =>
        val schemed = schema(schemaKey, config)
        val conf    = decode[Conf](schemed)
        conf.right.value.schema.toSchemaUri should equal(schemaKey)
      }
    }
    "be a proper JSON" in {
      forAll(configGen) { config =>
        val schemed = schema(schemaKey, config)
        val conf    = decode[Conf](schemed)

        conf.right.value.schema.toSchemaUri should equal(schemaKey)
      }
    }
  }

  val resolverConfig =
    """{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":0,"repositories":[{"name":"Priv","priority":1,"vendorPrefixes":["com.snowplowanalytics"],"connection":{"http":{"uri":"https://raw.githubusercontent.com/peel/schemas/master"}}}]}}"""

  "validateConfiguration" should {
    "should successfully validate a properly schemed json" in {
      import org.scalacheck.Shrink
      implicit val noShrink: Shrink[String] = Shrink.shrinkAny
      forAll(configGen) { config =>
        val schemed = schema(schemaKey, config)
        validateSchema(schemed, resolverConfig).value shouldEqual Right(())
      }
    }
    "fail when validating something that is not json" in {
      validateSchema("abc", "abc").value.left.value should include(
        "ParsingFailure: expected json value got 'abc' (line 1, column 1)"
      )
    }
    "fail when validating something that is not following schema" in {
      val badConfig =
        """{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/2-0-0","data":{}}"""
      validateSchema(badConfig, resolverConfig).value.left.value should include(
        "Instance is not valid against its schema"
      )
    }
  }

}
