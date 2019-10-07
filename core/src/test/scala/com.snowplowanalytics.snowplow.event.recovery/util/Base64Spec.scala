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

import util.base64

class Base64Spec extends FreeSpec with ScalaCheckPropertyChecks with EitherValues {

  "decodeBase64" - {
    "should successfully decode base64" in {
      base64.decode("YWJjCg==") shouldEqual Right("abc\n")
    }
    "should send an error message if not base64" in {
      base64.decode("é").left.value should include("Configuration is not properly base64-encoded")
    }
  }

  "encodeBase64" - {
    "should successfully decode base64" in {
      base64.encode("abc\n") shouldEqual Right("YWJjCg==")
    }
  }

}
