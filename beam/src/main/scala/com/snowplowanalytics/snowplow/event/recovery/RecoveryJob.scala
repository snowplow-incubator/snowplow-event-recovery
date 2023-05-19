/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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

import collection.JavaConverters._
import com.spotify.scio.{ScioContext, ScioMetrics}
import com.spotify.scio.coders.Coder
import com.spotify.scio.values.SCollection
import com.spotify.scio.pubsub._
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage
import com.snowplowanalytics.snowplow.badrows._
import config._
import util.paths._
import domain._

object RecoveryJob {

  /** Beam job running the event recovery process on GCP. It will:
    *   - read the input data from a GCS location
    *   - decode the bad row jsons
    *   - mutate the collector payloads contained in the concerned bad rows according to the specified recovery
    *     scenarios
    *   - write out the fixed payloads to PubSub
    *   - write failed recoveries to an GCS location
    *   - write unrecoverable bad rows to an GCS location
    * @param sc
    *   ScioContext necessary to interact with the SCIO/Beam API
    * @param input
    *   GCS location to read the bad rows from
    * @param output
    *   PubSub stream to write the fixed collector payloads to
    * @param failedOutput
    *   GCS location to write the failed recoveries
    * @param unrecoverableOutput
    *   GCS location to write unrecoverble bad rows
    * @param cfg
    *   configuration object containing mappings and recovery flow configurations
    */
  def run(
    sc: ScioContext,
    input: String,
    output: String,
    failedOutput: String,
    unrecoverableOutput: String,
    cfg: Config
  ): Unit =
    sc.withName(s"read-input-bad-rows")
      .textFile(input)
      .withName("recover-bad-rows")
      .map(execute(cfg))
      .withName("partition-by-result")
      .partitionByKey(Result.partitions)(Result.byKey)
      .foreach { case (k, v) =>
        sink(output, failedOutput, unrecoverableOutput)(k, v)
      }

  private[this] def sink(
    output: String,
    failedOutput: String,
    unrecoverableOutput: String
  )(
    k: Result,
    v: SCollection[Either[RecoveryError, Array[Byte]]]
  ) = k match {
    case Recovered =>
      v.withName("count-recovered")
        .map { r =>
          count("recovered")
          r.right.get
        }
        .withName("to-pubsub-message")
        .map { bytes =>
          new PubsubMessage(bytes, Map("recovered" -> output).asJava)
        }
        .saveAsCustomOutput("sink-recovered", PubsubIO.writeMessages().to(output))
    case Failed =>
      v.withName("count-failed")
        .map { r =>
          count("failed")
          r.left.get.json
        }
        .withName("sink-failed")
        .saveAsTextFile(
          path(failedOutput, Schemas.RecoveryError)
        )
    case Unrecoverable =>
      v.withName("count-unrecoverable")
        .map { r =>
          count("unrecoverable")
          r.left.get.json
        }
        .withName("sink-unrecoverable")
        .saveAsTextFile(
          path(unrecoverableOutput, Schemas.RecoveryError)
        )
  }

  private[this] val count = (suffix: String) => ScioMetrics.counter("snowplow", s"bad_rows_$suffix")

  implicit val payloadCodec: Coder[Payload] = Coder.kryo[Payload]
  implicit val recoveryErrorCodec: Coder[RecoveryError] =
    Coder.kryo[RecoveryError]
}
