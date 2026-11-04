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

import scala.collection.JavaConverters._

import org.apache.flink.api.common.accumulators.{IntMaximum, LongCounter}
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.configuration.Configuration
import org.apache.flink.runtime.state.{FunctionInitializationContext, FunctionSnapshotContext}
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.util.Collector

/** Counts the outcomes marked by `SplitByStatus`, for the end-of-run summary. Runs with parallelism 1.
  *
  * Accumulators are returned by env.execute(), but Flink does not checkpoint them, so the totals are also kept in
  * operator state and restored from it after a restart or resume. They live here rather than in `SplitByStatus`: with
  * parallelism 1 this operator only finishes once every recovering subtask has, so its state is in every checkpoint
  * (Flink keeps no state for subtasks that finished before a checkpoint).
  */
class RecoveryTotals extends ProcessFunction[Int, Int] with CheckpointedFunction {
  import RecoveryTotals._

  private val recoveredTotal     = new LongCounter()
  private val failedTotal        = new LongCounter()
  private val unrecoverableTotal = new LongCounter()
  private val attempt            = new IntMaximum()

  // Set when the restored totals were saved by another job, i.e. the run resumed from a checkpoint
  private val resumed = new IntMaximum()

  // Operator state holding the totals, assigned by Flink in initializeState()
  @transient private var totalsState: ListState[Array[Long]] = _

  override def initializeState(context: FunctionInitializationContext): Unit = {
    totalsState = context.getOperatorStateStore.getListState(new ListStateDescriptor(TotalsState, classOf[Array[Long]]))
    val job = getRuntimeContext().getJobInfo().getJobId()
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
    getRuntimeContext().addAccumulator(Recovered, recoveredTotal)
    getRuntimeContext().addAccumulator(Failed, failedTotal)
    getRuntimeContext().addAccumulator(Unrecoverable, unrecoverableTotal)
    attempt.add(getRuntimeContext().getTaskInfo().getAttemptNumber())
    getRuntimeContext().addAccumulator(Attempt, attempt)
    getRuntimeContext().addAccumulator(Resumed, resumed)
  }

  override def processElement(outcome: Int, ctx: ProcessFunction[Int, Int]#Context, out: Collector[Int]): Unit =
    outcome match {
      case SplitByStatus.RecoveredEvent     => recoveredTotal.add(1L)
      case SplitByStatus.FailedEvent        => failedTotal.add(1L)
      case SplitByStatus.UnrecoverableEvent => unrecoverableTotal.add(1L)
      case other                            => throw new IllegalStateException(s"Unknown event outcome $other")
    }
}

object RecoveryTotals {
  val Recovered     = "recovered"
  val Failed        = "failed"
  val Unrecoverable = "unrecoverable"

  /** Highest task attempt number, i.e. how many times the job restarted */
  val Attempt = "attempt"

  /** 1 if the run resumed from a checkpoint of another run, else 0 */
  val Resumed = "resumed"

  private val TotalsState = "recovery-totals"
}
