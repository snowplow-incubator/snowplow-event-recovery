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

import scala.io.Source

import cats.Id
import cats.implicits._
import io.circe.Json
import io.circe.parser._

import org.joda.time.DateTime
import org.scalatest.Inspectors
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._

import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.ThriftLoader
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.snowplow.badrows.Processor
import config._
import json.confD
import gens.idClock

import com.snowplowanalytics.snowplow.enrich.common.utils.BlockerF

import com.snowplowanalytics.snowplow.event.recovery.{execute => recoveryExecute}
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

  val client = Client
    .parseDefault[Id](parse(resolverConfig).right.get)
    .leftMap(_.toString)
    .value
    .fold(
      e => throw new RuntimeException(e),
      r => r
    )

  val enrichmentsRes = EnrichmentRegistry.parse[Id](
    parse(enrichmentsConfig).right.get,
    client,
    true
  )
  val enrichments = enrichmentsRes.toEither.right.get
  val registry    = EnrichmentRegistry.build[Id](enrichments, BlockerF.noop[Id]).value.right.get

  "GreedyArrayMatcherIntegrationSpec" in {

    val conf = decode[Conf](
      Source.fromResource("recovery_scenarios_greedy.json").mkString
    ).right.get.data

    val enriched = List(Source.fromResource("bad_rows_greedy.json").mkString.filter(_ >= ' '))
      .map(recoveryExecute(conf))
      .map(_.leftMap(_.badRow))
      .map {
        _.flatMap { bytes =>
          ThriftLoader.toCollectorPayload(bytes, Processor("recovery", "0.0.0")).toEither.leftMap(_.head)
        }
      }
      .flatMap { p =>
        EtlPipeline
          .processEvents[Id](
            new AdapterRegistry(),
            registry,
            client,
            Processor("recovery", "0.0.0"),
            new DateTime(1500000000L),
            p.toValidatedNel,
            EtlPipeline.FeatureFlags(acceptInvalid = true, legacyEnrichmentOrder = true),
            ()
          )
          .map(_.toEither)
      }

    forAll(enriched) { r =>
      r should be('right)
    }
  }

  "IntegrationSpec" in {

    val conf = decode[Conf](
      Source.fromResource("recovery_scenarios.json").mkString
    ).right.get.data

    val enriched = Source
      .fromResource("bad_rows.json")
      .getLines
      .toList
      .map(recoveryExecute(conf))
      .map(_.leftMap(_.badRow))
      .map {
        _.flatMap { bytes =>
          ThriftLoader.toCollectorPayload(bytes, Processor("recovery", "0.0.0")).toEither.leftMap(_.head)
        }
      }
      .flatMap { p =>
        EtlPipeline
          .processEvents[Id](
            new AdapterRegistry(),
            registry,
            client,
            Processor("recovery", "0.0.0"),
            new DateTime(1500000000L),
            p.toValidatedNel,
            EtlPipeline.FeatureFlags(acceptInvalid = true, legacyEnrichmentOrder = true),
            ()
          )
          .map(_.toEither)
      }

    val expected: List[Json] = decode[List[Json]](Source.fromResource("expected_payloads.json").mkString)
      .sequence
      .flatMap(_.toList)
      .flatMap(removeFields)
    val loaded: List[Json] = enriched.flatMap(_.flatMap(EnrichedEvent.toAtomic).toList).flatMap(removeFields)

    loaded should contain theSameElementsAs expected
  }

  val removeFields: Json => List[Json] = _.hcursor.downField("event_id").delete.up.downField("v_etl").delete.top.toList
}
