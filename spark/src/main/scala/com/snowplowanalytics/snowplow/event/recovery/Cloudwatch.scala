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

import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder
import com.amazonaws.services.cloudwatch.model.MetricDatum
import com.amazonaws.services.cloudwatch.model.StandardUnit
import com.amazonaws.services.cloudwatch.model.Dimension
import com.amazonaws.services.cloudwatch.model.PutMetricDataRequest
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.cloudwatch.AmazonCloudWatch
import cats.effect.Resource
import cats.effect.Sync
import com.amazonaws.auth.AWSCredentialsProvider

trait Cloudwatch[F[_]] {
  def report(metricName: String, value: Long): Resource[F, Unit]
}

object Cloudwatch {

  case class AWSOverrides(
    endpoint: AwsClientBuilder.EndpointConfiguration,
    credentials: AWSCredentialsProvider
  )

  case class Config(
    namespace: String,
    dimensions: Map[String, String]
  )

  def init[F[_]: Sync](config: Option[Cloudwatch.Config], overrides: Option[AWSOverrides] = None): Cloudwatch[F] =
    new Cloudwatch[F] {
      val client = Resource.make {
        Sync[F].delay {
          if (overrides.isDefined) {
            AmazonCloudWatchClientBuilder
              .standard()
              .withEndpointConfiguration(overrides.map(_.endpoint).get)
              .withCredentials(overrides.map(_.credentials).get)
              .build()
          } else {
            AmazonCloudWatchClientBuilder.defaultClient()
          }
        }
      }(client => Sync[F].delay(client.shutdown()))

      def reportOne(config: Config, cloudwatch: AmazonCloudWatch, metricName: String, value: Long): F[Unit] =
        Sync[F].catchNonFatal {
          val datum = new MetricDatum()
            .withMetricName(metricName)
            .withUnit(StandardUnit.Count)
            .withValue(value)
            .withDimensions(
              config
                .dimensions
                .map { case (k, v) =>
                  new Dimension().withName(k).withValue(v)
                }
                .toSeq
                .asJava
            )

          val putRequest = new PutMetricDataRequest().withNamespace(config.namespace).withMetricData(datum)

          cloudwatch.putMetricData(putRequest)
        }

      def report(metricName: String, value: Long): Resource[F, Unit] = config match {
        case Some(cfg) =>
          for {
            c <- client
            _ <- Resource.eval(reportOne(cfg, c, metricName, value))
          } yield ()
        case None =>
          Resource.eval(
            Sync[F].delay(System.out.println(s"CloudWatch reporting disabled. Metric $metricName: $value not sent."))
          )
      }
    }

}
