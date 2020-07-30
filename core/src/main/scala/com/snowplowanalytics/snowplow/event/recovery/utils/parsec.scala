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

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import cats.implicits._
import cats.Show
import atto._, Atto._
import com.snowplowanalytics.snowplow.badrows.NVP
import domain._

object parsec {
  val querystring = ((stringOf(noneOf("&=")) <~ char('=')) ~ stringOf(notChar('&'))).sepBy(char('&'))
  val queryparam  = many(chars.encoded | chars.bracketed | chars.reserved | chars.unreserved | chars.invalid)

  object chars {
    val encoded  = (char('%') ~> hexDigit ~ hexDigit).map { case (c1: Char, c2: Char) => QueryChar.Encoded(c1, c2) }
    val reserved = oneOf("/#@!$&\\*+,;").map(QueryChar.Reserved(_))
    val unreserved = {
      val chars   = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9')).mkString
      val special = "-._~"
      (oneOf(special) | oneOf(chars)).map(QueryChar.Unreserved(_))
    }
    val invalid = anyChar.map(QueryChar.Invalid(_))
    val bracketed = {
      val internal = many(encoded | reserved | unreserved | invalid)
      squareBrackets(internal).map(QueryChar.Bracketed('[', _, ']')) |
        parens(internal).map(QueryChar.Bracketed('(', _, ')'))
    }
  }
}


