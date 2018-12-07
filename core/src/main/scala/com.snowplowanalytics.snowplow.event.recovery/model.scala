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
