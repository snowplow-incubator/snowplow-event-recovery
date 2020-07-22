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
package com.snowplowanalytics.snowplow
package event.recovery

import org.scalatest._
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import gens._

import util.querystring._
import org.scalacheck.Shrink

class QuerystringSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {
  implicit val noShrink: Shrink[String] = Shrink.shrinkAny

  "querystring" should {
    "extract params from string" in {

      forAll(querystringGen) { qs =>
        val p = params(qs)
        p.right.value.size shouldEqual qs.split("&").size
      }
    }
    "extract values from query params" in {
      forAll(paramGen) { p =>
        (clean(p)).r.findFirstIn(p).isDefined should be(true)
      }
    }
    "convert strings to lists of NVPs and back" in {
      forAll(querystringGen) { qs =>
        fromNVP(toNVP(clean(qs))) shouldEqual clean(qs)
      }
    }
    "produce BadRow for invalid line data" in {
      orBadRow(null, None) should be('left)
    }
  }
}
