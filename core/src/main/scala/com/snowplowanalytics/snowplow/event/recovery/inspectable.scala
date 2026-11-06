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

    /** A typeclass defining a set of operations that can be applied as part of recovery steps for request Payloads.
      */
    trait Inspectable[A <: Payload] {

      /** A transformation replacing JSON values (including Base64-encoded) to with others. Can perform operations on
        * all JSON types.
        * @param a
        *   an instance of Payload to transform
        * @param path
        *   Json Path navigation route ie. raw.vendor
        * @param matcher
        *   a regex string for matching values
        * @param replacement
        *   a new value to be set
        */
      def replace(
        a: A
      )(
        path: String,
        matcher: Option[String],
        replacement: Json
      )(implicit
        e: Encoder[A],
        d: Decoder[A]
      ): Recovering[A] =
        inspect
          .replace(matcher, replacement)(json.path(path))(a.asJson)
          .flatMap(_.as[A].leftMap(err => InvalidJsonFormat(err.getMessage)))

      /** A transformation removing JSON attributes values (including Base64-encoded). Can perform operations on all
        * JSON types.
        * @param a
        *   an instance of Payload to transform
        * @param path
        *   Json Path navigation route ie. raw.vendor
        * @param matcher
        *   a regex string for matching values
        */
      def remove(a: A)(path: Path, matcher: Option[String])(implicit e: Encoder[A], d: Decoder[A]): Recovering[A] =
        replace(a)(path, matcher, "".asJson)

      /** A transformation casting JSON types (including Base64-encoded) to others. Can perform operations on all JSON
        * types.
        * @param a
        *   an instance of Payload to transform
        * @param path
        *   Json Path navigation route ie. raw.vendor
        * @param from
        *   current type of the field being cast
        * @param to
        *   target type of the field being cast
        */
      def cast(
        a: A
      )(
        path: Path,
        from: CastType,
        to: CastType
      )(implicit
        e: Encoder[A],
        d: Decoder[A]
      ): Recovering[A] =
        inspect
          .cast(from, to)(json.path(path))(a.asJson)
          .flatMap(_.as[A].leftMap(err => InvalidJsonFormat(err.getMessage)))

      /** A transformation adding JSON values to fields. Can perform operations on all JSON types.
        * @param a
        *   an instance of Payload to transform
        * @param path
        *   Json Path navigation route ie. raw.vendor
        * @param value
        *   Json object to merge with current value
        */
      def add(
        a: A
      )(
        path: Path,
        value: Json
      )(implicit
        e: Encoder[A],
        d: Decoder[A]
      ): Recovering[A] =
        inspect.add(json.path(path), value)(a.asJson).flatMap(_.as[A].leftMap(err => InvalidJsonFormat(err.getMessage)))
    }

    def apply[A <: Payload: Decoder: Encoder](implicit i: Inspectable[A]): Inspectable[A] = i

    object ops {
      implicit class InspectableOps[A <: Payload: Inspectable: Encoder: Decoder](a: A) {
        def replace(
          path: Path,
          matcher: Option[String],
          replacement: Json
        ) =
          Inspectable[A].replace(a)(path, matcher, replacement)
        def remove(path: Path, matcher: Option[String]) =
          Inspectable[A].remove(a)(path, matcher)
        def cast(
          path: Path,
          from: CastType,
          to: CastType
        ) =
          Inspectable[A].cast(a)(path, from, to)
        def add(
          path: Path,
          value: Json
        ) =
          Inspectable[A].add(a)(path, value)
      }
    }

    implicit val collectorPayloadInspectable: Inspectable[Payload.CollectorPayload] =
      new Inspectable[Payload.CollectorPayload] {}

    implicit val enrichmentPayloadInspectable: Inspectable[Payload.EnrichmentPayload] =
      new Inspectable[Payload.EnrichmentPayload] {}
  }

}
