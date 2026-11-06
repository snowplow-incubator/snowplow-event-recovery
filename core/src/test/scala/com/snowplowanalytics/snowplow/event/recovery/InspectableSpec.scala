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

import cats.syntax.option._
import org.scalatest.Inspectors
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatestplus.scalacheck._

import com.snowplowanalytics.snowplow.badrows._
import inspectable.Inspectable.ops._
import gens._
import io.circe.syntax._

class InspectableSpec extends AnyWordSpec with Inspectors with ScalaCheckPropertyChecks {
  val anyString = "(?U)^.*$".some
  val prefix    = "replacement"

  "Inspectable" should {
    "allow matcher-based field content replacement" in {
      forAll { (payload: Payload.CollectorPayload) =>
        val field       = Field(payload)
        val replacement = s"$prefix${field.name}"
        val replaced    = payload.replace(field.name, anyString, replacement.asJson)
        replaced should be('right)
        replaced.value should not be equal(payload)
        val reverted = replaced.value.replace(field.name, anyString, field.strValue.asJson)
        reverted should be('right)
        Field.extract(payload, field.name).map(_.value).value should equal(field.value)
      }
    }
    "allow matcher-based field content removal" in {
      forAll { (payload: Payload.CollectorPayload) =>
        val field    = Field(payload)
        val replaced = payload.remove(s"${field.name}", anyString)
        replaced should be('right)
        replaced.value should not be equal(payload)
        Field.extract(payload, field.name).map(_.value).value should equal(field.value)
      }
    }
  }
}
