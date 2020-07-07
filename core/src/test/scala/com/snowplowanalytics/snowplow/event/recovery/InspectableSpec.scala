/*
 * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
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

import cats.syntax.option._
import org.scalatest.{Inspectors, WordSpec}
import org.scalatest.Matchers._
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatestplus.scalacheck._

import com.snowplowanalytics.snowplow.badrows._
import inspectable.Inspectable.ops._
import gens._

class InspectableSpec extends WordSpec with Inspectors with ScalaCheckPropertyChecks {
  val anyString = "(?U)^.*$".some
  val prefix    = "replacement"

  "Inspectable" should {
    "allow matcher-based field content replacement" in {
      forAll { (payload: Payload.CollectorPayload) =>
        val field       = Field(payload)
        val replacement = s"$prefix${field.name}"
        val replaced    = payload.replace(field.name, anyString, replacement)
        replaced should be('right)
        replaced.right.value should not be equal(payload)
        val reverted = replaced.right.value.replace(field.name, anyString, field.strValue)
        reverted should be('right)
        Field.extract(payload, field.name).map(_.value).value should equal(field.value)
      }
    }
    "allow matcher-based field content removal" in {
      forAll { (payload: Payload.CollectorPayload) =>
        val field    = Field(payload)
        val replaced = payload.remove(s"${field.name}", anyString)
        replaced should be('right)
        replaced.right.value should not be equal(payload)
        Field.extract(payload, field.name).map(_.value).value should equal(field.value)
      }
    }
  }
}
