package com.snowplowanalytics.snowplow
package event.recovery

import java.util.Base64

import scala.util.Try

import cats.syntax.either._
import io.circe._
import io.circe.optics.JsonPath._
import io.circe.parser._
import io.lemonlabs.uri.QueryString

import CollectorPayload.thrift.model1.CollectorPayload
import model._

sealed trait RecoveryScenario {
  def error: String
  def filter(errors: List[model.Error]): Boolean =
    errors.map(_.message).exists(_.contains(error))
  def mutate(originalPayload: CollectorPayload): CollectorPayload
}

object RecoveryScenario {

  // Querystring recovery scenarios

  final case class ReplaceInQueryString(
    error: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      qs <- Option(originalPayload.querystring)
      replaced <- replaceAll(qs, toReplace, replacement)
    } yield {
      originalPayload.querystring = replaced
      originalPayload
    }).getOrElse(originalPayload)
  }

  final case class ReplaceInBase64FieldInQueryString(
    error: String,
    base64Field: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      rawQs <- Option(originalPayload.querystring)
      qs <- QueryString.parseOption(rawQs)
      b64Values <- qs.paramMap.get(base64Field)
      b64Value <- b64Values.headOption
      replaced <- replaceInB64(b64Value, toReplace, replacement)
      newQs = qs.replaceAll(base64Field, replaced)
    } yield {
      originalPayload.querystring = newQs.toString
      originalPayload
    }).getOrElse(originalPayload)
  }

  final case class RemoveFromQueryString(error: String, toRemove: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      qs <- Option(originalPayload.querystring)
      removed <- replaceAll(qs, toRemove, "")
    } yield {
      originalPayload.querystring = removed
      originalPayload
    }).getOrElse(originalPayload)
  }

  // Body recovery scenarios

  final case class ReplaceInBody(
    error: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      body <- Option(originalPayload.body)
      replaced <- replaceAll(body, toReplace, replacement)
    } yield {
      originalPayload.body = replaced
      originalPayload
    }).getOrElse(originalPayload)
  }

  private val modifyUePx: (String => String) => (Json => Json) = (f: (String => String)) =>
    root.data.each.ue_px.string.modify(f)
  private val modifyCx: (String => String) => (Json => Json) = (f: (String => String)) =>
    root.data.each.cx.string.modify(f)
  final case class ReplaceInBase64FieldInBody(
    error: String,
    base64Field: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      rawBody <- Option(originalPayload.body)
      body <- parse(rawBody).toOption
      f = (b64: String) => replaceInB64(b64, toReplace, replacement).getOrElse(b64)
      newBody = base64Field match {
        case "ue_px" => modifyUePx(f)(body)
        case "cx" => modifyCx(f)(body)
        case _ => body
      }
    } yield {
      originalPayload.body = newBody.noSpaces
      originalPayload
    }).getOrElse(originalPayload)
  }

  final case class RemoveFromBody(error: String, toRemove: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      body <- Option(originalPayload.body)
      removed <- replaceAll(body, toRemove, "")
    } yield {
      originalPayload.body = removed
      originalPayload
    }).getOrElse(originalPayload)
  }

  // Other recovery scenarios

  final case class PassThrough(error: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = originalPayload
  }

  // Helpers

  private val replaceInB64: (String, String, String) => Option[String] =
    (b64: String, toReplace: String, replacement: String) => for {
      decoded <- Try(new String(Base64.getDecoder.decode(b64))).toOption
      replaced <- replaceAll(decoded, toReplace, replacement)
      encoded <- Try(Base64.getEncoder.encodeToString(replaced.getBytes)).toOption
    } yield encoded

  private def replaceAll(str: String, toReplace: String, replacement: String): Option[String] =
    Try(str.replaceAll(toReplace, replacement)).toOption
}
