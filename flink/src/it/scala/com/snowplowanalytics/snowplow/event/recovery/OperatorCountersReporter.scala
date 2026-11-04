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

/** Records every operator-scoped counter with the name of the operator that registered it.
  *
  * Task-scoped groups carry no `<operator_name>` variable, so a task whose name merely contains an operator's name is
  * never mistaken for that operator.
  */
class OperatorCountersReporter extends MetricReporter {
  override def open(config: MetricConfig): Unit = {}
  override def close(): Unit                     = {}

  override def notifyOfAddedMetric(metric: Metric, name: String, group: MetricGroup): Unit =
    (metric, Option(group.getAllVariables.get("<operator_name>"))) match {
      case (counter: Counter, Some(operator)) =>
        OperatorCountersReporter.counters.add(OperatorCountersReporter.Entry(operator, name, counter))
        ()
      case _ => ()
    }

  override def notifyOfRemovedMetric(metric: Metric, name: String, group: MetricGroup): Unit = {}
}

object OperatorCountersReporter {
  final case class Entry(operator: String, metric: String, counter: Counter)

  val counters: ConcurrentLinkedQueue[Entry] = new ConcurrentLinkedQueue[Entry]()

  /** Sum of `metric` over every subtask of the operators whose name starts with `operatorPrefix` */
  def total(operatorPrefix: String, metric: String): Long =
    counters.asScala.collect {
      case Entry(operator, `metric`, counter) if operator.startsWith(operatorPrefix) => counter.getCount
    }.sum
}

class OperatorCountersReporterFactory extends MetricReporterFactory {
  override def createMetricReporter(properties: Properties): MetricReporter = new OperatorCountersReporter
}
