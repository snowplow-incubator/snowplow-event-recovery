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
package com.snowplowanalytics.snowplow.event

import cats.syntax.either._
import io.circe.parser.{parse => parseJson}
import com.snowplowanalytics.snowplow.badrows.{BadRow, Payload}
import recovery.config._
import recovery.recoverable._
import recovery.recoverable.Recoverable.ops._
import recovery.domain._
import recovery.config._
import recovery.util.payload._
import recovery.util.thrift

package object recovery {

  /**
    * Executes recovery config for given line.
    * @param cfg overall flow configuration
    * @param line a line to be recovered
    */
  def execute(cfg: Config)(line: String): Either[RecoveryError, String] =
    parseJson(line).leftMap(_ => InvalidJsonFormat(line).withRow(line)).flatMap(BadRowWithConfig.extract(cfg)).flatMap {
      v =>
        recover(v.steps, v.badRow).flatMap(coerce).flatMap(thrift.serialize).leftMap(_.withRow(line))
    }

  private[this] def recover(
    steps: List[StepConfig],
    b: BadRow
  )(
    implicit recoverable: Recoverable[BadRow, Payload]
  ): Recovering[Payload] = b.recover(steps)
}
