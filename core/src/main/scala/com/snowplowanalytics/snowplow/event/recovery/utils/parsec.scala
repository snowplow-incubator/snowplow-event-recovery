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

import atto._, Atto._

object parsec {
  val querystring = ((stringOf(noneOf("&=")) <~ char('=')) ~ stringOf(notChar('&'))).sepBy(char('&'))
  val queryparam  = many(chars.encoded | chars.bracketed | chars.reserved | chars.unreserved | chars.invalid)

  object chars {
    val encoded  = (char('%') ~> hexDigit ~ hexDigit).map { case (c1: Char, c2: Char) => QueryChar.Encoded(c1, c2) }
    val reserved = oneOf(":/#[]@!$&'()*+,;=").map(QueryChar.Reserved(_))
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
