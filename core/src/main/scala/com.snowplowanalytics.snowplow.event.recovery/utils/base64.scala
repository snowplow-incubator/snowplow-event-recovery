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
package util

import java.util.Base64
import cats.syntax.either._

object base64 {

  /**
    * Decode a base64-encoded string.
    * @param encoded base64-encoded string
    * @return either a successfully decoded string or a failure
    */
  def decode(encoded: String): Either[String, String] =
    Either
      .catchNonFatal(new String(Base64.getDecoder.decode(encoded)))
      .leftMap(e => s"Configuration is not properly base64-encoded: ${e.getMessage}")

  /**
    * Decode a base64-encoded string.
    * @param encoded base64-encoded string
    * @return either a successfully decoded string or a failure
    */
  def encode(str: String): Either[String, String] =
    Either
      .catchNonFatal(Base64.getEncoder.encodeToString(str.getBytes))
      .leftMap(e => s"Unable to base64-encode string: ${e.getMessage}")

}
