package com.snowplowanalytics.snowplow.event.recovery

import java.util.Base64

import cats.syntax.either._
import frameless.syntax._
import frameless.TypedDataset
import io.circe.Json
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import io.circe.literal._
import org.apache.thrift.TSerializer
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.Inspectors
import org.scalatest.Matchers._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.ThriftLoader
import com.snowplowanalytics.iglu.client.Resolver

import model._
import utils._

class IntegrationSpec extends SparkSpec with Inspectors {
  implicit val session = spark
  import session.implicits._
  implicit val genConfig: Configuration =
    Configuration.default.withDiscriminator("name")

  val originalPayloads = List(
    {
      val cp = new CollectorPayload()
      cp.schema = "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
      cp.encoding = "UTF-8"
      cp.collector = "c"
      cp.path = "/i"
      cp.querystring = "e=pv&page={DemoPageTitle}"
      cp
    },
    {
      val cp = new CollectorPayload()
      cp.schema = "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
      cp.encoding = "UTF-8"
      cp.collector = "c"
      cp.path = "/i"
      cp.querystring = "e=pv&page=DemoPageTitle"
      cp
    }
  )
  // zip this
  val badRows = io.circe.parser.parse(s"""
    [
      {
        "line": "${thriftSer(originalPayloads(0))}",
        "errors": [
          {
            "level": "x",
            "message": "Exception extracting name-value pairs from querystring"
          }
        ],
        "failure_tstamp": "xx"
      },
      {
        "line": "${thriftSer(originalPayloads(1))}",
        "errors": [
          {
            "level": "x",
            "message": "error: instance type (null) does not match any allowed primitive type"
          }
        ],
        "failure_tstamp": "xx"
      }
    ]
  """).getOrElse(Json.Null).as[List[BadRow]]
    .fold(f => throw new Exception(s"invalid bad rows: ${f.message}"), identity)
  val recoveryScenarios = json"""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0",
      "data": [
        {
          "name": "RemoveFromQueryString",
          "error": "Exception extracting name-value pairs from querystring",
          "toRemove": "\\{.*\\}"
        },
        {
          "name": "PassThrough",
          "error": "error: instance type (null) does not match any allowed primitive type"
        }
      ]
    }
  """.hcursor.get[List[RecoveryScenario]]("data")
    .fold(f => throw new Exception(s"invalid recovery scenarios: ${f.message}"), identity)
  val expectedPayloads = List(
    {
      val cp = new CollectorPayload()
      cp.schema = "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
      cp.encoding = "UTF-8"
      cp.collector = "c"
      cp.path = "/i"
      cp.querystring = "e=pv&page="
      cp
    },
    {
      val cp = new CollectorPayload()
      cp.schema = "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
      cp.encoding = "UTF-8"
      cp.collector = "c"
      cp.path = "/i"
      cp.querystring = "e=pv&page=DemoPageTitle"
      cp
    }
  )

  val resolver = Resolver.parse(parse("""
    {
      "schema": "iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1",
      "data": {
        "cacheSize": 500,
        "repositories": [
          {
            "name": "Iglu Central",
            "priority": 0,
            "vendorPrefixes": [ "com.snowplowanalytics" ],
            "connection": {
              "http": {
                "uri": "http://iglucentral.com"
              }
            }
          }
        ]
      }
    }
  """)).fold(l => throw new Exception(s"invalid resolver: $l"), identity)
  val registry = EnrichmentRegistry.parse(parse("""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow/enrichments/jsonschema/1-0-0",
      "data": []
    }
  """), true)(resolver)
    .fold(l => throw new Exception(s"invalid registry: $l"), identity)

  "IntegrationSpec" in {

    // filter -> check expected payloads counts
    val ds = TypedDataset.create(badRows)
    val filtered = RecoveryJob.filter(ds, recoveryScenarios)
    filtered.count().run() shouldEqual expectedPayloads.size

    // mutate -> check expected payloads
    val rdd = session.sparkContext.makeRDD(badRows)
    val mutated = RecoveryJob.mutate(rdd, recoveryScenarios)
    val payloads = mutated.collect()
    mutated.count() shouldEqual expectedPayloads.size
    payloads shouldEqual expectedPayloads

    // check enrich
    val enriched = payloads
      .map { payload =>
        val thriftSerializer = new TSerializer
        val bytes = thriftSerializer.serialize(payload)
        val res = Base64.getEncoder.encode(bytes)
        val r = ThriftLoader.toCollectorPayload(bytes)
        r
      }
      .map(payload => EtlPipeline.processEvents(
        registry,
        "etlVersion",
        org.joda.time.DateTime.now,
        payload)(resolver)
      )
      .flatten
    forAll (enriched) { r => r.isSuccess shouldEqual true }
  }
}
