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
