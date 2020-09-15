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

import io.circe._
import io.circe.syntax._

import domain._
import com.snowplowanalytics.snowplow.event.recovery.config.conditions.Matcher

/**
  * A faux transformation checking for specific conditions on JSON types (including Base64-encoded) to others including.
  */
object check {

  /**
    * Runs check operation.
    *
    * @param path a list describing route to field being transformed
    * @param body JSON structure being transformed
    */
  def apply(matcher: Matcher)(path: Seq[String])(body: Json): Recovering[Boolean] =
    transform(
      json => Right(matcher.checks(json).asJson),
      (_: ACursor) => _.focus.toRight(ComparisonFailure(path.mkString("."), matcher.toString, body.toString)),
      ComparisonFailure(path.mkString("."), matcher.toString, _)
    )(path)(body).map { v =>
      v.fold(
        false,
        identity,
        _ => false,
        _ == "true",
        _ => false,
        _ => false
      )
    }

}
