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

package com.snowplowanalytics.snowplow.event.recovery.config

import conditions.{Add, Cast, Remove, Replace}
import io.circe.Json

sealed trait StepConfig

case class Replacement(
  op: Replace.type,
  path: Path,
  `match`: Option[Regexp],
  value: Json
) extends StepConfig

case class Removal(
  op: Remove.type,
  path: Path,
  `match`: Option[Regexp]
) extends StepConfig

case class Casting(
  op: Cast.type,
  path: Path,
  from: CastType,
  to: CastType
) extends StepConfig

case class Addition(
  op: Add.type,
  path: Path,
  value: Json
) extends StepConfig
