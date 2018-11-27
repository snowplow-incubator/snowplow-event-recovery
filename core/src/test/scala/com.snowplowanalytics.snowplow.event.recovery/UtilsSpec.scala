package com.snowplowanalytics.snowplow
package event.recovery

import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.prop.PropertyChecks

import CollectorPayload.thrift.model1.CollectorPayload
import gens._
import utils._

class UtilsSpec extends FreeSpec with PropertyChecks {
  "thriftDeser" - {
    "should deserialize any collector payload" in {
      forAll { (cp: CollectorPayload) =>
        val oldCp = new CollectorPayload(cp)
        val newCp = (thriftSer andThen thriftDeser)(cp)
        oldCp shouldEqual newCp
      }
    }
  }
}
