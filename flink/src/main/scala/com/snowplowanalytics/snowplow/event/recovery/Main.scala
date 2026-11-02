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
package com.snowplowanalytics.snowplow.event.recovery

import scala.concurrent.duration.{DurationLong, MILLISECONDS, NANOSECONDS, TimeUnit}
import cats.Id
import cats.data.Validated
import cats.effect._
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import util.{base64, paths}
import config._
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment

object Main
    extends CommandIOApp(
      name = "snowplow-event-recovery-job",
      header = "Snowplow event recovery job"
    ) {

  /** Kinesis accepts at most this many records in one PutRecords call */
  val MaxKinesisBatchSize = 500

  // The Spark and Beam jobs name these --failedOutput, --unrecoverableOutput and --batchSize. Both names are accepted
  // so the same arguments work with every runner.
  private[recovery] val failedOutput: Opts[Option[String]] = optionWithAlias[String](
    "failed",
    "failedOutput",
    "Output path for bad rows that could not be recovered. Defaults to `input`/unrecovered"
  ).orNone

  private[recovery] val unrecoverableOutput: Opts[Option[String]] = optionWithAlias[String](
    "unrecoverable",
    "unrecoverableOutput",
    "Output path for bad rows that can never be recovered. Defaults to `failed`/unrecoverable, or `input`/unrecoverable"
  ).orNone

  private[recovery] val batchSize: Opts[Option[Int]] = optionWithAlias[Int](
    "batch-size",
    "batchSize",
    s"Records per Kinesis PutRecords call, at most $MaxKinesisBatchSize"
  ).mapValidated { size =>
    Validated.condNel(
      size >= 1 && size <= MaxKinesisBatchSize,
      size,
      s"--batch-size must be between 1 and $MaxKinesisBatchSize (the Kinesis PutRecords limit), got $size"
    )
  }.orNone

  /** Same defaults as the Spark and Beam jobs. They sit under `input`, so a rerun on the same input reads them too. */
  private[recovery] def failedPathOrDefault(input: String, failed: Option[String]): String =
    failed.getOrElse(paths.failedPath(input))

  private[recovery] def unrecoverablePathOrDefault(
    input: String,
    failed: Option[String],
    unrecoverable: Option[String]
  ): String =
    unrecoverable.orElse(failed.map(paths.unrecoverablePath)).getOrElse(paths.unrecoverablePath(input))

  /** An option that can also be given under the name the Spark and Beam jobs use */
  private def optionWithAlias[A: Argument](name: String, alias: String, help: String): Opts[A] =
    Opts.option[A](name, help).orElse(Opts.option[A](alias, s"Same as --$name"))

  override def main: Opts[IO[ExitCode]] = {
    val input = Opts.option[String](
      "input",
      help = "Input S3 path"
    )
    val output = Opts.option[String](
      "output",
      help = "Output Kinesis topic"
    )
    val resolver = Opts
      .option[String](
        "resolver",
        help = "Iglu resolver configuration"
      )
      .mapValidated(base64.decode(_).leftMap(_.message).toValidatedNel)
    val config = Opts
      .option[String](
        "config",
        help = "Base64 config with schema com.snowplowanalytics.snowplow/recovery_config/jsonschema/1-0-0"
      )
      .mapValidated(base64.decode(_).leftMap(_.message).toValidatedNel)

    val region = Opts.option[String]("region", help = "Kinesis region")

    val maxBuffered = Opts
      .option[Int](
        "max-buffered",
        help = "Backpressure. Raise if Kinesis cannot keep up. Dafault: 10000"
      )
      .orNone

    val checkpointInterval = Opts
      .option[Long](
        "checkpoint-interval",
        help = "How often, in seconds, the job takes a checkpoint. Needs --checkpoint-dir"
      )
      .orNone
    val checkpointDir = Opts.option[String]("checkpoint-dir", help = "Where to store the checkpoints").orNone

    val validatedConfig = (resolver, config).mapN((r, c) => validateSchema[Id](c, r).map(_ => c).value.flatMap(load(_)))
    val checkpointing =
      (checkpointInterval, checkpointDir).mapN((_, _).mapN((seconds, dir) => Checkpointing(seconds.seconds, dir)))

    (input, output, failedOutput, unrecoverableOutput, validatedConfig, region, batchSize, maxBuffered, checkpointing)
      .mapN { (i, o, f, u, c, r, bs, mb, ch) =>
        IO.fromEither(
          c.map(config =>
            RecoveryJob.run(
              StreamExecutionEnvironment.getExecutionEnvironment,
              i,
              o,
              failedPathOrDefault(i, f),
              unrecoverablePathOrDefault(i, f, u),
              config,
              KinesisConfig(region = r, batchSize = bs, maxBuffered = mb, checkpointing = ch)
            )
          ).map(_ => ExitCode.Success)
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
