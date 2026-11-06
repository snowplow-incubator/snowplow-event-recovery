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
