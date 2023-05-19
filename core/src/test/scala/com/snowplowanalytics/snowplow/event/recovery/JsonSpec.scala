/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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

import scala.util.Random
import org.scalatest.Inspectors
import org.scalatest.wordspec.AnyWordSpec

import org.scalatest.matchers.should.Matchers._
import org.scalatestplus.scalacheck._

import org.scalacheck._

class JsonSpec extends AnyWordSpec with Inspectors with ScalaCheckPropertyChecks {

  implicit val noShrink: Shrink[String] = Shrink.shrinkAny

  def listOf[A](gen: Gen[A]): Gen[List[A]]    = Gen.chooseNum(1, 1).flatMap(Gen.listOfN(_, gen))
  val listOfNonEmptyString: Gen[List[String]] = listOf(gens.nonEmptyString.arbitrary)
  val listOfInt: Gen[List[String]]            = listOf(Gen.posNum[Int].map(v => s"[$v]"))
  val pathGen = for {
    plain  <- listOfNonEmptyString
    filter <- listOfNonEmptyString.map(_.map(v => s"[?(@.$v.${v.reverse}=~($v))]"))
    item   <- listOfInt
    segments = plain ++ filter ++ item
  } yield (s"""$$.${Random.shuffle(segments).mkString(".")}""", segments.size)

  "path" should {
    "extract path segments for JsonPath" when {
      "arbitrary JsonPath provided" in forAll(pathGen) { case (path, segments) =>
        json.path(path).size should equal(segments)
      }
      "snake_case fields provided" in {
        val segments = List("payload", "raw", "parameters", "ue_px", "schema")
        val path     = segments.mkString(".")
        json.path(path) should contain theSameElementsAs segments
      }
    }
  }

}
