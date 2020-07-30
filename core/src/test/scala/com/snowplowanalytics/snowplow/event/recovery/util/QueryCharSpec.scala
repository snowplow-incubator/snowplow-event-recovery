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
import org.scalacheck.Gen
import shapeless.syntax.sized._

import util.QueryChar._
import org.scalacheck.Shrink

class QueryCharSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {
  implicit val noShrink: Shrink[String] = Shrink.shrinkAny

  val percentGen: Gen[(Char, Char)] = Gen.listOfN(2, Gen.hexChar).map(_.sized(2).map(_.tupled)).map(_.get)

  "QueryChar" should {
    "normalize query parameter string" when {
      "encoded characters supplied" in forAll(percentGen) { case ((c1: Char, c2: Char)) =>
        normalize(Encoded(c1, c2)) shouldEqual s"%$c1$c2"
      }
      "bracketed characters supplied" in { (s: String) =>
        normalize(Bracketed('[', s.map(Invalid(_)).toList, ']')) shouldEqual s"[${jEncode(s)}]"
      }
      "invalid characters supplied" in { (c: Char) =>
        normalize(Invalid(c)) shouldEqual jEncode(c.toString)
      }      
    }
  }

  val jEncode: String => String = URLEncoder.encode(_, UTF_8.toString)
}
