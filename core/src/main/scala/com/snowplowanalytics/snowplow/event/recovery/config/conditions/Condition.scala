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
