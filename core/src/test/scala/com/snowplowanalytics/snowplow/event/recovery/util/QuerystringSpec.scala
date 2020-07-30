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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
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
      forAll(querystringGen(paramGen)) { qs =>
        val p = params(qs)
        p.right.value.size shouldEqual qs.split("&").size
      }
    }
    "fix known querystring issues" in {
      val issues =
        "e={ue}&tv=${js}&ue_px={{unknown}}&pv=[pv !@]&er=(aaa,bbb)"
      val expected = Map("e" -> "%7Bue%7D", "tv" -> "$%7Bjs%7D", "pv" -> "%5Bpv+!@%5D", "ue_px" -> "%7B%7Bunknown%7D%7D", "er" -> "%28aaa,bbb%29")
      val recovered = params(issues).map(_.mapValues(clean)).right.value
      recovered shouldEqual expected
      recovered.map{case (k,v) => s"$k=$v"}.mkString("&") shouldEqual "e=%7Bue%7D&tv=$%7Bjs%7D&pv=%5Bpv+!@%5D&ue_px=%7B%7Bunknown%7D%7D&er=%28aaa,bbb%29"
    }
    "fix special characters" in {
      forAll{ (p: String) =>
        URLEncoder.encode(p, UTF_8.toString) shouldEqual clean(p)
      }
    }
    "convert strings to lists of NVPs and back" in {
      forAll(querystringGen(validParamGen)) { qs =>
        params(qs).map(toNVP).map(fromNVP).right.value shouldEqual java.net.URLDecoder.decode(qs, UTF_8.toString)
      }
    }
    "produce BadRow for invalid line data" in {
      orBadRow(null, None) should be('left)
    }
  }
}
