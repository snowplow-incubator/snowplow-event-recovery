package com.snowplowanalytics.snowplow.event.recovery

import java.util.Base64

import cats.syntax.either._
import io.circe.{Decoder, Json}
import io.circe.generic.extras.auto._
import io.circe.generic.extras.Configuration
import io.circe.syntax._
import org.apache.thrift.TSerializer
import scalaz._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{FreeSpec, Inspectors}
import org.scalatest.Matchers._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.ThriftLoader
import com.snowplowanalytics.iglu.client.Resolver

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

  val resolver = Resolver.parse(parse(getResourceContent("/resolver.json")))
    .fold(l => throw new Exception(s"invalid resolver: $l"), identity)
  val registry = EnrichmentRegistry
    .parse(parse(getResourceContent("/enrichments.json")), true)(resolver)
    .fold(l => throw new Exception(s"invalid registry: $l"), identity)

  "IntegrationSpec" in {

    // filter -> check expected payloads counts
    val filtered = badRows.filter(_.isAffected(recoveryScenarios))
    filtered.size shouldEqual expectedPayloads.size

    // mutate -> check expected payloads
    val mutated = filtered.map(_.mutateCollectorPayload(recoveryScenarios))
    mutated.size shouldEqual expectedPayloads.size
    mutated shouldEqual expectedPayloads

    // check enrich
    val enriched = mutated
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
    forAll (enriched) { r =>
      val e = r match {
        case Success(e) => Right(e)
        case Failure(e) => Left(e)
      }
      e should be ('right)
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
