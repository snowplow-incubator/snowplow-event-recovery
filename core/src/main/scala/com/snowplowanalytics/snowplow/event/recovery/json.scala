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
package com.snowplowanalytics.snowplow
package event.recovery

import cats.syntax.functor._
import io.circe.{Decoder, Encoder, Printer}
import io.circe.shapes._, io.circe.syntax._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.generic.extras.semiauto.{deriveEnumerationDecoder, deriveEnumerationEncoder}
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.circe.instances._
import config._
import config.conditions._

object json {
  def path(str: String): Seq[String] = {
    val fieldName = "([a-zA-Z0-9]+\\.?)"
    val arrayId   = "(\\[([0-9]+)\\]\\.?)"
    val filter    = "(\\[\\?\\(@\\.[a-zA-Z0-9.-]+=~(.+)(?!\\b)\\]\\.?)"
    val extractor = s"$fieldName|$arrayId|$filter".r
    extractor.findAllIn(str).map(_.stripSuffix(".")).toSeq
  }

  val printer = Printer.noSpaces.copy(dropNullValues = true)

  implicit val flowConfigE: Encoder[FlowConfig] = deriveEncoder
  implicit val flowConfigD: Decoder[FlowConfig] = deriveDecoder

  implicit val replaceE: Encoder[Replace.type] =
    Encoder.encodeString.contramap[Replace.type](_.toString)
  implicit val replaceD: Decoder[Replace.type] = Decoder.decodeString.emap {
    case "Replace" => Right(Replace)
    case _         => Left("Replace")
  }
  implicit val removeE: Encoder[Remove.type] =
    Encoder.encodeString.contramap[Remove.type](_.toString)
  implicit val removeD: Decoder[Remove.type] = Decoder.decodeString.emap {
    case "Remove" => Right(Remove)
    case _        => Left("Remove")
  }
  implicit val castE: Encoder[Cast.type] =
    Encoder.encodeString.contramap[Cast.type](_.toString)
  implicit val castD: Decoder[Cast.type] = Decoder.decodeString.emap {
    case "Cast" => Right(Cast)
    case _      => Left("Cast")
  }
  implicit val testE: Encoder[Test.type] =
    Encoder.encodeString.contramap[Test.type](_.toString)
  implicit val testD: Decoder[Test.type] = Decoder.decodeString.emap {
    case "Test" => Right(Test)
    case _      => Left("Test")
  }

  implicit val regexE: Encoder[conditions.RegularExpression] = deriveEncoder
  implicit val regexD: Decoder[conditions.RegularExpression] = deriveDecoder
  implicit val compareE: Encoder[conditions.Compare]         = deriveEncoder
  implicit val compareD: Decoder[conditions.Compare]         = deriveDecoder

  implicit val eqE: Encoder[conditions.Size.Eq] = deriveEncoder
  implicit val eqD: Decoder[conditions.Size.Eq] = deriveDecoder
  implicit val gtE: Encoder[conditions.Size.Gt] = deriveEncoder
  implicit val gtD: Decoder[conditions.Size.Gt] = deriveDecoder
  implicit val ltE: Encoder[conditions.Size.Lt] = deriveEncoder
  implicit val ltD: Decoder[conditions.Size.Lt] = deriveDecoder

  implicit val sizesD: Decoder[conditions.Size.Matcher] =
    List[Decoder[conditions.Size.Matcher]](
      Decoder[conditions.Size.Eq].widen,
      Decoder[conditions.Size.Gt].widen,
      Decoder[conditions.Size.Lt].widen
    ).reduceLeft(_.or(_))

  implicit val sizesE: Encoder[conditions.Size.Matcher] = Encoder.instance {
    case r: conditions.Size.Eq => r.asJson
    case r: conditions.Size.Gt => r.asJson
    case r: conditions.Size.Lt => r.asJson
  }

  implicit val sizeE: Encoder[conditions.Size] = deriveEncoder
  implicit val sizeD: Decoder[conditions.Size] = deriveDecoder

  implicit val matcherE: Encoder[conditions.Matcher] = Encoder.instance {
    case r: conditions.RegularExpression => r.asJson
    case r: conditions.Compare           => r.asJson
    case r: conditions.Size              => r.asJson
  }

  implicit val matcherD: Decoder[conditions.Matcher] =
    List[Decoder[conditions.Matcher]](
      Decoder[conditions.RegularExpression].widen,
      Decoder[conditions.Compare].widen,
      Decoder[conditions.Size].widen
    ).reduceLeft(_.or(_))

  implicit val castTypeD: Decoder[CastType] = deriveEnumerationDecoder
  implicit val castTypeE: Encoder[CastType] = deriveEnumerationEncoder

  implicit val replacementE: Encoder[Replacement] = deriveEncoder
  implicit val replacementD: Decoder[Replacement] = deriveDecoder

  implicit val removalE: Encoder[Removal] = deriveEncoder
  implicit val removalD: Decoder[Removal] = deriveDecoder

  implicit val castingE: Encoder[Casting] = deriveEncoder
  implicit val castingD: Decoder[Casting] = deriveDecoder

  implicit val stepE: Encoder[StepConfig] = Encoder.instance {
    case r: Replacement => r.asJson
    case r: Removal     => r.asJson
    case r: Casting     => r.asJson
  }

  implicit val stepD: Decoder[StepConfig] = List[Decoder[StepConfig]](
    Decoder[Replacement].widen,
    Decoder[Removal].widen,
    Decoder[Casting].widen
  ).reduceLeft(_.or(_))

  implicit val confD: Decoder[Conf] = deriveDecoder
  implicit val confE: Encoder[Conf] = deriveEncoder

  implicit val conditionD: Decoder[Condition] = deriveDecoder
  implicit val conditionE: Encoder[Condition] = deriveEncoder

  implicit val selfDescribingBadRowD: Decoder[SelfDescribingBadRow] =
    deriveDecoder

  object untyped {
    val payload: BadRow => Option[Any]    = b => field[Payload]("payload")(b).orElse(field[BadRow]("payload")(b))
    val recoveries: BadRow => Option[Int] = field[Int]("recoveries")

    private[this] def field[A: Decoder](fieldName: String)(b: BadRow): Option[A] =
      b.selfDescribingData.data.hcursor.get[A](fieldName).toOption
  }
}
