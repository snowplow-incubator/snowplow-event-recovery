/*
 * Copyright (c) 2018-2020 Snowplow Analytics Ltd. All rights reserved.
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
package inspect

import annotation.tailrec
import cats.implicits._
import io.circe._
import io.circe.syntax._
import io.circe.parser.{parse => parseJson}

import domain._
import com.snowplowanalytics.snowplow.badrows.NVP
import scala.util.matching.Regex

/**
  * A blueprint for transformation operations on JSON objects
  */
private[inspect] object transform {

  /**
    * Runs transformation opreration
    *
    * @param transformFn a function for transforming a JSON structure(s)
    * @param error an error description message for failed application of [[transformFn]]
    * @param path a list describing route to field being transformed
    * @param body JSON structure being transformed
    */
  def apply(
    transformFn: Json => Recovering[Json],
    post: ACursor     => ACursor => Recovering[Json],
    error: String     => RecoveryStatus
  )(
    path: Seq[String]
  )(
    body: Json
  ): Recovering[Json] = {
    @tailrec
    def run(
      ap: Json      => Recovering[Json],
      post: ACursor => ACursor => Recovering[Json]
    )(json: ACursor, path: Seq[String]): Recovering[Json] = path match {
      // Base case
      case Seq() =>
        json.withFocusM(ap).flatMap(post(json))

      // Access field by filter
      case Seq(h, t @ _*) if isFilter(h) =>
        val Some((path, value)) = filter(h)
        run(ap, post)(
          json.downArray.find(xs => findInArray(xs.hcursor, path, value)),
          t
        )

      // Access array item by id
      case Seq(h, t @ _*) if isArrayItem(h) =>
        run(ap, post)(json.downN(arrayItem(h).get), t)

      // Top-level Base64 encoded field
      case Seq(h, t @ _*) if isB64Encoded(h) =>
        json.downField(h).withFocusM(b64Fn(apply(transformFn, post, error))(t)).flatMap(top(error)(json))

      // An item in List[NVP] that is not Base64-encoded
      case Seq(h, th, tt @ _*) if isNVPs(h) && !isB64Encoded(th) && indexF(th)(json.downField(h)).isDefined =>
        run(ap, post)(json.downField(h).downN(indexF(th)(json.downField(h)).get).downField("value"), tt)

      // An item in List[NVP] that is Base64-encoded
      case Seq(h, th, tt @ _*) if isNVPs(h) && isB64Encoded(th) && indexF(th)(json.downField(h)).isDefined =>
        json
          .downField(h)
          .downN(indexF(th)(json.downField(h)).get)
          .downField("value")
          .withFocusM(b64Fn(apply(transformFn, post, error))(tt))
          .flatMap(top(error)(json))

      // Falsey item in List[NVP]
      case Seq(h, _) if isNVPs(h) =>
        json.focus match {
          case Some(j) => Left(InvalidDataFormat(j.some, s"Cannot access field $h"))
          case None    => Left(InvalidDataFormat(None, s"Cannot access field $h in empty cursor."))
        }

      // URL-encoded field
      case Seq(h, t @ _*) if isUrlEncoded(h) =>
        json.downField(h).withFocusM(urlFn(apply(transformFn, post, error))(t)).flatMap(top(error)(json))

      // Recursive case
      case Seq(h, t @ _*) =>
        run(ap, post)(json.downField(h), t)
    }

    run(transformFn, post)(body.hcursor, path)
  }

  private[this] def b64Fn(
    apply: Seq[String] => Json => Recovering[Json]
  )(path: Seq[String])(body: Json): Recovering[Json] = {
    def decode(str: String): Recovering[String] =
      util.base64.decode(str)
    def encode(json: Json): Recovering[Json] =
      util.base64.encode(json.noSpaces).map(_.asJson)

    encodedFn(decode, encode, apply)(path)(body)
  }

  private[this] def urlFn(
    apply: Seq[String] => Json => Recovering[Json]
  ): Seq[String] => Json => Recovering[Json] = {
    def decode(str: String) = str.asRight
    def encode(json: Json)  = Json.fromString(json.noSpaces).asRight

    encodedFn(decode, encode, apply)
  }

  private[this] def encodedFn(
    decode: String     => Recovering[String],
    encode: Json       => Recovering[Json],
    apply: Seq[String] => Json => Recovering[Json]
  )(path: Seq[String])(body: Json): Recovering[Json] =
    body
      .as[String]
      .leftMap(err => InvalidJsonFormat(err.message))
      .flatMap(decode)
      .flatMap(parse)
      .flatMap(apply(path))
      .flatMap(encode)

  private[this] val indexF = (name: String) =>
    (json: ACursor) => (Option(index(name)) <*> json.as[List[NVP]].toOption).filter(_ >= 0)

  private[this] val index = (name: String) => (nvps: List[NVP]) => nvps.indexWhere(_.name == name)

  private[inspect] def top(err: String => RecoveryStatus)(previous: ACursor): ACursor => Recovering[Json] =
    _.top.toRight(err(previous.history.map(_.toString).mkString))

  private[this] def isArrayItem(str: String) = arrayItem(str).isDefined
  private[this] def arrayItem(str: String): Option[Int] = {
    val extractor = ("^\\[([0-9]+)\\]").r
    str match {
      case extractor(id) => Either.catchNonFatal(id.toInt).toOption
      case _             => None
    }
  }

  @tailrec
  private[this] def findInArray(cursor: ACursor, path: Seq[String], value: Regex): Boolean = path match {
    case Seq()          => cursor.focus.flatMap(v => value.findFirstIn(v.noSpaces)).isDefined
    case Seq(h, t @ _*) => findInArray(cursor.downField(h), t, value)
  }

  private[this] def isFilter(str: String) = filter(str).isDefined
  private[inspect] def filter(str: String): Option[(Seq[String], Regex)] = {
    val extractor = ("^\\[\\?\\(@\\." + "([a-zA-Z0-9.]+)" + "=~" + "(.+)\\)\\]$").r
    str match {
      case extractor(path, value) => Some((path.split('.'), value.r))
      case _                      => None
    }
  }

  private[inspect] def isNVPs(str: String) =
    Seq("parameters", "querystring").contains(str.toLowerCase)

  private[inspect] def isUrlEncoded(str: String) =
    Seq("co", "ue_pr", "contexts", "derived_contexts").contains(str.toLowerCase)

  private[inspect] def isB64Encoded(str: String) =
    Seq("cx", "ue_px").contains(str.toLowerCase)

  private[inspect] def parse(data: String): Recovering[Json] =
    parseJson(data).leftMap(err => InvalidJsonFormat(err.message))

}
