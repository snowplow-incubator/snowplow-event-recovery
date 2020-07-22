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
import atto._, Atto._
import com.snowplowanalytics.snowplow.badrows.NVP
import domain._

object querystring {

  def params(querystring: String) = {
    val parser = ((stringOf(noneOf("&=")) <~ char('=')) ~ stringOf(notChar('&'))).sepBy(char('&'))
    val parsed = parser.parse(querystring).map(_.toMap).done
    (parsed.either match {
      case Right(r) if r.isEmpty => Left("empty")
      case Right(r)              => Right(r)
      case Left(l)               => Left(l)
    }).leftMap(err => unexpectedFormat(querystring, err.some))
  }

  def clean(param: String) = {
    val prefixes = "{{" :: "%{{" :: "${{" :: "{" :: "%{" :: "${" :: "[[" :: "[" :: Nil
    val suffixes = "}}" :: "}" :: "]" :: Nil
    val banned   = "()[]{}$"

    val parser =
      bracket(choice(prefixes.map(string)), stringOf(noneOf(suffixes.mkString)), choice(suffixes.map(string))) |
        stringOf(noneOf(banned))

    parser.parse(param).done.option.getOrElse("")
  }

  // FIXME use parser combinator
  def toNVP(s: String) = {
    val Empty      = List.empty
    val urlDecoded = (s: String) => URLDecoder.decode(s, UTF_8.toString())
    val toNVP = (s: String) =>
      s.split("=").toList match {
        case Nil                  => List(NVP("", Some("")))
        case "" :: "" :: Nil      => List(NVP("", Some("")))
        case "" :: value :: Nil   => List(NVP("", Some(urlDecoded(value))))
        case name :: "" :: Nil    => List(NVP(name, None))
        case "" :: Nil            => Empty
        case name :: Nil          => List(NVP(name, None))
        case name :: value :: Nil => List(NVP(name, Some(urlDecoded(value))))
        case _                    => Empty
      }
    val split = (s: String) => s.split("&").toList.flatMap(toNVP)

    Option(s).map(split).getOrElse(Empty)
  }

  def toNVP(params: Map[String, String]) =
    params.map { case (k, v) => NVP(k, Option(v)) }.toList

  def fromNVP(ns: List[NVP]) = {
    val urlEncode = (str: String) => URLEncoder.encode(str, UTF_8.toString)
    val show      = (n: NVP)      => s"${n.name}=${urlEncode(n.value.getOrElse(""))}"

    ns.map(show).mkString("&")
  }

  def orBadRow(str: String, error: Option[String]) =
    Option(str).toRight(unexpectedFormat("empty payload line", error))

  private[this] def unexpectedFormat(data: String, error: Option[String]) =
    UnexpectedFieldFormat(data, "querystring", "k1=v1&k2=v2".some, error)

}
