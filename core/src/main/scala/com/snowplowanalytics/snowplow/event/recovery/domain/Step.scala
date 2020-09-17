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
package com.snowplowanalytics.snowplow
package event.recovery
package domain.steps

import io.circe.{Decoder, Encoder}
import badrows._

import config._
import domain._
import inspectable.Inspectable._
import inspectable.Inspectable.ops._

/**
  * A definition of a single recovery process step.
  * Many subsequent steps form a recovery flow.
  * The flows are applied to BadRows in recovery process.
  * @param a type of Payload given Step operates on
  */
sealed trait Step[A <: Payload] {

  /**
    * Defines a process of application of the `Step` to `A`
    */
  val recover: A => Recovering[A]
}

/**
  * A pass-through step used for when no modification is required
  * and bad row was most likely caused by downstream failure.
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
