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
import util.thrift
import inspectable.Inspectable._
import util.payload._
import util.querystring._

object recoverable {

  /**
    * A typeclass that allows applying recovery scenarios.
    */
  trait Recoverable[A <: BadRow, B <: Payload] { self =>

    /**
      * Apply a recovery configuration flow to given `a`.
      */
    def recover(a: A)(config: List[StepConfig]): Either[RecoveryStatus, B]
  }

  object Recoverable {
    def apply[A <: BadRow, B <: Payload](implicit r: Recoverable[A, B]): Recoverable[A, B] = r

    final def instance[A <: BadRow, B <: Payload](
      r: A => List[StepConfig] => Recovering[B]
    ): Recoverable[A, B] =
      new Recoverable[A, B] {
        override def recover(a: A)(config: List[StepConfig]): Recovering[B] = r(a)(config)
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
      }
    }

    implicit val badRowRecovery: Recoverable[BadRow, Payload] =
      Recoverable.instance[BadRow, Payload] {
        case a: AdapterFailures           => adapterFailuresRecovery.recover(a)
        case a: CPFormatViolation         => cpFormatViolationRecovery.recover(a)
        case a: EnrichmentFailures        => enrichmentFailuresRecovery.recover(a)
        case a: SchemaViolations          => schemaViolationsRecovery.recover(a)
        case a: SizeViolation             => sizeViolationRecovery.recover(a)
        case a: TrackerProtocolViolations => trackerProtocolViolationsRecovery.recover(a)
        case a: BadRow.RecoveryError      => recoveryErrorRecovery.recover(a)
        case a: BadRow => { _ =>
          Left(UnrecoverableBadRowType(a))
        }
      }

    implicit val adapterFailuresRecovery: Recoverable[AdapterFailures, Payload.CollectorPayload] =
      recoverableInstance(_.payload)
    implicit val enrichmentFailuresRecovery: Recoverable[EnrichmentFailures, Payload.EnrichmentPayload] =
      recoverableInstance(_.payload)
    implicit val schemaViolationsRecovery: Recoverable[SchemaViolations, Payload.EnrichmentPayload] =
      recoverableInstance(_.payload)
    implicit val sizeViolationRecovery: Recoverable[SizeViolation, Payload.RawPayload] =
      unrecoverableInstance
    implicit val trackerProtocolViolationsRecovery: Recoverable[TrackerProtocolViolations, Payload.CollectorPayload] =
      recoverableInstance(_.payload)

    implicit val cpFormatViolationRecovery: Recoverable[CPFormatViolation, Payload.CollectorPayload] =
      new Recoverable[CPFormatViolation, Payload.CollectorPayload] {
        override def recover(b: CPFormatViolation)(config: List[StepConfig]) =
          for {
            payload   <- recoverQuery(b)
            recovered <- step(config, payload)(new Modify[Payload.CollectorPayload](_))
          } yield recovered

        private[this] def recoverQuery(b: CPFormatViolation) =
          for {
            msg       <- querystring(b)
            params    <- params(msg).map(_.mapValues(clean))
            payload   <- mkPayload(b.payload, params)
          } yield payload

        private[this] def querystring(b: CPFormatViolation) =
          orBadRow(b.payload.line, "nullified payload line".some)
            .flatMap(thrift.deserialize)
            .flatMap(v => orBadRow(v.querystring, "nullified querystring".some))

        private[this] def mkPayload(p: Payload.RawPayload, params: Map[String, String]) =
          thrift.deserialize(p.line).flatMap(cocoerce).map(_.copy(querystring = toNVP(params)))
      }

    implicit val recoveryErrorRecovery: Recoverable[BadRow.RecoveryError, Payload] =
      new Recoverable[BadRow.RecoveryError, Payload] {
        override def recover(b: BadRow.RecoveryError)(config: List[StepConfig]): Recovering[Payload] =
          b.payload match {
            case a: AdapterFailures           => adapterFailuresRecovery.recover(a)(config)
            case a: CPFormatViolation         => cpFormatViolationRecovery.recover(a)(config)
            case a: EnrichmentFailures        => enrichmentFailuresRecovery.recover(a)(config)
            case a: SchemaViolations          => schemaViolationsRecovery.recover(a)(config)
            case a: SizeViolation             => sizeViolationRecovery.recover(a)(config)
            case a: TrackerProtocolViolations => trackerProtocolViolationsRecovery.recover(a)(config)
            case _                            => Left(UnrecoverableBadRowType(b.payload))
          }
      }

    private[this] def recoverableInstance[A <: BadRow, B <: Payload: Inspectable: io.circe.Encoder: io.circe.Decoder](
      payload: A => B
    ) =
      new Recoverable[A, B] {
        override def recover(b: A)(config: List[StepConfig]) =
          step(config, payload(b))(new Modify[B](_))
      }

    private[this] def unrecoverableInstance[A <: BadRow, B <: Payload] =
      new Recoverable[A, B] {
        override def recover(a: A)(c: List[StepConfig]) =
          Left(UnrecoverableBadRowType(a))
      }
  }
}
