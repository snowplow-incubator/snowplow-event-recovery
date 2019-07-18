/*
 * Copyright (c) 2018-2018 Snowplow Analytics Ltd. All rights reserved.
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

import java.util.Base64

import scala.util.Try

import io.circe._
import io.circe.optics.JsonPath._
import io.circe.parser._
import io.lemonlabs.uri.QueryString

import CollectorPayload.thrift.model1.CollectorPayload

/**
 * Trait common to all recovery scenarios which, in essence, contains two things:
 * - a way to find out if a recovery scenario should be applied to a bad row, the error filter
 * - a way to fix the collector payload contained in the bad row, the mutation function
 */
sealed trait RecoveryScenario {
  /** Error discriminant used to check if a recovery scenario should be applied to a bad row. */
  def error: String

  /**
   * Function used to check if a recovery scenario should be applied to a bad row given its errors.
   * @param errors list of errors contained in a BadRow
   * @return true if there exists at least one error in the provided bad row errors which contains
   * this [[RecoveryScenario]]'s error
   */
  def filter(errors: List[model.Error]): Boolean =
    errors.map(_.message).exists(_.contains(error))

  /**
   * Function mutating a CollectorPayload.
   * @param originalPayload CollectorPayload before mutation
   * @return a fixed CollectorPayload
   */
  def mutate(originalPayload: CollectorPayload): CollectorPayload
}

object RecoveryScenario {

  // Query string recovery scenarios

  /**
   * Recovery scenario replacing part of a collector payload's query string.
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   * @param toReplace part of the query string that needs replacing
   * @param replacement for the part of the query string that needs to be replaced
   */
  final case class ReplaceInQueryString(
    error: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    /**
     * Mutate a collector payload by replacing part of its query string.
     * @param originalPayload the payload before applying the recovery scenario
     * @return a collector payload with part of its query string replaced, the payload remains
     * unchanged if the it doesn't have a query string
     */
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      qs <- Option(originalPayload.querystring)
      replaced <- replaceAll(qs, toReplace, replacement)
    } yield {
      originalPayload.querystring = replaced
      originalPayload
    }).getOrElse(originalPayload)
  }

  /**
   * Recovery scenario replacing part of a collector payload's base64-encoded field in its query
   * string.
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   * @param base64Field base64-encoded field in the query string, ue_px or cx
   * @param toReplace part of the base64-encoded field in the query string that needs replacing
   * @param replacement for the part of the base64-encoded field in the query string that needs
   * to be replaced
   */
  final case class ReplaceInBase64FieldInQueryString(
    error: String,
    base64Field: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    /**
     * Mutate a collector payload by replacing part of a base64-encoded field in its query string.
     * @param originalPayload the payload before applying the recovery scenario
     * @return a collector payload with part of a base64-encoded field in its query string replaced,
     * the payload remains unchanged if the it doesn't have a query string
     */
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

  /**
   * Recovery scenario removing part of a collector payload's query string.
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   * @param toRemove part of the query string that needs removing
   */
  final case class RemoveFromQueryString(error: String, toRemove: String) extends RecoveryScenario {
    /**
     * Mutate a collector payload by removing part of its query string.
     * @param originalPayload the payload before applying the recovery scenario
     * @return a collector payload with part of its query string removed, the payload remains
     * unchanged if it doesn't have a query string
     */
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      qs <- Option(originalPayload.querystring)
      removed <- replaceAll(qs, toRemove, "")
    } yield {
      originalPayload.querystring = removed
      originalPayload
    }).getOrElse(originalPayload)
  }

  // Body recovery scenarios

  /**
   * Recovery scenario replacing part of a collector payload's body.
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   * @param toReplace part of the body that needs replacing
   * @param replacement for the part of the body that needs to be replaced
   */
  final case class ReplaceInBody(
    error: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    /**
     * Mutate a collector payload by replacing part of its body.
     * @param originalPayload the payload before applying the recovery scenario
     * @return a collector payload with part of its body replaced, the payload remains
     * unchanged if the it doesn't have a body
     */
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
  /**
   * Recovery scenario replacing part of a collector payload's base64-encoded field in its body.
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   * @param base64Field base64-encoded field in the body, ue_px or cx
   * @param toReplace part of the base64-encoded field in the body that needs replacing
   * @param replacement for the part of the base64-encoded field in the body that needs to be
   * replaced
   */
  final case class ReplaceInBase64FieldInBody(
    error: String,
    base64Field: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    /**
     * Mutate a collector payload by replacing part of a base64-encoded field in its body.
     * @param originalPayload the payload before applying the recovery scenario
     * @return a collector payload with part of a base64-encoded field in its body replaced, the
     * payload remains unchanged if the it doesn't have a query string
     */
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

  /**
   * Recovery scenario removing part of a collector payload's body.
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   * @param toRemove part of the body that needs removing
   */
  final case class RemoveFromBody(error: String, toRemove: String) extends RecoveryScenario {
    /**
     * Mutate a collector payload by removing part of its body.
     * @param originalPayload the payload before applying the recovery scenario
     * @return a collector payload with part of its body removed, the payload remains unchanged if
     * it doesn't have a body
     */
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      body <- Option(originalPayload.body)
      removed <- replaceAll(body, toRemove, "")
    } yield {
      originalPayload.body = removed
      originalPayload
    }).getOrElse(originalPayload)
  }

  // Other recovery scenarios

  /**
   * Recovery scenario leaving the collector payload unchanged.
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   */
  final case class PassThrough(error: String) extends RecoveryScenario {
    /**
     * Does not mutate collector payloads.
     * @param originalPayload the payload before applying the recovery scenario
     * @return the original payload unchanged
     */
    def mutate(originalPayload: CollectorPayload): CollectorPayload = originalPayload
  }

  /**
   * Recovery scenario modifying the collector payload's path
   * @param error discriminant used to check if a recovery scenario should be applied to a bad row
   * @param toReplace part of the path that needs replacing
   * @param replacement for the part of the path that needs to be replaced
   */
  final case class ReplaceInPath(
    error: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = (for {
      path <- Option(originalPayload.path)
      replaced <- replaceAll(path, toReplace, replacement)
    } yield {
      originalPayload.path = replaced
      originalPayload
    }).getOrElse(originalPayload)
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
