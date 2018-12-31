/*
 * Copyright (c) 2018-2018 Snowplow Analytics Ltd. All rights reserved.
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

import frameless.syntax._
import frameless.TypedDataset
import org.scalatest.Matchers._

import model._
import RecoveryScenario._

class RecoveryJobSpec extends SparkSpec {
  implicit val session = spark
  "RecoveryJob" - {
    "filter" - {
      "should filter based on the criteria passed as arguments" in {
        val badRows = List(
          BadRow("line", List(Error("warn", "message")), "tstamp"),
          BadRow("line", List(Error("warn", ""), Error("error", "message")), "tstamp"),
          BadRow("line", List.empty, "tstamp"),
          BadRow("line", List(Error("warn", "msg")), "tstamp")
        )
        val ds = TypedDataset.create(badRows)
        val recoveryScenarios = List(PassThrough("message"))
        val filtered = RecoveryJob.filter(ds, recoveryScenarios)
        filtered.take(2).run() shouldEqual badRows.take(2)
        filtered.count().run() shouldEqual 2
      }
    }
  }
}
