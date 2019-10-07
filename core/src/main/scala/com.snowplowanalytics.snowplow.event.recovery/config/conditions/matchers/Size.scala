/*
 * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
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

import io.circe.Json

/**
  */
case class Size(size: Size.Matcher) extends Matcher {
  def string = v => size.check(v.size)
  def num: Long => Boolean = v => size.check(Math.ceil(v.toDouble).toInt)
  def seq: Seq[_] => Boolean = v => size.check(v.size)
  def json: Json => Boolean = _ => false
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
