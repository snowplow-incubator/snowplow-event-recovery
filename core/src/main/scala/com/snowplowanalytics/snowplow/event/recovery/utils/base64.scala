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
      .catchNonFatal(Base64.getDecoder.decode(encoded.replaceAll("-", "+").replaceAll("_", "/").getBytes("UTF-8")))
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
