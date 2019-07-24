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

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.{Base64, UUID}

import scala.collection.JavaConverters._

import cats.data.NonEmptyList
import cats.syntax.either._
import cats.syntax.option._
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.{CollectorPayload => CP}
import com.snowplowanalytics.snowplow.badrows.AdapterFailure._
import com.snowplowanalytics.snowplow.badrows.Failure.AdapterFailures
import com.snowplowanalytics.snowplow.badrows.Payload.CollectorPayload
import io.circe.parser._
import org.apache.http.client.utils.URLEncodedUtils
import org.scalacheck.Gen
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.prop.PropertyChecks

import model._
import RecoveryScenario._
import RecoveryScenario2._
import gens._

class RecoveryScenario2Spec extends FreeSpec with PropertyChecks {
  "RecoveryScenario2" - {
    val vendor = "com.snowplowanalytics.snowplow"
    val version = "v1"
    "AdapterFailuresRecoveryScenario" - {
      val notJsonAdapterFailure = NotJsonAdapterFailure("field", None, "error")
      val notSdAdapterFailure = NotSDAdapterFailure("json", "error")
      "should filter based on vendor and version" in {
        val afs = AdapterFailures(
          Instant.now(), vendor, version, NonEmptyList.one(notJsonAdapterFailure))
        PassThroughAdapterFailuresRecoveryScenario(vendor, version, None, None)
          .discriminant(afs) shouldBe true
        PassThroughAdapterFailuresRecoveryScenario("vendor", version, None, None)
          .discriminant(afs) shouldBe false
        PassThroughAdapterFailuresRecoveryScenario(vendor, "version", None, None)
          .discriminant(afs) shouldBe false
      }
      "should filter based on field" in {
        val afs = AdapterFailures(Instant.now(), vendor, version,
          NonEmptyList.of(notJsonAdapterFailure, notSdAdapterFailure))
        PassThroughAdapterFailuresRecoveryScenario(vendor, version, "field".some, None)
          .discriminant(afs) shouldBe true
        PassThroughAdapterFailuresRecoveryScenario(vendor, version, "abc".some, None)
          .discriminant(afs) shouldBe false
      }
      "should filter based on error" in {
        val afs = AdapterFailures(Instant.now(), vendor, version,
          NonEmptyList.of(notJsonAdapterFailure, notSdAdapterFailure))
        PassThroughAdapterFailuresRecoveryScenario(vendor, version, None, "error".some)
          .discriminant(afs) shouldBe true
        PassThroughAdapterFailuresRecoveryScenario(vendor, version, None, "abc".some)
          .discriminant(afs) shouldBe false
      }

      "PassThroughAdapterFailuresRecoveryScenario" - {
        "should not touch the collector payload" in {
          forAll { (cp: CollectorPayload) =>
            val pt = PassThroughAdapterFailuresRecoveryScenario(vendor, version, None, None)
            val newCp = pt.fix(cp)
            toCollectorPayload(cp) shouldEqual newCp
          }
        }
      }

      "ModifyQuerystringAdapterFailuresRecoveryScenario" - {
        "should replace part of the query string" in {
          forAll { (cp: CollectorPayload) =>
            val mqs = ModifyQuerystringAdapterFailuresRecoveryScenario(vendor, version, None, None,
              "tv=js", "tv=js2")
            val serialized = toCollectorPayload(cp)
            val newCp = mqs.fix(cp)
            if (cp.querystring.isEmpty) serialized shouldEqual newCp
            else {
              serialized.timestamp shouldEqual newCp.timestamp
              serialized.path shouldEqual newCp.path
              serialized.body shouldEqual newCp.body
              newCp.querystring.diff(serialized.querystring) shouldEqual "2"
            }
          }
        }
      }

      "ModifyBodyAdapterFailuresRecoveryScenario" - {
        "should replace part of the body" in {
          forAll { (cp: CollectorPayload) =>
            val mb = ModifyBodyAdapterFailuresRecoveryScenario(vendor, version, None, None,
              """"tv":"js"""", """"tv":"js2"""")
            val serialized = toCollectorPayload(cp)
            val newCp = mb.fix(cp)
            if (cp.body.isEmpty) serialized shouldEqual newCp
            else {
              serialized.timestamp shouldEqual newCp.timestamp
              serialized.path shouldEqual newCp.path
              serialized.querystring shouldEqual newCp.querystring
              newCp.body.diff(serialized.body) shouldEqual "2"
            }
          }
        }
      }

      "ModifyPathAdapterFailuresRecoveryScenario" - {
        "should replace part of the path" in {
          forAll { (cp: CollectorPayload) =>
            val mp = ModifyPathAdapterFailuresRecoveryScenario(vendor, version, None, None,
              "newVendor", "newVersion")
            val serialized = toCollectorPayload(cp)
            val newCp = mp.fix(cp)
            serialized.timestamp shouldEqual newCp.timestamp
            newCp.path shouldEqual "/newVendor/newVersion"
            serialized.body shouldEqual newCp.body
            serialized.querystring shouldEqual newCp.querystring
          }
        }
      }
    }
  }
}

class RecoveryScenarioSpec extends FreeSpec with PropertyChecks {
  val uuidRegex = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

  "RecoveryScenario" - {
    "should implement filter" in {
      forAll (badRowGen, Gen.alphaStr) { (br: BadRow, error: String) =>
        val rs = PassThrough(error)
        val filtered = rs.filter(br.errors)
        if (br.errors.map(_.message).exists(_.contains(error))) filtered shouldEqual true
        else filtered shouldEqual false
      }
    }
  }

  "ReplaceInQueryString" - {
    "should replace part of the query string" in {
      forAll { (cp: CP) =>
        val riqs = ReplaceInQueryString("placeholder", "tv=js", "tv=js2")
        val oldCp = new CP(cp)
        val newCp = riqs.mutate(cp)
        if (cp.querystring == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.body shouldEqual newCp.body
          newCp.querystring.diff(oldCp.querystring) shouldEqual "2"
        }
      }
    }
  }

  "ReplaceInBase64FieldInQueryString" - {
    "should replace part of ue_px in the query string using a regex" in {
      forAll { (cp: CP, uuid: UUID) =>
        val ribfiqs = ReplaceInBase64FieldInQueryString(
          "placeholder",
          "ue_px",
          s""""sessionId":"$uuidRegex"""",
          s""""sessionId":"${uuid.toString}""""
        )
        val oldCp = new CP(cp)
        val newCp = ribfiqs.mutate(cp)
        if (cp.querystring == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.body shouldEqual newCp.body
          val newUe = new String(Base64.getDecoder.decode(parseQuerystring(newCp.querystring)("ue_px")))
          (for {
            json <- parse(newUe).leftMap(_.message)
            sessionId <- json.hcursor.downField("data").downField("data").downField("sessionId").as[String]
              .leftMap(_.message)
          } yield sessionId).getOrElse("") shouldEqual uuid.toString()
        }
      }
    }
  }

  "RemoveFromQueryString" - {
    "should remove part of the query string" in {
      forAll { (cp: CP) =>
        val toRemove = "tv=js&"
        val rfqs = RemoveFromQueryString("placeholder", toRemove)
        val oldCp = new CP(cp)
        val newCp = rfqs.mutate(cp)
        if (cp.querystring == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.body shouldEqual newCp.body
          oldCp.querystring.diff(newCp.querystring) should contain theSameElementsAs (toRemove)
        }
      }
    }
  }

  "ReplaceInBody" - {
    "should replace part of the body" in {
      forAll { (cp: CP) =>
        val rib = ReplaceInBody("placeholder", """"tv":"js"""", """"tv":"js2"""")
        val oldCp = new CP(cp)
        val newCp = rib.mutate(cp)
        if (cp.body == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.querystring shouldEqual newCp.querystring
          newCp.body.diff(oldCp.body) shouldEqual "2"
        }
      }
    }
  }

  "ReplaceInBase64FieldInBody" - {
    "should replace part of ue_px in the body using a regex" in {
      def getUe(s: String): Either[String, String] = for {
        json <- parse(s).leftMap(_.message)
        uePx <- json.hcursor.downField("data").downArray.first.downField("ue_px").as[String]
          .leftMap(_.message)
      } yield uePx

      forAll { (cp: CP, uuid: UUID) =>
        val rib = ReplaceInBase64FieldInBody(
          "placeholder",
          "ue_px",
          s""""sessionId":"$uuidRegex"""",
          s""""sessionId":"${uuid.toString}""""
        )
        val oldCp = new CP(cp)
        val newCp = rib.mutate(cp)
        if (cp.body == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.querystring shouldEqual newCp.querystring
          val newUe = getUe(newCp.body).getOrElse(throw new Exception("invalid json"))
          val newUeDecoded = new String(Base64.getDecoder().decode(newUe))
          (for {
            json <- parse(newUeDecoded).leftMap(_.message)
            sessionId <- json.hcursor.downField("data").downField("data").downField("sessionId").as[String]
              .leftMap(_.message)
          } yield sessionId).getOrElse("") shouldEqual uuid.toString()
        }
      }
    }
  }

  "RemoveFromBody" - {
    "should remove part of the body" in {
      forAll { (cp: CP) =>
        val toRemove = """"tv":"js","""
        val rfb = RemoveFromBody("placeholder", toRemove)
        val oldCp = new CP(cp)
        val newCp = rfb.mutate(cp)
        if (cp.body == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.querystring shouldEqual newCp.querystring
          oldCp.body.diff(newCp.body) should contain theSameElementsAs (toRemove)
        }
      }
    }
  }

  "PassThrough" - {
    "shouldn't modify the payload" in {
      forAll { (cp: CP) =>
        val pt = PassThrough("placeholder")
        val oldCp = new CP(cp)
        val newCp = pt.mutate(cp)
        oldCp shouldEqual newCp
      }
    }
  }

  "ReplaceInPath" - {
    "should replace part of the path" in {
      forAll { (cp: CP) =>
        val rib = ReplaceInPath("placeholder", "v1", "v2")
        val oldCp = new CP(cp)
        val newCp = rib.mutate(cp)
        if (cp.path == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          newCp.path.diff(oldCp.path) shouldEqual "2"
          oldCp.querystring shouldEqual newCp.querystring
          oldCp.body shouldEqual newCp.body
        }
      }
    }
  }

  def parseQuerystring(s: String): Map[String, String] =
    URLEncodedUtils.parse(new URI("?" + s), StandardCharsets.UTF_8)
      .asScala
      .map(pair => pair.getName -> pair.getValue)
      .toMap

}
