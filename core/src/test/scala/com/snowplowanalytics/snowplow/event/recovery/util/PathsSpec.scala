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
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import com.snowplowanalytics.snowplow.badrows.Schemas

import gens._

import util.paths

class PathsSpec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues {

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
