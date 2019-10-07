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
import org.apache.thrift.{TDeserializer, TSerializer}
import CollectorPayload.thrift.model1.CollectorPayload
import cats.implicits._
import domain._
import java.nio.charset.StandardCharsets

object thrift {

  /** Deserialize a String into a CollectorPayload after having base64-decoded it. */
  val deserialize: String => CollectorPayload = { s =>
    val decoded = Base64.getDecoder.decode(s.getBytes(StandardCharsets.UTF_8))
    val thriftDeserializer = new TDeserializer
    val payload = new CollectorPayload
    thriftDeserializer.deserialize(payload, decoded)
    payload
  }

  /** Serialize a CollectorPayload into a byte array and base64-encode it. */
  val serialize: CollectorPayload => String = { cp =>
    val thriftSerializer = new TSerializer
    val bytes = thriftSerializer.serialize(cp)
    Base64.getEncoder.encodeToString(bytes)
  }

  /** Serialize CollectorPayload into a byte array and base64-encode it. */
  val thrift: CollectorPayload => Either[RecoveryStatus, String] =
    c =>
      Either
        .catchNonFatal(serialize(c))
        .leftMap(err => ThriftFailure(err.getMessage))

}
