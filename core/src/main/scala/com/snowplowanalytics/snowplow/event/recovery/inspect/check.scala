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

import cats.syntax.either._
import io.circe._
import io.circe.syntax._

import domain._
import com.snowplowanalytics.snowplow.event.recovery.config.conditions.Matcher

/** A faux transformation checking for specific conditions on JSON types (including Base64-encoded) to others including.
  */
object check {

  /** Runs check operation.
    *
    * @param path
    *   a list describing route to field being transformed
    * @param body
    *   JSON structure being transformed
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
        x => if (x == "true" || x.asRight == util.base64.encode("true")) true else false,
        _ => false,
        _ => false
      )
    }

}
