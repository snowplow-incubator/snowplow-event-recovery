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

import io.circe.parser._

import org.scalatest._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import badrows.{NVP, Payload}
import CollectorPayload.thrift.model1.CollectorPayload
import gens._

import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
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
    "coerce Sendgrid payload into an application/json body" in {
      def body(event: String = "") = s"""{
      $event
      "email": "example@test.com",
      "timestamp": 1446549615,
      "smtp-id": "<14c5d75ce93.dfd.64b469@ismtpd-555>",
      "category": "cat facts",
      "sg_event_id": "sZROwMGMagFgnOEmSdvhig==",
      "sg_message_id": "14c5d75ce93.dfd.64b469.filter0001.16648.5515E0B88.0",
      "marketing_campaign_id": 12345,
      "marketing_campaign_name": "campaign name",
      "marketing_campaign_version": "B",
      "marketing_campaign_split_id": 13471
    }"""
      val inputJson = s"""{
  "schema": "iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
  "data": {
    "schema": "iglu:com.sendgrid/processed/jsonschema/2-0-0",
    "data": ${body()}
  }
}      """.replaceAll("\"", "\\\"")

      val rawEvent = Payload.RawEvent(
        vendor = "com.sendgrid",
        version = "v3",
        parameters = List(NVP("ue_pr", Some(inputJson))),
        contentType = Some("application/json"),
        loaderName = "dummy",
        encoding = "UTF-8",
        None,
        None,
        None,
        None,
        None,
        List.empty,
        None
      )
      val p = new Payload.EnrichmentPayload(EnrichedEvent.toPartiallyEnrichedEvent(new EnrichedEvent()), rawEvent)
      (for {
        actual   <- payload.coerce(p).map(_.body).flatMap(parse)
        expected <- parse(s"[${body(""""event":"processed",""")}]")
      } yield actual should be(expected)).value
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
