package com.snowplowanalytics.snowplow
package event.recovery

import cats.Monoid

import CollectorPayload.thrift.model1.CollectorPayload
import model._

sealed trait RecoveryScenario {
  def error: String
  def filter(errors: List[Error]): Boolean =
    errors.map(_.message).exists(_.contains(error))
  def mutate(originalPayload: CollectorPayload): CollectorPayload
}

object RecoveryScenario {
  implicit val monoid: Monoid[RecoveryScenario] = new Monoid[RecoveryScenario] {
    def combine(r1: RecoveryScenario, r2: RecoveryScenario): RecoveryScenario =
      new RecoveryScenario {
        def error: String = r1.error + r2.error
        override def filter(errors: List[Error]): Boolean = r1.filter(errors) || r2.filter(errors)
        def mutate(originalPayload: CollectorPayload): CollectorPayload =
          (r1.mutate _ andThen r2.mutate)(originalPayload)
      }

    def empty: RecoveryScenario = new RecoveryScenario {
      def error: String = ""
      override def filter(errors: List[Error]): Boolean = false
      def mutate(originalPayload: CollectorPayload): CollectorPayload = originalPayload
    }
  }

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
