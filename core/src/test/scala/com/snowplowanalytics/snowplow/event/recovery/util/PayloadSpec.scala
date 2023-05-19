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
import badrows.{NVP, Payload}
import CollectorPayload.thrift.model1.CollectorPayload
import gens._

import util.payload

class PayloadSpec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues {

  "co/coerce" should {
    "cocoerce the payload" in {
      forAll { (cp: CollectorPayload) =>
        val p = payload.cocoerce(cp)
        p should be('right)
      }
    }
    "cocoerce payloads with invalid UUIDs" in {
      forAll { (cp: CollectorPayload, str: String) =>
        cp.networkUserId = str
        payload.cocoerce(cp) should be('right)
      }
    }
    "coerce the collector payload" in {
      forAll { (p: Payload.CollectorPayload) =>
        val cp = payload.coerce(p)
        cp should be('right)
      }
    }
    "coerce and cocoerce the payload" in {
      forAll { (p: Payload.CollectorPayload) =>
        val cp          = payload.coerce(p)
        val newP        = payload.cocoerce(cp.value)
        val unifiedP    = unify(p)
        val unifiedNewP = unify(newP.value)
        unifiedP shouldEqual unifiedNewP
      }
    }
  }

  private[this] val unify = (p: Payload.CollectorPayload) => {
    val unifyToNone = (l: List[NVP]) =>
      l.map {
        case NVP(name, Some("")) => NVP(name, None)
        case x                   => x
      }

    p.copy(querystring = unifyToNone(p.querystring))
  }

}
