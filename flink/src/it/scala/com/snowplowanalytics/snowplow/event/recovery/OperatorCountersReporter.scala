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
