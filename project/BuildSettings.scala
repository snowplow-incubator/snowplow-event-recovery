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
import Keys._

import sbtdynver.DynVerPlugin.autoImport._

// Docker
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import com.typesafe.sbt.packager.Keys.{daemonUser, maintainer}

// Assembly
import sbtassembly.AssemblyPlugin.defaultUniversalScript
import sbtassembly.AssemblyPlugin.autoImport._

object BuildSettings {
  lazy val commonProjectSettings: Seq[sbt.Setting[_]] = Seq(
    organization := "com.snowplowanalytics",
    maintainer := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
    scalaVersion := "2.12.21",
    licenses += ("SLULA-1.1", url("https://docs.snowplow.io/limited-use-license-1.1"))
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
  lazy val cliProjectSettings: Seq[sbt.Setting[_]] = commonProjectSettings ++ Seq(
    name := "snowplow-event-recovery",
    description := "Snowplow Event recovery CLI"
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
      "-Ywarn-value-discard",
      // Built on JDK 21. The Beam image and the Flink job on EMR run on Java 11.
      "-release",
      "11"
    ),
    javacOptions := Seq(
      "--release",
      "11",
      "-Xlint"
    )
  )

  lazy val helperSettings: Seq[sbt.Setting[_]] = Seq[Setting[_]](
    initialCommands := "import com.snowplowanalytics.snowplow.event.recovery._"
  )

  lazy val resolverSettings: Seq[sbt.Setting[_]] = Seq[Setting[_]](
    resolvers ++= Seq(
      "Confluent Repository".at("https://packages.confluent.io/maven/")
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
    dockerBaseImage := "eclipse-temurin:11-jre-noble",
    Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
    Docker / daemonUser := "snowplow"
  )

  lazy val assemblySettings: Boolean => Seq[sbt.Setting[_]] = includeScala =>
    Seq(
      assembly / assemblyJarName := name.value + "-" + version.value + ".jar",
      assembly / assemblyOption := (assembly / assemblyOption).value.copy(includeScala = includeScala),
      assembly / assemblyMergeStrategy := {
        // How slf4j 2 finds its logging backend
        case "META-INF/services/org.slf4j.spi.SLF4JServiceProvider" => MergeStrategy.first
        case x if x.startsWith("META-INF")                                     => MergeStrategy.discard
        case x if x.endsWith(".html")                                          => MergeStrategy.discard
        case x if x.endsWith("package-info.class")                             => MergeStrategy.discard
        case x if x.endsWith("module-info.class")                              => MergeStrategy.discard
        case x if x.endsWith("ProjectSettings$.class")                         => MergeStrategy.first
        case PathList("scala", "annotation", "nowarn.class" | "nowarn$.class") => MergeStrategy.first
        case PathList("org", "slf4j", "impl", _)                               => MergeStrategy.first
        case "build.properties"                                                => MergeStrategy.first
        case PathList("META-INF", "io.netty.versions.properties")              => MergeStrategy.discard
        // In both httpclient 4 and 5, which read the same format
        case "mozilla/public-suffix-list.txt" => MergeStrategy.first
        case x =>
          val oldStrategy = (assembly / assemblyMergeStrategy).value
          oldStrategy(x)
      },
      assembly / assemblyShadeRules := {
        val shadePackage = "com.snowplowanalytics.snowplow.event.recovery.shaded"
        Seq(
          ShadeRule.rename("shapeless.**" -> s"$shadePackage.shapeless.@1").inAll,
          ShadeRule.rename("cats.kernel.**" -> s"$shadePackage.cats.kernel.@1").inAll
        )
      }
    )

  lazy val executableSettings = Seq(
    // Executable jarfile
    assembly / assemblyPrependShellScript := Some(defaultUniversalScript(shebang = true)),

    assembly / mainClass := Some("com.snowplowanalytics.snowplow.event.recovery.Main"),
    // Name it as an executable
    assembly / assemblyJarName := name.value
  )

  lazy val commonBuildSettings: Seq[sbt.Setting[_]] =
    compilerSettings ++ helperSettings ++ resolverSettings ++ publishSettings ++ dynVerSettings ++ Seq(
      dependencyOverrides ++= Dependencies.jacksonOverrides
    )

  lazy val coreBuildSettings: Seq[sbt.Setting[_]] =
    coreProjectSettings ++ commonBuildSettings ++ publishSettings ++ assemblySettings(false)

  lazy val cliBuildSettings: Seq[sbt.Setting[_]] =
    cliProjectSettings ++ commonBuildSettings ++ assemblySettings(true) ++ executableSettings

  lazy val beamBuildSettings: Seq[sbt.Setting[_]] = beamProjectSettings ++ commonBuildSettings ++ dockerSettings

  /** Copied from env.java.default-opts.all in Flink 1.20's flink-dist config.yaml. Java 8 rejects these flags. */
  lazy val flinkJvmModuleOptions: Seq[String] =
    if (sys.props("java.specification.version").startsWith("1.")) Nil
    else
      Seq(
        "--add-exports=java.base/sun.net.util=ALL-UNNAMED",
        "--add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports=java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED"
      )

  lazy val flinkBuildSettings: Seq[sbt.Setting[_]] = flinkProjectSettings ++ commonBuildSettings ++ assemblySettings(
    false
  ) ++ dockerSettings
}
