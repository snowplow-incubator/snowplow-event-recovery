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
import com.snowplowanalytics.snowplow.badrows.Schemas

import gens._

import util.paths

class PathsSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {

  "path" should {
    "provide a standard path for bad rows" in {
      forAll(nonEmptyString.arbitrary) { output =>
        val schema       = Schemas.RecoveryError
        val Array(p, sk) = paths.path(output, schema).split('/')
        p should equal(output)
        sk.startsWith(schema.vendor)
        sk.endsWith(schema.name)
      }
    }
  }

  "trimDir" should {
    "sanitize paths" in {
      forAll(nonEmptyString.arbitrary) { path =>
        val trimmed = paths.trimDir(s"gs://$path")
        (trimmed should not).endWith("/")
        (trimmed should not).endWith("*")
      }
    }
  }
  "append" should {
    "make a path with a suffix" in {
      forAll(nonEmptyString.arbitrary) { path =>
        val appended = paths.append("unrecovered")(path)
        appended should startWith(path)
        appended should endWith("/unrecovered")
      }
    }
  }
}
