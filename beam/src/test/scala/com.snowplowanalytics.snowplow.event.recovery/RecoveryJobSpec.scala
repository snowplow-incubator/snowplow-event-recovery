package com.snowplowanalytics.snowplow
package event.recovery

import java.util.Base64

import com.spotify.scio.ScioMetrics
import com.spotify.scio.testing._
import org.apache.thrift.TDeserializer

import CollectorPayload.thrift.model1.CollectorPayload
import utils._

class RecoveryJobSpec extends PipelineSpec {
  val recoveryScenarios = Base64.getEncoder.encodeToString("""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0",
      "data": [
        {
          "name": "PassThrough",
          "error": "Exception"
        }
      ]
    }
  """.getBytes)
  val fixedCollectorPayloads = Seq(
    {
      val c = new CollectorPayload(
        "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0",
        null, 0, "UTF-8", "c"
      )
      c.path = "/i"
      c.querystring = "e=pv&page={DemoPageTitle}"
      c
    }
  )
  val badRows = fixedCollectorPayloads.map { cp =>
    s"""
    {
      "line": "${thriftSer(cp)}",
      "errors": [
        {
          "level": "error",
          "message": "Exception"
        }
      ],
      "failure_tstamp": "1544614464"
    }
    """
  }

  "Recovery" should "fix a set of bad rows according to a set of recovery scenarios" in {
    JobTest[Main.type]
      .args("--inputDirectory=in", "--outputTopic=out", s"--config=$recoveryScenarios")
      .input(TextIO("in"), badRows)
      .output(PubsubIO[Array[Byte]]("out")) { s =>
        s should haveSize(1)
        s should forAll { cp: Array[Byte] =>
          val thriftDeserializer = new TDeserializer
          val payload = new CollectorPayload
          thriftDeserializer.deserialize(payload, cp)
          fixedCollectorPayloads.contains(payload)
        }
      }
      .counter(ScioMetrics.counter("snowplow", "bad_rows_recovered_PassThrough"))(_ shouldBe 1)
      .run()
  }
}
