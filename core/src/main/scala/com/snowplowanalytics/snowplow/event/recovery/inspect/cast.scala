/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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

import domain._
import com.snowplowanalytics.snowplow.event.recovery.config.CastType

/** A transformation casting JSON types (including Base64-encoded) to others including. Can perform following
  * transformations: Numeric -> Boolean (treats 0 as false, other values as true) Boolean -> Numeric (turns true to 1
  * and false to 0) String -> Numeric (tries to parse string as a number) _ -> Array (wraps existing value into array) _
  * -> String (casts value to string) Returns Left for other cases.
  */
object cast {

  /** Runs cast operation.
    *
    * @param from
    *   current type of the field being cast
    * @param to
    *   target type of the field being cast
    * @param path
    *   a list describing route to field being transformed
    * @param body
    *   JSON structure being transformed
    */
  def apply(from: CastType, to: CastType)(path: Seq[String])(body: Json): Recovering[Json] = {
    val error = CastFailure(_, from, to)
    transform(castFn(from, to), transform.top(error), error)(path)(body)
  }

  def castFn(from: CastType, to: CastType)(value: Json): Recovering[Json] = (from, to) match {
    case (_, CastType.Array) if !value.isArray => Right(Json.arr(value))
    case (_, CastType.String)                  => Right(value.noSpaces.asJson)
    case (CastType.Numeric, CastType.Boolean) if value.isNumber =>
      value.asNumber.map(v => if (v == 0) Json.False else Json.True).toRight(CastFailure(value.noSpaces, from, to))
    case (CastType.Boolean, CastType.Numeric) if value.isBoolean =>
      value.asBoolean.map(v => if (v == true) 1.asJson else 0.asJson).toRight(CastFailure(value.noSpaces, from, to))
    case (CastType.String, CastType.Numeric) if value.isString =>
      value
        .asString
        .flatMap(v => Either.catchNonFatal(v.toLong.asJson).toOption)
        .toRight(CastFailure(value.noSpaces, from, to))
    case _ => Left(CastFailure(value.noSpaces, from, to))
  }
}
