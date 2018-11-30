package com.snowplowanalytics.snowplow
package event.recovery

import org.scalatest.{FreeSpec, EitherValues}
import org.scalatest.Matchers._
import org.scalatest.prop.PropertyChecks

import CollectorPayload.thrift.model1.CollectorPayload
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
      validateConfiguration("abc").left.value should include("Unexpected character ('a'")
    }
    "should fail when validating something that is not according to schema" in {
      val json = """{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0","data":[{"abc":12}]}"""
      validateConfiguration(json).left.value should include("error: instance failed to match at least one required schema among 5")
    }
  }
}
