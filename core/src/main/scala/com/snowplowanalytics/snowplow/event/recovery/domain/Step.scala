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
package com.snowplowanalytics.snowplow
package event.recovery
package domain.steps

import io.circe.{Decoder, Encoder}
import badrows._

import config._
import domain._
import inspectable.Inspectable._
import inspectable.Inspectable.ops._

/** A definition of a single recovery process step. Many subsequent steps form a recovery flow. The flows are applied to
  * BadRows in recovery process.
  * @param a
  *   type of Payload given Step operates on
  */
sealed trait Step[A <: Payload] {

  /** Defines a process of application of the `Step` to `A`
    */
  val recover: A => Recovering[A]
}

/** A pass-through step used for when no modification is required and bad row was most likely caused by downstream
  * failure.
  */
class PassThrough[A <: Payload] extends Step[A] {
  val recover: A => Recovering[A] = a => Right(a)
}

class Modify[A <: Payload: Inspectable: Encoder: Decoder](config: StepConfig) extends Step[A] {
  val recover: A => Recovering[A] = a =>
    config match {
      case Replacement(_, context, matcher, replacement) =>
        a.replace(context, matcher, replacement)
      case Removal(_, context, matcher) =>
        a.remove(context, matcher)
      case Casting(_, context, from, to) =>
        a.cast(context, from, to)
      case Addition(_, context, value) =>
        a.add(context, value)
      case step =>
        Left(InvalidStep(a, step.getClass.getCanonicalName))
    }
}
