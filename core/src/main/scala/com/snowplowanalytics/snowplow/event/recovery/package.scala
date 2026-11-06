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

  /** Executes recovery config for given line.
    * @param cfg
    *   overall flow configuration
    * @param line
    *   a line to be recovered
    */
  def execute(cfg: Config)(line: String): Either[RecoveryError, Array[Byte]] =
    parseJson(line).leftMap(_ => InvalidJsonFormat(line).withRow(line)).flatMap(BadRowWithConfig.extract(cfg)).flatMap {
      v =>
        recover(v.steps, v.badRow).flatMap(coerce).flatMap(thrift.serializeNoB64).leftMap(_.withRow(line))
    }

  private[this] def recover(
    steps: List[StepConfig],
    b: BadRow
  )(implicit
    recoverable: Recoverable[BadRow, Payload]
  ): Recovering[Payload] = b.recover(steps)
}
