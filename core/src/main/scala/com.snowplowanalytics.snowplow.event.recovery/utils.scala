package com.snowplowanalytics.snowplow
package event.recovery

import java.util.Base64

import org.apache.thrift.{TDeserializer, TSerializer}

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

  val thriftSer: CollectorPayload => String = { cp =>
    val thriftSerializer = new TSerializer
    val bytes = thriftSerializer.serialize(cp)
    Base64.getEncoder.encodeToString(bytes)
  }
}
