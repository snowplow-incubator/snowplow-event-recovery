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

import CollectorPayload.thrift.model1.CollectorPayload
import gens._

import util.thrift

class ThriftSpec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues {
  "thriftSerDe" should {
    "deserialize any collector payload with base64-encoding" in {
      forAll { (cp: CollectorPayload) =>
        val oldCp = new CollectorPayload(cp)
        val newCp = thrift.serialize(cp).map(new String(_)).flatMap(thrift.deserialize)
        oldCp shouldEqual newCp.value
      }
    }
    "deserialize any collector payload without base64 encoding" in {
      forAll { (cp: CollectorPayload) =>
        val oldCp = new CollectorPayload(cp)
        val newCp = thrift.serializeNoB64(cp).flatMap(util.thrift.deser)
        oldCp shouldEqual newCp.value
      }
    }
  }

}
