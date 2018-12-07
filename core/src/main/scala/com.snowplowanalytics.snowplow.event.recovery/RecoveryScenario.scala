package com.snowplowanalytics.snowplow
package event.recovery

import java.util.Base64

import scala.util.Try

import io.lemonlabs.uri.QueryString

import CollectorPayload.thrift.model1.CollectorPayload
import model._

sealed trait RecoveryScenario {
  def error: String
  def filter(errors: List[Error]): Boolean =
    errors.map(_.message).exists(_.contains(error))
  def mutate(originalPayload: CollectorPayload): CollectorPayload

  protected def replaceAll(str: String, toReplace: String, replacement: String): Option[String] =
    Try(str.replaceAll(toReplace, replacement)).toOption
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
      decoded <- Try(new String(Base64.getDecoder.decode(b64Value))).toOption
      replaced <- replaceAll(decoded, toReplace, replacement)
      encoded <- Try(Base64.getEncoder.encodeToString(replaced.getBytes)).toOption
      newQs = qs.replaceAll(base64Field, encoded)
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
    def mutate(originalPayload: CollectorPayload): CollectorPayload = {
      if (originalPayload.body != null) {
        originalPayload.body = originalPayload.body.replaceAll(toReplace, replacement)
        originalPayload
      } else {
        originalPayload
      }
    }
  }
  final case class RemoveFromBody(error: String, toRemove: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = {
      if (originalPayload.body != null) {
        originalPayload.body = originalPayload.body.replaceAll(toRemove, "")
        originalPayload
      } else {
        originalPayload
      }
    }
  }

  // Other recovery scenarios

  final case class PassThrough(error: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = originalPayload
  }
}
