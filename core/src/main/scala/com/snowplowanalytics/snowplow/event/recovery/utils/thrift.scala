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

import org.apache.thrift.{TDeserializer, TSerializer}
import CollectorPayload.thrift.model1.CollectorPayload
import cats.implicits._
import domain._

object thrift {

  /** Serialize a CollectorPayload into a byte array and base64-encode it. */
  val serialize: CollectorPayload => Recovering[String] = serializeNoB64(_).flatMap(base64.encode)

  /** Deserialize a String into a CollectorPayload after having base64-decoded it. */
  val deserialize: String => Recovering[CollectorPayload] = base64.decodeBytes(_).flatMap(deser)

  /** Serialize a CollectorPayload into a byte array. */
  val serializeNoB64: CollectorPayload => Recovering[Array[Byte]] = cp =>
    Either.catchNonFatal(new TSerializer().serialize(cp)).leftMap(err => ThriftFailure(err.getMessage))

  /** Deserialize a String into a CollectorPayload. */
  val deserializeNoB64: String => Recovering[CollectorPayload] = str => deser(str.getBytes)

  /** Deserialize a String into a CollectorPayload. */
  val deser: Array[Byte] => Recovering[CollectorPayload] = str => {
    val payload = new CollectorPayload
    Either
      .catchNonFatal(new TDeserializer().deserialize(payload, str))
      .leftMap(err => ThriftFailure(err.getMessage))
      .map(_ => payload)
  }

}
