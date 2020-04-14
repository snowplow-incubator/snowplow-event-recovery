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
package inspect

import cats.syntax.option._
import cats.instances.either._
import org.scalatest._
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import io.circe.syntax._
import io.circe.parser.parse
import com.snowplowanalytics.snowplow.badrows._
import Data._

class ReplaceSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {

  "replace" should {
    "replace values" when {
      "matcher supplied" in {
        forAll(gens.badRowSizeViolationA.arbitrary) { br =>
          val json     = br.asJson
          val expected = "com.lorem.ipsum.dolor"
          val replaced =
            replace("(?U)^.*$".some, expected)(Seq("processor", "artifact"))(json)

          replaced
            .flatMap {
              _.hcursor.downField("processor").downField("artifact").focus.get.as[String]
            }
            .right
            .value should equal(expected)
        }
      }
      "no matcher supplied" in {
        forAll(gens.badRowSizeViolationA.arbitrary) { br =>
          val json     = br.asJson
          val expected = "com.lorem.ipsum.dolor"
          val replaced =
            replace(None, expected)(Seq("processor", "artifact"))(json)

          replaced
            .flatMap {
              _.hcursor.downField("processor").downField("artifact").focus.get.as[String]
            }
            .right
            .value should equal(expected)
        }
      }
    }
    "replace objects" in {
      forAll(gens.badRowSizeViolationA.arbitrary) { br =>
        val json     = br.asJson
        val expected = Processor("lorem", "ipsum")
        val replaced =
          replace("(?U)^.*$".some, expected.asJson.noSpaces)(Seq("processor"))(json)
        replaced
          .flatMap {
            _.hcursor.downField("processor").as[Processor]
          }
          .right
          .value should equal(expected)
      }
    }
    "raise for unknown field in base64-encoded parameters" in {
      val expected = "[]"
      replace(
        "(?U)^.*$".some,
        expected
      )(Seq("payload", "raw", "parameters", "cx", "impossible"))(base64Field).left.value.message should startWith(
        "Failed to replace"
      )
    }
    "replace base64-encoded values" in {
      val expected = "[]"
      val replaced = replace(
        "(?U)^.*$".some,
        expected
      )(Seq("payload", "raw", "parameters", "cx", "schema"))(base64Field)

      replaced
        .flatMap {
          _.hcursor
            .downField("payload")
            .downField("raw")
            .downField("parameters")
            .downN(6)
            .focus
            .get
            .as[NVP]
            .map(_.value)
            .map(_.get)
            .flatMap(
              util
                .base64
                .decode(_)
                .flatMap(parse)
                .map(
                  _.hcursor.downField("schema").focus.get.as[String].right.get
                )
            )
        }
        .right
        .value should equal(expected)
    }
  }
  "replace array base64-encoded values" in {
    val expected = "1"
    val replaced = replace("(?U)^.*$".some, expected)(
      Seq(
        "payload",
        "raw",
        "parameters",
        "cx",
        "data",
        "[1]",
        "data",
        "domComplete"
      )
    )(base64Array)
    replaced
      .flatMap {
        _.hcursor
          .downField("payload")
          .downField("raw")
          .downField("parameters")
          .downN(6)
          .focus
          .get
          .as[NVP]
          .map(_.value)
          .map(_.get)
          .flatMap(
            util
              .base64
              .decode(_)
              .flatMap(parse)
              .map(
                _.hcursor
                  .downField("data")
                  .downN(1)
                  .downField("data")
                  .downField("domComplete")
                  .focus
                  .get
                  .as[Int]
                  .right
                  .get
              )
          )
      }
      .right
      .value should equal(expected.toInt)
  }
  "replace filtered base64-encoded values" in {
    val expected = "1"
    val replaced = replace("(?U)^.*$".some, expected)(
      Seq(
        "payload",
        "raw",
        "parameters",
        "cx",
        "data",
        "[?(@.data.navigationStart=~([0-9]+))]",
        "data",
        "domComplete"
      )
    )(base64Array)
    replaced
      .flatMap {
        _.hcursor
          .downField("payload")
          .downField("raw")
          .downField("parameters")
          .downN(6)
          .focus
          .get
          .as[NVP]
          .map(_.value)
          .map(_.get)
          .flatMap(
            util
              .base64
              .decode(_)
              .flatMap(parse)
              .map(
                _.hcursor
                  .downField("data")
                  .downN(1)
                  .downField("data")
                  .downField("domComplete")
                  .focus
                  .get
                  .as[Int]
                  .right
                  .get
              )
          )
      }
      .right
      .value should equal(expected.toInt)
  }
  "replace url-encoded values" in {
    val expected = "666"
    val replaced = replace(
      "(?U)^.*$".some,
      expected
    )(Seq("payload", "enriched", "contexts", "data", "[1]", "data", "loadEventEnd"))(base64Field)

    replaced
      .flatMap {
        _.hcursor
          .downField("payload")
          .downField("enriched")
          .downField("contexts")
          .withFocusM(
            _.as[String]
              .flatMap(parse)
              .flatMap(_.hcursor.downField("data").downN(1).downField("data").get[io.circe.Json]("loadEventEnd"))
          )
          .flatMap(_.focus.toRight("empty focus"))
      }
      .right
      .value
      .noSpaces should equal(expected)
  }

}
