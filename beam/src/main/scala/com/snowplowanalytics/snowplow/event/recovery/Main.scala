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

import scala.concurrent.duration.{MILLISECONDS, NANOSECONDS, TimeUnit}
import cats.Id
import cats.data.Validated.{Invalid, Valid}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.option._
import cats.effect.Clock
import com.spotify.scio.ContextAndArgs
import com.spotify.scio.coders.Coder
import com.snowplowanalytics.snowplow.badrows.BadRow
import util.paths._
import util.base64
import config._

object Main {
  implicit val badRowScioCodec: Coder[BadRow] = Coder.kryo[BadRow]

  /** Entry point for the Beam recovery job */
  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, args) = ContextAndArgs(cmdlineArgs)
    val input =
      args.optional("inputDirectory").toValidNel("Input GCS path is mandatory")
    val output = args.optional("outputTopic").toValidNel("Output PubSub topic is mandatory")
    val failedOutput =
      args
        .optional("failedOutput")
        .toValidNel(
          "Failed (bad row) output GCS path. Defaults to `inputDirectory/unrecovered`"
        )
        .orElse(input.map(failedPath))
    val unrecoverableOutput =
      args
        .optional("unrecoverableOutput")
        .toValidNel(
          "Unrecoverable (bad row) output GCS path. Defaults to `unrecoveredDirectory/unrecoverable` or `inputDirectory/unrecovered`"
        )
        .orElse(failedOutput.map(unrecoverablePath))
        .orElse(input.map(unrecoverablePath))
    val resolverConfig = for {
      config  <- args.optional("resolver").toRight("Iglu resolver configuration")
      decoded <- base64.decode(config).leftMap(_.message)
    } yield decoded
    val config = (for {
      config <- args
        .optional("config")
        .toRight(
          "Base64-encoded configuration with schema " +
            "com.snowplowanalytics.snowplow/recovery_config/jsonschema/1-0-0 is mandatory"
        )
      decoded  <- base64.decode(config).leftMap(_.message)
      resolver <- resolverConfig
      _        <- validateSchema(decoded, resolver).value
      cfg      <- load(decoded)
    } yield cfg).toValidatedNel
    (input, output, failedOutput, unrecoverableOutput, config).tupled match {
      case Valid((i, o, f, u, cfg)) =>
        RecoveryJob.run(sc, i, o, f, u, cfg)
        val _ = sc.run()
        ()
      case Invalid(l) =>
        System.err.println(l.toList.mkString("\n"))
        System.exit(1)
    }
  }

  implicit private[this] val catsClockIdInstance: Clock[Id] = new Clock[Id] {
    override def realTime(unit: TimeUnit): Id[Long] =
      unit.convert(System.nanoTime(), NANOSECONDS)
    override def monotonic(unit: TimeUnit): Id[Long] =
      unit.convert(System.currentTimeMillis(), MILLISECONDS)
  }
}
