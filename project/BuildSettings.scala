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
import Keys._

import sbtdynver.DynVerPlugin.autoImport._

// Docker
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import com.typesafe.sbt.packager.Keys.{daemonUser, maintainer}

// Assembly
import sbtassembly.{MergeStrategy, PathList}
import sbtassembly.AssemblyKeys.{assembly, assemblyJarName, assemblyMergeStrategy, assemblyOption}

object BuildSettings {
  lazy val commonProjectSettings: Seq[sbt.Setting[_]] = Seq(
    organization := "com.snowplowanalytics",
    maintainer := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
    scalaVersion := "2.12.11",
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0"))
  )

  lazy val dynVerSettings = Seq(
    ThisBuild / dynverVTagPrefix := false, // Otherwise git tags required to have v-prefix
    ThisBuild / dynverSeparator := "-"     // to be compatible with docker
  )

  lazy val coreProjectSettings: Seq[sbt.Setting[_]] = commonProjectSettings ++ Seq(
    name := "snowplow-event-recovery-core",
    description := "Core recovery logic"
  )
  lazy val beamProjectSettings: Seq[sbt.Setting[_]] = commonProjectSettings ++ Seq(
    name := "snowplow-event-recovery-beam",
    description := "Apache Beam recovery job"
  )
  lazy val flinkProjectSettings: Seq[sbt.Setting[_]] = commonProjectSettings ++ Seq(
    name := "snowplow-event-recovery-flink",
    description := "Apache Flink recovery job"
  )
  lazy val sparkProjectSettings: Seq[sbt.Setting[_]] = commonProjectSettings ++ Seq(
    name := "snowplow-event-recovery-spark",
    description := "Apache Spark recovery job"
  )

  lazy val compilerSettings = Seq[Setting[_]](
    scalacOptions := Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-explaintypes",
      "-feature",
      "-language:existentials",
      "-language:experimental.macros",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-unchecked",
      "-Xcheckinit",
      "-Xfuture",
      "-Yno-adapted-args",
      "-Ypartial-unification",
      "-Ywarn-dead-code",
      "-Ywarn-extra-implicit",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused",
      "-Ywarn-value-discard"
    ),
    javacOptions := Seq(
      "-source",
      "1.8",
      "-target",
      "1.8",
      "-Xlint"
    )
  )

  lazy val helperSettings: Seq[sbt.Setting[_]] = Seq[Setting[_]](
    initialCommands := "import com.snowplowanalytics.snowplow.event.recovery._"
  )

  lazy val resolverSettings: Seq[sbt.Setting[_]] = Seq[Setting[_]](
    resolvers ++= Seq(
      "Sonatype OSS Releases"
        .at("http://oss.sonatype.org/content/repositories/releases/")
        .withAllowInsecureProtocol(true),
      "Sonatype OSS Snapshots"
        .at("http://oss.sonatype.org/content/repositories/snapshots/")
        .withAllowInsecureProtocol(true),
      "Twitter Maven Repo".at("http://maven.twttr.com/").withAllowInsecureProtocol(true),
      "Snowplow Analytics Maven repo".at("http://maven.snplow.com/releases/").withAllowInsecureProtocol(true),
      "Confluent Repository".at("https://packages.confluent.io/maven/"),
      Resolver.mavenLocal
    )
  )

  lazy val publishSettings = Seq[Setting[_]](
    pomIncludeRepository := { _ => false },
    ThisBuild / dynverVTagPrefix := false, // Otherwise git tags required to have v-prefix
    homepage := Some(url("http://snowplowanalytics.com")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/snowplow-incubator/snowplow-event-recovery"),
        "scm:git@github.com:snowplow-incubator/snowplow-event-recovery.git"
      )
    ),
    publishArtifact := true,
    Test / publishArtifact := false,
    developers := List(
      Developer(
        "Snowplow Analytics Ltd",
        "Snowplow Analytics Ltd",
        "support@snowplowanalytics.com",
        url("https://snowplowanalytics.com")
      )
    )
  )

  lazy val dockerSettings: Seq[sbt.Setting[_]] = Seq(
    dockerUsername := Some("snowplow"),
    dockerBaseImage := "eclipse-temurin:8-jre-focal",
    maintainer in Docker := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
    daemonUser in Docker := "snowplow"
  )

  lazy val assemblySettings: Boolean => Seq[sbt.Setting[_]] = includeScala =>
    Seq(
      assemblyJarName in assembly := name.value + "-" + version.value + ".jar",
      assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = includeScala),
      assemblyMergeStrategy in assembly := {
        case x if x.startsWith("META-INF")                           => MergeStrategy.discard
        case x if x.endsWith(".html")                                => MergeStrategy.discard
        case x if x.endsWith("ProjectSettings$.class")               => MergeStrategy.first
        case x if x.endsWith("package-info.class")                   => MergeStrategy.first
        case x if x.endsWith("module-info.class")                    => MergeStrategy.first
        case PathList("org", "apache", "spark", "unused", tail @ _*) => MergeStrategy.first
        case "build.properties"                                      => MergeStrategy.first
        case x =>
          val oldStrategy = (assemblyMergeStrategy in assembly).value
          oldStrategy(x)
      }
    )

  lazy val commonBuildSettings: Seq[sbt.Setting[_]] =
    compilerSettings ++ helperSettings ++ resolverSettings ++ publishSettings ++ dynVerSettings

  lazy val coreBuildSettings: Seq[sbt.Setting[_]] = coreProjectSettings ++ commonBuildSettings ++ publishSettings

  lazy val beamBuildSettings: Seq[sbt.Setting[_]] = beamProjectSettings ++ commonBuildSettings ++ dockerSettings

  lazy val flinkBuildSettings: Seq[sbt.Setting[_]] = flinkProjectSettings ++ commonBuildSettings ++ assemblySettings(
    false
  ) ++ dockerSettings

  lazy val sparkBuildSettings: Seq[sbt.Setting[_]] = sparkProjectSettings ++ commonBuildSettings ++ assemblySettings(
    true
  )
}
