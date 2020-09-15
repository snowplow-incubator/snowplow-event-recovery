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
import io.circe.syntax._
import monocle.macros.syntax.lens._
import com.snowplowanalytics.snowplow.badrows.NVP
import config.conditions._
import json.path
import com.snowplowanalytics.snowplow.event.recovery.util.querystring

class CheckSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {

  "check" should {
    "apply matchers to values" when {

      "checking values" in forAll(gens.badRowSizeViolationA.arbitrary) { br =>
        val version = "0.0.0"
        val json    = br.lens(_.processor.version).set(version).asJson
        check(Compare(version.asJson))(path("processor.version"))(json).right.value should equal(true)
      }

      "using filters" in {
        import org.scalacheck.Shrink
        implicit val noShrinkS: Shrink[String] = Shrink.shrinkAny

        forAll(gens.badRowAdapterFailuresA.arbitrary, gens.querystringGen(gens.validParamGen).suchThat(_.size >= 1)) {
          (br, qs) =>
            val params           = querystring.toNVP(qs)
            val NVP(name, value) = params.head
            val json             = br.lens(_.payload.querystring).set(params).asJson
            check(Compare(value.getOrElse("").asJson))(path(s"payload.querystring.[?(@.name=~${name})].value"))(json)
              .right
              .value should equal(true)
        }
      }

      "checking arrays" in forAll(gens.badRowAdapterFailuresA.arbitrary) { br =>
        check(RegularExpression(".*"))(json.path("failure.messages.[0]"))(br.asJson) should be('right)
      }

    }
  }
}
