/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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
import json.nvpsDecoder

/** A blueprint for transformation operations on JSON objects
  */
private[inspect] object transform {

  /** Runs transformation opreration
    *
    * @param transformFn
    *   a function for transforming a JSON structure(s)
    * @param error
    *   an error description message for failed application of [[transformFn]]
    * @param post
    *   Continuation-passing style(CPS) style function. Call on it with post(oldCursor)(newCursor) to record the
    *   transformation. Neutral element is post(cursor)(cursor) where cursor == cursor
    * @param path
    *   a list describing route to field being transformed
    * @param body
    *   JSON structure being transformed
    */
  def apply(
    transformFn: Json => Recovering[Json],
    post: ACursor => ACursor => Recovering[Json],
    error: String => RecoveryStatus,
    prevHistory: Seq[String] = Seq.empty[String]
  )(
    path: Seq[String]
  )(
    body: Json
  ): Recovering[Json] = {
    @tailrec
    def run(
      ap: Json => Recovering[Json],
      post: ACursor => ACursor => Recovering[Json]
    )(json: ACursor, path: Seq[String], prev: Seq[String]): Recovering[Json] = path match {
      // Base case
      case Seq() =>
        json.withFocusM(ap).flatMap(post(json))

      // Access field by filter
      case Seq(h, t @ _*) if isFilter(h) =>
        val Some((path, value)) = filter(h)

        def go(thisCur: ACursor, lastGoodCur: ACursor): Recovering[Json] =
          thisCur match {
            case _: HCursor =>
              // apply transformation to the matched element
              thisCur
                .withFocusM(apply(transformFn, post, error, prev :+ h)(t))
                // go to the next matching element.
                .flatMap { newJson =>
                  go(
                    newJson
                      .right
                      .success
                      .map(_.find(json => !findInArr(path, value)(json).isNull))
                      .getOrElse(newJson.right),
                    newJson
                  )
                }
                // record the transformation
                .flatMap(newJson => post(thisCur)(newJson.hcursor))

            // _ means that thisCur failed - no more matching elements.
            case _ =>
              if (thisCur == lastGoodCur)
                // first call to go, allow errored thisCur to propagate
                post(lastGoodCur)(thisCur)
              else
                // subsequent call to go. It will end up here every time when array doesn't have any more matching
                // elements. Record empty transformation.
                post(lastGoodCur)(lastGoodCur)
          }

        // Initialize by finding first matched element. If no element is found both will be Failed
        // cursors.
        go(
          json.downArray.success.map(_.find(!findInArr(path, value)(_).isNull)).getOrElse(json),
          json.downArray.success.map(_.find(!findInArr(path, value)(_).isNull)).getOrElse(json)
        )

      // Access array item by id
      case Seq(h, t @ _*) if isArrayItem(h) =>
        run(ap, post)(json.downN(arrayItem(h).get), t, prev :+ h)

      // Top-level Base64 encoded field
      case Seq(h, t @ _*) if isB64Encoded(h, prev) =>
        json.downField(h).withFocusM(b64Fn(apply(transformFn, post, error))(t)).flatMap(post(json))

      // An item in List[NVP] that is not Base64-encoded nor url-encoded
      case Seq(h, th, tt @ _*)
          if isNVPs(h, prev) && !isB64Encoded(th, prev) && !isUrlEncoded(th, prev) && indexF(th)(
            json.downField(h)
          ).isDefined =>
        run(ap, post)(
          json.downField(h).downN(indexF(th)(json.downField(h)).get).downField("value"),
          tt,
          prev :+ h :+ th
        )

      // An item in List[NVP] that is Base64-encoded
      case Seq(h, th, tt @ _*)
          if isNVPs(h, prev) && isB64Encoded(th, prev) && indexF(th)(json.downField(h)).isDefined =>
        json
          .downField(h)
          .downN(indexF(th)(json.downField(h)).get)
          .downField("value")
          .withFocusM(b64Fn(apply(transformFn, post, error))(tt))
          .flatMap(post(json))

      // An item in List[NVP] that is URL-encoded
      case Seq(h, th, tt @ _*)
          if isNVPs(h, prev) && isUrlEncoded(th, prev) && indexF(th)(json.downField(h)).isDefined =>
        json
          .downField(h)
          .downN(indexF(th)(json.downField(h)).get)
          .downField("value")
          .withFocusM(urlFn(apply(transformFn, post, error))(tt))
          .flatMap(post(json))

      // Falsey item in List[NVP]
      case Seq(h, _) if isNVPs(h, prev) =>
        json.focus match {
          case Some(j) => Left(InvalidDataFormat(j.some, s"Cannot access field $h"))
          case None    => Left(InvalidDataFormat(None, s"Cannot access field $h in empty arrNext."))
        }

      // URL-encoded field
      case Seq(h, t @ _*) if isUrlEncoded(h, prev) =>
        json.downField(h).withFocusM(urlFn(apply(transformFn, post, error))(t)).flatMap(post(json))

      // Recursive case
      case Seq(h, t @ _*) =>
        run(ap, post)(json.downField(h), t, prev :+ h)
    }

    run(transformFn, post)(body.hcursor, path, prevHistory)
  }

  private[inspect] def b64Fn(
    apply: Seq[String] => Json => Recovering[Json]
  )(path: Seq[String])(body: Json): Recovering[Json] = {
    def decode(str: String): Recovering[String] =
      util.base64.decode(str)

    def encode(json: Json): Recovering[Json] =
      util.base64.encode(json.noSpaces).map(_.asJson)

    encodedFn(decode, encode, apply)(path)(body)
  }

  private[inspect] def urlFn(
    apply: Seq[String] => Json => Recovering[Json]
  ): Seq[String] => Json => Recovering[Json] = {
    def decode(str: String) = str.asRight

    def encode(json: Json) = Json.fromString(json.noSpaces).asRight

    encodedFn(decode, encode, apply)
  }

  private[this] def encodedFn(
    decode: String => Recovering[String],
    encode: Json => Recovering[Json],
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
    (json: ACursor) => (Option(index(name)) <*> json.as[List[NVP]](nvpsDecoder).toOption).filter(_ >= 0)

  private[this] val index = (name: String) => (nvps: List[NVP]) => nvps.indexWhere(_.name == name)

  private[inspect] def top(err: String => RecoveryStatus)(previous: ACursor): ACursor => Recovering[Json] =
    _.top.toRight(err(previous.history.map(_.toString).mkString))

  def isArrayItem(str: String) = arrayItem(str).isDefined

  private[this] def arrayItem(str: String): Option[Int] = {
    val extractor = "^\\[([0-9]+)\\]".r
    str match {
      case extractor(id) => Either.catchNonFatal(id.toInt).toOption
      case _             => None
    }
  }

  @tailrec
  private[inspect] def findInArr(path: Seq[String], value: Regex)(json: Json): Json = path match {
    case Seq()          => value.findFirstIn(json.noSpaces).asJson
    case Seq(h, t @ _*) => findInArr(t, value)(json.hcursor.downField(h).focus.getOrElse(Json.Null))
  }

  @tailrec
  private[inspect] def findInArray(cursor: ACursor, path: Seq[String], value: Regex): Boolean = path match {
    case Seq()          => cursor.focus.flatMap(v => value.findFirstIn(v.noSpaces)).isDefined
    case Seq(h, t @ _*) => findInArray(cursor.downField(h), t, value)
  }

  private[inspect] def isFilter(str: String) = filter(str).isDefined

  def filter(str: String): Option[(Seq[String], Regex)] = {
    val extractor = ("^\\[\\?\\(@\\." + "([a-zA-Z0-9.]+)" + "=~" + "(.+)\\)\\]$").r
    str match {
      case extractor(path, value) => Some((path.split('.'), value.r))
      case _                      => None
    }
  }

  // TODO these are all pretty naive ways of figuring out the underlying format
  //      we should be able to better infer these types using ie. parsec
  def isNVPs(str: String, prev: Seq[String] = Seq.empty) =
    isSpecial(Seq("parameters", "querystring"))(str, prev)

  def isUrlEncoded(str: String, prev: Seq[String] = Seq.empty) =
    isSpecial(Seq("co", "ue_pr", "contexts", "derived_contexts"))(str, prev)

  def isB64Encoded(str: String, prev: Seq[String] = Seq.empty) =
    isSpecial(Seq("cx", "ue_px"))(str, prev)

  private[inspect] def isSpecial(specialFlags: Seq[String])(str: String, previousPath: Seq[String] = Seq.empty) = {
    val currentNodeIsSpecial   = specialFlags.contains(str.toLowerCase)
    val previousNodeWasSpecial = !specialFlags.intersect(previousPath.filterNot(isFilter)).isEmpty
    val previousFilterWasSpecial = specialFlags.exists(
      previousPath.filter(isFilter).flatMap(filter(_).toSeq).filter(_._1.contains("name")).map(_._2.regex).contains
    )
    currentNodeIsSpecial || previousNodeWasSpecial || previousFilterWasSpecial
  }

  private[inspect] def parse(data: String): Recovering[Json] =
    parseJson(data).leftMap(err => InvalidJsonFormat(err.message))

}
