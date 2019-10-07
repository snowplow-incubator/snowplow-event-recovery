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

import java.time.{Clock => JClock, Instant, ZoneId}
import org.scalatest._
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import com.snowplowanalytics.snowplow.badrows.Schemas

import gens._

import util.paths

class PathsSpec extends FreeSpec with ScalaCheckPropertyChecks with EitherValues {

  "path" - {
    "should provide a standard path for bad rows" in {
      forAll(nonEmptyString.arbitrary) { output =>
        val schema = Schemas.RecoveryError
        val time = JClock.fixed(Instant.now, ZoneId.systemDefault)
        val Array(p, sk, ts) = paths.path(output, schema, time).split('/')
        p should equal (output)
        sk.startsWith(schema.vendor)
        sk.endsWith(schema.name)
        ts should equal (time.millis.toString)
      }
    }
  }

  "trimDir" - {
    "should sanitize paths" in {
      forAll(nonEmptyString.arbitrary) { path =>
        val trimmed = paths.trimDir(s"gs://$path")
        trimmed should not endWith ("/")
        trimmed should not endWith ("*")
      }
    }
  }
  "append" - {
    "should make a path with a suffix" in {
      forAll(nonEmptyString.arbitrary) { path =>
        val appended = paths.append("unrecovered")(path)
        appended should startWith (path)
        appended should endWith ("/unrecovered")
      }
    }
  }
}
