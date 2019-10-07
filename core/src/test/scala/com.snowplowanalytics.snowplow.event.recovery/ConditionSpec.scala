// /*
//  * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
//  *
//  * This program is licensed to you under the Apache License Version 2.0,
//  * and you may not use this file except in compliance with the Apache License Version 2.0.
//  * You may obtain a copy of the Apache License Version 2.0 at
//  * http://www.apache.org/licenses/LICENSE-2.0.
//  *
//  * Unless required by applicable law or agreed to in writing,
//  * software distributed under the Apache License Version 2.0 is distributed on an
//  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the Apache License Version 2.0 for the specific language governing permissions and
//  * limitations there under.
//  */
// package com.snowplowanalytics.snowplow.event.recovery
// package conditions

// import org.scalatest._
// import org.scalatest.Matchers._
// import org.scalatestplus.scalacheck._
// import cats.implicits._
// import io.circe.syntax._

// import com.snowplowanalytics.snowplow.event.recovery.config.conditions._
// import com.snowplowanalytics.snowplow.badrows._
// import com.snowplowanalytics.snowplow.badrows.BadRow._

// class ConditionSpec
//     extends FreeSpec
//     with ScalaCheckPropertyChecks {

//   "Condition" - {
//     "should test path against regular expression" in {
//       forAll(gens.badRowcpFormatViolationA.arbitrary){ badRow =>
//         val json = badRow.asInstanceOf[BadRow].asJson
//         val condition = Condition(Test, "body.timestamp", RegularExpression(".*").some)
//         condition.check(json) should equal (true)
//       }
//     }
//     "should test path for size" in {
//     }
//     "should compare path" in {
//     }
//   }
// }
