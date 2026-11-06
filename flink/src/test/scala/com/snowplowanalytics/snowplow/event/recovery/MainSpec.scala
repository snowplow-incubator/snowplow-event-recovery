/*
 * Copyright (c) 2026-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.event.recovery

import com.monovore.decline.{Command, Opts}
import scala.concurrent.duration._

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

class MainSpec extends AnyWordSpec with EitherValues {
  private def parse[A](opts: Opts[A], args: String*) = Command("test", "test")(opts).parse(args)

  "--failed" should {
    "be accepted under the older name --failedOutput" in {
      parse(Main.failedOutput, "--failed", "s3://bucket/failed").value shouldBe Some("s3://bucket/failed")
      parse(Main.failedOutput, "--failedOutput", "s3://bucket/failed").value shouldBe Some("s3://bucket/failed")
      parse(Main.failedOutput).value shouldBe None
    }
  }

  "--unrecoverable" should {
    "be accepted under the older name --unrecoverableOutput" in {
      parse(Main.unrecoverableOutput, "--unrecoverableOutput", "s3://bucket/u").value shouldBe Some("s3://bucket/u")
      parse(Main.unrecoverableOutput, "--unrecoverable", "s3://bucket/u").value shouldBe Some("s3://bucket/u")
    }
  }

  "--batch-size" should {
    "be accepted under the older name --batchSize" in {
      parse(Main.batchSize, "--batchSize", "150").value shouldBe Some(150)
      parse(Main.batchSize, "--batch-size", "500").value shouldBe Some(500)
    }
    "reject sizes Kinesis PutRecords does not accept" in {
      parse(Main.batchSize, "--batchSize", "501").left.value.errors.mkString should include("between 1 and 500")
      parse(Main.batchSize, "--batch-size", "0").left.value.errors.mkString should include("between 1 and 500")
    }
  }

  "Recovered event outputs" should {
    "accept a Kinesis stream, a directory or both" in {
      parse(Main.recoveredOutputs, "--output", "raw").value shouldBe ((Some("raw"), None))
      parse(Main.recoveredOutputs, "--directoryOutput", "s3://bucket/out").value shouldBe (
        (
          None,
          Some("s3://bucket/out")
        )
      )
      parse(Main.recoveredOutputs, "--directory-output", "s3://bucket/out", "--output", "raw").value shouldBe
        ((Some("raw"), Some("s3://bucket/out")))
    }
    "require at least one" in {
      parse(Main.recoveredOutputs).left.value.errors.mkString should include(
        "Give --output, --directory-output or both"
      )
    }
  }

  "Output paths" should {
    "default like the Beam job" in {
      Main.failedPathOrDefault("s3://bucket/in/", None) shouldBe "s3://bucket/in/unrecovered"
      Main.unrecoverablePathOrDefault("s3://bucket/in/", None, None) shouldBe "s3://bucket/in/unrecoverable"
      Main.unrecoverablePathOrDefault(
        "s3://bucket/in",
        Some("s3://bucket/f"),
        None
      ) shouldBe "s3://bucket/f/unrecoverable"
    }
    "use the given paths" in {
      Main.failedPathOrDefault("s3://bucket/in", Some("s3://bucket/f")) shouldBe "s3://bucket/f"
      Main.unrecoverablePathOrDefault("s3://bucket/in", Some("s3://bucket/f"), Some("s3://bucket/u")) shouldBe
        "s3://bucket/u"
    }
  }

  "Checkpointing" should {
    "be off without either flag and on with both, the interval in seconds" in {
      parse(Main.checkpointing).value shouldBe None
      parse(Main.checkpointing, "--checkpoint-interval", "60", "--checkpoint-dir", "s3://bucket/chk").value shouldBe
        Some(Checkpointing(60.seconds, "s3://bucket/chk"))
    }
    "reject one flag without the other" in {
      parse(Main.checkpointing, "--checkpoint-interval", "60").left.value.errors.mkString should include(
        "--checkpoint-interval needs --checkpoint-dir"
      )
      parse(Main.checkpointing, "--checkpoint-dir", "s3://bucket/chk").left.value.errors.mkString should include(
        "--checkpoint-dir needs --checkpoint-interval"
      )
    }
    "reject an interval under a second" in {
      parse(Main.checkpointing, "--checkpoint-interval", "0", "--checkpoint-dir", "s3://b/c")
        .left
        .value
        .errors
        .mkString should include("at least 1")
    }
  }
}
