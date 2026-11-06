/*
 * Copyright (c) 2026-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
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
