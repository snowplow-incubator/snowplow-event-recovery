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

import sbt._

object SecurityOverrides {
  object V {
    val commonsCodec          = "1.13"
    val guava                 = "32.0.0-jre"
    val json                  = "20231013"
    val snakeyaml             = "2.0"
    val snappy                = "1.1.10.4"
    val avro                  = "1.11.3"
    val commonsCompress       = "1.26.0"
    val reload4j              = "1.2.25"
  }

  val dependencies = Seq(
    "com.google.guava"                 % "guava"                   % V.guava,
    "commons-codec"                    % "commons-codec"           % V.commonsCodec,
    "org.json"                         % "json"                    % V.json,
    "org.yaml"                         % "snakeyaml"               % V.snakeyaml,
    "org.xerial.snappy"                % "snappy-java"             % V.snappy,
    "org.apache.avro"                  % "avro"                    % V.avro,
    "org.apache.commons"               % "commons-compress"        % V.commonsCompress,
    "ch.qos.reload4j"                  % "reload4j"                % V.reload4j 
  )

}

object Dependencies {

  object V {
    // Java
    val thrift          = "0.25.0" // at least the version of the thrift compiler that generates CollectorPayload
    val slf4j           = "1.7.36"
    val jacksonDatabind = "2.12.7.1"
    val httpClient4     = "4.5.14"
    val aws             = "1.12.576"

    // Scala third-party
    val atto          = "0.9.5"
    val catsCore      = "2.8.0"
    val catsEffect    = "2.5.5"
    val circeOptics   = "0.14.1"
    val circe         = "0.14.2"
    val monocle       = "2.1.0"
    val flink         = "1.20.0" // match the Flink shipped by EMR 7.6+ (1.20.0-amzn-*)
    val flinkKinesis  = "5.1.0-1.20"
    val scio          = "0.13.6"
    val beam          = "2.52.0"
    val decline       = "1.4.0"
    val declineEffect = "1.4.0"

    // Scala first-party
    val badRows    = "2.2.0"
    val igluClient = "1.1.1"

    // Testing
    val scalatest           = "3.2.11"
    val scalaCheckMinor     = "1.15"
    val scalaCheck          = s"$scalaCheckMinor.4"
    val scalaCheckToolBox   = "0.6.0"
    val scalaCheckShapeless = "1.3.0"
    val scalaCommonEnrich   = "3.2.1"
    val testContainers      = "0.43.6"
    val testContainersJava  = "1.21.4" // Docker Engine 29 rejects the API version older testcontainers-java requests

    //cli
    val mainargs = "0.7.0"
    val osLib    = "0.10.0"
  }

  // Java
  // Only TSerializer and TDeserializer are used.
  // Not excluded build-wide: YAUAA, in snowplow-common-enrich, needs httpclient5.
  val thrift = ("org.apache.thrift" % "libthrift" % V.thrift).excludeAll(
    ExclusionRule("org.apache.httpcomponents.client5"),
    ExclusionRule("org.apache.httpcomponents.core5"),
    ExclusionRule("jakarta.servlet"),
    ExclusionRule("jakarta.annotation")
  )
  val slf4jSimple  = "org.slf4j"                  % "slf4j-simple"            % V.slf4j
  val slf4jLog4j   = "org.slf4j"                  % "slf4j-log4j12"           % V.slf4j
  val jackson      = "com.fasterxml.jackson.core" % "jackson-databind"        % V.jacksonDatabind
  // snowplow-common-enrich 3.x needs it at runtime but does not depend on it
  val httpClient4  = "org.apache.httpcomponents"  % "httpclient"              % V.httpClient4
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
    // Part of the Flink distribution, as are flink-connector-base and the shaded libraries the AWS connectors pull in
    // transitively; those are left out of the jar by the flink project's assemblyExcludedJars
    ("org.apache.flink" % "flink-connector-files" % V.flink % Provided)
      .exclude("com.google.protobuf", "protobuf-java") :+
    ("org.apache.flink" % "flink-test-utils" % V.flink % IntegrationTest) :+
    ("org.apache.flink" % "flink-test-utils" % V.flink % Test) :+
    ("org.apache.flink" % "flink-streaming-java" % V.flink % Test classifier Artifact.TestsClassifier)

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
  val scalaCheckShapeless =
    "com.github.alexarchambault" %% s"scalacheck-shapeless_${V.scalaCheckMinor}" % V.scalaCheckShapeless % Test
  val scalaCheckToolbox = "com.47deg" %% "scalacheck-toolbox-datetime" % V.scalaCheckToolBox % Test
  val scalaCommonEnrich = ("com.snowplowanalytics" %% "snowplow-common-enrich" % V.scalaCommonEnrich % Test)
    .exclude("com.maxmind.geoip2", "geoip2")
  val scioTest = "com.spotify"       %% "scio-test"   % V.scio              % Test
  val mockito  = "org.scalatestplus" %% "mockito-4-2" % s"${V.scalatest}.0" % Test
  val testContainers = Seq("testcontainers-scala-scalatest", "testcontainers-scala-localstack").map(
    "com.dimafeng" %% _ % V.testContainers % Seq(Test, IntegrationTest).mkString(",")
  ) ++ Seq("testcontainers", "localstack").map(
    "org.testcontainers" % _ % V.testContainersJava % Seq(Test, IntegrationTest).mkString(",")
  )

  val cliDeps = Seq(
    "com.lihaoyi"            %% "mainargs"               % V.mainargs,
    "com.lihaoyi"            %% "os-lib"                 % V.osLib,
    ("com.snowplowanalytics" %% "snowplow-common-enrich" % V.scalaCommonEnrich).exclude("com.maxmind.geoip2", "geoip2"),
    httpClient4
  )
}
