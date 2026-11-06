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
package util

import cats.syntax.compose._
import cats.instances.function._
import com.snowplowanalytics.iglu.core.SchemaKey

object paths {

  /** A helper function for trimming directory paths.
    */
  val trimDir = (dir: String) => dir.filterNot(_ == '*').trim.stripSuffix("/")

  /** A helper function structuring subpaths
    */
  val append = (str: String) => trimDir >>> (_ + "/" + str)

  /** A helper function for setting up unrecovered path
    */
  val failedPath = append("unrecovered")

  /** A helper function for setting up unrecoverable path
    */
  val unrecoverablePath = append("unrecoverable")

  /** Relative bucket path for given output bucket
    * @param output
    *   prefix for where to output
    * @param schema
    *   unrecovered bad row schema
    */
  val path = (output: String, schema: SchemaKey) => s"$output/${schema.vendor}.${schema.name}"

}
