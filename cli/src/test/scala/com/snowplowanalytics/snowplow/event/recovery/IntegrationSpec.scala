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

import java.io.File
import scala.io.Source

import cats.effect.unsafe.implicits.global
import cats.implicits._
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._

import org.scalatest.Inspectors
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._

import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import config._
import json.confD

class IntegrationSpec extends AnyWordSpec with Inspectors {
  private val resolverConfig = """{
    "schema": "iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1",
    "data": {
      "cacheSize": 500,
      "repositories": [
        {
          "name": "Embedded src/test/resources",
          "priority": 100,
          "vendorPrefixes": [ "com.snowplowanalytics" ],
          "connection": {
            "embedded": {
              "path": "/iglu-schemas"
            }
          }
        },
        {
          "name": "Iglu Central",
          "priority": 0,
          "vendorPrefixes": [ "com.snowplowanalytics" ],
          "connection": {
            "http": {
              "uri": "http://iglucentral.com"
            }
          }
        },
       {
         "name":"Priv",
         "priority":0,
         "vendorPrefixes":["com.snowplowanalytics"],
         "connection":{"http":{"uri":"https://raw.githubusercontent.com/peel/schemas/master"}}
       }
      ]
    }
  }""".stripMargin

  val enrichmentsConfig =
    """{"schema": "iglu:com.snowplowanalytics.snowplow/enrichments/jsonschema/1-0-0", "data": []}"""

  lazy val pipeline = Enrich
    .pipeline(parse(resolverConfig).toOption.get, parse(enrichmentsConfig).toOption.get)
    .unsafeRunSync()
    .fold(e => throw new RuntimeException(e), identity)

  private def recoverAndEnrich(conf: Config)(lines: List[String]) =
    lines
      .flatTraverse { line =>
        Enrich.enrich(pipeline)(recoverExecute(conf)(line).leftMap(_.badRow).flatMap(Enrich.toCollectorPayload))
      }
      .unsafeRunSync()

  private val recoverExecute = com.snowplowanalytics.snowplow.event.recovery.execute _

  "GreedyArrayMatcherIntegrationSpec" in {

    val conf = decode[Conf](
      Source.fromResource("recovery_scenarios_greedy.json").mkString
    ).toOption.get.data

    val enriched = recoverAndEnrich(conf)(List(Source.fromResource("bad_rows_greedy.json").mkString.filter(_ >= ' ')))

    enriched should not be empty
    forAll(enriched) { r =>
      r should be('right)
    }
  }

  "IntegrationSpec" in {

    val conf = decode[Conf](
      Source.fromFile(new File("examples/config.json")).mkString
    ).toOption.get.data

    val enriched = recoverAndEnrich(conf)(Source.fromFile(new File("examples/input.txt")).getLines.toList)

    val expected: List[Json] =
      decode[List[Json]](Source.fromResource("expected_payloads.json").mkString).sequence.flatMap(_.toList)

    val loaded: List[Json] = enriched
      .flatMap(_.toOption.toList)
      .map(e => EnrichedEvent.toPartiallyEnrichedEvent(e).asJson.dropNullValues)
      .flatMap(removeField("event_id"))
      .flatMap(removeField("v_etl"))

    loaded.size should be > 0
    loaded should contain theSameElementsAs expected
  }

  def removeField(fieldName: String)(body: Json): Option[Json] = body.hcursor.downField(fieldName).delete.top
}
