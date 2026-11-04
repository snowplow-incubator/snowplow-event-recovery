/*
 * Copyright (c) 2026 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.event.recovery

import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TSimpleJSONProtocol

/** Recovered events as readable JSON, for writing them to files instead of Kinesis */
object RecoveredJson {

  /** One recovered collector payload, as Thrift bytes, to a single-line JSON object keyed by the Thrift field names
    * (path, querystring, body, headers, ...). Fields the payload does not set are left out.
    *
    * The bytes come from the recovery step's own serialisation, so a payload that cannot be read is a bug and fails the
    * job rather than being skipped.
    */
  def fromThrift(bytes: Array[Byte]): String =
    util
      .thrift
      .deser(bytes)
      .fold(
        err => throw new IllegalStateException(s"Cannot read a recovered collector payload: ${err.message}"),
        payload => new TSerializer(new TSimpleJSONProtocol.Factory()).toString(payload)
      )
}
