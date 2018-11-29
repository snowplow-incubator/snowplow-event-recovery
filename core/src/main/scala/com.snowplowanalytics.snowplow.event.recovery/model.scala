package com.snowplowanalytics.snowplow.event.recovery

object model {
  final case class Error(level: String, message: String)
  final case class BadRow(line: String, errors: List[Error], failure_tstamp: String)
}
