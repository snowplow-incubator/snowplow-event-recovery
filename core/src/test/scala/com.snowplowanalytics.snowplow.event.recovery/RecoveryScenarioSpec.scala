package com.snowplowanalytics.snowplow
package event.recovery

import java.net.URI
import java.util.UUID

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
        val riqs = ReplaceInQueryString("placeholder", "page=Title", "page=Title2")
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
    "should replace part of the query string using a regex" in {
      forAll { (cp: CollectorPayload, uuid: UUID) =>
        val riqs = ReplaceInQueryString(
          "placeholder",
          s"%22sessionId%22%3A%22$uuidRegex%22",
          s"%22sessionId%22%3a%22${uuid.toString}%22"
        )
        val oldCp = new CollectorPayload(cp)
        val newCp = riqs.mutate(cp)
        if (cp.querystring == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.body shouldEqual newCp.body
          val oldCo = parse(parseQuerystring(oldCp.querystring)("co"))
          val newCo = parse(parseQuerystring(newCp.querystring)("co"))
          val Diff(changed, JNothing, JNothing) = oldCo diff newCo
          changed shouldEqual
            JObject(List(("data", JObject(List(("sessionId", JString(uuid.toString)))))))
        }
      }
    }
  }

  "RemoveFromQueryString" - {
    "should remove part of the query string" in {
      forAll { (cp: CollectorPayload) =>
        val toRemove = "page=Title&"
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
    "should remove part of the query string using a regex" in {
      forAll { (cp: CollectorPayload) =>
        val rfqs = RemoveFromQueryString("placeholder", s"%22sessionId%22%3A%22$uuidRegex%22%2C")
        val oldCp = new CollectorPayload(cp)
        val newCp = rfqs.mutate(cp)
        if (cp.querystring == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.body shouldEqual newCp.body
          val oldCo = parse(parseQuerystring(oldCp.querystring)("co"))
          val newCo = parse(parseQuerystring(newCp.querystring)("co"))
          val Diff(JNothing, JNothing, deleted) = oldCo diff newCo
          val JObject(List(("data", JObject(List(("sessionId", JString(uuid))))))) = deleted
          uuid should fullyMatch regex(uuidRegex)
        }
      }
    }
  }

  "ReplaceInBody" - {
    "should replace part of the body" in {
      forAll { (cp: CollectorPayload) =>
        val rib = ReplaceInBody("placeholder", """"page":"Title"""", """"page":"Title2"""")
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
    "should replace part of the body using a regex" in {
      forAll { (cp: CollectorPayload, uuid: UUID) =>
        val rib = ReplaceInBody(
          "placeholder",
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
          val oldBody = parse(oldCp.body)
          val newBody = parse(newCp.body)
          val Diff(changed, JNothing, JNothing) = oldBody diff newBody
          changed shouldEqual JObject(List(("data", JObject(List(("co",
            JObject(List(("data", JObject(List(("sessionId", JString(uuid.toString)))))))))))))
        }
      }
    }
  }

  "RemoveFromBody" - {
    "should remove part of the body" in {
      forAll { (cp: CollectorPayload) =>
        val toRemove = """"page":"Title","""
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
    "should remove part of the body using a regex" in {
      forAll { (cp: CollectorPayload) =>
        val rfb = RemoveFromBody("placeholder", s""""sessionId":"$uuidRegex",""")
        val oldCp = new CollectorPayload(cp)
        val newCp = rfb.mutate(cp)
        if (cp.body == null) oldCp shouldEqual newCp
        else {
          oldCp.timestamp shouldEqual newCp.timestamp
          oldCp.path shouldEqual newCp.path
          oldCp.querystring shouldEqual newCp.querystring
          val oldCo = parse(oldCp.body)
          val newCo = parse(newCp.body)
          val Diff(JNothing, JNothing, deleted) = oldCo diff newCo
          val JObject(List(("data", JObject(List(("co",
            JObject(List(("data", JObject(List(("sessionId", JString(uuid))))))))))))) = deleted
          uuid should fullyMatch regex(uuidRegex)
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
