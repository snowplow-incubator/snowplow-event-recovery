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

import sbt._

object SecurityOverrides {
  object V {
    val libthrift             = "0.16.0"
    val fastjson              = "1.2.83"
    val guava                 = "30.0-jre"
    val protobuf              = "3.16.1"
    val oauthClient           = "1.33.3"
    val commonsCodec          = "1.13"
    val jawnParser            = "1.4.0"
    val jacksonDataformatCbor = "2.12.3"
    val netty                 = "4.1.77.Final"
    val bcprov                = "1.69"
    val springExpression      = "5.3.17"
  }

  val dependencies = Seq(
    "org.apache.thrift"                % "libthrift"               % V.libthrift,
    "com.alibaba"                      % "fastjson"                % V.fastjson,
    "com.google.guava"                 % "guava"                   % V.guava,
    "com.google.oauth-client"          % "google-oauth-client"     % V.oauthClient,
    "commons-codec"                    % "commons-codec"           % V.commonsCodec,
    "org.typelevel"                    % "jawn-parser_2.12"        % V.jawnParser,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % V.jacksonDataformatCbor,
    "io.netty"                         % "netty-common"            % V.netty,
    "org.bouncycastle"                 % "bcprov-jdk15on"          % V.bcprov,
    "org.springframework"              % "spring-expression"       % V.springExpression
  )

}

object Dependencies {

  object V {
    // Java
    val thriftSchema    = "0.0.0"
    val elephantBird    = "4.17"
    val mockito         = "1.10.19"
    val slf4j           = "1.7.36"
    val hadoopLzo       = "0.4.20"
    val jacksonDatabind = "2.12.6"
    val aws             = "1.12.261"

    // Scala third-party
    val atto            = "0.9.5"
    val catsCore        = "2.8.0"
    val catsEffect      = "2.5.5"
    val circeOptics     = "0.14.1"
    val circe           = "0.14.2"
    val monocle         = "2.1.0"
    val spark           = "3.2.1"
    val awsKinesisSpark = "0.0.12"
    val flink           = "1.15.2"
    val flinkKinesis    = "1.15.2"
    val scio            = "0.11.9"
    val beam            = "2.40.0"
    val decline         = "1.4.0"
    val declineEffect   = "1.4.0"
    val scalaMacros     = "2.1.0"

    // Scala first-party
    val badRows    = "2.2.0"
    val igluClient = "1.1.1"

    // Testing
    val scalatest           = "3.2.11"
    val scalaCheckMinor     = "1.15"
    val scalaCheck          = s"$scalaCheckMinor.4"
    val scalaCheckToolBox   = "0.6.0"
    val scalaCheckSchema    = "0.2.1"
    val scalaCheckShapeless = "1.3.0"
    val scalaCommonEnrich   = "3.2.1"
    val testContainers      = "0.40.15"
    val catsRetry           = "2.1.0"
  }

  // Java
  val thriftSchema = "com.snowplowanalytics"      % "collector-payload-1"     % V.thriftSchema
  val slf4jSimple  = "org.slf4j"                  % "slf4j-simple"            % V.slf4j
  val slf4jLog4j   = "org.slf4j"                  % "slf4j-log4j12"           % V.slf4j
  val jackson      = "com.fasterxml.jackson.core" % "jackson-databind"        % V.jacksonDatabind
  val elephantBird = "com.twitter.elephantbird"   % "elephant-bird-core"      % V.elephantBird
  val hadoopLzo    = "com.hadoop.gplcompression"  % "hadoop-lzo"              % V.hadoopLzo
  val cloudwatch   = "com.amazonaws"              % "aws-java-sdk-cloudwatch" % V.aws
  val kinesis      = "com.amazonaws"              % "aws-java-sdk-kinesis"    % V.aws

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
  val monocle = "com.github.julien-truffaut" %% "monocle-macro"                           % V.monocle
  val scio    = "com.spotify"                %% "scio-core"                               % V.scio
  val scioGCP = "com.spotify"                %% "scio-google-cloud-platform"              % V.scio
  val beam    = "org.apache.beam"             % "beam-runners-google-cloud-dataflow-java" % V.beam
  val decline =
    Seq(("decline", V.decline), ("decline-effect", V.declineEffect)).map { case (pkg, version) =>
      "com.monovore" %% pkg % version
    }
  val flink = Seq("flink-scala", "flink-streaming-scala").map(
    "org.apache.flink" %% _ % V.flink % Provided
  ) :+
    ("org.apache.flink" % "flink-s3-fs-hadoop" % V.flink % Provided) :+
    ("org.apache.flink" % "flink-connector-aws-kinesis-streams" % V.flinkKinesis)
      .exclude("com.google.protobuf", "protobuf-java") :+
    ("org.apache.flink" % "flink-connector-files" % V.flink).exclude("com.google.protobuf", "protobuf-java") :+
    ("org.apache.flink" % "flink-test-utils" % V.flink % IntegrationTest) :+
    ("org.apache.flink" % "flink-test-utils" % V.flink % Test) :+
    ("org.apache.flink" % "flink-streaming-java" % V.flink % Test classifier Artifact.TestsClassifier)
  val spark           = Seq("spark-core", "spark-sql").map("org.apache.spark" %% _ % V.spark % Provided)
  val awsKinesisSpark = "jp.co.bizreach" %% "aws-kinesis-spark" % V.awsKinesisSpark

  // Scala first-party
  val badRows    = "com.snowplowanalytics" %% "snowplow-badrows"  % V.badRows
  val igluClient = "com.snowplowanalytics" %% "iglu-scala-client" % V.igluClient

  // Testing
  val scalatest = Seq("scalatest", "scalatest-wordspec").map("org.scalatest" %% _ % V.scalatest % Test) :+
    ("org.scalatestplus" %% "scalacheck-1-15" % s"${V.scalatest}.0" % Test)
  val scalatestIT = Seq("scalatest", "scalatest-wordspec").map(
    "org.scalatest" %% _ % V.scalatest % IntegrationTest
  ) :+
    ("org.scalatestplus" %% "scalacheck-1-15" % s"${V.scalatest}.0" % IntegrationTest)
  val scalaCheck       = "org.scalacheck"        %% "scalacheck"        % V.scalaCheck       % Test
  val scalaCheckSchema = "com.snowplowanalytics" %% "scalacheck-schema" % V.scalaCheckSchema % Test
  val scalaCheckShapeless =
    "com.github.alexarchambault" %% s"scalacheck-shapeless_${V.scalaCheckMinor}" % V.scalaCheckShapeless % Test
  val scalaCheckToolbox = "com.47deg" %% "scalacheck-toolbox-datetime" % V.scalaCheckToolBox % Test
  val scalaCommonEnrich = ("com.snowplowanalytics" %% "snowplow-common-enrich" % V.scalaCommonEnrich % Test)
    .exclude("com.maxmind.geoip2", "geoip2")
  val scioTest = "com.spotify"       %% "scio-test"   % V.scio              % Test
  val mockito  = "org.scalatestplus" %% "mockito-4-2" % s"${V.scalatest}.0" % Test
  val testContainers = Seq("testcontainers-scala-scalatest", "testcontainers-scala-localstack").map(
    "com.dimafeng" %% _ % V.testContainers % Seq(Test, IntegrationTest).mkString(",")
  )
  val catsRetry = "com.github.cb372" %% "cats-retry" % V.catsRetry % IntegrationTest
}
