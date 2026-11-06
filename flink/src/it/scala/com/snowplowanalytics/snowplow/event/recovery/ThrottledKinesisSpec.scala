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

import scala.annotation.tailrec
import scala.collection.JavaConverters._

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

import org.apache.flink.api.scala._
import org.apache.flink.configuration.Configuration
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.test.util.MiniClusterWithClientResource

/** A recovery into a stream that throttles writes must deliver every event. The former Spark job lost events here.
  *
  * LocalStack's KINESIS_ERROR_PROBABILITY makes a PutRecords call fail every record in it with
  * ProvisionedThroughputExceededException while still answering 200, the partial-failure shape the former Spark
  * job's sink dropped. Throttled records are not written, so the stream must end up with exactly one copy of each event.
  */
class ThrottledKinesisSpec extends AnyWordSpec with TestContainerForAll with BeforeAndAfter {
  import Fixtures._

  val ErrorProbability = "0.5"
  val EventCount       = 200
  // Small batches mean many PutRecords calls, so throttling is all but certain to hit some of them
  val BatchSize = 10

  override val containerDef = new ContainerDef {
    override type Container = LocalStackContainer

    override protected def createContainer(): LocalStackContainer = {
      val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:2.0.2"), List(Service.KINESIS))
      localstack.container.withEnv("KINESIS_ERROR_PROBABILITY", ErrorProbability)
      localstack
    }
  }

  val flinkCluster = new MiniClusterWithClientResource(
    new MiniClusterResourceConfiguration.Builder()
      .setConfiguration {
        val config = new Configuration()
        config.setString("metrics.reporter.counters.factory.class", classOf[OperatorCountersReporterFactory].getName)
        config
      }
      .setNumberSlotsPerTaskManager(1)
      .setNumberTaskManagers(1)
      .build
  )

  before(flinkCluster.before())
  after(flinkCluster.after())

  val pages: List[String] = (1 to EventCount).map(i => s"DemoPageTitle$i").toList

  object ThrottledRecoveryJob extends RecoveryJob {
    override def load(env: StreamExecutionEnvironment, input: String, exclude: List[String]): DataStream[String] =
      env.fromCollection(pages.map(badRowFor))
  }

  "RecoveryJob" should {
    "deliver every event to a single-shard stream that throttles writes" in withContainers {
      localstack: LocalStackContainer =>
        val streamName = "throttled"
        val endpoint = new EndpointConfiguration(
          localstack.container.getEndpointOverride(Service.KINESIS).toString,
          localstack.container.getRegion
        )
        val accessKey = localstack.container.getAccessKey
        val secretKey = localstack.container.getSecretKey
        val kinesis = AmazonKinesisClientBuilder
          .standard()
          .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
          .withEndpointConfiguration(endpoint)
          .build()

        val errorsDir = java.nio.file.Files.createTempDirectory("throttled-kinesis-spec").toUri.toString

        kinesis.createStream(new CreateStreamRequest().withStreamName(streamName).withShardCount(1))
        kinesis.waiters().streamExists().run(new WaiterParameters(new DescribeStreamRequest().withStreamName(streamName)))

        val summary = ThrottledRecoveryJob.run(
          StreamExecutionEnvironment.getExecutionEnvironment,
          "unused",
          Some(streamName),
          s"$errorsDir/failed",
          s"$errorsDir/unrecoverable",
          cfg,
          KinesisConfig(
            region = endpoint.getSigningRegion,
            endpoint = Some(endpoint.getServiceEndpoint),
            credentials = Some(Credentials(accessKey, secretKey)),
            batchSize = Some(BatchSize)
          )
        )

        val delivered = readAll(kinesis, streamName).map(_.toSeq)

        // numRecordsOut counts every send attempt, retries included. numRecordsOutErrors adds PutRecords'
        // FailedRecordCount, which LocalStack 2.0.2 always reports as 1 for a throttled batch, so it only shows that
        // throttling happened, not how many records were rejected.
        val attempted = OperatorCountersReporter.total(RecoveryJob.KinesisSinkName, "numRecordsOut")
        val rejected  = OperatorCountersReporter.total(RecoveryJob.KinesisSinkName, "numRecordsOutErrors")

        withClue("throttling never happened, so the test proved nothing:") {
          rejected should be > 0L
        }
        withClue("no record was sent twice, so nothing was retried:") {
          attempted should be > EventCount.toLong
        }
        delivered should have size EventCount.toLong
        delivered should contain theSameElementsAs pages.map(payloadFor(_).toSeq)
        summary shouldBe RecoverySummary(recovered = EventCount.toLong, failed = 0L, unrecoverable = 0L)
    }
  }

  /** Reads the stream's only shard from the start until three reads in a row return nothing */
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
        val result  = kinesis.getRecords(new GetRecordsRequest().withShardIterator(iterator).withLimit(1000))
        val records = result.getRecords.asScala.toVector.map(_.getData.array())
        go(result.getNextShardIterator, acc ++ records, if (records.isEmpty) emptyReads + 1 else 0)
      }

    go(first, Vector.empty, 0)
  }
}
