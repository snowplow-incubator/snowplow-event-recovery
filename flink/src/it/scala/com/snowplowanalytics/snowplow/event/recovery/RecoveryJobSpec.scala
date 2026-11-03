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

import scala.collection.JavaConverters._
import io.circe.parser

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers._
import org.scalatest.OptionValues._
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.dimafeng.testcontainers.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service

import com.snowplowanalytics.snowplow.event.recovery.config.Config
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.{
  CreateStreamRequest,
  DescribeStreamRequest,
  GetRecordsRequest,
  GetShardIteratorRequest
}
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.scalatest.BeforeAndAfter
import org.apache.flink.test.util.MiniClusterWithClientResource
import org.apache.flink.runtime.testutils
import org.apache.flink.api.scala._

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.kinesis.model.DeleteStreamRequest
import org.apache.flink.configuration.Configuration
import org.apache.flink.configuration.ConfigConstants
import org.apache.flink.metrics.reporter.MetricReporter
import org.apache.flink.metrics.MetricConfig
import org.apache.flink.metrics.Metric
import org.apache.flink.metrics.Counter
import org.apache.flink.metrics.MetricGroup

class RecoveryJobSpec extends AnyWordSpec with TestContainerForAll with BeforeAndAfter {
  override val containerDef = LocalStackContainer.Def(
    dockerImageName = "localstack/localstack:2.0.2",
    services = List(Service.KINESIS)
  )

  // testcontainers-scala 0.41+ dropped its AWS SDK v1 helpers; rebuild them from the Java container
  private def endpointConfiguration(localstack: LocalStackContainer, service: Service): EndpointConfiguration =
    new EndpointConfiguration(localstack.container.getEndpointOverride(service).toString, localstack.container.getRegion)

  private def credentialsProvider(localstack: LocalStackContainer): AWSStaticCredentialsProvider =
    new AWSStaticCredentialsProvider(
      new BasicAWSCredentials(localstack.container.getAccessKey, localstack.container.getSecretKey)
    )

  import Fixtures._

  val flinkCluster = new MiniClusterWithClientResource(
    new testutils.MiniClusterResourceConfiguration.Builder()
      .setNumberSlotsPerTaskManager(1)
      .setNumberTaskManagers(1)
      .build
  )

  before {
    flinkCluster.before()
  }

  after {
    flinkCluster.after()
  }

  object RecoveryJobTest extends RecoveryJob {
    override def load(env: StreamExecutionEnvironment, input: String) =
      env.fromElements(input, input, "{}")
  }

  "RecoveryJob" must {
    "recover" should {
      "bad events into Kinesis stream" in withContainers { localstack: LocalStackContainer =>
        val outputStreamName = "output"
        val errorsDir        = java.nio.file.Files.createTempDirectory("recovery-job-spec").toUri.toString
        val endpoint         = endpointConfiguration(localstack, Service.KINESIS)
        val credentials      = credentialsProvider(localstack)
        val accessValue      = credentials.getCredentials.getAWSAccessKeyId
        val secretValue      = credentials.getCredentials.getAWSSecretKey

        val kinesisClient = AmazonKinesisClientBuilder
          .standard()
          .withCredentials(credentials)
          .withEndpointConfiguration(
            new EndpointConfiguration(endpoint.getServiceEndpoint(), endpoint.getSigningRegion())
          )
          .build()

        val createStream: CreateStreamRequest =
          new CreateStreamRequest().withStreamName(outputStreamName).withShardCount(1)
        kinesisClient.createStream(createStream)

        RecoveryJobTest.run(
          StreamExecutionEnvironment.getExecutionEnvironment,
          badRow,
          Some(outputStreamName),
          s"$errorsDir/failed",
          s"$errorsDir/unrecoverable",
          cfg,
          KinesisConfig(
            region = endpoint.getSigningRegion(),
            endpoint = Some(endpoint.getServiceEndpoint()),
            credentials = Some(
              Credentials(
                accessKey = accessValue,
                secretKey = secretValue
              )
            )
          )
        )

        val describeStreamRequest: DescribeStreamRequest = new DescribeStreamRequest().withStreamName(outputStreamName)
        val shardId = kinesisClient
          .describeStream(describeStreamRequest)
          .getStreamDescription()
          .getShards()
          .asScala
          .toList
          .map(_.getShardId())
          .head

        val getShardIteratorRequest: GetShardIteratorRequest = new GetShardIteratorRequest()
          .withStreamName(outputStreamName)
          .withShardId(shardId)
          .withShardIteratorType("TRIM_HORIZON")
        val shardIterator = kinesisClient.getShardIterator(getShardIteratorRequest).getShardIterator()

        val req: GetRecordsRequest =
          new GetRecordsRequest().withShardIterator(shardIterator).withLimit(1)

        val res = kinesisClient.getRecords(req).getRecords().asScala.toList.map(_.getData().array())

        val del = new DeleteStreamRequest().withStreamName(outputStreamName)
        kinesisClient.deleteStream(del)

        res should contain(fixed)
      }
    }

    "refuse to start" should {
      "when the Kinesis stream cannot be described, instead of retrying forever" in withContainers {
        localstack: LocalStackContainer =>
          val endpoint    = endpointConfiguration(localstack, Service.KINESIS)
          val credentials = credentialsProvider(localstack).getCredentials
          val errorsDir   = java.nio.file.Files.createTempDirectory("recovery-job-spec").toUri.toString

          val err = the[IllegalStateException] thrownBy RecoveryJobTest.run(
            StreamExecutionEnvironment.getExecutionEnvironment,
            badRow,
            Some("missing"),
            s"$errorsDir/failed",
            s"$errorsDir/unrecoverable",
            cfg,
            KinesisConfig(
              region = endpoint.getSigningRegion(),
              endpoint = Some(endpoint.getServiceEndpoint()),
              credentials = Some(Credentials(credentials.getAWSAccessKeyId, credentials.getAWSSecretKey))
            )
          )

          err.getMessage should include("Cannot describe Kinesis stream missing")
          err.getMessage should include("ResourceNotFoundException")
      }
    }
  }
}
