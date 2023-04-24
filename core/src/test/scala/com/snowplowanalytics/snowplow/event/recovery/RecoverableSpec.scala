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

import java.time.Instant

import cats.implicits._
import org.scalatest.Inspectors
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.EitherValues._
import org.scalatestplus.scalacheck._

import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.badrows.Payload.RawPayload
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import recoverable.Recoverable.ops._
import config._
import config.conditions._
import domain._
import util._
import gens._
import io.circe.syntax._

class RecoverableSpec extends AnyWordSpec with Inspectors with ScalaCheckPropertyChecks {
  val anyString = "(?U)^.*$".some
  val prefix    = "payload.replacement"

  "Recoverable" should {
    "allow matcher-based field content replacement" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val field       = "vendor"
        val value       = b.payload.vendor
        val replacement = s"$prefix$value"
        val conf        = (replacement: String) => List(Replacement(Replace, field, anyString, replacement.asJson))

        val recovered = b.recover(conf(replacement))
        recovered should be('right)
        recovered.value.vendor should equal(replacement)

        val reverted = b.recover(conf(value))
        reverted should be('right)
        reverted.value.vendor should equal(value)
      }
    }
    "allow matcher-based field content removal" in {
      forAll { (b: BadRow.TrackerProtocolViolations) =>
        val field     = Field(b.payload)
        val conf      = List(Removal(Remove, field.name, anyString))
        val recovered = b.recover(conf)

        recovered should be('right)
        recovered.flatMap(v => Field.extract(v, field.name).map(_.value).toRight("value missing")).value match {
          case Some(v) => v shouldEqual ""
          case None    => true
          case v       => v shouldEqual ""
        }
      }
    }
    "allow chaining processing steps" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val field       = Field(b.payload)
        val replacement = s"$prefix${field.name}"

        val conf =
          List(
            Replacement(Replace, field.name, anyString, replacement.asJson),
            Removal(Remove, field.name, field.name.some)
          )
        val recovered = b.recover(conf)
        recovered should be('right)
        recovered.flatMap(v => Field.extract(v, field.name).map(_.value).toRight("value missing")).value match {
          case Some(v) => v shouldEqual prefix
          case None    => true
          case v       => v shouldEqual prefix
        }
      }
    }
    "mark flows recoverable" in {
      forAll { (b: BadRow) =>
        b.recover(List.empty) should be('right)
      }
    }
    "mark flows unercoverable" in {
      forAll { (b: BadRow.SizeViolation) =>
        b.recover(List.empty) should be('left)
      }
      forAll { (b: BadRow.CPFormatViolation, cp: CollectorPayload) =>
        val withoutQuerystring = withQS(b, Map.empty, cp)
        withoutQuerystring.value.recover(List.empty) should be('left)
      }
    }
    "handle composite bad row" in {
      forAll { (b: BadRow.RecoveryError) =>
        b.recover(List.empty) should be('right)
      }
    }
    "handle CPFormatViolation when querystring contains invalid characters" in {
      forAll { (b: BadRow.CPFormatViolation, cp: CollectorPayload) =>
        val fill            = "lorem-ipsum"
        val formats         = Seq(s"[$fill]", s"{{$fill}}", s"{$fill}", s"$${$fill}")
        val withQuerystring = withQS(b, Stream.from(1).map(_.toString).zip(formats).toMap, cp)
        val recovered       = withQuerystring.getOrElse(b).recover(List.empty)
        val params          = recovered.value.querystring.map { case NVP(_, v) => v }.flatten

        recovered should be('right)
        params.filter(_.contains(fill)) should have size (formats.size)
      }
    }
    "handle CPFormatViolation when querystring is null" in {
      forAll { (b: BadRow.CPFormatViolation, cp: CollectorPayload) =>
        cp.querystring = null
        val badRow = thrift.serialize(cp).map(p => b.copy(payload = RawPayload(p)))
        badRow.flatMap(_.recover(List.empty)) should be('left)
      }
    }
    "handle CPFormatViolation when body is double Base64-encoded" in {
      forAll{ (b: BadRow.CPFormatViolation, cp: CollectorPayload) =>
        val base64cp: String = thrift.serialize(cp).flatMap(base64.encode).right.get
        val bad = b.copy(payload = Payload.RawPayload(base64cp), failure = Failure.CPFormatViolation(Instant.ofEpochMilli(0), "some-loader-1.0.0", FailureDetails.CPFormatViolationMessage.Fallback("error deserializing raw event: Unrecognized type 67")))
        val recovered: Recovering[Payload.CollectorPayload] = bad.recover(List.empty)

        val original = payload.cocoerce(cp)
        recovered should be ('right)
        recovered.value shouldEqual(original.value)
      }
    }
  }

  private[this] def withQS(
    b: BadRow.CPFormatViolation,
    value: Map[String, String],
    cp: CollectorPayload
  ): Recovering[BadRow.CPFormatViolation] = {
    cp.querystring =
      value.toSeq.map { case (k, v) => s"$k=$v" }.foldRight("")((acc, curr) => s"$acc&$curr").dropRight(1)
    thrift.serialize(cp).map(p => b.copy(payload = RawPayload(p)))
  }
}
