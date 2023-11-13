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

import java.util.Base64
import java.nio.charset.StandardCharsets.UTF_8
import cats.syntax.either._
import domain.{Base64Failure, Recovering}

object base64 {

  /** Decode a base64-encoded string.
    * @param encoded
    *   base64-encoded string
    * @return
    *   either a successfully decoded string or a failure
    */
  def decodeBytes(encoded: String): Recovering[Array[Byte]] = decode(encoded, identity)

  def decode[A](encoded: String, fn: Array[Byte] => A = byteToString): Recovering[A] =
    Either
      .catchNonFatal(Base64.getDecoder.decode(encoded.replaceAll("-","+").replaceAll("_","/").getBytes("UTF-8")))
      .map(fn)
      .leftMap(e => Base64Failure(encoded, s"Data is not properly base64-encoded: ${e.getMessage}"))

  /** Decode a base64-encoded string.
    * @param encoded
    *   base64-encoded string
    * @return
    *   either a successfully decoded string or a failure
    */
  def encode(str: String): Recovering[String] = encode(str.getBytes(UTF_8))
  def encode(str: Array[Byte]): Recovering[String] =
    Either
      .catchNonFatal(Base64.getEncoder.encodeToString(str))
      .leftMap(e => Base64Failure(byteToString(str), s"Unable to base64-encode string: ${e.getMessage}"))

  val byteToString = (arr: Array[Byte]) => new String(arr, UTF_8)
}
