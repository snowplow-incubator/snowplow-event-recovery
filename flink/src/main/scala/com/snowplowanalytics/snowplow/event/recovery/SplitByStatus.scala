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

import org.apache.flink.api.common.accumulators.{IntMaximum, LongCounter}
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.configuration.Configuration
import org.apache.flink.metrics.Counter
import org.apache.flink.runtime.state.{FunctionInitializationContext, FunctionSnapshotContext}
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.streaming.api.scala.OutputTag
import org.apache.flink.util.Collector

import domain._

class SplitByStatus(failed: OutputTag[RecoveryError], unrecoverable: OutputTag[RecoveryError])
    extends ProcessFunction[Either[RecoveryError, Array[Byte]], Array[Byte]]
    with CheckpointedFunction {
  import SplitByStatus._

  // Live metrics, reset when the job restarts. Flink assigns them in open(), see
  //  https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/metrics
  @transient private var recoveredCount: Counter     = _
  @transient private var failedCount: Counter        = _
  @transient private var unrecoverableCount: Counter = _

  // Totals for the end-of-run summary. Accumulators are merged across subtasks and returned by env.execute(), but
  // Flink does not checkpoint them, so they are also kept in operator state and restored from it after a restart.
  private val recoveredTotal     = new LongCounter()
  private val failedTotal        = new LongCounter()
  private val unrecoverableTotal = new LongCounter()
  private val attempt            = new IntMaximum()

  // Set when this subtask restored totals saved by another job, i.e. the run resumed from a checkpoint
  private val resumed = new IntMaximum()

  // Operator state holding the totals, assigned by Flink in initializeState()
  @transient private var totalsState: ListState[Array[Long]] = _

  override def initializeState(context: FunctionInitializationContext): Unit = {
    totalsState = context.getOperatorStateStore.getListState(new ListStateDescriptor(TotalsState, classOf[Array[Long]]))
    val job = getRuntimeContext().getJobInfo().getJobId()
    // Each subtask stores one element. Flink hands whole elements out when the parallelism changes, so after a restore
    // a subtask may hold several elements or none; their sum over all subtasks is the total either way.
    totalsState.get.asScala.foreach {
      case Array(recovered, failed, unrecoverable, jobUpper, jobLower, wasResumed) =>
        recoveredTotal.add(recovered)
        failedTotal.add(failed)
        unrecoverableTotal.add(unrecoverable)
        if (wasResumed == 1L || jobUpper != job.getUpperPart || jobLower != job.getLowerPart) resumed.add(1)
      case other =>
        throw new IllegalStateException(s"Corrupt $TotalsState state: ${other.mkString("[", ",", "]")}")
    }
  }

  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    val job = getRuntimeContext().getJobInfo().getJobId()
    totalsState.update(
      List(
        Array(
          recoveredTotal.getLocalValue.longValue,
          failedTotal.getLocalValue.longValue,
          unrecoverableTotal.getLocalValue.longValue,
          job.getUpperPart,
          job.getLowerPart,
          resumed.getLocalValue.longValue
        )
      ).asJava
    )
  }

  override def open(parameters: Configuration): Unit = {
    recoveredCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("recovered")
    failedCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("failed")
    unrecoverableCount = getRuntimeContext().getMetricGroup().addGroup("events").counter("unrecoverable")
    getRuntimeContext().addAccumulator(Recovered, recoveredTotal)
    getRuntimeContext().addAccumulator(Failed, failedTotal)
    getRuntimeContext().addAccumulator(Unrecoverable, unrecoverableTotal)
    attempt.add(getRuntimeContext().getTaskInfo().getAttemptNumber())
    getRuntimeContext().addAccumulator(Attempt, attempt)
    getRuntimeContext().addAccumulator(Resumed, resumed)
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

  /** Highest task attempt number, i.e. how many times the job restarted */
  val Attempt = "attempt"

  /** 1 if the run resumed from a checkpoint of another run, else 0 */
  val Resumed = "resumed"

  private val TotalsState = "recovery-totals"
}
