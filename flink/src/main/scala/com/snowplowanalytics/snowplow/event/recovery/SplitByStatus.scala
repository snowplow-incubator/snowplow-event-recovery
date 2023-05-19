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

class SplitByStatus(failed: OutputTag[RecoveryError], unrecoverable: OutputTag[RecoveryError])
    extends ProcessFunction[Either[RecoveryError, Array[Byte]], Array[Byte]] {

  // we need this as described here:
  //  https://nightlies.apache.org/flink/flink-docs-release-1.15/docs/ops/metrics
  @transient private var recoveredCount: Counter     = _
  @transient private var failedCount: Counter        = _
  @transient private var unrecoverableCount: Counter = _

  override def open(parameters: Configuration): Unit = {
    recoveredCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("recovered")
    failedCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("failed")
    unrecoverableCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("unrecoverable")
  }

  override def processElement(
    value: Either[RecoveryError, Array[Byte]],
    ctx: ProcessFunction[Either[RecoveryError, Array[Byte]], Array[Byte]]#Context,
    out: Collector[Array[Byte]]
  ): Unit =
    value match {
      case Right(v) =>
        recoveredCount.inc()
        out.collect(v)
      case v @ Left(RecoveryError(UnrecoverableBadRowType(_), _, _)) =>
        unrecoverableCount.inc()
        ctx.output[RecoveryError](
          unrecoverable,
          v.left.get
        )
      case Left(v) =>
        failedCount.inc()
        ctx.output[RecoveryError](
          failed,
          v
        )
    }
}
