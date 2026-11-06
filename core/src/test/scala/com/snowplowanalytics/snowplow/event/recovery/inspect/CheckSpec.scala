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
package inspect

import cats.implicits._
import org.scalatest._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import io.circe.syntax._
import monocle.macros.syntax.lens._
import com.snowplowanalytics.snowplow.badrows.NVP
import config.conditions._
import json.path
import com.snowplowanalytics.snowplow.event.recovery.util.querystring

class CheckSpec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues {

  "check" should {
    "apply matchers to values" when {

      "checking values" in forAll(gens.badRowSizeViolationA.arbitrary) { br =>
        val version = "0.0.0"
        val json    = br.lens(_.processor.version).set(version).asJson
        check(Compare(version.asJson))(path("processor.version"))(json).value should equal(true)
      }

      "using filters" in {
        import org.scalacheck.{Gen, Shrink}
        implicit val noShrinkS: Shrink[String] = Shrink.shrinkAny

        // Alphanumeric and longer than any name querystringGen makes (7 chars), so the =~ regex matches only this one
        val targetName = Gen.choose(8, 12).flatMap(Gen.listOfN(_, Gen.alphaNumChar)).map(_.mkString)

        forAll(
          gens.badRowAdapterFailuresA.arbitrary,
          gens.querystringGen(gens.validParamGen).suchThat(_.size >= 1),
          targetName,
          gens.validParamGen
        ) { (br, qs, name, value) =>
          val params = querystring.toNVP(s"$name=$value&$qs")
          params.head shouldBe NVP(name, Some(value))
          val json = br.lens(_.payload.querystring).set(params).asJson
          check(Compare(value.asJson))(path(s"payload.querystring.[?(@.name=~${name})].value"))(json)
            .right
            .value should equal(true)
        }
      }

      "filter base64-encoded fields" in {
        val name  = "iglu:com.snowplowanalytics.snowplow/web_page/jsonschema/1-0-0"
        val value = "39a9934a-ddd3-4581-a4ea-d0ba20e63b92"
        val base64Ctx = util
          .base64
          .encode(
            s"""{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0","data":[{"schema":"${name}","data":{"id":"${value}"}}]}"""
          )
          .value
        val json =
          s"""{"schema":"iglu:com.snowplowanalytics.snowplow.badrows/schema_violations/jsonschema/2-0-0","data":{"processor":{"artifact":"stream-enrich","version":"1.3.0"},"failure":{"timestamp":"2020-07-31T23:57:03.934Z","messages":[{"schemaKey":"iglu:com.dpm/video_impression/jsonschema/1-0-0","error":{"error":"ResolutionError","lookupHistory":[]}}]},"payload":{"enriched":{"se_value":null,"tr_orderid":null,"tr_affiliation":null,"app_id":"bad"},"raw":{"vendor":"com.snowplowanalytics.iglu","version":"v1","parameters":[{"name":"e","value":"ue"},{"name":"aid","value":"b78"},{"name":"tv","value":"com.snowplowanalytics.iglu-v1"},{"name":"cx","value":"${base64Ctx}"}],"contentType":null,"loaderName":"ssc-1.0.1-kinesis","encoding":"UTF-8","hostname":"p.tvpixel.com","timestamp":"2020-07-31T23:57:02.501Z","ipAddress":"00.000.000.000","useragent":"Ro170A)","refererUri":null,"headers":[],"userId":"3e02e9c7-0000-0000-0000-b592ed92120d"}}}}"""

        check(Compare(value.asJson))(path(s"data.payload.raw.parameters.cx.data.[?(@.schema=~$name)].data.id"))(
          io.circe.parser.parse(json).value
        ).value should equal(true)

      }

      "checking arrays" in forAll(gens.badRowAdapterFailuresA.arbitrary) { br =>
        check(RegularExpression(".*"))(json.path("failure.messages.[0]"))(br.asJson) should be('right)
      }

    }
  }
}
