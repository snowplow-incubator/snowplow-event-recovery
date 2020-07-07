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

import io.circe._
import io.circe.generic.semiauto._
import com.snowplowanalytics.snowplow.badrows._
import config._
import json._

/**
  * Failure statuses for erroneous recovery cases.
  */
sealed trait RecoveryStatus {
  def message: String
  def data: Any
  def withRow(r: String, configName: Option[String] = None) = RecoveryError(this, r, configName)
}

object RecoveryStatus {
  implicit val recoveryStatusEncoder: Encoder[RecoveryStatus] =
    Encoder.instance {
      case s: InvalidStep           => InvalidStep.encoder(s)
      case s: InvalidJsonFormat     => InvalidJsonFormat.encoder(s)
      case s: InvalidDataFormat     => InvalidDataFormat.encoder(s)
      case s: UnexpectedFieldFormat => UnexpectedFieldFormat.encoder(s)
      case s: FailedToMatchConfiguration =>
        FailedToMatchConfiguration.encoder(s)
      case s: UncoerciblePayload   => UncoerciblePayload.encoder(s)
      case s: UncocoerciblePayload => UncocoerciblePayload.encoder(s)
      case s: ThriftFailure        => ThriftFailure.encoder(s)
      case s: FieldDoesNotExist[_] => FieldDoesNotExist.encoder.apply(s)
      case s: UnrecoverableBadRowType[_] =>
        UnrecoverableBadRowType.encoder.apply(s)
      case s: RecoveryFailed[_]         => RecoveryFailed.encoder.apply(s)
      case s: ProcessingFailure[_]      => ProcessingFailure.encoder.apply(s)
      case s: UnsupportedStep[_]        => UnsupportedStep.encoder.apply(s)
      case s: RecoveryThresholdExceeded => RecoveryThresholdExceeded.encoder.apply(s)
      case s: ReplacementFailure        => ReplacementFailure.encoder(s)
      case s: Base64Failure             => Base64Failure.encoder(s)
      case s: UrlCodecFailure           => UrlCodecFailure.encoder(s)
      case s: CastFailure               => CastFailure.encoder(s)
    }
}

/**
  * Signifies that a step configuration to be applied is not supported, does not exist.
  */
case class InvalidStep(data: Payload, stepName: String)(implicit val encoder: Encoder[Payload]) extends RecoveryStatus {
  def message =
    s"Failed to recover $data - recovery step $stepName does not exist"
}

object InvalidStep {
  implicit val encoder: Encoder[InvalidStep] = deriveEncoder
}

/**
  * Signifies string to JSON parsing case.
  * Supplied string does not properly resolve to a valid JSON string.
  */
case class InvalidJsonFormat(data: String) extends RecoveryStatus {
  def message = s"Failed to parse JSON string from $data"
}

object InvalidJsonFormat {
  implicit val encoder: Encoder[InvalidJsonFormat] = deriveEncoder
}

/**
  * Signifies a generic field format exception.
  * Data supplied at runtime did not comply expectation.
  */
case class UnexpectedFieldFormat(
  data: String,
  field: String,
  expected: Option[String] = None,
  error: Option[String]    = None
) extends RecoveryStatus {
  def message              = s"Supplied data: $data in $field, does not comply expected data format${fmt(expected)}.${raised(error)}"
  private[this] val fmt    = (expected: Option[String]) => expected.map(v => s" ($v)").getOrElse("")
  private[this] val raised = (error: Option[String]) => error.map(v => s" Raised error: $v").getOrElse("")
}

object UnexpectedFieldFormat {
  implicit val encoder: Encoder[UnexpectedFieldFormat] = deriveEncoder
}

/**
  * Signifies Base64 encoding/decoding failure.
  */
case class Base64Failure(data: String, error: String) extends RecoveryStatus {
  def message = s"Failed to properly transform to/from Base64 string: $data. $error"
}

object Base64Failure {
  implicit val encoder: Encoder[Base64Failure] = deriveEncoder
}

/**
  * Signifies URL encoding/decoding failure.
  */
case class UrlCodecFailure(data: String, error: String) extends RecoveryStatus {
  def message = s"Failed to properly transform to/from URL-encoded string: $data. $error"
}

object UrlCodecFailure {
  implicit val encoder: Encoder[UrlCodecFailure] = deriveEncoder
}

/**
  * Signifies SelfDescribingData structural error.
  * Supplied JSON does not properly resolve to a known data structure.
  */
case class InvalidDataFormat(data: Option[Json], error: String) extends RecoveryStatus {
  def message = s"""Failed to create object from JSON string ${data.map(v => s"in $v.").getOrElse(".")} $error"""
}

object InvalidDataFormat {
  implicit val encoder: Encoder[InvalidDataFormat] = deriveEncoder
}

/**
  * Signifies that there is no configuration for given Bad Row format.
  * No mapping between schema key and recovery configuration was found.
  */
case class FailedToMatchConfiguration(data: String) extends RecoveryStatus {
  def message =
    s"Failed to load recovery configuration for $data. No schema-configuration mapping found."
}

object FailedToMatchConfiguration {
  implicit val encoder: Encoder[FailedToMatchConfiguration] = deriveEncoder
}

/**
  * Signifies that a field that was supposed to be replaced does not exist in Bad Row.
  */
case class FieldDoesNotExist[+A <: Payload: Encoder](data: A, field: String) extends RecoveryStatus {
  def message = s"Failed to modify $field. Field does not exist in $data"
}

object FieldDoesNotExist {
  implicit def encoder[A <: Payload](implicit encoder: Encoder[A]): Encoder[FieldDoesNotExist[A]] =
    Encoder.forProduct2[FieldDoesNotExist[A], Json, String]("data", "reason") {
      case m @ FieldDoesNotExist(d, _) => (encoder(d), m.message)
    }
}

/**
  * Signifies that the supplied bad row type is not known to be recoverable.
  */
case class UnrecoverableBadRowType[+A <: BadRow](data: A) extends RecoveryStatus {
  def message =
    s"Failed to recover bad row of type ${data.getClass.getCanonicalName}."
}

object UnrecoverableBadRowType {
  implicit def encoder[A <: BadRow](implicit encoder: Encoder[A]): Encoder[UnrecoverableBadRowType[A]] =
    Encoder.forProduct2[UnrecoverableBadRowType[A], Json, String](
      "data",
      "reason"
    ) {
      case m @ UnrecoverableBadRowType(d) => (encoder(d), m.message)
    }
}

/**
  * Signifies that the supplied bad row type is not known to be recoverable.
  */
case class RecoveryThresholdExceeded(data: BadRow.RecoveryError) extends RecoveryStatus {
  def message =
    s"Maximum number of recoveries (${data.recoveries}) exceeded of type ${data.payload.getClass.getCanonicalName}."
}

object RecoveryThresholdExceeded {
  implicit val encoder: Encoder[RecoveryThresholdExceeded] = deriveEncoder
}

/**
  * Signifies that recovery process' step application has failed and is unable to proceed.
  */
case class RecoveryFailed[+A <: BadRow: Encoder](data: A, steps: List[StepConfig]) extends RecoveryStatus {
  def message =
    s"Failed to apply step $steps for bad row $data."
}

object RecoveryFailed {
  implicit def encoder[A <: BadRow](implicit encoder: Encoder[A]): Encoder[RecoveryFailed[A]] =
    Encoder.forProduct2[RecoveryFailed[A], Json, String]("data", "reason") {
      case m @ RecoveryFailed(d, _) => (encoder(d), m.message)
    }
}

/**
  * Signifies an unsupported step is requested.
  */
case class UnsupportedStep[+A <: Payload: Encoder](data: A, step: String) extends RecoveryStatus {
  def message =
    s"Step $step unsupported for ${data.getClass.getCanonicalName}"
}

object UnsupportedStep {
  implicit def encoder[A <: Payload](implicit encoder: Encoder[A]): Encoder[UnsupportedStep[A]] =
    Encoder.forProduct2[UnsupportedStep[A], Json, String]("data", "reason") {
      case m @ UnsupportedStep(d, _) => (encoder(d), m.message)
    }
}

case class ReplacementFailure(
  data: String,
  matcher: Option[String],
  value: String
) extends RecoveryStatus {
  def message = s"""Failed to replace ${matcher.getOrElse("full field contents")} with $value in $data"""
}

object ReplacementFailure {
  implicit val encoder: Encoder[ReplacementFailure] = deriveEncoder
}

case class CastFailure(
  data: String,
  from: CastType,
  to: CastType
) extends RecoveryStatus {
  def message = s"Failed to cast $data from $from to $to"
}

object CastFailure {
  implicit val encoder: Encoder[CastFailure] = deriveEncoder
}

/**
  * Signifies a step has failed during processing.
  */
case class ProcessingFailure[+A <: Payload: Encoder](data: A, reason: String) extends RecoveryStatus {
  def message = s"A processing failure has occured because of $reason"
}

object ProcessingFailure {
  implicit def encoder[A <: Payload](implicit encoder: Encoder[A]): Encoder[ProcessingFailure[A]] =
    Encoder.forProduct2[ProcessingFailure[A], Json, String]("data", "reason") {
      case m @ ProcessingFailure(d, _) => (encoder(d), m.message)
    }
}

case class UncoerciblePayload(data: Payload) extends RecoveryStatus {
  def message = s"Unable to coerce payload of type ${data.getClass.getCanonicalName} to CollectorPayload"
}

object UncoerciblePayload {
  implicit val encoder: Encoder[UncoerciblePayload] = deriveEncoder
}

case class UncocoerciblePayload(data: String) extends RecoveryStatus {
  def message = s"Unable to cocoerce payload of type CollectorPayload to Payload.CollectorPayload"
}

object UncocoerciblePayload {
  implicit val encoder: Encoder[UncocoerciblePayload] = deriveEncoder
}

case class ThriftFailure(data: String) extends RecoveryStatus {
  def message = s"Unable to serialize data to thrift: $data"
}

object ThriftFailure {
  implicit val encoder: Encoder[ThriftFailure] = deriveEncoder
}
