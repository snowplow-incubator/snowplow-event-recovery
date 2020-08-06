/*
 * Copyright (c) 2018-2020 Snowplow Analytics Ltd. All rights reserved.
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
package config

import org.scalatest.{EitherValues, Inspectors, WordSpec}
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck._

import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.snowplow.badrows.{BadRow, Schemas}

import gens._
import conditions._
import io.circe.syntax._

class BadRowWithConfigSpec extends WordSpec with Inspectors with ScalaCheckPropertyChecks with EitherValues {

  val mkCfg = (conditions: List[Condition]) => FlowConfig("default", conditions, List.empty)
  "BadRowWithConfig" should {
    "find config matching bad row" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val cfg            = mkCfg(List.empty)
        val config: Config = Map(Schemas.AdapterFailures.toSchemaUri -> List(cfg))
        val badRow         = SelfDescribingData[BadRow](Schemas.AdapterFailures, b)
        BadRowWithConfig.find(config, badRow).right.value should equal(cfg)
      }
    }
    "handle direct comparison matchers" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val cfg = mkCfg(
          List(Condition(conditions.Test, "$.processor.artifact", Compare(b.processor.artifact.asJson)))
        )
        val config: Config = Map(Schemas.AdapterFailures.toSchemaUri -> List(cfg))
        val badRow         = SelfDescribingData[BadRow](Schemas.AdapterFailures, b)
        val res            = BadRowWithConfig.find(config, badRow)
        res.right.value should equal(cfg)
      }
    }
    "handle size matchers on arrays" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val size           = Size(Size.Eq(b.payload.querystring.size))
        val cfg            = mkCfg(List(Condition(conditions.Test, "$.payload.querystring", size)))
        val config: Config = Map(Schemas.AdapterFailures.toSchemaUri -> List(cfg))
        val badRow         = SelfDescribingData[BadRow](Schemas.AdapterFailures, b)

        BadRowWithConfig.find(config, badRow).right.value should equal(cfg)
      }
    }
  }

}
