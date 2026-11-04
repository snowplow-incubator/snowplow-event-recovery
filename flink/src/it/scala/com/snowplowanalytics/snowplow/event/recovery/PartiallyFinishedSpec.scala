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
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.api.scala._
import org.apache.flink.configuration.{Configuration, StateRecoveryOptions}
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.test.util.MiniClusterWithClientResource

/** Two input files of very different sizes at parallelism 2: the subtask reading the small file finishes long before
  * the other one fails. Directory output only, so no Kinesis needed.
  */
/** Totals stay right when part of the job finished before a restart or resume.
  *
  * Two input files of different sizes at parallelism 2: the subtask reading the small one finishes while the other is
  * still running, then fails. Flink keeps no state for a subtask that finished, so counts kept by the recovering
  * operators would lose the small file's events on restore.
  */
class PartiallyFinishedSpec extends AnyWordSpec with BeforeAndAfter {
  import Fixtures._
  import PartiallyFinishedSpec._

  val flinkCluster = new MiniClusterWithClientResource(
    new MiniClusterResourceConfiguration.Builder().setNumberSlotsPerTaskManager(4).setNumberTaskManagers(1).build
  )
  before(flinkCluster.before())
  after(flinkCluster.after())

  val SmallCount = 300
  val BigCount   = 1500
  val Total      = SmallCount + BigCount

  object SlowFailingJob extends RecoveryJob {
    override def load(env: StreamExecutionEnvironment, input: String, exclude: List[String]): DataStream[String] =
      super.load(env, input, exclude).map(new FailAfter(1000L)).uid("fail-after")
  }

  def setup(name: String) = {
    val workDir = Files.createTempDirectory(s"adv-$name")
    val input   = Files.createDirectories(workDir.resolve("input"))
    Files.write(
      input.resolve("small.txt"),
      (1 to SmallCount).map(i => badRowFor(s"S$i")).asJava,
      StandardCharsets.UTF_8
    )
    Files.write(input.resolve("big.txt"), (1 to BigCount).map(i => badRowFor(s"B$i")).asJava, StandardCharsets.UTF_8)
    workDir
  }

  def kc(workDir: JPath, restarts: Int) = KinesisConfig(
    region = "eu-central-1",
    checkpointing = Some(
      Checkpointing(200.millis, workDir.resolve("checkpoints").toUri.toString, maxFailures = restarts, restartDelay = 100.millis)
    )
  )

  def env(p: Int) = {
    val e = StreamExecutionEnvironment.getExecutionEnvironment
    e.setParallelism(p)
    e
  }

  "partially finished subtasks" should {
    "count every event after a restart within the run" in {
      val workDir = setup("restart")
      failuresLeft.set(1)
      val out = workDir.resolve("output")
      val summary = SlowFailingJob.run(
        env(2),
        workDir.resolve("input").toUri.toString,
        None,
        workDir.resolve("failed").toUri.toString,
        workDir.resolve("unrecoverable").toUri.toString,
        cfg,
        kc(workDir, 3),
        Some(out.toUri.toString)
      )
      val lines = finishedLines(out)
      info(s"summary=$summary lines=${lines.size} distinct=${lines.toSet.size}")
      lines.toSet.size shouldBe Total
      summary.recovered shouldBe Total.toLong
      summary.restarts shouldBe 1
    }

    "count every event after resuming from a failed run's checkpoint" in {
      val workDir = setup("resume")
      failuresLeft.set(5)
      val out = workDir.resolve("output")
      an[Exception] should be thrownBy SlowFailingJob.run(
        env(2),
        workDir.resolve("input").toUri.toString,
        None,
        workDir.resolve("failed").toUri.toString,
        workDir.resolve("unrecoverable").toUri.toString,
        cfg,
        kc(workDir, 0),
        Some(out.toUri.toString)
      )
      val firstLines = finishedLines(out)
      val checkpoint = latestCheckpoint(workDir.resolve("checkpoints"))
      info(s"first run wrote ${firstLines.size} lines; resuming from $checkpoint")
      failuresLeft.set(0)
      val e = env(2)
      e.getJavaEnv.configure {
        val c = new Configuration(); c.set(StateRecoveryOptions.SAVEPOINT_PATH, checkpoint.toUri.toString); c
      }
      val summary = SlowFailingJob.run(
        e,
        workDir.resolve("input").toUri.toString,
        None,
        workDir.resolve("failed").toUri.toString,
        workDir.resolve("unrecoverable").toUri.toString,
        cfg,
        kc(workDir, 0),
        Some(out.toUri.toString)
      )
      val lines = finishedLines(out)
      info(s"summary=$summary lines=${lines.size} distinct=${lines.toSet.size}")
      lines.toSet.size shouldBe Total
      summary.recovered shouldBe Total.toLong
      summary.resumed shouldBe true
    }
  }

  private def finishedLines(dir: JPath): List[String] =
    if (!Files.exists(dir)) Nil
    else {
      val stream = Files.walk(dir)
      try stream.iterator.asScala
        .filter(p => Files.isRegularFile(p) && !p.getFileName.toString.startsWith("."))
        .toList
        .flatMap(Files.readAllLines(_, StandardCharsets.UTF_8).asScala)
      finally stream.close()
    }

  private def latestCheckpoint(dir: JPath): JPath = {
    val stream = Files.walk(dir)
    try stream.iterator.asScala
      .filter(p => p.getFileName.toString.matches("chk-\\d+") && Files.exists(p.resolve("_metadata")))
      .toList
      .maxBy(_.getFileName.toString.stripPrefix("chk-").toInt)
    finally stream.close()
  }
}

object PartiallyFinishedSpec {
  val failuresLeft = new AtomicInteger(0)

  class FailAfter(after: Long) extends RichMapFunction[String, String] {
    private val seen = new AtomicLong(0L)
    override def map(value: String): String = {
      Thread.sleep(2L)
      if (seen.incrementAndGet() >= after && failuresLeft.getAndUpdate(n => math.max(n - 1, 0)) > 0)
        throw new RuntimeException(s"injected failure after $after records")
      value
    }
  }
}
