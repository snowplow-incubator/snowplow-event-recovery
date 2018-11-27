package com.snowplowanalytics.snowplow
package event.recovery

import java.util.Base64

import org.apache.thrift.TDeserializer

import CollectorPayload.thrift.model1.CollectorPayload
import model._

object utils {
  val thriftDeser: String => CollectorPayload = { s =>
    val decoded = Base64.getDecoder.decode(s)
    val thriftDeserializer = new TDeserializer
    val payload = new CollectorPayload
    thriftDeserializer.deserialize(payload, decoded)
    payload
  }
}
