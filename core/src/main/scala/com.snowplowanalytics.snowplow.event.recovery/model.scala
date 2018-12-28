/*
 * Copyright (c) 2018-2018 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow
package event.recovery

import CollectorPayload.thrift.model1.CollectorPayload

/** Object repertoriating the model case classes. */
object model {
  /**
   * Case class modeling a single error inside a [[BadRow]].
   * @param level error level, can be "error", "warning", etc.
   * @param message the actual content
   */
  final case class Error(level: String, message: String)
  /**
   * Case class modeling a bad row.
   * @param line original collector payload
   * @param errors list of [[Error]]
   * @param failure_stamp timestamp at which the bad row was created
   */
  final case class BadRow(line: String, errors: List[Error], failure_tstamp: String) {
    /**
     * Determine if a bad row is affected by one or more [[RecoveryScenario]].
     * @param recoveryScenarios list of [[RecoveryScenario]] to check against
     * @return true if the bad row is affected by at least one of the supplied [[RecoveryScenario]]
     */
    def isAffected(recoveryScenarios: List[RecoveryScenario]): Boolean =
      recoveryScenarios.map(_.filter(errors)).fold(false)(_ || _)

    /**
     * Mutate the collector payload contained in the bad row according to the suppplied
     * [[RecoveryScenario]]s.
     * @param recoveryScenarios list of [[RecoveryScenario]] to apply to this bad row's collector
     * payload
     * @return a fixed collector payload
     */
    def mutateCollectorPayload(recoveryScenarios: List[RecoveryScenario]): CollectorPayload =
      (utils.thriftDeser andThen
        recoveryScenarios
          .filter(_.filter(errors))
          .map(_.mutate _)
          .fold(identity[CollectorPayload] _)(_ andThen _)
      )(line)
  }
}
