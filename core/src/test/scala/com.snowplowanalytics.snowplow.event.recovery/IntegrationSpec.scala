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

import cats.Id
import cats.syntax.either._
import com.snowplowanalytics.iglu.client.Client
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.snowplowanalytics.snowplow.badrows.Processor
import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.ThriftLoader
import io.circe.{Decoder, Json}
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import io.circe.parser._
import io.circe.syntax._
import org.apache.thrift.TSerializer
import org.scalatest.{FreeSpec, Inspectors}
import org.scalatest.Matchers._

import model._
import utils._

sealed trait ExpectedPayload {
  def path: String
}
final case class Body(path: String, body: Json) extends ExpectedPayload
final case class QueryString(path: String, queryString: String) extends ExpectedPayload

class IntegrationSpec extends FreeSpec with Inspectors {

  implicit def eitherDecoder[L, R](implicit l: Decoder[L], r: Decoder[R]): Decoder[Either[L, R]] =
    l.either(r)
  implicit val genConfig: Configuration =
    Configuration.default.withDiscriminator("name")

  val badRows = {
    val br = io.circe.parser.parse(getResourceContent("/bad_rows.json"))
      .flatMap(_.as[List[BadRow]])
      .fold(f => throw new Exception(s"invalid bad rows: ${f.getMessage}"), identity)
    println("Decoded bad rows are:")
    println(br.map(r => r.copy(line = thriftDeser(r.line).toString)).asJson)
    br
  }
  val recoveryScenarios = io.circe.parser.parse(getResourceContent("/recovery_scenarios.json"))
    .flatMap(_.hcursor.get[List[RecoveryScenario]]("data"))
    .fold(f => throw new Exception(s"invalid recovery scenarios: ${f.getMessage}"), identity)
  val expectedPayloads = io.circe.parser.parse(getResourceContent("/expected_payloads.json"))
    .flatMap(_.as[List[ExpectedPayload]])
    .fold(f => throw new Exception(s"invalid expected payloads: ${f.getMessage}"), identity)
    .map { p =>
      val cp = new CollectorPayload()
      cp.schema = "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
      cp.encoding = "UTF-8"
      cp.collector = "c"
      cp.path = p.path
      p match {
        case QueryString(_, qs) => cp.querystring = qs
        case Body(_, b) =>
          cp.contentType = "application/json; charset=UTF-8"
          cp.body = b.noSpaces
      }
      cp
    }

  val client = parse(getResourceContent("/resolver.json"))
    .leftMap(_.message)
    .flatMap(json => Client.parseDefault[Id](json).value)
    .fold(l => throw new Exception(s"invalid resolver: $l"), identity)
  val confs = parse(getResourceContent("/enrichments.json"))
    .leftMap(_.message)
    .flatMap(json => EnrichmentRegistry.parse[Id](json, client, true).toEither.leftMap(_.toString))
    .fold(l => throw new Exception(s"invalid registry: $l"), identity)
  val registry = EnrichmentRegistry
    .build(confs)
    .fold(e => throw new Exception(e.toList.mkString("\n")), identity)

  "IntegrationSpec" in {

    // filter -> check expected payloads counts
    val filtered = badRows.filter(_.isAffected(recoveryScenarios))
    filtered.size shouldEqual expectedPayloads.size

    // mutate -> check expected payloads
    val mutated = filtered.map(_.mutateCollectorPayload(recoveryScenarios))
    mutated.size shouldEqual expectedPayloads.size
    // check only the modifiable fields
    mutated.map(cp => (cp.path, cp.querystring, cp.body)) shouldEqual
      expectedPayloads.map(cp => (cp.path, cp.querystring, cp.body))

    // check enrich
    val enriched = mutated
      .map { payload =>
        val thriftSerializer = new TSerializer
        val bytes = thriftSerializer.serialize(payload)
        val r = ThriftLoader.toCollectorPayload(bytes, Processor("test", "0.1.0"))
        r
      }
      .map(payload => EtlPipeline.processEvents(
        new AdapterRegistry(),
        registry,
        client,
        Processor("test", "0.1.0"),
        org.joda.time.DateTime.now,
        payload
      ))
      .flatten
    forAll (enriched) { r =>
      r.toEither should be ('right)
    }
  }

  private def getResourceContent(resource: String): String = {
    val f = getClass.getResource(resource).getFile
    val s = scala.io.Source.fromFile(f)
    val c = s.mkString
    s.close()
    c
  }
}
