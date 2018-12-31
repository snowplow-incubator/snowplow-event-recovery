/*
 * Copyright (c) 2018-2018 Snowplow Analytics Ltd. All rights reserved.
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

import cats.data.Validated.{Invalid, Valid}
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.option._
import com.spotify.scio.{ContextAndArgs, ScioContext, ScioMetrics}
import io.circe.generic.auto._
import io.circe.parser.decode
import org.apache.thrift.TSerializer

import model._

object Main {
  /** Entry point for the Beam recovery job */
  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, args) = ContextAndArgs(cmdlineArgs)
    val input = args.optional("input").toValidNel("Input GCS path is mandatory")
    val output = args.optional("output").toValidNel("Output PubSub topic is mandatory")
    val recoveryScenarios = (for {
      config <- args.optional("config").toRight("Base64-encoded configuration with schema " +
        "com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0 is mandatory")
      decoded <- utils.decodeBase64(config)
      _ <- utils.validateConfiguration(decoded)
      rss <- utils.parseRecoveryScenarios(decoded)
    } yield rss).toValidatedNel
    (input, output, recoveryScenarios).tupled match {
      case Valid((i, o, rss)) =>
        RecoveryJob.run(sc, i, o, rss)
        val _ = sc.close()
        ()
      case Invalid(l) =>
        System.err.println(l.toList.mkString("\n"))
        System.exit(1)
    }
  }
}

object RecoveryJob {
  /**
   * Beam job running the event recovery process on GCP.
   * It will:
   * - read the input data from a GCS location
   * - decode the bad row jsons
   * - filter out those that are not covered by the specified recovery scenarios
   * - mutate the collector payloads contained in the concerned bad rows according to the specified
   * recovery scenarios
   * - write out the fixed payloads to PubSub
   * Additionally, it will emit metrics on the number of bad rows recovered per recovery scenario.
   * @param sc ScioContext necessary to interact with the SCIO/Beam API
   * @param input GCS location to read the bad rows from
   * @param output PubSub topic in the form projects/project/topics/topic
   * @param recoveryScenarios list of recovery scenarios to apply on the bad rows
   */
  def run(
    sc: ScioContext,
    input: String,
    output: String,
    recoveryScenarios: List[RecoveryScenario]
  ): Unit = {
    val _ = sc.withName(s"read-input-bad-rows-from")
      .textFile(input)
      .withName("parse-bad-rows")
      .map(decode[BadRow])
      .withName("filter-bad-rows")
      .collect { case Right(br) if br.isAffected(recoveryScenarios) =>
        recoveryScenarios
          .filter(rs => br.isAffected(List(rs)))
          .foreach { rs =>
            ScioMetrics
              .counter("snowplow", s"bad_rows_recovered_${rs.getClass.getSimpleName}")
              .inc()
          }
        br
      }
      .withName("fix-collector-payloads")
      .map { br =>
        val newCollectorPayload = br.mutateCollectorPayload(recoveryScenarios)
        val thriftSerializer = new TSerializer
        thriftSerializer.serialize(newCollectorPayload)
      }
      .withName(s"save-to-pubsub-topic")
      .saveAsPubsub(output)
    ()
  }
}
