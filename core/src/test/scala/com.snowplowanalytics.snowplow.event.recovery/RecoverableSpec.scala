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

import cats.implicits._
import org.scalatest.{FreeSpec, Inspectors}
import org.scalatest.Matchers._
import org.scalatest.EitherValues._
import org.scalatestplus.scalacheck._

import com.snowplowanalytics.snowplow.badrows._
import recoverable.Recoverable.ops._
import config._
import config.conditions._
import gens._

class RecoverableSpec extends FreeSpec with Inspectors with ScalaCheckPropertyChecks {
  val anyString = "(?U)^.*$"
  val prefix = "payload.replacement"

  "Recoverable" - {
    "allow matcher-based field content replacement" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val field = "vendor"
        val value = b.payload.vendor
        val replacement = s"$prefix$value"
        val conf = (replacement: String) => List(Replacement(Replace, field, anyString, replacement))

        val recovered = b.recover(conf(replacement))
        recovered should be ('right)
        recovered.right.value.payload.vendor should equal (replacement)

        val reverted = b.recover(conf(value))
        reverted should be ('right)
        reverted.right.value.payload.vendor should equal (value)
      }
    }
    "allow matcher-based field content removal" in {
      forAll { (b: BadRow.TrackerProtocolViolations) =>
        val field = Field(b.payload)
        val conf = List(Removal(Remove, field.name, anyString))

        val recovered = b.recover(conf)
        recovered should be ('right)
        recovered.map(v => Field.extract(v.payload, field.name).map(_.value)).right.value.get match {
          case Some(v) => v shouldEqual ""
          case None => true
          case v => v shouldEqual ""
        }
      }
    }
    "allow chaining processing steps" in {
      forAll { (b: BadRow.AdapterFailures) =>
        val field = Field(b.payload)
        val replacement = s"$prefix${field.name}"

        val conf = List(Replacement(Replace, field.name, anyString, replacement), Removal(Remove, field.name, field.name))

        val recovered = b.recover(conf)
        recovered should be ('right)
        recovered.map(v => Field.extract(v.payload, field.name).map(_.value)).right.value.get match {
          case Some(v) => v shouldEqual prefix
          case None => true
          case v => v shouldEqual prefix
        }
      }
    }
    "mark flows unercoverable" in {
      forAll { (b: BadRow.SizeViolation) =>
        b.recover(List.empty) should be ('left)
      }
      forAll { (b: BadRow.CPFormatViolation) =>
        b.recover(List.empty) should be ('left)
      }
    }
  }
}
