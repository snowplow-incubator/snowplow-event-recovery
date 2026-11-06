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
