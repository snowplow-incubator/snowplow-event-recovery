/*
 * Copyright (c) 2018-2018 Snowplow Analytics Ltd. All rights reserved.
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
  scalaVersion := "2.11.12",
  version := "0.1.2",
  javacOptions := Seq("-source", "1.8", "-target", "1.8"),
  initialize ~= { _ => makeColorConsole() },
  resolvers ++= Seq("Snowplow Analytics Maven repo" at "http://maven.snplow.com/releases/")
)

lazy val snowplowEventRecovery = (project.in(file(".")))
  .settings(buildSettings)
  .aggregate(core, spark, beam)
  .dependsOn(core)

lazy val thriftSchemaVersion = "0.0.0"
lazy val catsVersion = "1.4.0"
lazy val scalaUriVersion = "1.4.0"
lazy val circeOpticsVersion = "0.10.0"
lazy val igluClientVersion = "0.5.0"
lazy val slf4jVersion = "1.7.25"
lazy val scalatestVersion = "3.0.5"
lazy val scalacheckVersion = "1.14.0"
lazy val scalacheckSchemaVersion = "0.1.0"
lazy val sceVersion = "0.35.0"

lazy val circeVersion = "0.11.0"
lazy val circeDependencies = Seq(
  "circe-generic-extras",
  "circe-parser"
).map("io.circe" %% _ % circeVersion)

lazy val core = project
  .settings(moduleName := "snowplow-event-recovery")
  .settings(buildSettings)
  .settings(
    resolvers += "Snowplow Analytics Maven repo" at "http://maven.snplow.com/releases/",
    libraryDependencies ++= Seq(
      "com.snowplowanalytics" % "collector-payload-1" % thriftSchemaVersion,
      "org.typelevel" %% "cats-core" % catsVersion,
      "io.lemonlabs" %% "scala-uri" % scalaUriVersion,
      "io.circe" %% "circe-optics" % circeOpticsVersion,
      "com.snowplowanalytics" %% "iglu-scala-client" % igluClientVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % "test",
      "org.scalacheck" %% "scalacheck" % scalacheckVersion % "test",
      "com.snowplowanalytics" %% "scalacheck-schema" % scalacheckSchemaVersion % "test",
      ("com.snowplowanalytics" %% "snowplow-common-enrich" % sceVersion % "test")
        .exclude("com.maxmind.geoip2", "geoip2"),
      // needed for thrift ser/de
      "org.slf4j" % "slf4j-log4j12" % slf4jVersion % " test"
    ) ++ circeDependencies
  )

lazy val sparkVersion = "2.3.2"
lazy val framelessVersion = "0.6.1"
lazy val structTypeEncoderVersion = "0.3.0"
lazy val declineVersion = "0.5.0"
lazy val hadoopLzoVersion = "0.4.20"
lazy val elephantBirdVersion = "4.17"

lazy val spark = project
  .settings(packageName := "snowplow-event-recovery-spark")
  .settings(buildSettings)
  .settings(
    description := "Snowplow event recovery job for AWS",
    resolvers += "Twitter Maven Repo" at "http://maven.twttr.com/",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "frameless-dataset" % framelessVersion,
      "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
      "org.apache.spark" %% "spark-sql"  % sparkVersion % "provided",
      "com.github.benfradet" %% "struct-type-encoder" % structTypeEncoderVersion,
      "com.monovore" %% "decline" % declineVersion,
      "com.hadoop.gplcompression" % "hadoop-lzo" % hadoopLzoVersion,
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

lazy val scioVersion = "0.6.1"
lazy val beamVersion = "2.5.0"
lazy val scalaMacrosVersion = "2.1.0"

lazy val paradiseDependency =
  "org.scalamacros" % "paradise" % scalaMacrosVersion cross CrossVersion.full
lazy val macroSettings = Seq(
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  addCompilerPlugin(paradiseDependency)
)

import com.typesafe.sbt.packager.docker._
dockerRepository := Some("snowplow-docker-registry.bintray.io")
dockerUsername := Some("snowplow")
dockerBaseImage := "snowplow-docker-registry.bintray.io/snowplow/base-debian:0.1.0"
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
