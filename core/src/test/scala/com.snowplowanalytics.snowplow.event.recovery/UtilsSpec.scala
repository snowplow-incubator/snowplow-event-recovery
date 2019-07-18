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

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import org.scalatest.{FreeSpec, EitherValues}
import org.scalatest.Matchers._
import org.scalatest.prop.PropertyChecks

import gens._
import RecoveryScenario._
import utils._

class UtilsSpec extends FreeSpec with PropertyChecks with EitherValues {
  "thriftSerDe" - {
    "should deserialize any collector payload" in {
      forAll { (cp: CollectorPayload) =>
        val oldCp = new CollectorPayload(cp)
        val newCp = (thriftSer andThen thriftDeser)(cp)
        oldCp shouldEqual newCp
      }
    }
  }

  "decodeBase64" - {
    "should successfully decode base64" in {
      decodeBase64("YWJjCg==") shouldEqual Right("abc\n")
    }
    "should send an error message if not base64" in {
      decodeBase64("é").left.value should include("Configuration is not properly base64-encoded")
    }
  }

  val json = """{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0","data":[{"name":"ReplaceInBody","error":"","toReplace":"nam","replacement":"name"}]}"""

  "parseRecoveryScenarios" - {
    "should successfully parse a well-formed list of reco scenarios" in {
      parseRecoveryScenarios(json) shouldEqual Right(List(ReplaceInBody("", "nam", "name")))
    }
    "should fail at parsing non json" in {
      parseRecoveryScenarios("abc") shouldEqual
        Left("Configuration is not properly formatted: expected json value got 'abc' (line 1, column 1)")
    }
    "should fail at parsing a json which doesn't have a data field" in {
      parseRecoveryScenarios("""{"abc":12}""").left.value should include("Configuration is not properly formatted")
    }
    "should fail at parsing a json which is not a list of reco scenarios" in {
      parseRecoveryScenarios("""{"data":[{"abc":12}]}""").left.value should include("Configuration is not properly formatted")
    }
  }

  "validateConfiguration" - {
    "should successfully validate a properly schemad json" in {
      validateConfiguration(json) shouldEqual Right(())
    }
    "should fail when validating something that is not json" in {
      validateConfiguration("abc").left.value should include("expected json value got 'abc' (line 1, column 1)")
    }
    "should fail when validating something that is not according to schema" in {
      val json = """{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0","data":[{"abc":12}]}"""
      validateConfiguration(json).left.value should include("""{"error":"ValidationError","dataReports":[{"message":"$[0].abc: is not defined in the schema and the schema does not allow additional properties","path":"$[0]","keyword":"additionalProperties","targets":["abc"]},{"message":"$[0].name: is missing but it is required","path":"$[0]","keyword":"required","targets":["name"]},{"message":"$[0].error: is missing but it is required","path":"$[0]","keyword":"required","targets":["error"]},{"message":"$[0].toRemove: is missing but it is required","path":"$[0]","keyword":"required","targets":["toRemove"]},{"message":"$[0].toReplace: is missing but it is required","path":"$[0]","keyword":"required","targets":["toReplace"]},{"message":"$[0].replacement: is missing but it is required","path":"$[0]","keyword":"required","targets":["replacement"]}]}""")
    }
  }
}
