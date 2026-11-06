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

package com.snowplowanalytics.snowplow.event.recovery.config.conditions

import io.circe.Json

/** */
case class RegularExpression(regex: String) extends Matcher {
  def checks(j: Json) = compare(j.noSpaces)

  private[this] def compare(str: String) =
    regex.r.findFirstIn(str).isDefined
}
