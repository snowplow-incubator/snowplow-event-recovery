/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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
