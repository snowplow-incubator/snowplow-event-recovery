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
package com.snowplowanalytics.snowplow
package event.recovery

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen
import shapeless.syntax.sized._

import util.QueryChar._
import org.scalacheck.Shrink

class QueryCharSpec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues {
  implicit val noShrink: Shrink[String] = Shrink.shrinkAny

  val percentGen: Gen[(Char, Char)] = Gen.listOfN(2, Gen.hexChar).map(_.sized(2).map(_.tupled)).map(_.get)

  "QueryChar" should {
    "normalize query parameter string" when {
      "encoded characters supplied" in forAll(percentGen) { case (c1: Char, c2: Char) =>
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
