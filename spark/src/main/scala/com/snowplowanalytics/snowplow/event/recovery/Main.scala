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

import java.util.concurrent.ScheduledThreadPoolExecutor
import org.slf4j.LoggerFactory

import cats.implicits._
import cats.effect._

import com.monovore.decline._
import com.monovore.decline.effect._

import util.paths._
import util.base64
import config._
import cats.data.EitherT
import cats.data.Validated

/** Entry point for the Spark recovery job */
object Main
    extends CommandIOApp(
      name = "snowplow-event-recovery-job",
      header = "Snowplow event recovery job"
    ) {
  lazy val log = LoggerFactory.getLogger(getClass)

  override def main: Opts[IO[ExitCode]] = {
    val input = Opts.option[String](
      "input",
      help = "Input S3 path"
    )
    val output = Opts
      .option[String](
        "output",
        help = "Output Kinesis topic"
      )
      .orNone
    val failedOutput = Opts
      .option[String](
        "failedOutput",
        help = "Unrecovered (bad row) output S3 path. Defaults to `input`"
      )
      .orNone
    val unrecoverableOutput = Opts
      .option[String](
        "unrecoverableOutput",
        help = "Unrecoverable (bad row) output S3 path. Defaults failedOutput/unrecoverable` or `input/unrecoverable`"
      )
      .orNone
    val directoryOutput = Opts
      .option[String](
        "directoryOutput",
        help = "Directory output for pushing recovered `ControllerPayload` to S3 path."
      )
      .orNone
    val region = Opts.option[String](
      "region",
      help = "Kinesis region"
    )
    val batchSize = Opts
    val kinesisThreadpool = Opts
      .option[Int](
        "kinesis-threadpool",
        help = "Kinesis client threadpool size. Default: `8`"
      )
      .orNone
    val resolver = Opts
      .option[String](
        "resolver",
        help = "Iglu resolver configuration"
      )
      .mapValidated(base64.decode(_).leftMap(_.message).toValidatedNel)
    val cloudwatchNamespace = Opts
      .option[String](
        "cloudwatch-namespace",
        help = "Namespace name for CloudWatch"
      )
      .orNone
    val cloudwatchDimensions = Opts
      .option[String](
        "cloudwatch-dimensions",
        help = "Cloudwatch dimensions"
      )
      .mapValidated { input =>
        input
          .split(";")
          .toList
          .traverse(str =>
            str.split(":", 2) match {
              case Array(key, value) => Validated.valid(Map[String, String](key -> value))
              case _                 => Validated.invalid(s"Invalid key:value pair $str")
            }
          )
          .map(_.foldLeft(Map.empty[String, String])((acc, next) => acc |+| next))
          .toValidatedNel
      }
      .orNone
    val config = Opts
      .option[String](
        "config",
        help = "Base64 config with schema com.snowplowanalytics.snowplow/recovery_config/jsonschema/1-0-0"
      )
      .mapValidated(base64.decode(_).leftMap(_.message).toValidatedNel)

    val validatedConfig = (resolver, config).mapN((r, c) =>
      validateSchema[IO](c, r).map(_ => c).flatMap(v => EitherT.fromEither(load(v))).value
    )

    val cloudwatchConfig = (cloudwatchNamespace, cloudwatchDimensions).mapN((_, _).mapN(Cloudwatch.Config))
    val kinesisConfig    = (region, kinesisThreadpool).mapN((r, kt) => KinesisSink.Config(region = r, threadpool = kt))

    (
      input,
      output,
      failedOutput,
      unrecoverableOutput,
      directoryOutput,
      kinesisConfig,
      validatedConfig,
      cloudwatchConfig
    ).mapN { (i, o, f, u, d, k, c, cw) =>
      c.map(
        _.bimap(
          err => new RuntimeException(err),
          cfg =>
            RecoveryJob.run(
              i,
              o,
              f.getOrElse(failedPath(i)),
              u.orElse(f.map(unrecoverablePath)).getOrElse(unrecoverablePath(i)),
              d,
              Some(k),
              cfg,
              Cloudwatch.init[SyncIO](cw)
            )
        )
      ).map(_ => ExitCode.Success)

    }
  }

  def buildExecutorService(threadPoolSize: Int = 16): ScheduledThreadPoolExecutor = {
    log.info("Creating thread pool of size " + threadPoolSize)
    new ScheduledThreadPoolExecutor(threadPoolSize)
  }
}
