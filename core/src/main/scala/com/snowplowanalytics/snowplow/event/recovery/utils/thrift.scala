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

import org.apache.thrift.{TDeserializer, TSerializer}
import CollectorPayload.thrift.model1.CollectorPayload
import cats.implicits._
import domain._

object thrift {

  /** Serialize a CollectorPayload into a byte array and base64-encode it. */
  val serialize: CollectorPayload => Recovering[String] = cp =>
    Either
      .catchNonFatal(new TSerializer().serialize(cp))
      .leftMap(err => ThriftFailure(err.getMessage))
      .flatMap(base64.encode)

  /** Deserialize a String into a CollectorPayload after having base64-decoded it. */
  val deserialize: String => Recovering[CollectorPayload] = { s =>
    base64.decodeBytes(s).flatMap { decoded =>
      val payload = new CollectorPayload
      Either
        .catchNonFatal(new TDeserializer().deserialize(payload, decoded))
        .leftMap(err => ThriftFailure(err.getMessage))
        .map(_ => payload)
    }
  }

}
