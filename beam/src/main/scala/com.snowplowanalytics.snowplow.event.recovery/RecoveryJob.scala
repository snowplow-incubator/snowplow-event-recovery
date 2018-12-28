package com.snowplowanalytics.snowplow.event.recovery

import cats.data.ValidatedNel
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
  def main(cmdlineArgs: Array[String]): Unit = {
    val (sc, args) = ContextAndArgs(cmdlineArgs)
    val input = args.optional("inputDirectory").toValidNel("Input GCS path is mandatory")
    val output = args.optional("outputTopic").toValidNel("Output PubSub topic is mandatory")
    val recoveryScenarios = (for {
      config <- args.optional("config").toRight(
        "Base64 config with schema com.snowplowanalytics.snowplow/recoveries/jsonschema/1-0-0 is mandatory")
      decoded <- utils.decodeBase64(config)
      _ <- utils.validateConfiguration(decoded)
      rss <- utils.parseRecoveryScenarios(decoded)
    } yield rss).toValidatedNel
    (input, output, recoveryScenarios).tupled match {
      case Valid((i, o, rss)) =>
        RecoveryJob.run(sc, i, o, rss)
        sc.close()
      case Invalid(l) =>
        System.err.println(l.toList.mkString("\n"))
        System.exit(1)
    }
  }
}

object RecoveryJob {
  def run(
    sc: ScioContext,
    input: String,
    output: String,
    recoveryScenarios: List[RecoveryScenario]
  ): Unit =
    sc.withName(s"read-input-bad-rows")
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
}
