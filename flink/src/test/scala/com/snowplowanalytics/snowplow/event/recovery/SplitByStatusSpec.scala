/*
 * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow
package event.recovery

import org.scalatest.{EitherValues, WordSpec}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen

import org.apache.flink.util.Collector
import org.apache.flink.streaming.api.scala.OutputTag
import org.apache.flink.streaming.api.functions.ProcessFunction
import typeinfo._
import org.mockito.Mockito._
import domain._

class SplitByStatusSpec extends WordSpec with ScalaCheckPropertyChecks with EitherValues with MockitoSugar {
  implicit val eitherBRorPayload: Gen[Either[RecoveryError, String]] =
    Gen.either(gens.invalidJsonFormatA.arbitrary.map(i => RecoveryError(i, "{}")), gens.nonEmptyString.arbitrary)

  "SplitByStatus" should {
    "separate recovery success from failure" in {
      forAll(eitherBRorPayload) { (b: Either[RecoveryError, String]) =>
        val tag1      = OutputTag[RecoveryError]("failed")
        val tag2      = OutputTag[RecoveryError]("unrecoverable")
        val fn        = new SplitByStatus(tag1, tag2)
        val collector = mock[Collector[String]]
        val ctx       = mock[ProcessFunction[Either[RecoveryError, String], String]#Context]
        fn.processElement(b, ctx, collector)
        if (b.isRight) verify(collector).collect(b.right.get)
      }
    }
  }
}
