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
