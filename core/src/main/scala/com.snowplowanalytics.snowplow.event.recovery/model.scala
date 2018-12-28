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

object model {
  final case class Error(level: String, message: String)
  final case class BadRow(line: String, errors: List[Error], failure_tstamp: String) {
    def isAffected(recoveryScenarios: List[RecoveryScenario]): Boolean =
      recoveryScenarios.map(_.filter(errors)).fold(false)(_ || _)

    def mutateCollectorPayload(recoveryScenarios: List[RecoveryScenario]): CollectorPayload =
      (utils.thriftDeser andThen
        recoveryScenarios
          .filter(_.filter(errors))
          .map(_.mutate _)
          .fold(identity[CollectorPayload] _)(_ andThen _)
      )(line)
  }
}
