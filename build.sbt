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

lazy val buildSettings = Seq(
  organization := "com.snowplowanalytics",
  scalaVersion := "2.12.10",
  version := "0.1.1",
  javacOptions := Seq("-source", "1.8", "-target", "1.8"),
  initialize ~= { _ => makeColorConsole() },
  resolvers ++= Seq("Snowplow Analytics Maven repo" at "http://maven.snplow.com/releases/")
)

lazy val snowplowEventRecovery = (project.in(file(".")))
  .settings(buildSettings)
  .aggregate(core, beam, flink, spark)
  .dependsOn(core)

lazy val thriftSchemaVersion = "0.0.0"
lazy val badRowsVersion = "0.2.0-M2"
lazy val catsVersion = "1.4.0"
lazy val scalaUriVersion = "1.4.0"
lazy val circeOpticsVersion = "0.10.0"
lazy val igluClientVersion = "0.6.0"
lazy val slf4jVersion = "1.7.25"
lazy val scalatestVersion = "3.0.6"
lazy val scalacheckMinorVersion = "1.14"
lazy val scalacheckVersion = s"$scalacheckMinorVersion.0"
lazy val scalacheckSchemaVersion = "0.2.0-M1"
lazy val shapelessScalacheckVersion = "1.2.3"
lazy val sceVersion = "1.1.0"
lazy val monocleVersion = "1.5.0-cats"
lazy val circeVersion = "0.11.0"
lazy val circeDependencies = Seq(
  "circe-generic-extras",
  "circe-parser",
  "circe-literal",
  "circe-shapes"
).map("io.circe" %% _ % circeVersion)
lazy val scalacheckToolBoxDatetimeVersion = "0.3.1"

lazy val core = project
  .settings(moduleName := "snowplow-event-recovery")
  .settings(buildSettings ++ macroSettings)
  .settings(
    resolvers ++= Seq(
      "Snowplow Analytics Maven repo" at "http://maven.snplow.com/releases/",
      Resolver.bintrayRepo("reug", "maven")
    ),
    libraryDependencies ++= Seq(
      // thrift is only used for writing into kinesis
      "com.snowplowanalytics" % "collector-payload-1" % thriftSchemaVersion,
      "com.snowplowanalytics" %% "snowplow-badrows" % badRowsVersion,
      "org.typelevel" %% "cats-core" % catsVersion,
      "io.lemonlabs" %% "scala-uri" % scalaUriVersion,
      "io.circe" %% "circe-optics" % circeOpticsVersion,
      "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion,
      "com.snowplowanalytics" %% "iglu-scala-client" % igluClientVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % "test",
      "org.scalacheck" %% "scalacheck" % scalacheckVersion % "test",
      "com.github.alexarchambault" %% s"scalacheck-shapeless_$scalacheckMinorVersion" % shapelessScalacheckVersion % "test",
      "com.47deg" %% "scalacheck-toolbox-datetime" % scalacheckToolBoxDatetimeVersion % "test",
      "com.snowplowanalytics" %% "scalacheck-schema" % scalacheckSchemaVersion % "test",
      ("com.snowplowanalytics" %% "snowplow-common-enrich" % sceVersion % "test")
        .exclude("com.maxmind.geoip2", "geoip2"),
      // needed for thrift ser/de
      "org.slf4j" % "slf4j-log4j12" % slf4jVersion,
      ) ++ circeDependencies
  )

lazy val declineVersion = "0.5.0"
lazy val declineEffectVersion = "1.0.0"
lazy val scioVersion = "0.8.4"
lazy val beamVersion = "2.19.0"
lazy val scalaMacrosVersion = "2.1.0"
lazy val scalacheckShapelessVersion = "1.2.3"

lazy val paradiseDependency =
  "org.scalamacros" % "paradise" % scalaMacrosVersion cross CrossVersion.full
lazy val macroSettings = Seq(
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  addCompilerPlugin(paradiseDependency)
)

import com.typesafe.sbt.packager.docker._
dockerRepository := Some("snowplow-docker-registry.bintray.io")
dockerUsername := Some("snowplow")
dockerBaseImage := "snowplow-docker-registry.bintray.io/snowplow/k8s-dataflow:0.1.1"
maintainer in Docker := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>"
daemonUser in Docker := "snowplow"

lazy val beam = project
  .settings(packageName := "snowplow-event-recovery-beam")
  .settings(buildSettings ++ macroSettings)
  .settings(
    description := "Snowplow event recovery job for GCP",
    libraryDependencies ++= Seq(
      "com.spotify" %% "scio-core" % scioVersion,
      "org.apache.beam" % "beam-runners-google-cloud-dataflow-java" % beamVersion,
      "org.slf4j" % "slf4j-simple" % slf4jVersion,
      "com.spotify" %% "scio-test" % scioVersion % "test",
    )
  ).dependsOn(core % "compile->compile;test->test")
  .enablePlugins(JavaAppPackaging)

lazy val flinkVersion = "1.9.0"
lazy val mockitoVersion = "1.9.0"

lazy val flink = project
  .settings(packageName := "snowplow-event-recovery-flink")
  .settings(buildSettings)
  .settings(
    description := "Snowplow event recovery job for AWS",
    resolvers += Resolver.mavenLocal,
    libraryDependencies ++= Seq(
      "com.monovore" %% "decline" % declineVersion,
      "com.monovore" %% "decline-effect" % declineEffectVersion,
      "org.apache.flink" %% "flink-scala" % flinkVersion % "provided",
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % "provided",
      "org.apache.flink" % "flink-s3-fs-hadoop" % flinkVersion % "provided",
      "org.apache.flink" %% "flink-connector-kinesis" % flinkVersion % "provided",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.mockito" % "mockito-all" % mockitoVersion % "test",
      "com.github.alexarchambault" %% "scalacheck-shapeless_1.14" % scalacheckShapelessVersion % "test"
    )
  ).settings(
    assemblyJarName in assembly := { packageName.value + "-" + version.value + ".jar" },
    assemblyOption in assembly  := (assemblyOption in assembly).value.copy(includeScala = false),
  ).settings(
    Compile / run  := Defaults.runTask(Compile / fullClasspath,
                                       Compile / run / mainClass,
                                       Compile / run / runner
    ).evaluated,
    Compile / run / fork := true,
    Global / cancelable := true
  ).dependsOn(core % "compile->compile;test->test")

lazy val sparkVersion = "2.4.3"
lazy val elephantBirdVersion = "4.17"
lazy val awsKinesisSparkVersion = "0.0.12"

lazy val spark = project
  .settings(packageName := "snowplow-event-recovery-spark")
  .settings(buildSettings)
  .settings(
    description := "Snowplow event recovery job for AWS",
    resolvers += "Twitter Maven Repo" at "http://maven.twttr.com/",
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
      "org.apache.spark" %% "spark-sql"  % sparkVersion % "provided",
      "jp.co.bizreach" %% "aws-kinesis-spark" % awsKinesisSparkVersion,
      "com.monovore" %% "decline" % declineVersion,
      "com.monovore" %% "decline-effect" % declineEffectVersion,      
      "com.twitter.elephantbird" % "elephant-bird-core" % elephantBirdVersion,
    )
  ).settings(
    initialCommands in console :=
      """
        |import org.apache.spark.{SparkConf, SparkContext}, org.apache.spark.sql.SparkSession
        |import frameless.functions.aggregate._, frameless.syntax._, frameless.TypedDataset
        |val conf = new SparkConf().setMaster("local[*]").setAppName("frameless-repl").set("spark.ui.enabled", "false")
        |implicit val spark = SparkSession.builder().config(conf).appName("recovery").getOrCreate()
        |import spark.implicits._
        |spark.sparkContext.setLogLevel("WARN")
      """.stripMargin,
    cleanupCommands in console :=
      """
        |spark.stop()
      """.stripMargin
  ).settings(
    assemblyJarName in assembly := { packageName.value + "-" + version.value + ".jar" },
    assemblyMergeStrategy in assembly := {
      case x if x.startsWith("META-INF") => MergeStrategy.discard
      case x if x.endsWith(".html") => MergeStrategy.discard
      case x if x.endsWith("package-info.class") => MergeStrategy.first
      case PathList("org", "apache", "spark", "unused", tail@_*) => MergeStrategy.first
      case "build.properties" => MergeStrategy.first
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  ).dependsOn(core % "compile->compile;test->test")

lazy val repl: Project = Project(
  "repl",
  file(".repl")
).settings(
  buildSettings ++ macroSettings,
  description := "Scio REPL for snowplow-event-recovery",
  libraryDependencies ++= Seq(
    "com.spotify" %% "scio-repl" % scioVersion
  ),
  mainClass in Compile := Some("com.spotify.scio.repl.ScioShell")
).dependsOn(beam)

def makeColorConsole() = {
  val ansi = System.getProperty("sbt.log.noformat", "false") != "true"
  if (ansi) System.setProperty("scala.color", "true")
}
