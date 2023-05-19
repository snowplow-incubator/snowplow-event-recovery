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
package config.conditions

import io.circe.Json

/** Defines a condition used for matching against data to check whether to apply given set of steps in FlowConfig.
  */
case class Condition(
  op: Test.type,
  path: String,
  value: Matcher
) {
  def check(str: Json): Boolean = inspect.check(value)(json.path(path))(str).getOrElse(false)
}
