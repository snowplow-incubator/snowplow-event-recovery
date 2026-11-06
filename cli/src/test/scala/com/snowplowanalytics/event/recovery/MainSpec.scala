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

import org.scalatest.Inspectors
import org.scalatest.wordspec.AnyWordSpec

import org.scalatest.matchers.should.Matchers._
import com.snowplowanalytics.snowplow.event.recovery.inspect.Data.input
import os._

class MainSpec extends AnyWordSpec with Inspectors {

  "Main" should {
    "run a small-scale recovery" in {
      val output   = os.temp.dir()
      val examples = os.pwd / "examples"
      Main.run(
        input = examples.toString(),
        config = (examples / "config.json").toString(),
        resolver = None,
        output = Some(output.toString()),
        enrichments = None
      )

      val inputSize = os.read.lines(examples / "input.txt").size
      val goodCount = os.read.lines(output / "good.txt").size
      val badCount  = os.read.lines(output / "bad.txt").size

      inputSize should equal(goodCount)
      badCount should equal(0)

    }
  }
}
