/*
 * Copyright (c) 2018-2020 Snowplow Analytics Ltd. All rights reserved.
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

import BuildSettings._

lazy val root = project.in(file(".")).settings(commonProjectSettings).aggregate(core, beam, flink, spark)

lazy val core = project
  .settings(coreBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.thriftSchema,
      Dependencies.badRows,
      Dependencies.atto,
      Dependencies.catsCore,
      Dependencies.monocle,
      Dependencies.scalatest,
      Dependencies.scalaCheck,
      Dependencies.scalaCheckShapeless,
      Dependencies.scalaCheckToolbox,
      Dependencies.scalaCheckSchema,
      Dependencies.scalaCommonEnrich,
      Dependencies.slf4jLog4j
    ) ++ Dependencies.circe
  )

lazy val beam = project
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(JavaAppPackaging)
  .settings(beamBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.scio,
      Dependencies.beam,
      Dependencies.scioTest,
      Dependencies.slf4jSimple
    )
  )

lazy val flink = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(flinkBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.mockito
    ) ++ Dependencies.decline ++ Dependencies.flink ++ Dependencies.circe,
    dependencyOverrides += Dependencies.jackson
  )

lazy val spark =
  project
    .dependsOn(core % "compile->compile;test->test")
    .settings(sparkBuildSettings)
    .settings(
      libraryDependencies ++= Seq(
        Dependencies.awsKinesisSpark,
        Dependencies.elephantBird
      ) ++ Dependencies.spark ++ Dependencies.decline,
      dependencyOverrides += Dependencies.jackson
    )
