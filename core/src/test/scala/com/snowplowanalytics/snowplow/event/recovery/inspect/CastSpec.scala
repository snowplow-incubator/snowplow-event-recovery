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

import cats.implicits._
import org.scalatest._
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import io.circe.syntax._
import io.circe.parser.parse
import monocle.macros.syntax.lens._
import com.snowplowanalytics.snowplow.badrows.{BadRow, NVP}
import config._
import Data._

class CastSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {

  "cast" should {
    "cast values" in {
      forAll(gens.badRowSizeViolationA.arbitrary) { br =>
        val artifact = "lorem-ipsum"
        val json     = br.lens(_.processor.artifact).set(artifact).asJson

        cast(CastType.String, CastType.Array)(Seq("processor", "artifact"))(json)
          .flatMap {
            _.hcursor.downField("processor").downField("artifact").focus.get.asArray.get.asRight
          }
          .right
          .value should contain(artifact.asJson)
      }

      cast(CastType.String, CastType.Numeric)(Seq("payload", "enriched", "br_colordepth"))(base64Field)
        .flatMap {
          _.hcursor.downField("payload").downField("enriched").downField("br_colordepth").as[Int]
        }
        .right
        .value should equal(24)

    }
    "raise cast failure for undefined casting rules" in {
      forAll(gens.recoverableBadRowA.arbitrary) { (br: BadRow) =>
        cast(CastType.Array, CastType.Boolean)(Seq("processor"))(br.asJson) should be('left)
      }
    }
    "cast base64-encoded values" in {
      val expected = "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0"
      val casted = cast(
        CastType.String,
        CastType.Array
      )(Seq("payload", "raw", "parameters", "cx", "schema"))(base64Field)

      casted
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
                  _.hcursor.downField("schema").focus.get.asArray.get
                )
            )
        }
        .right
        .value should contain(expected.asJson)
    }
  }
  "cast array base64-encoded values" in {
    val expected = true
    val casted = cast(CastType.Numeric, CastType.Boolean)(
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
    casted
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
                _.hcursor.downField("data").downN(1).downField("data").downField("domComplete").focus.get.asBoolean.get
              )
          )
      }
      .right
      .value should equal(expected)
  }
  "cast filtered base64-encoded values" in {
    val expected = true
    val casted = cast(CastType.Numeric, CastType.Boolean)(
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
    casted
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
                _.hcursor.downField("data").downN(1).downField("data").downField("domComplete").focus.get.asBoolean.get
              )
          )
      }
      .right
      .value should equal(expected)
  }

  "cast url-encoded values" in {
    val replaced = cast(CastType.Numeric, CastType.String)(
      Seq("payload", "enriched", "contexts", "data", "[1]", "data", "loadEventEnd")
    )(base64Field)
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
      .value should equal("0".asJson)
  }

}
