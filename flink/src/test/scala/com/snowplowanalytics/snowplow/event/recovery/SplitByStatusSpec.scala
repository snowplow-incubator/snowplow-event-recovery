/*
 * Copyright (c) 2023-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow
package event.recovery

import scala.collection.JavaConverters._

import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers._

import org.apache.flink.streaming.api.scala.OutputTag
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.util.ProcessFunctionTestHarnesses
import org.apache.flink.api.scala._
import domain._
import org.apache.flink.configuration.Configuration
import org.apache.flink.configuration.ConfigConstants

class SplitByStatusSpec extends AnyWordSpec with ScalaCheckPropertyChecks with EitherValues with OptionValues {
  implicit val eitherBRorPayload: Gen[Either[RecoveryError, Array[Byte]]] =
    Gen.either(
      gens.invalidJsonFormatA.arbitrary.map(i => RecoveryError(i, "{}")),
      gens.nonEmptyString.arbitrary.map(_.getBytes)
    )

  "SplitByStatus" should {
    "separate recovery success from failure" in {
      forAll(eitherBRorPayload) { (b: Either[RecoveryError, Array[Byte]]) =>
        val tag1 = OutputTag[RecoveryError]("failed")
        val tag2 = OutputTag[RecoveryError]("unrecoverable")
        val fn   = new SplitByStatus(tag1, tag2)

        val harness = ProcessFunctionTestHarnesses.forProcessFunction(fn)
        harness.processElement(b, 1)

        b match {
          case Right(_) =>
            harness.extractOutputValues() should contain theSameElementsAs b.toOption.toList
          case Left(RecoveryError(UnrecoverableBadRowType(_), _, _)) =>
            harness.getSideOutput(tag2).iterator.asScala.toSeq.map(_.getValue()) should contain(b.left.get)
            harness.extractOutputValues() shouldBe empty
          case Left(_) =>
            harness.getSideOutput(tag1).iterator.asScala.toSeq.map(_.getValue()) should contain(b.left.get)
            harness.extractOutputValues() shouldBe empty
        }
      }
    }
    "calculate metrics" in forAll(Gen.listOf(eitherBRorPayload)) { (bs: List[Either[RecoveryError, Array[Byte]]]) =>
      val config = new Configuration()
      config.setString(
        ConfigConstants.METRICS_REPORTER_PREFIX +
          "testReporter." +
          ConfigConstants.METRICS_REPORTER_FACTORY_CLASS_SUFFIX,
        classOf[TestMetricReporterFactory].getName
      )
      val tag1 = OutputTag[RecoveryError]("failed")
      val tag2 = OutputTag[RecoveryError]("unrecoverable")
      val fn   = new SplitByStatus(tag1, tag2)

      val env = StreamExecutionEnvironment.createLocalEnvironment(1, config)
      env.fromCollection(bs).process(fn)
      env.execute()

      TestMetricReporter.counterMetrics.get("recovered").value.getCount() should equal(bs.filter(_.isRight).length)
      TestMetricReporter.counterMetrics.get("failed").value.getCount() + TestMetricReporter
        .counterMetrics
        .get("unrecoverable")
        .value
        .getCount() should equal(bs.filter(_.isLeft).length)
    }
  }
}
