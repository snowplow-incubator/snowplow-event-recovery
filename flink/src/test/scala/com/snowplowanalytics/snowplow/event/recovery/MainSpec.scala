/*
 * Copyright (c) 2026 Snowplow Analytics Ltd. All rights reserved.
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

import com.monovore.decline.{Command, Opts}
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

class MainSpec extends AnyWordSpec with EitherValues {
  private def parse[A](opts: Opts[A], args: String*) = Command("test", "test")(opts).parse(args)

  "--failed" should {
    "be accepted under the Spark and Beam name --failedOutput" in {
      parse(Main.failedOutput, "--failed", "s3://bucket/failed").value shouldBe Some("s3://bucket/failed")
      parse(Main.failedOutput, "--failedOutput", "s3://bucket/failed").value shouldBe Some("s3://bucket/failed")
      parse(Main.failedOutput).value shouldBe None
    }
  }

  "--unrecoverable" should {
    "be accepted under the Spark and Beam name --unrecoverableOutput" in {
      parse(Main.unrecoverableOutput, "--unrecoverableOutput", "s3://bucket/u").value shouldBe Some("s3://bucket/u")
      parse(Main.unrecoverableOutput, "--unrecoverable", "s3://bucket/u").value shouldBe Some("s3://bucket/u")
    }
  }

  "--batch-size" should {
    "be accepted under the Spark name --batchSize" in {
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
    "default like the Spark and Beam jobs" in {
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
}
