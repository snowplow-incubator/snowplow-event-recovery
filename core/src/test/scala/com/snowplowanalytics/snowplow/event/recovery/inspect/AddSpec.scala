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
package inspect

import cats.syntax.either._
import cats.syntax.option._
import org.scalatest._
import org.scalatest.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import io.circe.parser.parse
import io.circe.syntax._
import monocle.macros.syntax.lens._
import com.snowplowanalytics.snowplow.badrows._
import cats.data.EitherT
import org.scalacheck.Gen

class AddSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues {

  "add" should {

    "concatenate string values" in forAll(gens.badRowSizeViolationA.arbitrary) { br =>
      val suffix = "-SNAPSHOT"
      val value  = br.lens(_.processor.version).get

      (for {
        a <- EitherT.fromEither(add(Seq("processor", "version"), suffix.asJson)(br.asJson).leftMap(_.message))
        f <- EitherT.fromOption(a.hcursor.downField("processor").downField("version").focus, "empty cursor")
        o <- EitherT.fromEither(f.as[String].leftMap(_.message))
      } yield o).value.right.value should equal(s"$value$suffix")
    }

    "merge arrays" in forAll(gens.badRowAdapterFailuresA.arbitrary) { br =>
      val suffix           = NVP("lorem", "ipsum".some)
      val value: List[NVP] = br.lens(_.payload.querystring).get

      (for {
        a <- EitherT.fromEither(add(Seq("payload", "querystring"), List(suffix).asJson)(br.asJson).leftMap(_.message))
        f <- EitherT.fromOption(a.hcursor.downField("payload").downField("querystring").focus, "empty cursor")
        o <- EitherT.fromEither(f.as[List[NVP]].leftMap(_.message))
      } yield o).value.right.value should contain theSameElementsAs (value :+ suffix)

    }

    "merge object keys" in forAll(gens.badRowAdapterFailuresA.arbitrary) { br =>
      val key    = "lorem"
      val value  = "ipsum"
      val suffix = parse(s"""{"$key":"$value"}""").right.get

      (for {
        a <- EitherT.fromEither(add(Seq("processor"), suffix)(br.asJson).leftMap(_.message))
        f <- EitherT.fromOption(a.hcursor.downField("processor").focus, "empty cursor")
        o <- EitherT.fromOption(f.asObject, "not an object")
        v <- EitherT.fromOption(o.toMap.get(key), "no key")
      } yield v).value.right.value should equal(value.asJson)

    }

    val key       = "i"
    val path      = key :: Nil
    def struct[T] = (i: T) => parse(s"""{"$key": $i}""").right.get

    "add numbers" in forAll(Gen.posNum[Double], Gen.posNum[Double]) { (i, j) =>
      (for {
        a <- EitherT.fromEither(add(path, j.asJson)(struct(i)).leftMap(_.message))
        f <- EitherT.fromOption(a.hcursor.downField(key).focus, "empty cursor")
        o <- EitherT.fromEither(f.as[Double].leftMap(_.message))
      } yield o).value.right.value should equal(i + j)
    }

    "perform a logical AND on booleans" in forAll { (i: Boolean, j: Boolean) =>
      (for {
        a <- EitherT.fromEither(add(path, j.asJson)(struct(i)).leftMap(_.message))
        f <- EitherT.fromOption(a.hcursor.downField(key).focus, "empty cursor")
        o <- EitherT.fromEither(f.as[Boolean].leftMap(_.message))
      } yield o).value.right.value should equal(i && j)
    }

    "override null values" in forAll(gens.jsonA.arbitrary) { json =>
      (for {
        a <- EitherT.fromEither(add(path, json)(struct(null)).leftMap(_.message))
        f <- EitherT.fromOption(a.hcursor.downField(key).focus, "empty cursor")
      } yield f).value.right.value should equal(json)
    }

  }

}
