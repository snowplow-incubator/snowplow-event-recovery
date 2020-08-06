/*
 * Copyright (c) 2018-2020 Snowplow Analytics Ltd. All rights reserved.
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
package domain

sealed trait Result
case object Recovered extends Result
case object Failed extends Result
case object Unrecoverable extends Result

object Result {
  val partitions = Set[Result](Recovered, Failed, Unrecoverable)
  val byKey: Either[RecoveryError, Array[Byte]] => Result = {
    case Right(_)                                              => Recovered
    case Left(RecoveryError(UnrecoverableBadRowType(_), _, _)) => Unrecoverable
    case Left(_)                                               => Failed
  }
}
