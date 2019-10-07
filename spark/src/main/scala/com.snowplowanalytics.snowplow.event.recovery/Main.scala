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
package com.snowplowanalytics.snowplow.event.recovery

import scala.concurrent.duration.{TimeUnit, MILLISECONDS, NANOSECONDS}
import com.amazonaws.regions.Regions

import cats.Id
import cats.implicits._
import cats.effect._

import com.monovore.decline._
import com.monovore.decline.effect._

import util.paths._
import util.base64
import config._

/** Entry point for the Spark recovery job */
object Main
    extends CommandIOApp(
      name = "snowplow-event-recovery-job",
      header = "Snowplow event recovery job"
    ) {
  override def main: Opts[IO[ExitCode]] = {
    val input = Opts.option[String](
      "input",
      help = "Input S3 path"
    )
    val output = Opts.option[String](
      "output",
      help = "Output Kinesis topic"
    )
    val unrecoveredOutput = Opts
      .option[String](
        "unrecoveredOutput",
        help =
          "Unrecovered (bad row) output GCS path. Defaults to `inputDirectory`"
      )
      .orElse(input.map(unrecoveredPath))
    val unrecoverableOutput = Opts
      .option[String](
        "unrecoverableOutput",
        help =
          "Unrecovered (bad row) output GCS path. Defaults to `unrecoveredDirectory/unrecoverable` or `inputDirectory/unrecovered`"
      )
      .orElse(unrecoveredOutput.map(unrecoverablePath))
      .orElse(input.map(unrecoverablePath))
    val region = Opts
      .option[String](
        "region",
        help = "Kinesis region"
      )
      .map(Regions.fromName)
    val resolver = Opts
      .option[String](
        "resolver",
        help = "Iglu resolver configuration"
      )
      .mapValidated(base64.decode(_).toValidatedNel)
    val config = Opts
      .option[String](
        "config",
        help =
          "Base64 config with schema com.snowplowanalytics.snowplow/recovery_config/jsonschema/1-0-0"
      )
      .mapValidated(base64.decode(_).toValidatedNel)

    val validatedConfig = (resolver, config).mapN(
      (r, c) =>
      validateSchema[Id](c, r)
          .map(_ => c)
          .value
          .flatMap(load(_))
    )

    (
      input,
      output,
      unrecoveredOutput,
      unrecoverableOutput,
      region,
      validatedConfig
    ).mapN { (i, o, u, e, r, c) =>
      IO.fromEither(
        c.map(RecoveryJob.run(i, o, u, e, r, _))
          .map(_ => ExitCode.Success)
          .leftMap(new RuntimeException(_))
      )
    }
  }

  implicit val catsClockIdInstance: Clock[Id] = new Clock[Id] {
    override def realTime(unit: TimeUnit): Id[Long] =
      unit.convert(System.nanoTime(), NANOSECONDS)
    override def monotonic(unit: TimeUnit): Id[Long] =
      unit.convert(System.currentTimeMillis(), MILLISECONDS)
  }
}
