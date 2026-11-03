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

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._

import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

import com.dimafeng.testcontainers.{ContainerDef, LocalStackContainer}
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.utility.DockerImageName

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.model.{
  CreateStreamRequest,
  DescribeStreamRequest,
  GetRecordsRequest,
  GetShardIteratorRequest
}
import com.amazonaws.waiters.WaiterParameters

import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.api.scala._
import org.apache.flink.configuration.{Configuration, StateRecoveryOptions}
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.test.util.MiniClusterWithClientResource

/** A task failure part way through a recovery: Flink restarts the job from its last checkpoint, and a run that failed
  * for good can be resumed from its last checkpoint by another run.
  *
  * Kinesis is at-least-once here: events the sink sent after the checkpoint a run resumes from are sent again, so the
  * stream may hold duplicates. The tests require every event at least once and an exact summary; they only report
  * the duplicates.
  */
class CheckpointRecoverySpec extends AnyWordSpec with TestContainerForAll with BeforeAndAfter {
  import Fixtures._
  import CheckpointRecoverySpec._

  val EventCount = 1500
  val FailAfter  = 1000L

  override val containerDef = new ContainerDef {
    override type Container = LocalStackContainer
    override protected def createContainer(): LocalStackContainer =
      LocalStackContainer(DockerImageName.parse("localstack/localstack:2.0.2"), List(Service.KINESIS))
  }

  val flinkCluster = new MiniClusterWithClientResource(
    new MiniClusterResourceConfiguration.Builder().setNumberSlotsPerTaskManager(2).setNumberTaskManagers(1).build
  )

  before(flinkCluster.before())
  after(flinkCluster.after())

  val pages: List[String] = (1 to EventCount).map(i => s"DemoPageTitle$i").toList
  val expected            = pages.map(payloadFor(_).toSeq).toSet

  /** The real file source with a slow pass-through that can throw. Both runs of a resume use this same job, so their
    * graphs match.
    */
  object FailingJob extends RecoveryJob {
    override def load(env: StreamExecutionEnvironment, input: String, exclude: List[String]): DataStream[String] =
      super.load(env, input, exclude).map(new FailAfter(FailAfter)).uid("fail-after")
  }

  "RecoveryJob with checkpointing" should {
    "restart from its last checkpoint after a task failure and count every event once" in withContainers {
      localstack: LocalStackContainer =>
        val setup = Setup(localstack, "restored")
        failuresLeft.set(1)

        val summary = FailingJob.run(
          withParallelism(1),
          setup.inputDir,
          Some(setup.streamName),
          setup.failedDir,
          setup.unrecoverableDir,
          cfg,
          setup.kinesisConfig(restartAttempts = 3)
        )

        val delivered = readAll(setup.kinesis, setup.streamName).map(_.toSeq)
        info(s"delivered ${delivered.size} records for $EventCount events, ${delivered.size - delivered.toSet.size} duplicates")

        failuresLeft.get shouldBe 0
        delivered.toSet shouldBe expected
        summary shouldBe RecoverySummary(recovered = EventCount.toLong, failed = 0L, unrecoverable = 0L, restarts = 1)
    }

    "fail once restarts are used up, and resume from the failed run's last checkpoint at another parallelism" in withContainers {
      localstack: LocalStackContainer =>
        val setup = Setup(localstack, "resumed")

        // More failures than the restart cap allows, so the first run must give up. Under Flink's default restart
        // strategy it would keep restarting until the failures ran out and then succeed.
        failuresLeft.set(5)
        an[Exception] should be thrownBy FailingJob.run(
          withParallelism(1),
          setup.inputDir,
          Some(setup.streamName),
          setup.failedDir,
          setup.unrecoverableDir,
          cfg,
          setup.kinesisConfig(restartAttempts = 0)
        )
        val deliveredByFirstRun = readAll(setup.kinesis, setup.streamName).size
        val checkpoint          = latestCheckpoint(setup.checkpointDir)
        info(s"first run failed after delivering $deliveredByFirstRun records; resuming from $checkpoint")

        // Resumed with more parallelism than the failed run had, which redistributes the saved state
        failuresLeft.set(0)
        val resumedEnv = withParallelism(2)
        resumedEnv.getJavaEnv.configure {
          val config = new Configuration()
          config.set(StateRecoveryOptions.SAVEPOINT_PATH, checkpoint.toUri.toString)
          config
        }
        val summary = FailingJob.run(
          resumedEnv,
          setup.inputDir,
          Some(setup.streamName),
          setup.failedDir,
          setup.unrecoverableDir,
          cfg,
          setup.kinesisConfig(restartAttempts = 0)
        )

        val delivered = readAll(setup.kinesis, setup.streamName).map(_.toSeq)
        info(s"both runs delivered ${delivered.size} records, ${delivered.size - delivered.toSet.size} duplicates")

        deliveredByFirstRun.toLong should (be > 0L and be < EventCount.toLong)
        delivered.toSet shouldBe expected
        // The resumed run starts from the counts in the checkpoint, so it reports the whole recovery
        summary shouldBe RecoverySummary(
          recovered = EventCount.toLong,
          failed = 0L,
          unrecoverable = 0L,
          restarts = 0,
          resumed = true
        )
    }
  }

  private def withParallelism(parallelism: Int): StreamExecutionEnvironment = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(parallelism)
    env
  }

  final case class Setup(localstack: LocalStackContainer, streamName: String) {
    private val endpoint = new EndpointConfiguration(
      localstack.container.getEndpointOverride(Service.KINESIS).toString,
      localstack.container.getRegion
    )
    private val accessKey = localstack.container.getAccessKey
    private val secretKey = localstack.container.getSecretKey

    val kinesis: AmazonKinesis = AmazonKinesisClientBuilder
      .standard()
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
      .withEndpointConfiguration(endpoint)
      .build()
    kinesis.createStream(new CreateStreamRequest().withStreamName(streamName).withShardCount(1))
    kinesis.waiters().streamExists().run(new WaiterParameters(new DescribeStreamRequest().withStreamName(streamName)))

    private val workDir: JPath = Files.createTempDirectory(s"checkpoint-recovery-spec-$streamName")
    private val input: JPath   = Files.createDirectories(workDir.resolve("input"))
    // Several files, like a stash
    pages.grouped(100).zipWithIndex.foreach { case (group, i) =>
      Files.write(input.resolve(f"bad-rows-$i%02d.txt"), group.map(badRowFor).asJava, StandardCharsets.UTF_8)
    }

    val inputDir: String         = input.toUri.toString
    val failedDir: String        = workDir.resolve("failed").toUri.toString
    val unrecoverableDir: String = workDir.resolve("unrecoverable").toUri.toString
    val checkpointDir: JPath     = workDir.resolve("checkpoints")

    def kinesisConfig(restartAttempts: Int): KinesisConfig =
      KinesisConfig(
        region = endpoint.getSigningRegion,
        endpoint = Some(endpoint.getServiceEndpoint),
        credentials = Some(Credentials(accessKey, secretKey)),
        checkpointing = Some(
          Checkpointing(
            interval = 200.millis,
            directory = checkpointDir.toUri.toString,
            restartAttempts = restartAttempts,
            restartDelay = 100.millis
          )
        )
      )
  }

  /** The newest completed checkpoint: `<dir>/<job id>/chk-<n>` holding a `_metadata` file */
  private def latestCheckpoint(dir: JPath): JPath = {
    val stream = Files.walk(dir)
    try stream.iterator.asScala
      .filter(p => p.getFileName.toString.matches("chk-\\d+") && Files.exists(p.resolve("_metadata")))
      .toList
      .maxBy(_.getFileName.toString.stripPrefix("chk-").toInt)
    finally stream.close()
  }

  private def readAll(kinesis: AmazonKinesis, streamName: String): Vector[Array[Byte]] = {
    val shardId = kinesis.describeStream(streamName).getStreamDescription.getShards.asScala.head.getShardId
    val first = kinesis
      .getShardIterator(
        new GetShardIteratorRequest()
          .withStreamName(streamName)
          .withShardId(shardId)
          .withShardIteratorType("TRIM_HORIZON")
      )
      .getShardIterator

    @tailrec
    def go(iterator: String, acc: Vector[Array[Byte]], emptyReads: Int): Vector[Array[Byte]] =
      if (iterator == null || emptyReads >= 3) acc
      else {
        val result  = kinesis.getRecords(new GetRecordsRequest().withShardIterator(iterator).withLimit(10000))
        val records = result.getRecords.asScala.toVector.map(_.getData.array())
        go(result.getNextShardIterator, acc ++ records, if (records.isEmpty) emptyReads + 1 else 0)
      }

    go(first, Vector.empty, 0)
  }
}

object CheckpointRecoverySpec {

  /** How many more times FailAfter may throw. Shared by every task attempt of the in-process cluster. */
  val failuresLeft = new AtomicInteger(0)

  /** Passes records through slowly, so checkpoints complete before a failure, and throws once a task attempt has seen
    * `after` records, while failures are left.
    */
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
