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

package com.snowplowanalytics.snowplow.event.recovery.config.conditions

import scala.annotation.tailrec
import io.circe.Json
import io.circe.ACursor

/**
  * Defines a condition used for matching against data to check whether to apply given set of steps in FlowConfig.
  */
case class Condition(
  op: Test.type,
  path: String,
  value: Matcher
) {
  def check(str: Json): Boolean =
    nested(str, path).map(value.checks).getOrElse(false)

  private[this] def nested(json: Json, path: String): Option[String] = {
    @tailrec
    def apply(root: ACursor, nodes: Seq[String]): Option[String] = nodes match {
      case Seq() =>
        root.focus.flatMap(_.asString)
      case Seq(h, t @ _*) =>
        apply(root.downField(h), t)
    }
    apply(json.hcursor, path.split('.'))
  }
}
