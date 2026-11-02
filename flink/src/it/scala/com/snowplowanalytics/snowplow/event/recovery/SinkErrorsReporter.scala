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

import java.util.Properties

import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters._

import org.apache.flink.metrics.{Counter, Metric, MetricConfig, MetricGroup}
import org.apache.flink.metrics.reporter.{MetricReporter, MetricReporterFactory}

/** Captures every sink writer's `numRecordsOutErrors` counter.
  *
  * The Kinesis sink increments it once per record a PutRecords call rejects, before re-queueing the record. The file
  * sinks for failed and unrecoverable rows register it too but never increment it. All three writers are named
  * "Sink: Writer", so their metric identifiers collide and the counters are kept as a list, not keyed by identifier.
  */
class SinkErrorsReporter extends MetricReporter {
  override def open(config: MetricConfig): Unit = {}
  override def close(): Unit                     = {}

  override def notifyOfAddedMetric(metric: Metric, name: String, group: MetricGroup): Unit =
    metric match {
      case counter: Counter if name == SinkErrorsReporter.MetricName =>
        SinkErrorsReporter.counters.add(counter)
        ()
      case _ => ()
    }

  override def notifyOfRemovedMetric(metric: Metric, name: String, group: MetricGroup): Unit = {}
}

object SinkErrorsReporter {
  val MetricName = "numRecordsOutErrors"

  val counters: ConcurrentLinkedQueue[Counter] = new ConcurrentLinkedQueue[Counter]()

  def total: Long = counters.asScala.map(_.getCount).sum
}

class SinkErrorsReporterFactory extends MetricReporterFactory {
  override def createMetricReporter(properties: Properties): MetricReporter = new SinkErrorsReporter
}
