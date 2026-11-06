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
package com.snowplowanalytics.snowplow.event.recovery

import cats.data.Validated.{Invalid, Valid}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.option._
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
      _        <- validateSchema(decoded, resolver)
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
}
