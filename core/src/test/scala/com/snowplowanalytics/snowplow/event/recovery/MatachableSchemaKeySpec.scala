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
package domain

import org.scalatest._
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck._
import gens._
import org.scalacheck.Gen

class MatchableSchemaKeySpec extends WordSpec with ScalaCheckPropertyChecks with OptionValues {
  "MatchableSchemaKey" should {
    "should be able to parse uri" in {
      forAll(igluUriGen) { uri =>
        MatchableSchemaKey.parse(uri).isDefined
      }
    }
    "should be able to match uris" in {
      forAll(igluUriGen, Gen.posNum[Int]) { (uri, specific) =>
        val starred = uri.replaceAll("\\*", specific.toString)
        MatchableSchemaKey.matchSchema(uri, starred) should equal(true)
      }
    }
  }
}
