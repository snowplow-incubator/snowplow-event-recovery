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

import java.nio.file.Files

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

class LastCheckpointSpec extends AnyWordSpec {
  "lastCheckpoint" should {
    "pick the highest chk-<n> of the job that has _metadata" in {
      val dir = Files.createTempDirectory("last-checkpoint-spec")
      val job = dir.resolve("job-a")
      List("chk-2", "chk-10", "chk-11", "shared", "taskowned").foreach(d => Files.createDirectories(job.resolve(d)))
      List("chk-2", "chk-10").foreach(d => Files.createFile(job.resolve(d).resolve("_metadata")))
      // Another job's newer checkpoint in the same directory is not this job's
      Files.createDirectories(dir.resolve("job-b").resolve("chk-99"))
      Files.createFile(dir.resolve("job-b").resolve("chk-99").resolve("_metadata"))

      val found = RecoveryJob.lastCheckpoint(dir.toUri.toString, "job-a")

      found.map(_.stripSuffix("/")).getOrElse("") should endWith(s"${dir.getFileName}/job-a/chk-10")
    }
    "be empty when no checkpoint completed or the directory is missing" in {
      val dir = Files.createTempDirectory("last-checkpoint-spec")
      Files.createDirectories(dir.resolve("job-a").resolve("chk-1"))

      RecoveryJob.lastCheckpoint(dir.toUri.toString, "job-a") shouldBe None
      RecoveryJob.lastCheckpoint(dir.toUri.toString, "job-missing") shouldBe None
    }
  }
}
