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
package com.snowplowanalytics.snowplow
package event.recovery

import cats.data._
import cats.implicits._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.badrows.BadRow._
import com.snowplowanalytics.snowplow.badrows.Payload
import config._
import domain._
import steps._
import inspectable.Inspectable._

object recoverable {

  /**
    * A typeclass that allows applying recovery scenarios.
    */
  trait Recoverable[A <: BadRow, B <: Payload] { self =>

    /**
      * Apply a recovery configuration flow to given `a`.
      */
    def recover(a: A)(config: List[StepConfig]): Either[RecoveryStatus, A]

    /**
      * Query `Recoverable`'s payload.
      * @param recoverable
      * @return optionally `Recoverable`'s `Payload`
      */
    def payload(a: A): Option[B]
  }

  object Recoverable {
    def apply[A <: BadRow, B <: Payload](implicit r: Recoverable[A, B]): Recoverable[A, B] = r

    final def instance[A <: BadRow, B <: Payload](
      r: A => List[StepConfig] => Recovering[A]
    )(
      p: A => Option[B]
    ): Recoverable[A, B] =
      new Recoverable[A, B] {
        override def recover(a: A)(config: List[StepConfig]): Recovering[A] = r(a)(config)
        override def payload(a: A): Option[B]                               = p(a)
      }

    /**
      * Step through configured flow on top of payload.
      * @param config: recovery configuration
      * @param flow: a flow which shall be applied
      * @param payload
      * @param mkStep: a definition of how to turn `StepConfig` to `Step`
      */
    def step[B <: Payload](steps: List[StepConfig], payload: B)(mkStep: StepConfig => Step[B]): Recovering[B] =
      steps.map(c => Kleisli(mkStep(c).recover)).foldLeft(Kleisli(new PassThrough[B]().recover))(_ >>> _)(payload)

    def recover[A <: BadRow, B <: Payload](a: A)(config: List[StepConfig])(implicit rs: Recoverable[A, B]) =
      rs.recover(a)(config)
    object ops {
      implicit class RecoverableOps[A <: BadRow, B <: Payload](a: A)(implicit rec: Recoverable[A, B]) {
        def recover(config: List[StepConfig]) =
          Recoverable[A, B].recover(a)(config)
        def payload = Recoverable[A, B].payload(a)
      }
    }

    implicit val badRowRecovery: Recoverable[BadRow, Payload] =
      Recoverable.instance[BadRow, Payload] {
        case a: AdapterFailures => adapterFailuresRecovery.recover(a)
        case a: TrackerProtocolViolations =>
          trackerProtocolViolationsRecovery.recover(a)
        case a: SchemaViolations   => schemaViolationsRecovery.recover(a)
        case a: EnrichmentFailures => enrichmentFailuresRecovery.recover(a)
        case a: BadRow => { _ =>
          Left(UnrecoverableBadRowType(a))
        }
      } {
        case a: AdapterFailures           => a.payload.some
        case a: TrackerProtocolViolations => a.payload.some
        case a: SchemaViolations          => a.payload.some
        case a: EnrichmentFailures        => a.payload.some
        case _                            => None
      }

    implicit val sizeViolationRecovery: Recoverable[SizeViolation, Payload.RawPayload]         = unrecoverable
    implicit val cpFormatViolationRecovery: Recoverable[CPFormatViolation, Payload.RawPayload] = unrecoverable

    implicit val adapterFailuresRecovery: Recoverable[AdapterFailures, Payload.CollectorPayload] =
      new Recoverable[AdapterFailures, Payload.CollectorPayload] {
        override def payload(b: AdapterFailures) = b.payload.some
        override def recover(b: AdapterFailures)(config: List[StepConfig]) = {
          def update(b: AdapterFailures)(p: Payload.CollectorPayload) =
            b.copy(payload = p)

          step(config, b.payload)(new Modify[Payload.CollectorPayload](_)).map(update(b))
        }
      }

    implicit val trackerProtocolViolationsRecovery: Recoverable[TrackerProtocolViolations, Payload.CollectorPayload] =
      new Recoverable[TrackerProtocolViolations, Payload.CollectorPayload] {
        override def payload(b: TrackerProtocolViolations) = b.payload.some
        override def recover(b: TrackerProtocolViolations)(config: List[StepConfig]) = {
          def update(b: TrackerProtocolViolations)(p: Payload.CollectorPayload) = b.copy(payload = p)
          step(config, b.payload)(new Modify[Payload.CollectorPayload](_)).map(update(b))
        }
      }

    implicit val schemaViolationsRecovery: Recoverable[SchemaViolations, Payload.EnrichmentPayload] =
      new Recoverable[SchemaViolations, Payload.EnrichmentPayload] {
        override def payload(b: SchemaViolations) = b.payload.some
        override def recover(b: SchemaViolations)(config: List[StepConfig]) = {
          def update(b: SchemaViolations)(p: Payload.EnrichmentPayload) =
            b.copy(payload = p)
          step(config, b.payload)(new Modify[Payload.EnrichmentPayload](_)).map(update(b))
        }
      }

    implicit val enrichmentFailuresRecovery: Recoverable[EnrichmentFailures, Payload.EnrichmentPayload] =
      new Recoverable[EnrichmentFailures, Payload.EnrichmentPayload] {
        override def payload(b: EnrichmentFailures) = b.payload.some
        override def recover(b: EnrichmentFailures)(config: List[StepConfig]) = {
          def update(b: EnrichmentFailures)(p: Payload.EnrichmentPayload) =
            b.copy(payload = p)
          step(config, b.payload)(new Modify[Payload.EnrichmentPayload](_)).map(update(b))
        }
      }

    implicit val recoveryErrorRecovery: Recoverable[BadRow.RecoveryError, Payload] =
      new Recoverable[BadRow.RecoveryError, Payload] {
        override def payload(b: BadRow.RecoveryError): Option[Payload] = ???
        override def recover(b: BadRow.RecoveryError)(config: List[StepConfig]): Recovering[BadRow.RecoveryError] =
          (b.payload match {
            case f: AdapterFailures =>
              adapterFailuresRecovery.recover(f)(config)
            case f: SizeViolation => sizeViolationRecovery.recover(f)(config)
            case f: EnrichmentFailures =>
              enrichmentFailuresRecovery.recover(f)(config)
            case f: TrackerProtocolViolations =>
              trackerProtocolViolationsRecovery.recover(f)(config)
            case _ => Left(UnrecoverableBadRowType(b.payload))
          }).map(recovered => b.copy(payload = recovered))
      }

    private[this] def unrecoverable[A <: BadRow, B <: Payload] =
      new Recoverable[A, B] {
        override def payload(a: A) = None
        override def recover(a: A)(c: List[StepConfig]) =
          Left(UnrecoverableBadRowType(a))
      }
  }
}
