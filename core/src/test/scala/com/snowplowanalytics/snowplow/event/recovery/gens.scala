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
package com.snowplowanalytics
package snowplow
package event.recovery

import java.util.{Base64, UUID}

import shapeless._
import io.circe.Json
import io.circe.syntax._
import org.scalacheck.{Arbitrary, Gen}
import com.fortysevendeg.scalacheck.datetime.joda.ArbitraryJoda._
import com.fortysevendeg.scalacheck.datetime.jdk8.ArbitraryJdk8._
import org.scalacheck.ScalacheckShapeless._

import badrows._
import CollectorPayload.thrift.model1.CollectorPayload

import config.conditions._
import config._
import domain._

object gens {
  val qs = (json: Json) => {
    val str = json.toString
    val unstruct =
      s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-0-1","data":$str}}"""
    val encoded = Base64.getEncoder.encodeToString(unstruct.getBytes)
    s"e=ue&tv=js&ue_px=$encoded"
  }

  val body = (json: Json) => {
    val str = json.toString
    val unstruct =
      s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-0-1","data":$str}}"""
    val encoded = Base64.getEncoder.encodeToString(unstruct.getBytes)
    s"""{"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4","data":[{"e":"ue","p":"web","tv":"js","ue_px":"$encoded"}]}"""
  }

  /** Data of a client_session 1-0-1 entity */
  val clientSessionGen: Gen[Json] = {
    val uuid                 = Gen.uuid.map(_.toString)
    val nullableUuid         = Gen.option(uuid).map(_.asJson)
    val optionalNullableUuid = Gen.option(nullableUuid)
    for {
      userId            <- Gen.oneOf(uuid, Gen.listOfN(16, Gen.hexChar).map(_.mkString.toLowerCase))
      sessionId         <- uuid
      sessionIndex      <- Gen.choose(0, Int.MaxValue)
      previousSessionId <- nullableUuid
      storageMechanism  <- Gen.oneOf("SQLITE", "COOKIE_1", "COOKIE_3", "LOCAL_STORAGE", "FLASH_LSO")
      firstEventId      <- optionalNullableUuid
    } yield Json
      .obj(
        "userId"            -> userId.asJson,
        "sessionId"         -> sessionId.asJson,
        "sessionIndex"      -> sessionIndex.asJson,
        "previousSessionId" -> previousSessionId,
        "storageMechanism"  -> storageMechanism.asJson
      )
      .mapObject(o => firstEventId.fold(o)(o.add("firstEventId", _)))
  }

  implicit val collectorPayloadArb: Arbitrary[CollectorPayload] = Arbitrary {
    for {
      ts <- Gen.choose(1, Long.MaxValue)
      path = "/com.snowplowanalytics.snowplow/v1"
      post <- Gen.oneOf(true, false)
      contentType = "application/json; charset=UTF-8"
      json <- clientSessionGen
    } yield {
      val collectorPayload = new CollectorPayload()
      collectorPayload.timestamp = ts
      collectorPayload.path = path
      if (post) collectorPayload.body = body(json)
      else collectorPayload.querystring = qs(json)
      collectorPayload
    }
  }

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    Gen.delay(UUID.randomUUID)
  }
  implicit val processorA     = implicitly[Arbitrary[Processor]]
  implicit val notJsonA       = implicitly[Arbitrary[FailureDetails.AdapterFailure.NotJson]]
  implicit val inputDataA     = implicitly[Arbitrary[FailureDetails.AdapterFailure.InputData]]
  implicit val schemaMappingA = implicitly[Arbitrary[FailureDetails.AdapterFailure.SchemaMapping]]
  implicit val adapterFailureA: Arbitrary[FailureDetails.AdapterFailure] = Arbitrary(
    Gen.oneOf(notJsonA.arbitrary, inputDataA.arbitrary, schemaMappingA.arbitrary)
  )
  implicit val tpvCriterionMismatchA = implicitly[Arbitrary[FailureDetails.TrackerProtocolViolation.CriterionMismatch]]
  implicit val tpvNotJsonA           = implicitly[Arbitrary[FailureDetails.TrackerProtocolViolation.NotJson]]
  implicit val tpvInputDataA         = implicitly[Arbitrary[FailureDetails.TrackerProtocolViolation.InputData]]
  implicit val trackerProtocolViolationA: Arbitrary[FailureDetails.TrackerProtocolViolation] = Arbitrary(
    Gen.oneOf(tpvNotJsonA.arbitrary, tpvInputDataA.arbitrary, tpvCriterionMismatchA.arbitrary)
  )
  implicit val sizeViolationA    = implicitly[Arbitrary[Failure.SizeViolation]]
  implicit val collectorPayloadA = implicitly[Arbitrary[Payload.CollectorPayload]]

  implicit val badRowAdapterFailuresA           = implicitly[Arbitrary[BadRow.AdapterFailures]]
  implicit val badRowTrackerProtocolViolationsA = implicitly[Arbitrary[BadRow.TrackerProtocolViolations]]
  implicit val badRowSizeViolationA             = implicitly[Arbitrary[BadRow.SizeViolation]]
  implicit val badRowcpFormatViolationA         = implicitly[Arbitrary[BadRow.CPFormatViolation]]
  implicit val recoverableBadRowA: Arbitrary[BadRow] = Arbitrary(
    Gen.oneOf(badRowAdapterFailuresA.arbitrary, badRowTrackerProtocolViolationsA.arbitrary)
  )
  implicit val recoveryErrorBadRow: Arbitrary[BadRow.RecoveryError] = Arbitrary(
    for {
      processor  <- processorA.arbitrary
      error      <- nonEmptyString.arbitrary
      failure    <- Gen.option(nonEmptyString.arbitrary)
      payload    <- recoverableBadRowA.arbitrary
      recoveries <- Gen.choose(1, 3)
    } yield BadRow.RecoveryError(processor, Failure.RecoveryFailure(error, failure), payload, recoveries)
  )

  implicit val uuidGen: Gen[UUID] = Gen.uuid

  implicit val replacementA = implicitly[Arbitrary[Replacement]]
  implicit val removalA     = implicitly[Arbitrary[Removal]]
  implicit val castingA     = implicitly[Arbitrary[Casting]]
  implicit val stepConfigA  = implicitly[Arbitrary[StepConfig]]

  implicit val jsonA: Arbitrary[Json] = Arbitrary(Gen.alphaNumStr.map(_.asJson))
  implicit val basic                  = Vector(Gen.alphaNumStr, Gen.posNum[Double])
  implicit val regexA                 = implicitly[Arbitrary[RegularExpression]]

  implicit val sizeGtA = implicitly[Arbitrary[Size.Gt]]
  implicit val sizeLtA = implicitly[Arbitrary[Size.Lt]]
  implicit val sizeEqA = implicitly[Arbitrary[Size.Eq]]
  implicit val sizesA  = implicitly[Arbitrary[Size.Matcher]]
  implicit val sizeA   = implicitly[Arbitrary[Size]]

  implicit val invalidJsonFormatA = implicitly[Arbitrary[InvalidJsonFormat]]

  implicit val matcherA: Arbitrary[Matcher] = Arbitrary(
    Gen.oneOf(regexA.arbitrary, sizeA.arbitrary)
  )
  implicit val conditionA = implicitly[Arbitrary[Condition]]

  val badRowTypeA = Arbitrary(
    Gen.oneOf(
      "adapter_failures",
      "collector_format_violation",
      "enrichment_failures",
      "loader_iglu_error",
      "loader_parsing_error",
      "loader_recovery_error",
      "loader_runtime_error",
      "relay_failure",
      "schema_violations",
      "size_violation",
      "snowflake_error",
      "tracker_protocol_violations"
    )
  )
  val numberOrStarA = Arbitrary(Gen.oneOf(Gen.posNum[Int], Gen.const("*")))
  val igluUriGen = for {
    badRowType <- badRowTypeA.arbitrary
    major      <- numberOrStarA.arbitrary
    minor      <- numberOrStarA.arbitrary
    patch      <- numberOrStarA.arbitrary
  } yield s"iglu:com.snowplowanalytics.snowplow.badrows/$badRowType/jsonschema/$major-$minor-$patch"

  val nonEmptyString = Arbitrary(Gen.nonEmptyListOf[Char](Gen.alphaChar).map(_.mkString))

  val querystringGen = (paramGen: Gen[String]) =>
    (for {
      xs <- Gen.choose(5, 20)
      ks <- Gen.listOfN(xs, nonEmptyString.arbitrary.map(_.take(7)).map(v => "(=|&)".r.replaceAllIn(v, "")))
      vs <- Gen.listOfN(xs, paramGen)
    } yield ks.zip(vs).toMap.map { case (k, v) => s"$k=$v" }.mkString("&"))

  val validParamGen = Gen.nonEmptyListOf(Gen.oneOf((('a' to 'z') ++ ('A' to 'Z') ++ (0 to 9)).mkString)).map(_.mkString)
  val paramGen = for {
    format <- Gen.oneOf(("", ""), ("{{", "}}"), ("${", "}"), ("[", "]"))
    str    <- Gen.alphaNumStr.map(v => "(=|&)".r.replaceAllIn(v, ""))
  } yield format._1 ++ str ++ format._2
}
