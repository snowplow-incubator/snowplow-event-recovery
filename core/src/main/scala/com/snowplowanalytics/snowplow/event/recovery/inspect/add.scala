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
