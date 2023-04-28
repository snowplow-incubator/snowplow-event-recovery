package com.snowplowanalytics.snowplow.event.recovery

import org.apache.flink.connector.kinesis.sink.PartitionKeyGenerator

final class Partitioner extends PartitionKeyGenerator[Array[Byte]] {

  override def apply(record: Array[Byte]): String =
    java.util.Arrays.hashCode(record).toString()

}
