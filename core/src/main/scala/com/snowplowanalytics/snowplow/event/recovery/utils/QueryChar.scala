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
package com.snowplowanalytics.snowplow.event.recovery
package util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import cats.implicits._
import cats.Show

object QueryChar {

  def normalize: QueryChar.QueryChar => String = {
    case QueryChar.Invalid(v)            => URLEncoder.encode(v.show, UTF_8.toString)
    case QueryChar.Bracketed(b1, xs, b2) => b1 + xs.map(normalize).mkString + b2
    case v: QueryChar.QueryChar          => v.show
  }

  sealed trait QueryChar
  case class Encoded(h1: Char, h2: Char) extends QueryChar
  case class Unreserved(char: Char) extends QueryChar
  case class Reserved(char: Char) extends QueryChar
  case class Bracketed(b1: Char, chars: List[QueryChar], b2: Char) extends QueryChar
  case class Invalid(char: Char) extends QueryChar

  implicit val showQ: Show[QueryChar] = Show.show {
    case v: Encoded    => s"%${v.h1}${v.h2}"
    case r: Reserved   => r.char.toString
    case u: Unreserved => u.char.toString
    case i: Invalid    => i.char.toString
    case b: Bracketed  => s"${b.b1}${b.chars.map(_.show).mkString}${b.b2}"
  }
}
