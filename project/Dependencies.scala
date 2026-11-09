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
    val commonsCodec    = "1.13"
    val commonsCompress = "1.26.0"
    val reload4j        = "1.2.25"
    val guava           = "33.8.0-jre"
    val snappy          = "1.1.10.12"
    val netty41         = "4.1.140.Final"
    val nettyTcnative   = "2.0.85.Final" // the version netty-bom declares for V.netty41
    val netty42         = "4.2.20.Final"
    val bouncycastle    = "1.86"
    val zstdJni         = "1.5.7-26"
    val openTelemetry   = "1.68.0"
    val http4s          = "0.23.38"
    val httpClient5     = "5.6.5"
    val mariadb         = "3.5.11"
    val postgresql      = "42.7.14"
  }

  // libraryDependencies of core: each has an older version on the classpath of core and of every project that
  // depends on core
  val coreDependencies = Seq(
    "org.apache.commons" % "commons-compress" % V.commonsCompress,
    "ch.qos.reload4j"    % "reload4j"         % V.reload4j
  )

  val cliDependencies = Seq(
    "commons-codec" % "commons-codec" % V.commonsCodec
  )

  // The rest are for dependencyOverrides, which only raise the version of an artifact already on a project's
  // classpath. A module listed here that a project does not use is not added to it.
  // Netty modules work only with others of the same version, as do OpenTelemetry's and http4s's.

  private val netty41 = Seq(
    "netty-buffer",
    "netty-codec",
    "netty-codec-http",
    "netty-codec-http2",
    "netty-codec-socks",
    "netty-common",
    "netty-handler",
    "netty-handler-proxy",
    "netty-resolver",
    "netty-transport",
    "netty-transport-classes-epoll",
    "netty-transport-native-unix-common"
  ).map("io.netty" % _ % V.netty41) ++ Seq(
    "netty-tcnative-boringssl-static",
    "netty-tcnative-classes"
  ).map("io.netty" % _ % V.nettyTcnative)

  // netty-all, which brings every 4.2 module
  private val netty42 = Seq(
    "netty-all",
    "netty-buffer",
    "netty-codec",
    "netty-codec-base",
    "netty-codec-classes-quic",
    "netty-codec-compression",
    "netty-codec-dns",
    "netty-codec-haproxy",
    "netty-codec-http",
    "netty-codec-http2",
    "netty-codec-http3",
    "netty-codec-marshalling",
    "netty-codec-memcache",
    "netty-codec-mqtt",
    "netty-codec-native-quic",
    "netty-codec-protobuf",
    "netty-codec-redis",
    "netty-codec-smtp",
    "netty-codec-socks",
    "netty-codec-stomp",
    "netty-codec-xml",
    "netty-common",
    "netty-handler",
    "netty-handler-proxy",
    "netty-handler-ssl-ocsp",
    "netty-resolver",
    "netty-resolver-dns",
    "netty-resolver-dns-classes-macos",
    "netty-resolver-dns-native-macos",
    "netty-transport",
    "netty-transport-classes-epoll",
    "netty-transport-classes-io_uring",
    "netty-transport-classes-kqueue",
    "netty-transport-native-epoll",
    "netty-transport-native-io_uring",
    "netty-transport-native-kqueue",
    "netty-transport-native-unix-common",
    "netty-transport-rxtx",
    "netty-transport-sctp",
    "netty-transport-udt"
  ).map("io.netty" % _ % V.netty42)

  val beamOverrides = Seq(
    "com.google.guava"  % "guava"          % V.guava,
    "org.xerial.snappy" % "snappy-java"    % V.snappy,
    "org.bouncycastle"  % "bcprov-jdk18on" % V.bouncycastle,
    "com.github.luben"  % "zstd-jni"       % V.zstdJni
  ) ++ Seq(
    "opentelemetry-api",
    "opentelemetry-common",
    "opentelemetry-context",
    "opentelemetry-sdk",
    "opentelemetry-sdk-common",
    "opentelemetry-sdk-extension-autoconfigure-spi",
    "opentelemetry-sdk-logs",
    "opentelemetry-sdk-metrics",
    "opentelemetry-sdk-trace"
  ).map("io.opentelemetry" % _ % V.openTelemetry) ++ netty41

  // Only Beam's ProtoByteUtils, for protobuf schemas from a schema registry, uses these. The job never calls it.
  val beamExclusions = Seq(
    ExclusionRule("io.apicurio"),
    ExclusionRule("com.squareup.wire"),
    ExclusionRule("com.charleskorn.kaml")
  )

  // Netty comes with the AWS SDK's netty-nio-client, in flink-connector-aws
  val flinkOverrides = netty41

  // All from snowplow-common-enrich
  val cliOverrides = Seq(
    "com.google.guava"                 % "guava"                % V.guava,
    "org.apache.httpcomponents.client5" % "httpclient5"          % V.httpClient5,
    "org.mariadb.jdbc"                 % "mariadb-java-client"  % V.mariadb,
    "org.postgresql"                   % "postgresql"           % V.postgresql
  ) ++ Seq(
    "http4s-client",
    "http4s-core",
    "http4s-ember-client",
    "http4s-ember-core"
  ).map("org.http4s" %% _ % V.http4s) ++ netty42

}

object Dependencies {

  object V {
    // Java
    val thrift          = "0.25.0" // at least the version of the thrift compiler that generates CollectorPayload
    val slf4j           = "2.0.17"
    val jackson         = "2.21.8"
    val jacksonAnnot    = "2.21"
    val aws             = "1.12.576"

    // Scala third-party
    val atto          = "0.9.5"
    val catsCore      = "2.13.0"
    val catsEffect    = "3.7.1"
    val circeOptics   = "0.14.1"
    val circe         = "0.14.17"
    val circeExtras   = "0.14.4"
    val monocle       = "2.1.0"
    val flink         = "1.20.0" // match the Flink shipped by EMR 7.6+ (1.20.0-amzn-*)
    val flinkKinesis  = "5.1.0-1.20"
    val scio          = "0.16.1"
    val beam          = "2.77.0"
    val decline       = "2.4.1"

    // Scala first-party
    val badRows    = "2.3.0"
    val igluClient = "4.3.1"

    // Testing
    val scalatest           = "3.2.11"
    val scalaCheckMinor     = "1.15"
    val scalaCheck          = s"$scalaCheckMinor.4"
    val scalaCheckToolBox   = "0.6.0"
    val scalaCheckShapeless = "1.3.0"
    val scalaCommonEnrich   = "6.13.1"
    val testContainers      = "0.43.6"
    val testContainersJava  = "1.21.4" // Docker Engine 29 rejects the API version older testcontainers-java requests

    //cli
    val mainargs       = "0.7.0"
    val osLib          = "0.10.0"
    val typesafeConfig = "1.4.10"
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
  val slf4jReload4j = "org.slf4j"                 % "slf4j-reload4j"          % V.slf4j
  val kinesis      = "com.amazonaws"              % "aws-java-sdk-kinesis"    % V.aws

  // Jackson modules work only with others of the same minor version
  val jacksonOverrides = Seq(
    "com.fasterxml.jackson.core"       % "jackson-annotations"     % V.jacksonAnnot,
    "com.fasterxml.jackson.core"       % "jackson-core"            % V.jackson,
    "com.fasterxml.jackson.core"       % "jackson-databind"        % V.jackson,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % V.jackson,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % V.jackson,
    "com.fasterxml.jackson.datatype"   % "jackson-datatype-jdk8"   % V.jackson,
    "com.fasterxml.jackson.datatype"   % "jackson-datatype-joda"   % V.jackson,
    "com.fasterxml.jackson.datatype"   % "jackson-datatype-jsr310" % V.jackson,
    "com.fasterxml.jackson.module"    %% "jackson-module-scala"    % V.jackson
  )

  // Scala third-party
  val atto       = "org.tpolecat"  %% "atto-core"   % V.atto
  val catsCore   = "org.typelevel" %% "cats-core"   % V.catsCore
  val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
  val circe = Seq(
    "circe-core",
    "circe-generic",
    "circe-parser",
    "circe-shapes",
    "circe-literal"
  ).map("io.circe" %% _ % V.circe) :+
    ("io.circe" %% "circe-generic-extras" % V.circeExtras) :+
    ("io.circe" %% "circe-optics" % V.circeOptics)
  val monocle = "com.github.julien-truffaut" %% "monocle-macro"                           % V.monocle
  val scio    = "com.spotify"                %% "scio-core"                               % V.scio
  val scioGCP = "com.spotify"                %% "scio-google-cloud-platform"              % V.scio
  val beam    = "org.apache.beam"             % "beam-runners-google-cloud-dataflow-java" % V.beam
  val decline = Seq("decline", "decline-effect").map("com.monovore" %% _ % V.decline)
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
    "com.typesafe"            % "config"                 % V.typesafeConfig
  )
}
