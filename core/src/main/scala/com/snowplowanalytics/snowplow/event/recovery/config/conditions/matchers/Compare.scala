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

package com.snowplowanalytics.snowplow.event.recovery.config.conditions

import io.circe.Json

/**
  *  A matcher allowing comparing data against Value.
  */
case class Compare(value: Json) extends Matcher {
  def checks(j: Json) =
    j.fold(
      value.isNull,
      b => value.asBoolean.map(_ == b).getOrElse(false),
      n => value.asNumber.map(_ == n).getOrElse(false),
      s => value.asString.map(_ == s).getOrElse(false),
      a => value.asArray.map(_ == a).getOrElse(false),
      o => value.asObject.map(_ == o).getOrElse(false)
    )
}
