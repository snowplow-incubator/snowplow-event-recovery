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

import sbt._

object Dependencies {

  object V {
    // Java
    val thriftSchema = "0.0.0"
    val elephantBird = "4.17"
    val mockito      = "1.9.0"
    val slf4j        = "1.7.25"

    // Scala third-party
    val atto            = "0.8.0"
    val catsCore        = "1.6.1"
    val catsEffect      = "1.4.1"
    val circe           = "0.11.1"
    val circeOptics     = "0.10.0"
    val monocle         = "1.5.0-cats"
    val spark           = "2.4.4"
    val awsKinesisSpark = "0.0.12"
    val flink           = "1.10.0"
    val scio            = "0.8.4"
    val beam            = "2.19.0"
    val decline         = "0.5.0"
    val declineEffect   = "1.0.0"
    val scalaMacros     = "2.1.0"

    // Scala first-party
    val badRows    = "1.0.0-M2"
    val igluClient = "0.6.1"

    // Testing
    val scalatest           = "3.0.6"
    val scalaCheckMinor     = "1.14"
    val scalaCheck          = s"$scalaCheckMinor.1"
    val scalaCheckToolBox   = "0.3.1"
    val scalaCheckSchema    = "0.2.0-M1"
    val scalaCheckShapeless = "1.2.3"
    val scalaCommonEnrich   = "1.1.0-M2"
  }

  // Java
  val thriftSchema = "com.snowplowanalytics"      % "collector-payload-1" % V.thriftSchema
  val slf4jSimple  = "org.slf4j"                  % "slf4j-simple"        % V.slf4j
  val slf4jLog4j   = "org.slf4j"                  % "slf4j-log4j12"       % V.slf4j
  val jackson      = "com.fasterxml.jackson.core" % "jackson-databind"    % "2.6.7.3"
  val elephantBird = "com.twitter.elephantbird"   % "elephant-bird-core"  % V.elephantBird

  // Scala third-party
  val atto       = "org.tpolecat"  %% "atto-core"   % V.atto
  val catsCore   = "org.typelevel" %% "cats-core"   % V.catsCore
  val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
  val circe = Seq(
    "circe-core",
    "circe-generic",
    "circe-generic-extras",
    "circe-parser",
    "circe-shapes",
    "circe-literal"
  ).map("io.circe" %% _ % V.circe) :+ ("io.circe" %% "circe-optics" % V.circeOptics)
  val monocle = "com.github.julien-truffaut" %% "monocle-macro"                          % V.monocle
  val scio    = "com.spotify"                %% "scio-core"                              % V.scio
  val beam    = "org.apache.beam"            % "beam-runners-google-cloud-dataflow-java" % V.beam
  val decline =
    Seq(("decline", V.decline), ("decline-effect", V.declineEffect)).map {
      case (pkg, version) => "com.monovore" %% pkg % version
    }
  val flink = Seq("flink-scala", "flink-streaming-scala", "flink-connector-kinesis").map(
    "org.apache.flink" %% _ % V.flink % Provided
  ) :+ "org.apache.flink" % "flink-s3-fs-hadoop" % V.flink % Provided
  val spark           = Seq("spark-core", "spark-sql").map("org.apache.spark" %% _ % V.spark % Provided)
  val awsKinesisSpark = "jp.co.bizreach" %% "aws-kinesis-spark" % V.awsKinesisSpark

  // Scala first-party
  val badRows    = "com.snowplowanalytics" %% "snowplow-badrows"  % V.badRows
  val igluClient = "com.snowplowanalytics" %% "iglu-scala-client" % V.igluClient

  // Testing
  val scalatest           = "org.scalatest"              %% "scalatest"                                  % V.scalatest           % Test
  val scalaCheck          = "org.scalacheck"             %% "scalacheck"                                 % V.scalaCheck          % Test
  val scalaCheckSchema    = "com.snowplowanalytics"      %% "scalacheck-schema"                          % V.scalaCheckSchema    % Test
  val scalaCheckShapeless = "com.github.alexarchambault" %% s"scalacheck-shapeless_${V.scalaCheckMinor}" % V.scalaCheckShapeless % Test
  val scalaCheckToolbox   = "com.47deg"                  %% "scalacheck-toolbox-datetime"                % V.scalaCheckToolBox   % Test
  val scalaCommonEnrich = ("com.snowplowanalytics" %% "snowplow-common-enrich" % V.scalaCommonEnrich % Test)
    .exclude("com.maxmind.geoip2", "geoip2")
  val scioTest = "com.spotify" %% "scio-test"  % V.scio    % Test
  val mockito  = "org.mockito" % "mockito-all" % V.mockito % Test
}
