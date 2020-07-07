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
import atto._, Atto._
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.badrows.BadRow._
import com.snowplowanalytics.snowplow.badrows.Payload
import config._
import domain._
import steps._
import util.thrift
import inspectable.Inspectable._
import util.payload._

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
        case a: AdapterFailures => adapterFailuresRecovery.recover(a)
        case a: TrackerProtocolViolations =>
          trackerProtocolViolationsRecovery.recover(a)
        case a: SchemaViolations   => schemaViolationsRecovery.recover(a)
        case a: EnrichmentFailures => enrichmentFailuresRecovery.recover(a)
        case a: CPFormatViolation  => cpFormatViolationRecovery.recover(a)
        case a: BadRow => { _ =>
          Left(UnrecoverableBadRowType(a))
        }
      }

    implicit val sizeViolationRecovery: Recoverable[SizeViolation, Payload.RawPayload] =
      unrecoverable
    implicit val adapterFailuresRecovery: Recoverable[AdapterFailures, Payload.CollectorPayload] =
      recoverable(_.payload)
    implicit val trackerProtocolViolationsRecovery: Recoverable[TrackerProtocolViolations, Payload.CollectorPayload] =
      recoverable(_.payload)
    implicit val schemaViolationsRecovery: Recoverable[SchemaViolations, Payload.EnrichmentPayload] =
      recoverable(_.payload)
    implicit val enrichmentFailuresRecovery: Recoverable[EnrichmentFailures, Payload.EnrichmentPayload] =
      recoverable(_.payload)

    /**
      * Fixes bad rows originating in
      * https://github.com/snowplow/enrich/blob/4732d4d8b2e0d75c2b88530a60913542a3bd49c3/modules/common/src/main/scala/com.snowplowanalytics.snowplow.enrich/common/loaders/Loader.scala#L60
      */
    implicit val cpFormatViolationRecovery: Recoverable[CPFormatViolation, Payload.CollectorPayload] =
      new Recoverable[CPFormatViolation, Payload.CollectorPayload] {
        override def recover(b: CPFormatViolation)(config: List[StepConfig]) =
          for {
            msg       <- querystring(b)
            params    <- queryParams(msg).map(_.mapValues(filterInvalid))
            payload   <- mkPayload(b.payload, params)
            recovered <- step(config, payload)(new Modify[Payload.CollectorPayload](_))
          } yield recovered

        private[this] def querystring(b: CPFormatViolation) =
          Option(b.payload.line).toRight(unexpectedFormat("empty")).flatMap(thrift.deserialize).map(_.querystring)

        private[this] def queryParams(message: String) = {
          val parser = ((stringOf(noneOf("&=")) <~ char('=')) ~ stringOf(notChar('&'))).sepBy(char('&'))
          val parsed = parser.parse(message).map(_.toMap).done
          (parsed.either match {
            case Right(r) if r.isEmpty => Left("empty")
            case Right(r)              => Right(r)
            case Left(l)               => Left(l)
          }).leftMap(err => unexpectedFormat(message, err.some))
        }

        private[this] def mkPayload(p: Payload.RawPayload, params: Map[String, String]) =
          thrift.deserialize(p.line).flatMap(cocoerce).map(_.copy(querystring = toNVP(params)))

        private[this] def toNVP(params: Map[String, String]) =
          params.map { case (k, v) => NVP(k, Option(v)) }.toList

        // TODO remove name of placeholder or leave it?
        private[this] def filterInvalid(s: String) = s.filterNot(Seq('[',']','{','}').contains(_))

        private[this] def unexpectedFormat(data: String, error: Option[String] = None) =
          UnexpectedFieldFormat(data, "querystring", "k1=v1&k2=v2".some, error)
      }

    implicit val recoveryErrorRecovery: Recoverable[BadRow.RecoveryError, Payload] =
      new Recoverable[BadRow.RecoveryError, Payload] {
        override def recover(b: BadRow.RecoveryError)(config: List[StepConfig]): Recovering[Payload] =
          b.payload match {
            case f: AdapterFailures =>
              adapterFailuresRecovery.recover(f)(config)
            case f: SizeViolation => sizeViolationRecovery.recover(f)(config)
            case f: EnrichmentFailures =>
              enrichmentFailuresRecovery.recover(f)(config)
            case f: TrackerProtocolViolations =>
              trackerProtocolViolationsRecovery.recover(f)(config)
            case _ => Left(UnrecoverableBadRowType(b.payload))
          }
      }

    private[this] def recoverable[A <: BadRow, B <: Payload: Inspectable: io.circe.Encoder: io.circe.Decoder](
      payload: A => B
    ) =
      new Recoverable[A, B] {
        override def recover(b: A)(config: List[StepConfig]) =
          step(config, payload(b))(new Modify[B](_))
      }

    private[this] def unrecoverable[A <: BadRow, B <: Payload] =
      new Recoverable[A, B] {
        override def recover(a: A)(c: List[StepConfig]) =
          Left(UnrecoverableBadRowType(a))
      }
  }
}
