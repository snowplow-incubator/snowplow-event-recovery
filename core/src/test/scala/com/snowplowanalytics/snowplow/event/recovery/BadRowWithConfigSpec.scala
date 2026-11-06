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
package config

import org.scalatest.{EitherValues, Inspectors}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatestplus.scalacheck._

import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.snowplow.badrows.{BadRow, Schemas}

import gens._
import conditions._
import io.circe.syntax._
import java.util.UUID

class BadRowWithConfigSpec extends AnyWordSpec with Inspectors with ScalaCheckPropertyChecks with EitherValues {

  val mkCfg = (conditions: List[Condition]) => FlowConfig("default", conditions, List.empty)
  "BadRowWithConfig" should {
    "find config matching bad row" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val cfg            = mkCfg(List.empty)
        val config: Config = Map(Schemas.AdapterFailures.toSchemaUri -> List(cfg))
        val badRow         = SelfDescribingData[BadRow](Schemas.AdapterFailures, b)
        BadRowWithConfig.find(config, badRow).value should equal(List(cfg))
      }
    }
    "find config all configs matching bad row" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val uuid = UUID.randomUUID()
        val bad  = b.copy(payload = b.payload.copy(networkUserId = Some(uuid)))
        val cfgWithConditions = FlowConfig(
          "default",
          List(Condition(op = Test, path = "$.payload.networkUserId", value = RegularExpression(uuid.toString()))),
          List(Replacement(conditions.Replace, "$.payload.raw.test", None, "sample".asJson))
        )
        val cfg = FlowConfig(
          "default",
          List.empty,
          List(Replacement(conditions.Replace, "$.payload.raw.hello", None, "also-a-sample".asJson))
        )
        val configs        = List(cfg, cfgWithConditions)
        val config: Config = Map(Schemas.AdapterFailures.toSchemaUri -> configs)
        val badRow         = SelfDescribingData[BadRow](Schemas.AdapterFailures, bad)
        BadRowWithConfig.find(config, badRow).value should equal(configs)
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
        res.value should equal(List(cfg))
      }
    }
    "handle size matchers on arrays" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val size           = Size(Size.Eq(b.payload.querystring.size))
        val cfg            = mkCfg(List(Condition(conditions.Test, "$.payload.querystring", size)))
        val config: Config = Map(Schemas.AdapterFailures.toSchemaUri -> List(cfg))
        val badRow         = SelfDescribingData[BadRow](Schemas.AdapterFailures, b)

        BadRowWithConfig.find(config, badRow).value should equal(List(cfg))
      }
    }
  }

}
