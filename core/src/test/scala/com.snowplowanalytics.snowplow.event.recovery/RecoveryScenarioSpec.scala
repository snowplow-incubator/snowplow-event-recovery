package com.snowplowanalytics.snowplow
package event.recovery

import java.net.URI
import java.util.{Base64, UUID}

import scala.collection.JavaConverters._

import org.apache.http.client.utils.URLEncodedUtils
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalacheck.Gen
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.prop.PropertyChecks

import CollectorPayload.thrift.model1.CollectorPayload
import model._
import RecoveryScenario._
import gens._

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
      forAll { (cp: CollectorPayload) =>
        val riqs = ReplaceInQueryString("placeholder", "tv=js", "tv=js2")
        val oldCp = new CollectorPayload(cp)
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
      forAll { (cp: CollectorPayload, uuid: UUID) =>
        val ribfiqs = ReplaceInBase64FieldInQueryString(
          "placeholder",
          "ue_px",
          s""""sessionId":"$uuidRegex"""",
          s""""sessionId":"${uuid.toString}""""
        )
        val oldCp = new CollectorPayload(cp)
        val newCp = ribfiqs.mutate(cp)
        if (cp.querystring == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.body shouldEqual newCp.body
          val oldUe = parse(new String(Base64.getDecoder.decode(parseQuerystring(oldCp.querystring)("ue_px"))))
          val newUe = parse(new String(Base64.getDecoder.decode(parseQuerystring(newCp.querystring)("ue_px"))))
          val Diff(changed, JNothing, JNothing) = oldUe diff newUe
          changed shouldEqual
            JObject(List(("data", JObject(List(("sessionId", JString(uuid.toString)))))))
        }
      }
    }
  }

  "RemoveFromQueryString" - {
    "should remove part of the query string" in {
      forAll { (cp: CollectorPayload) =>
        val toRemove = "tv=js&"
        val rfqs = RemoveFromQueryString("placeholder", toRemove)
        val oldCp = new CollectorPayload(cp)
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
      forAll { (cp: CollectorPayload) =>
        val rib = ReplaceInBody("placeholder", """"tv":"js"""", """"tv":"js2"""")
        val oldCp = new CollectorPayload(cp)
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
      forAll { (cp: CollectorPayload, uuid: UUID) =>
        val rib = ReplaceInBase64FieldInBody(
          "placeholder",
          "ue_px",
          s""""sessionId":"$uuidRegex"""",
          s""""sessionId":"${uuid.toString}""""
        )
        val oldCp = new CollectorPayload(cp)
        val newCp = rib.mutate(cp)
        if (cp.body == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.querystring shouldEqual newCp.querystring
          val oldUe = parse(new String(Base64.getDecoder.decode(((parse(oldCp.body) \ "data")(0) \ "ue_px").values.toString)))
          val newUe = parse(new String(Base64.getDecoder.decode(((parse(newCp.body) \ "data")(0) \ "ue_px").values.toString)))
          val Diff(changed, JNothing, JNothing) = oldUe diff newUe
          changed shouldEqual
            JObject(List(("data", JObject(List(("sessionId", JString(uuid.toString)))))))
        }
      }
    }
  }

  "RemoveFromBody" - {
    "should remove part of the body" in {
      forAll { (cp: CollectorPayload) =>
        val toRemove = """"tv":"js","""
        val rfb = RemoveFromBody("placeholder", toRemove)
        val oldCp = new CollectorPayload(cp)
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
      forAll { (cp: CollectorPayload) =>
        val pt = PassThrough("placeholder")
        val oldCp = new CollectorPayload(cp)
        val newCp = pt.mutate(cp)
        oldCp shouldEqual newCp
      }
    }
  }

  def parseQuerystring(s: String): Map[String, String] =
    URLEncodedUtils.parse(new URI("?" + s), "UTF-8")
      .asScala
      .map(pair => pair.getName -> pair.getValue)
      .toMap

}
