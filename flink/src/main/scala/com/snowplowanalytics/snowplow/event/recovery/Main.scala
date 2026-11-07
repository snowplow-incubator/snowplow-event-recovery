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

import scala.concurrent.duration.DurationLong
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

  // The Beam job and the former Spark job name these --failedOutput, --unrecoverableOutput and --batchSize. Both
  // names are accepted so existing command lines keep working.
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

  /** Where recovered events go: a Kinesis stream, a directory of JSON lines, or both */
  private[recovery] val recoveredOutputs: Opts[(Option[String], Option[String])] =
    (
      Opts.option[String]("output", "Kinesis stream to write recovered events to").orNone,
      optionWithAlias[String](
        "directory-output",
        "directoryOutput",
        "Path to write recovered events to instead of, or as well as, Kinesis: one JSON object per line"
      ).orNone
    ).tupled.mapValidated { case outputs @ (stream, directory) =>
      Validated.condNel(
        stream.isDefined || directory.isDefined,
        outputs,
        "Give --output, --directory-output or both"
      )
    }

  /** Same defaults as the Beam job. They sit under `input`, so a rerun on the same input reads them too. */
  private[recovery] def failedPathOrDefault(input: String, failed: Option[String]): String =
    failed.getOrElse(paths.failedPath(input))

  private[recovery] def unrecoverablePathOrDefault(
    input: String,
    failed: Option[String],
    unrecoverable: Option[String]
  ): String =
    unrecoverable.orElse(failed.map(paths.unrecoverablePath)).getOrElse(paths.unrecoverablePath(input))

  /** An option that can also be given under an older name */
  private def optionWithAlias[A: Argument](name: String, alias: String, help: String): Opts[A] =
    Opts.option[A](name, help).orElse(Opts.option[A](alias, s"Same as --$name"))

  // Both or neither: with only one of them the run would silently go without checkpoints, and could not be resumed
  private[recovery] val checkpointing: Opts[Option[Checkpointing]] = (
    Opts
      .option[Long](
        "checkpoint-interval",
        help = "How often, in seconds, the job takes a checkpoint. Needs --checkpoint-dir"
      )
      .orNone,
    Opts.option[String]("checkpoint-dir", help = "Where to store the checkpoints. Needs --checkpoint-interval").orNone
  ).tupled.mapValidated {
    case (Some(seconds), Some(dir)) =>
      Validated.condNel(
        seconds >= 1,
        Some(Checkpointing(seconds.seconds, dir)),
        s"--checkpoint-interval is in seconds and must be at least 1, got $seconds"
      )
    case (None, None)    => Validated.validNel(None)
    case (Some(_), None) => Validated.invalidNel("--checkpoint-interval needs --checkpoint-dir")
    case (None, Some(_)) => Validated.invalidNel("--checkpoint-dir needs --checkpoint-interval")
  }

  override def main: Opts[IO[ExitCode]] = {
    val input = Opts.option[String](
      "input",
      help = "Input S3 path"
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

    val validatedConfig = (resolver, config).mapN((r, c) => validateSchema(c, r).flatMap(_ => load(c)))

    (
      input,
      recoveredOutputs,
      failedOutput,
      unrecoverableOutput,
      validatedConfig,
      region,
      batchSize,
      maxBuffered,
      checkpointing
    ).mapN { case (i, (o, d), f, u, c, r, bs, mb, ch) =>
      IO.fromEither(
        c.map(config =>
          RecoveryJob.run(
            StreamExecutionEnvironment.getExecutionEnvironment,
            i,
            o,
            failedPathOrDefault(i, f),
            unrecoverablePathOrDefault(i, f, u),
            config,
            KinesisConfig(region = r, batchSize = bs, maxBuffered = mb, checkpointing = ch),
            d
          )
        ).map(_ => ExitCode.Success)
          .leftMap(new RuntimeException(_))
      )
    }
  }
}
