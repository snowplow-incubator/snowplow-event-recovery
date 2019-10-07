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
package com.snowplowanalytics.snowplow
package event.recovery

import cats.implicits._
import io.circe._
import io.circe.syntax._

import com.snowplowanalytics.snowplow.badrows._
import config._
import domain._

object inspectable {
  object Inspectable {

    /**
      *  A typeclass defining a set of operations that can be applied
      *  as part of recovery steps for request Payloads.
      */
    trait Inspectable[A <: Payload] {

      /**
        * A transformation replacing JSON values (including Base64-encoded) to with others.
        * Can perform operations on all JSON types.
        * @param a an instance of Payload to transform
        * @param path Json Path navigation route ie. raw.vendor
        * @param matcher a regex string for matching values
        * @param replacement a new value to be set
        */
      def replace(
          a: A
      )(
          path: Path,
          matcher: String,
          replacement: String
      )(implicit e: Encoder[A], d: Decoder[A]): Recovering[A] =
        inspect
          .replace(matcher, replacement)(extractPath(path))(a.asJson)
          .flatMap(_.as[A].leftMap(err => InvalidJsonFormat(err.getMessage)))

      /**
        * A transformation removing JSON attributes values (including Base64-encoded).
        * Can perform operations on all JSON types.
        * @param a an instance of Payload to transform
        * @param path Json Path navigation route ie. raw.vendor
        * @param matcher a regex string for matching values
        */
      def remove(a: A)(
          path: Path,
          matcher: String
      )(implicit e: Encoder[A], d: Decoder[A]): Recovering[A] =
        replace(a)(path, matcher, "")

      /**
        * A transformation casting JSON types (including Base64-encoded) to others.
        * Can perform operations on all JSON types.
        * @param a an instance of Payload to transform
        * @param path Json Path navigation route ie. raw.vendor
        * @param from current type of the field being cast
        * @param to target type of the field being cast
        */
      def cast(
          a: A
      )(path: Path, from: CastType, to: CastType)(
          implicit e: Encoder[A],
          d: Decoder[A]
      ): Recovering[A] =
        inspect
          .cast(from, to)(extractPath(path))(a.asJson)
          .flatMap(_.as[A].leftMap(err => InvalidJsonFormat(err.getMessage)))
    }

    def apply[A <: Payload: Decoder: Encoder](
        implicit i: Inspectable[A]
    ): Inspectable[A] = i

    object ops {
      implicit class InspectableOps[A <: Payload: Inspectable: Encoder: Decoder](
          a: A
      ) {
        def replace(path: Path, matcher: String, replacement: String) =
          Inspectable[A].replace(a)(path, matcher, replacement)
        def remove(path: Path, matcher: String) =
          Inspectable[A].remove(a)(path, matcher)
        def cast(path: Path, from: CastType, to: CastType) =
          Inspectable[A].cast(a)(path, from, to)
      }
    }

    implicit val collectorPayloadInspectable
        : Inspectable[Payload.CollectorPayload] =
      new Inspectable[Payload.CollectorPayload] {}

    implicit val enrichmentPayloadInspectable
        : Inspectable[Payload.EnrichmentPayload] =
      new Inspectable[Payload.EnrichmentPayload] {}
  }

  private[this] def extractPath(stringPath: String): Seq[String] =
    stringPath.split('.')
}
