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
