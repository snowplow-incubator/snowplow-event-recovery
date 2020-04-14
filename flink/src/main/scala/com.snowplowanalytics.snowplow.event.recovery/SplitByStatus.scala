/*
 * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
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

import org.apache.flink.util.{OutputTag => JOutputTag}
import org.apache.flink.util.Collector
import org.apache.flink.streaming.api.scala.OutputTag
import org.apache.flink.streaming.api.functions.ProcessFunction
import domain._

class SplitByStatus(failed: OutputTag[RecoveryError], unrecoverable: OutputTag[RecoveryError])
    extends ProcessFunction[Either[RecoveryError, String], String] {
  override def processElement(
    value: Either[RecoveryError, String],
    ctx: ProcessFunction[Either[RecoveryError, String], String]#Context,
    out: Collector[String]
  ): Unit = {
    def logAndCollect(tag: OutputTag[RecoveryError])(s: RecoveryError) = {
      println(s.status.message)
      ctx.output(
        tag.asInstanceOf[JOutputTag[Object]],
        s.badRow.asInstanceOf[Object]
      )
    }

    value match {
      case Right(v) => out.collect(v)
      case v @ Left(RecoveryError(UnrecoverableBadRowType(_), _, _)) =>
        logAndCollect(failed)(v.left.get)
      case Left(v) => logAndCollect(unrecoverable)(v)
    }
  }
}
