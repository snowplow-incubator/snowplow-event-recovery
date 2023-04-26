/*
 * Copyright (c) 20203 Snowplow Analytics Ltd. All rights reserved.
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

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global

import org.scalatest.wordspec.AnyWordSpec
import com.dimafeng.testcontainers.LocalStackContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.testcontainers.containers.localstack.LocalStackContainer.Service

import cats.effect.{IO, Timer}
import retry._
import retry.syntax.all._

import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder
import com.amazonaws.services.cloudwatch.model.GetMetricDataRequest
import com.amazonaws.services.cloudwatch.model.Metric
import com.amazonaws.services.cloudwatch.model.MetricStat
import com.amazonaws.services.cloudwatch.model.MetricDataQuery
import com.amazonaws.services.cloudwatch.model.MetricDataResult

class CloudwatchSpec extends AnyWordSpec with TestContainerForAll {
  override val containerDef = LocalStackContainer.Def(
    dockerImageName = "localstack/localstack:2.0.2",
    services = List(Service.CLOUDWATCH, Service.CLOUDWATCHLOGS)
  )

  "Cloudwatch" should {
    "deliver metrics" in withContainers { case localstack: LocalStackContainer =>
      val namespace  = "event-recovery"
      val dimensions = Map("d1" -> "val1")
      val metricName = "recovered"
      val value      = 10L

      val client = AmazonCloudWatchClientBuilder
        .standard()
        .withEndpointConfiguration(
          localstack.endpointConfiguration(Service.CLOUDWATCH)
        )
        .withCredentials(localstack.defaultCredentialsProvider)
        .build()

      val report = Cloudwatch
        .init[IO](
          Some(Cloudwatch.Config(namespace, dimensions)),
          Some(
            Cloudwatch.AWSOverrides(
              localstack.endpointConfiguration(Service.CLOUDWATCH),
              localstack.defaultCredentialsProvider
            )
          )
        )
        .report(metricName, value)
        .use(IO.pure)

      val results: IO[List[MetricDataResult]] = IO.delay {
        val CHECK_DURATION_IN_SECONDS = 1 * 60L;
        val REQUESTER                 = s"${namespace}-it"
        val startTime       = Instant.now().minusSeconds(CHECK_DURATION_IN_SECONDS).truncatedTo(ChronoUnit.MINUTES);
        val endTime         = startTime.plusSeconds(CHECK_DURATION_IN_SECONDS);
        val metric          = new Metric().withNamespace(namespace).withMetricName(metricName)
        val metricStat      = new MetricStat().withMetric(metric).withStat("Sum").withPeriod(60)
        val metricDataQuery = new MetricDataQuery().withMetricStat(metricStat).withId(REQUESTER)
        val query = new GetMetricDataRequest()
          .withMetricDataQueries(metricDataQuery)
          .withStartTime(Date.from(startTime))
          .withEndTime(Date.from(endTime))

        client.getMetricData(query).getMetricDataResults().asScala.toList
      }

      implicit val timer: Timer[IO] = IO.timer(global)

      def reported(results: List[MetricDataResult], value: Long): Boolean =
        results.flatMap(_.getValues().asScala.toList).contains(value)

      // This is a very slow test that runs for a long time until
      // the values can be retrieved from CloudWatch
      val res = (report *> results.retryingOnFailures(
        policy = RetryPolicies.constantDelay(1.second),
        wasSuccessful = (l: List[MetricDataResult]) => reported(l, value),
        onFailure = retry.noop[IO, List[MetricDataResult]]
      )).unsafeRunSync()

      assert(reported(res, value))
    }
  }

}
