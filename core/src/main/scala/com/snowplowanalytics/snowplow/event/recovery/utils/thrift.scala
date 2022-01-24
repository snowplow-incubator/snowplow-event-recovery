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
