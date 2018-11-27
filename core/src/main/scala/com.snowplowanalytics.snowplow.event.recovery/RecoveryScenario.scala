package com.snowplowanalytics.snowplow
package event.recovery

import CollectorPayload.thrift.model1.CollectorPayload
import model._

sealed trait RecoveryScenario {
  def error: String
  def filter(errors: List[Error]): Boolean =
    errors.map(_.message).exists(_.contains(error))
  def mutate(originalPayload: CollectorPayload): CollectorPayload
}

object RecoveryScenario {

  final case class ReplaceInQueryString(
    error: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = {
      if (originalPayload.querystring != null)
        originalPayload.querystring = originalPayload.querystring.replaceAll(toReplace, replacement)
      originalPayload
    }
  }
  final case class RemoveFromQueryString(error: String, toRemove: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = {
      if (originalPayload.querystring != null)
        originalPayload.querystring = originalPayload.querystring.replaceAll(toRemove, "")
      originalPayload
    }
  }
  final case class ReplaceInBody(
    error: String,
    toReplace: String,
    replacement: String
  ) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = {
      if (originalPayload.body != null)
        originalPayload.body = originalPayload.body.replaceAll(toReplace, replacement)
      originalPayload
    }
  }
  final case class RemoveFromBody(error: String, toRemove: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = {
      if (originalPayload.body != null)
        originalPayload.body = originalPayload.body.replaceAll(toRemove, "")
      originalPayload
    }
  }
  final case class PassThrough(error: String) extends RecoveryScenario {
    def mutate(originalPayload: CollectorPayload): CollectorPayload = originalPayload
  }
}
