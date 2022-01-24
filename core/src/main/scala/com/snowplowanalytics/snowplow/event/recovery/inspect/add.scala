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

import cats.implicits._
import io.circe._
import io.circe.syntax._

import domain._

/** A faux transformation checking for specific conditions on JSON types (including Base64-encoded) to others including.
  */
object add {

  /** Runs check operation.
    *
    * @param path
    *   a list describing route to field being transformed
    * @param body
    *   JSON structure being transformed
    */
  def apply(path: Seq[String], value: Json)(body: Json): Recovering[Json] = {
    val error = AdditionFailure(_, body.noSpaces, value.noSpaces)
    transform(addFn(value, error), transform.top(error), error)(path)(body)
  }

  def addFn(add: Json, error: String => RecoveryStatus)(to: Json): Recovering[Json] =
    to.fold[Json](
      add,
      x => (x && add.asBoolean.getOrElse(false)).asJson,
      x => add.asNumber.map(_.toDouble + x.toDouble).getOrElse(x.toDouble).asJson, // FIXME number type?
      x => (x + add.asString.getOrElse("".asJson)).asJson,
      x => (x ++ add.asArray.getOrElse(Vector.empty[Json])).asJson,
      x =>
        add
          .asObject
          .map(_.toMap.foldLeft[JsonObject](x) { case (acc, (k, v)) =>
            acc.add(k, v)
          })
          .map(_.asJson)
          .getOrElse(x.asJson)
    ).asRight
      .leftMap(error) // FIXME proper left
}
