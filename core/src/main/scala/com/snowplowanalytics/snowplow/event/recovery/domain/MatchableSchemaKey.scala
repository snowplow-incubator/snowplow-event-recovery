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
package com.snowplowanalytics.snowplow.event.recovery.domain

import scala.util.matching.Regex

object MatchableSchemaKey {
  private val schema: Regex = ("^iglu:" +
    "([a-zA-Z0-9-_.]+)\\/" +
    "([a-zA-Z0-9-_]+)\\/" +
    "([a-zA-Z0-9-_]+)\\/" +
    "([0-9]+|\\*)" +
    "\\-([0-9]+|\\*)" +
    "\\-([0-9]+|\\*)$").r

  def parse(uri: String): Option[MatchableSchemaKey] = uri match {
    case schema(vnd, n, f, m, r, a) =>
      Some(MatchableSchemaKey(vnd, n, f, m, r, a))
    case _ =>
      None
  }

  def matchSchema(s1: String, s2: String): Boolean =
    (for {
      k <- MatchableSchemaKey.parse(s1)
      d <- MatchableSchemaKey.parse(s2)
    } yield k == d).getOrElse(false)
}

/** A loose schema string definition that allows matching parts of schema key wit '*'
  *
  * example: `iglu:com.snowplowanalytics.snowplow.badrows/tracker_protocol_violation/jsonschema/1-*-*` will match
  * revision and addition version.
  */
case class MatchableSchemaKey(
  vendor: String,
  name: String,
  format: String,
  model: String,
  revision: String,
  addition: String
) {
  override def hashCode = (vendor, name, format).##
  override def equals(any: Any) = any match {
    case that: MatchableSchemaKey =>
      val any = "*"
      vendor == that.vendor &&
      name == that.name &&
      format == that.format &&
      (model == that.model       || model == any    || that.model == any) &&
      (revision == that.revision || revision == any || that.revision == any) &&
      (addition == that.addition || addition == any || that.addition == any)
    case _ =>
      false
  }
}
