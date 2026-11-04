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

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path => JPath}

import scala.collection.JavaConverters._

import io.circe.parser.parse
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

import org.apache.flink.api.scala._
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.test.util.MiniClusterWithClientResource

/** A dry run: recovered events written to files as JSON lines instead of to Kinesis */
class DirectoryOutputSpec extends AnyWordSpec with BeforeAndAfter {
  import Fixtures._

  val EventCount = 200

  val flinkCluster = new MiniClusterWithClientResource(
    new MiniClusterResourceConfiguration.Builder().setNumberSlotsPerTaskManager(1).setNumberTaskManagers(1).build
  )

  before(flinkCluster.before())
  after(flinkCluster.after())

  val pages: List[String] = (1 to EventCount).map(i => s"DemoPageTitle$i").toList

  /** The recoverable bad rows plus one that is not a bad row at all */
  object DryRunJob extends RecoveryJob {
    override def load(env: StreamExecutionEnvironment, input: String, exclude: List[String]): DataStream[String] =
      env.fromCollection(pages.map(badRowFor) :+ "{}")
  }

  "RecoveryJob" should {
    "write each recovered event as one JSON line when given only a directory" in {
      val workDir = Files.createTempDirectory("directory-output-spec")
      val output  = workDir.resolve("output")

      val summary = DryRunJob.run(
        StreamExecutionEnvironment.getExecutionEnvironment,
        "unused",
        None,
        workDir.resolve("failed").toUri.toString,
        workDir.resolve("unrecoverable").toUri.toString,
        cfg,
        KinesisConfig(region = "eu-central-1"),
        directoryOutput = Some(output.toUri.toString)
      )

      val files = finishedFiles(output)
      val lines = files.flatMap(Files.readAllLines(_, StandardCharsets.UTF_8).asScala)
      val json  = lines.map(line => parse(line).fold(err => fail(s"not JSON: $line ($err)"), identity))

      info(s"${files.size} file(s), ${lines.size} lines")
      all(files.map(_.getFileName.toString)) should endWith(".json")
      json.map(_.hcursor.get[String]("path").toOption) should contain only Some("/com.snowplowanalytics.snowplow/tp2")
      json.flatMap(_.hcursor.get[String]("querystring").toOption) should contain theSameElementsAs
        pages.map(page => s"e=pv&page=$page")

      summary shouldBe RecoverySummary(recovered = EventCount.toLong, failed = 1L, unrecoverable = 0L)
      finishedFiles(workDir.resolve("failed")) should not be empty
    }
  }

  "RecoveryJob reading its input" should {
    "skip a previous run's failed output kept under the input, so a rerun does not recover it again" in {
      val workDir = Files.createTempDirectory("directory-output-spec")
      val input   = Files.createDirectories(workDir.resolve("input"))
      Files.write(input.resolve("bad-rows.txt"), pages.take(5).map(badRowFor).asJava, StandardCharsets.UTF_8)
      // Where a run without --failed writes failed rows: under the input
      val previous = Files.createDirectories(input.resolve("unrecovered").resolve("2026-01-01--00"))
      Files.write(previous.resolve("part-0"), pages.drop(5).take(3).map(badRowFor).asJava, StandardCharsets.UTF_8)

      val summary = RecoveryJob.run(
        StreamExecutionEnvironment.getExecutionEnvironment,
        input.toUri.toString,
        None,
        Main.failedPathOrDefault(input.toUri.toString, None),
        Main.unrecoverablePathOrDefault(input.toUri.toString, None, None),
        cfg,
        KinesisConfig(region = "eu-central-1"),
        directoryOutput = Some(workDir.resolve("output").toUri.toString)
      )

      summary shouldBe RecoverySummary(recovered = 5L, failed = 0L, unrecoverable = 0L)
    }
  }

  /** Files the sink finished. In-progress and pending files are hidden: their names start with a dot. */
  private def finishedFiles(dir: JPath): List[JPath] =
    if (!Files.exists(dir)) Nil
    else {
      val stream = Files.walk(dir)
      try stream.iterator.asScala.filter(p => Files.isRegularFile(p) && !p.getFileName.toString.startsWith(".")).toList
      finally stream.close()
    }
}
