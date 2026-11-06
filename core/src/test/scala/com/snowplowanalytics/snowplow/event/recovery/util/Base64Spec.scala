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

import org.scalatest._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import util.base64

class Base64Spec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues {

  "decodeBase64" should {
    "successfully decode base64" in {
      base64.decode("YWJjCg==") shouldEqual Right("abc\n")
    }
    "send an error message if not base64" in {
      base64.decode("é").left.value.message should include("Data is not properly base64-encoded")
    }
    "successfilly decode url-unsafe alphabet base64 string" in {
      val expected = """What does 2 + 2.1 equal?? ~ 4"""
      base64.decode("V2hhdCBkb2VzIDIgKyAyLjEgZXF1YWw/PyB+IDQ=") shouldEqual Right(expected)
      base64.decode("V2hhdCBkb2VzIDIgKyAyLjEgZXF1YWw_PyB-IDQ") shouldEqual Right(expected)
    }
  }

  "encodeBase64" should {
    "successfully decode base64" in {
      base64.encode("abc\n") shouldEqual Right("YWJjCg==")
    }
  }

}
