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
package inspect

import cats.implicits._
import io.circe._
import io.circe.syntax._
import io.circe.parser.{parse => parseJson}

import transform._
import domain._


/**
  * A transformation casting JSON types (including Base64-encoded) to others including.
  * Can perform operations on all JSON types.
  */
object replace {

  /**
    * Runs replace operation.
    * 
    * @param matcher a regex string for matching values
    * @param value a new value to be set for matches
    * @param path a list describing route to field being transformed
    * @param body JSON structure being transformed
    */
  def apply(matcher: String, value: String)(path: Seq[String])(
      body: Json
  ): Recovering[Json] =
    transform(
      replaceFn(matcher, value),
      ReplacementFailure(
        _,
        matcher,
        value
      )
    )(path)(body)

  private[this] def replaceFn(
      matcher: String,
      value: String
  ): Json => Recovering[Json] = {
    case v if v.isNumber =>
      number(value)(v.asNumber.get).map(_.asJson)
    case v if v.isObject =>
      jObject(Seq.empty, matcher, value)(v.asObject.get).map(_.asJson)
    case v if v.isBoolean =>
      boolean(value)(v.asBoolean.get).map(_.asJson)
    case v if v.isArray =>
      array(matcher, value)(v.asArray.get).map(_.asJson)
    case v if v.isNull =>
      string(Seq.empty, matcher, value)("").map(_.asJson)
    case v if v.isString =>
      string(Seq.empty, matcher, value)(v.asString.get).map(_.asJson)
  }

  private[this] def boolean(value: String)(
      x: Boolean
  ): Recovering[Boolean] = {
    Either
      .catchNonFatal(value.toBoolean)
      .leftMap(err => ReplacementFailure(err.getMessage, "any", x.toString))
  }

  private[this] def number(value: String)(
      x: JsonNumber
  ): Recovering[JsonNumber] = {
    JsonNumber
      .fromString(value)
      .toRight(ReplacementFailure(x.toString, "any", value))
  }

  private[this] def array(matcher: String, value: String)(
      x: Vector[Json]
  ): Recovering[Vector[Json]] = {
    x.map(
        _.fold[Recovering[Json]](
          Right("".asJson),
          b => boolean(value)(b).map(_.asJson),
          n => number(value)(n).map(_.asJson),
          s => string(Seq.empty, matcher, value)(s).map(_.asJson),
          a => array(matcher, value)(a).map(_.asJson),
          o => jObject(Seq.empty, matcher, value)(o).map(_.asJson)
        )
      )
      .sequence[Recovering, Json]
  }

  private[this] def string(
      context: Seq[String],
      matcher: String,
      value: String
  )(
      x: String
  ): Recovering[String] =
    if (isB64Encoded(x)) {
      base64(context, matcher, value)(x)
    } else {
      Right(matcher.r.replaceAllIn(x, value))
    }

  private[this] def jObject(
      context: Seq[String],
      matcher: String,
      value: String
  )(
      x: JsonObject
  ): Recovering[JsonObject] =
    for {
      s <- string(context, matcher, value)(x.asJson.noSpaces)
      p <- parseJson(s).leftMap(_ => InvalidJsonFormat(s))
      o <- p.asObject.toRight(
        ReplacementFailure(x.asJson.noSpaces, matcher, value)
      )
    } yield o

  private[this] def base64(
      path: Seq[String],
      matcher: String,
      replacement: String
  )(
      str: String
  ): Recovering[String] =
    for {
      decoded <- decode(str)
      parsed <- parse(decoded)
      replaced <- replace(matcher, replacement)(path)(parsed)
    } yield replaced.noSpaces
}
