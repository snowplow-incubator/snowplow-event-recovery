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
package inspect

import cats.Id
import cats.syntax.either._
import cats.syntax.option._
import org.scalatest._
import org.scalatest.matchers.should.Matchers._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import io.circe.parser.parse
import io.circe.syntax._
import monocle.macros.syntax.lens._
import com.snowplowanalytics.snowplow.badrows._
import cats.data.EitherT
import org.scalacheck.Gen
import org.scalatest.wordspec.AnyWordSpec

class AddSpec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues {

  "add" should {

    "concatenate string values" in forAll(gens.badRowSizeViolationA.arbitrary) { br =>
      val suffix = "-SNAPSHOT"
      val value  = br.lens(_.processor.version).get

      (for {
        a <- EitherT.fromEither[Id](add(Seq("processor", "version"), suffix.asJson)(br.asJson).leftMap(_.message))
        f <- EitherT.fromOption[Id](a.hcursor.downField("processor").downField("version").focus, "empty cursor")
        o <- EitherT.fromEither[Id](f.as[String].leftMap(_.message))
      } yield o).value.value should equal(s"$value$suffix")
    }

    "merge arrays" in forAll(gens.badRowAdapterFailuresA.arbitrary) { br =>
      val suffix           = NVP("lorem", "ipsum".some)
      val value: List[NVP] = br.lens(_.payload.querystring).get

      (for {
        a <- EitherT.fromEither[Id](
          add(Seq("payload", "querystring"), List(suffix).asJson)(br.asJson).leftMap(_.message)
        )
        f <- EitherT.fromOption[Id](a.hcursor.downField("payload").downField("querystring").focus, "empty cursor")
        o <- EitherT.fromEither[Id](f.as[List[NVP]](json.nvpsDecoder).leftMap(_.message))
      } yield o).value.value should contain theSameElementsAs (value :+ suffix)

    }

    "merge object keys" in forAll(gens.badRowAdapterFailuresA.arbitrary) { br =>
      val key    = "lorem"
      val value  = "ipsum"
      val suffix = parse(s"""{"$key":"$value"}""").right.get

      (for {
        a <- EitherT.fromEither[Id](add(Seq("processor"), suffix)(br.asJson).leftMap(_.message))
        f <- EitherT.fromOption[Id](a.hcursor.downField("processor").focus, "empty cursor")
        o <- EitherT.fromOption[Id](f.asObject, "not an object")
        v <- EitherT.fromOption[Id](o.toMap.get(key), "no key")
      } yield v).value.value should equal(value.asJson)

    }

    val key       = "i"
    val path      = key :: Nil
    def struct[T] = (i: T) => parse(s"""{"$key": $i}""").right.get

    "add numbers" in forAll(Gen.posNum[Double], Gen.posNum[Double]) { (i, j) =>
      (for {
        a <- EitherT.fromEither[Id](add(path, j.asJson)(struct(i)).leftMap(_.message))
        f <- EitherT.fromOption[Id](a.hcursor.downField(key).focus, "empty cursor")
        o <- EitherT.fromEither[Id](f.as[Double].leftMap(_.message))
      } yield o).value.value should equal(i + j)
    }

    "perform a logical AND on booleans" in forAll { (i: Boolean, j: Boolean) =>
      (for {
        a <- EitherT.fromEither[Id](add(path, j.asJson)(struct(i)).leftMap(_.message))
        f <- EitherT.fromOption[Id](a.hcursor.downField(key).focus, "empty cursor")
        o <- EitherT.fromEither[Id](f.as[Boolean].leftMap(_.message))
      } yield o).value.value should equal(i && j)
    }

    "override null values" in forAll(gens.jsonA.arbitrary) { json =>
      (for {
        a <- EitherT.fromEither[Id](add(path, json)(struct(null)).leftMap(_.message))
        f <- EitherT.fromOption[Id](a.hcursor.downField(key).focus, "empty cursor")
      } yield f).value.value should equal(json)
    }

  }

}
