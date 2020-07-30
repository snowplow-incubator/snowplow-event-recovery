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

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import cats.syntax.either._
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
        "e={ue}&tv=${js}&ue_px={{unknown}}&pv=[pv !@]&er=(aaa,bbb)&zx=iglu:test&xz='lorem'"
      val expected = Map(
        "e"     -> "%7Bue%7D",
        "tv"    -> "$%7Bjs%7D",
        "pv"    -> "[pv+!@]",
        "ue_px" -> "%7B%7Bunknown%7D%7D",
        "er"    -> "(aaa,bbb)",
        "zx"    -> "iglu:test",
        "xz"    -> "'lorem'"
      )
      val recovered = params(issues).map(_.mapValues(clean)).right.value
      recovered shouldEqual expected
      mkQS(recovered) shouldEqual "e=%7Bue%7D&zx=iglu:test&tv=$%7Bjs%7D&pv=[pv+!@]&xz='lorem'&ue_px=%7B%7Bunknown%7D%7D&er=(aaa,bbb)"
      javaDecode(mkQS(recovered)) should be('right)
    }
    "not manipulate iglu uris" in {
      val uri = "iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/4-0-0"
      clean(uri) shouldEqual uri
      javaDecode(uri) should be('right)
    }
    "fix special characters" in {
      forAll { (p: String) =>
        javaEncode(p).right.value shouldEqual clean(p)
      }
    }
    "convert strings to lists of NVPs and back" in {
      forAll(querystringGen(validParamGen)) { qs =>
        params(qs).map(toNVP).map(fromNVP).right.value shouldEqual javaDecode(qs).right.value
      }
    }
    "produce BadRow for invalid line data" in {
      orBadRow(null, None) should be('left)
    }
  }

  val mkQS: Map[String, String] => String                    = _.map { case (k, v) => s"$k=$v" }.mkString("&")
  val javaEncode: String        => Either[Throwable, String] = x => Either.catchNonFatal(URLEncoder.encode(x, UTF_8.toString))
  val javaDecode: String        => Either[Throwable, String] = x => Either.catchNonFatal(URLDecoder.decode(x, UTF_8.toString))
}
