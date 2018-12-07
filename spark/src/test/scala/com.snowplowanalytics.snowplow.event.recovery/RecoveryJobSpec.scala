package com.snowplowanalytics.snowplow.event.recovery

import java.nio.file.Files

import frameless.syntax._
import frameless.TypedDataset
import org.scalatest.FreeSpec
import org.scalatest.Matchers._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import model._
import RecoveryScenario._
import utils._

class RecoveryJobSpec extends SparkSpec {
  implicit val session = spark
  import session.implicits._
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
