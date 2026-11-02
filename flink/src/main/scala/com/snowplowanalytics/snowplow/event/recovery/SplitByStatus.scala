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

import org.apache.flink.util.Collector
import org.apache.flink.streaming.api.scala.OutputTag
import org.apache.flink.streaming.api.functions.ProcessFunction
import domain._
import org.apache.flink.configuration.Configuration
import org.apache.flink.metrics.Counter
import org.apache.flink.api.common.accumulators.LongCounter

class SplitByStatus(failed: OutputTag[RecoveryError], unrecoverable: OutputTag[RecoveryError])
    extends ProcessFunction[Either[RecoveryError, Array[Byte]], Array[Byte]] {

  // we need this as described here:
  //  https://nightlies.apache.org/flink/flink-docs-release-1.15/docs/ops/metrics
  @transient private var recoveredCount: Counter     = _
  @transient private var failedCount: Counter        = _
  @transient private var unrecoverableCount: Counter = _

  // Totals for the end-of-run summary. Accumulators are merged across subtasks and returned by env.execute()
  private val recoveredTotal     = new LongCounter()
  private val failedTotal        = new LongCounter()
  private val unrecoverableTotal = new LongCounter()

  override def open(parameters: Configuration): Unit = {
    recoveredCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("recovered")
    failedCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("failed")
    unrecoverableCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("unrecoverable")
    getRuntimeContext().addAccumulator(SplitByStatus.Recovered, recoveredTotal)
    getRuntimeContext().addAccumulator(SplitByStatus.Failed, failedTotal)
    getRuntimeContext().addAccumulator(SplitByStatus.Unrecoverable, unrecoverableTotal)
  }

  override def processElement(
    value: Either[RecoveryError, Array[Byte]],
    ctx: ProcessFunction[Either[RecoveryError, Array[Byte]], Array[Byte]]#Context,
    out: Collector[Array[Byte]]
  ): Unit =
    value match {
      case Right(v) =>
        recoveredCount.inc()
        recoveredTotal.add(1L)
        out.collect(v)
      case v @ Left(RecoveryError(UnrecoverableBadRowType(_), _, _)) =>
        unrecoverableCount.inc()
        unrecoverableTotal.add(1L)
        ctx.output[RecoveryError](
          unrecoverable,
          v.left.get
        )
      case Left(v) =>
        failedCount.inc()
        failedTotal.add(1L)
        ctx.output[RecoveryError](
          failed,
          v
        )
    }
}

object SplitByStatus {
  val Recovered     = "recovered"
  val Failed        = "failed"
  val Unrecoverable = "unrecoverable"
}
