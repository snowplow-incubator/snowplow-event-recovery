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
package domain

import org.scalatest._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck._
import gens._
import org.scalacheck.Gen

class MatchableSchemaKeySpec extends AnyWordSpec with ScalaCheckPropertyChecks with OptionValues {
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
