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
case class Size(size: Size.Matcher) extends Matcher {
  def checks(j: Json) =
    j.fold(
      false,
      _ => false,
      n => size.check(Math.ceil(n.toDouble).toInt),
      s => size.check(s.size),
      a => size.check(a.size),
      _ => false
    )
}

case object Size {
  sealed trait Matcher {
    def check(that: Int): Boolean
  }
  case class Eq(eq: Int) extends Matcher {
    def check(that: Int) = that == eq
  }
  case class Gt(gt: Int) extends Matcher {
    def check(that: Int) = that > gt
  }
  case class Lt(lt: Int) extends Matcher {
    def check(that: Int) = that < lt
  }
}
