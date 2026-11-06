/*
 * Copyright (c) 2023-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */

package com.snowplowanalytics.snowplow.event.recovery

import org.apache.flink.metrics._
import org.apache.flink.metrics.reporter.{MetricReporter, MetricReporterFactory}

import java.util.Properties
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

/** Flink 1.16+ only instantiates reporters through a factory (`metrics.reporter.<name>.factory.class`) */
class TestMetricReporterFactory extends MetricReporterFactory {
  override def createMetricReporter(properties: Properties): MetricReporter = new TestMetricReporter
}

object TestMetricReporter {
  val counterMetrics: mutable.Map[String, Counter]     = mutable.Map[String, Counter]()
  val meterMetrics: mutable.Map[String, Meter]         = mutable.Map[String, Meter]()
  val histogramMetrics: mutable.Map[String, Histogram] = mutable.Map[String, Histogram]()
}
