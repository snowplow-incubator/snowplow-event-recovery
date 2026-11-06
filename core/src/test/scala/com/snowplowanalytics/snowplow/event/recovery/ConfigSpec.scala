/*
 * Copyright (c) 2023-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.event.recovery

import org.scalatest.Inspectors
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.EitherValues._
import org.scalatestplus.scalacheck._

import cats.Id
import io.circe.parser.decode

import config._
import json._
import org.scalacheck._
import gens._

class ConfigSpec extends AnyWordSpec with Inspectors with ScalaCheckPropertyChecks {

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
        conf.value.schema.toSchemaUri should equal(schemaKey)
      }
    }
    "be a proper JSON" in {
      forAll(configGen) { config =>
        val schemed = schema(schemaKey, config)
        val conf    = decode[Conf](schemed)

        conf.value.schema.toSchemaUri should equal(schemaKey)
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
        validateSchema[Id](schemed, resolverConfig).value shouldEqual Right(())
      }
    }
    "fail when validating something that is not json" in {
      validateSchema[Id]("abc", "abc").value.left.value should include(
        "ParsingFailure: expected json value got 'abc' (line 1, column 1)"
      )
    }
    "fail when validating something that is not following schema" in {
      val badConfig =
        """{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/2-0-0","data":{}}"""
      validateSchema[Id](badConfig, resolverConfig).value.left.value should include(
        "Instance is not valid against its schema"
      )
    }
  }

}
