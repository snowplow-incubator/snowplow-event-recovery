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

import org.apache.flink.metrics._
import org.apache.flink.metrics.reporter.MetricReporter

import scala.collection.mutable

class TestMetricReporter extends MetricReporter {
  import TestMetricReporter._

  override def open(metricConfig: MetricConfig): Unit = {}

  override def close(): Unit = {}

  override def notifyOfAddedMetric(metric: Metric, metricName: String, group: MetricGroup): Unit =
    metric match {
      case counter: Counter     => counterMetrics(metricName) = counter
      case meter: Meter         => meterMetrics(metricName) = meter
      case histogram: Histogram => histogramMetrics(metricName) = histogram
      case _                    =>
    }

  override def notifyOfRemovedMetric(metric: Metric, metricName: String, group: MetricGroup): Unit = {}
}

object TestMetricReporter {
  val counterMetrics: mutable.Map[String, Counter]     = mutable.Map[String, Counter]()
  val meterMetrics: mutable.Map[String, Meter]         = mutable.Map[String, Meter]()
  val histogramMetrics: mutable.Map[String, Histogram] = mutable.Map[String, Histogram]()
}
