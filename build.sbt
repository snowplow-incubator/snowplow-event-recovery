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

import BuildSettings._

// Read by sbt-thrift-plugin's task, which sbt's lint does not see
Global / excludeLintKeys += thriftJavaOptions

lazy val root = project.in(file(".")).settings(commonProjectSettings).aggregate(core, beam, flink)

lazy val core = project
  .settings(coreBuildSettings)
  // Needs the thrift compiler on the PATH. Suppressed: javax.annotation.Generated is not in the JDK after Java 8.
  .settings(com.intenthq.sbt.ThriftPlugin.Thrift / thriftJavaOptions := Seq("generated_annotations=suppress"))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(
    libraryDependencies ++= (Seq(
      Dependencies.thrift,
      Dependencies
        .badRows
        .excludeAll(ExclusionRule(organization = "com.snowplowanalytics", name = "iglu-scala-client-data_2.12")),
      Dependencies.igluClient,
      Dependencies.atto,
      Dependencies.catsCore,
      Dependencies.catsEffect,
      Dependencies.monocle,
      Dependencies.scalaCheck,
      Dependencies.scalaCheckShapeless,
      Dependencies.scalaCheckToolbox,
      Dependencies.scalaCheckSchema,
      Dependencies.scalaCommonEnrich,
      Dependencies.httpClient4 % Test,
      Dependencies.slf4jLog4j
    ) ++ Dependencies.circe
      ++ Dependencies.scalatest
      ++ SecurityOverrides.dependencies.map(_.excludeAll(ExclusionRule(organization = "commons-logging"))))
  )
  .enablePlugins(BuildInfoPlugin)

lazy val cli = project
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(JavaAppPackaging)
  .settings(dynVerSettings)
  .settings(cliBuildSettings)
  .settings(
    libraryDependencies ++= Dependencies.cliDeps,
    excludeDependencies ++= Seq(
      ExclusionRule("com.snowplowanalytics", "iglu-scala-client-data_2.12")
    )
  )

lazy val beam = project
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(JavaAppPackaging)
  .settings(dynVerSettings)
  .settings(beamBuildSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.scio,
      Dependencies.scioGCP,
      Dependencies.beam,
      Dependencies.scioTest,
      Dependencies.slf4jSimple
    ) ++ SecurityOverrides.dependencies.map(_.excludeAll(ExclusionRule(organization = "commons-logging")))
  )

lazy val flink = project
  .dependsOn(core % "compile->compile;test->test")
  .settings(flinkBuildSettings)
  .configs(IntegrationTest)
  .settings(Defaults.itSettings)
  .settings(
    // Flink's MiniCluster does not work inside sbt's layered classloader, and on Java 9+ it needs
    // the same module flags Flink passes to its own JVMs (env.java.default-opts.all in Flink 1.20)
    Test / fork := true,
    IntegrationTest / fork := true,
    Test / javaOptions ++= BuildSettings.flinkJvmModuleOptions,
    IntegrationTest / javaOptions ++= BuildSettings.flinkJvmModuleOptions,
    // Leave out what the cluster's Flink provides: its own jars and logging. Direct Flink dependencies are Provided;
    // this also catches the ones that come transitively (connector-base, table-common, shaded libraries via the AWS
    // connectors) and the logging binding from core. On YARN the job jar can be on the JobManager's system classpath
    // ahead of Flink's lib, where a bundled logging binding or a second copy of Flink's classes breaks the JobManager.
    // Only the AWS connectors are not part of Flink's distribution.
    assembly / assemblyExcludedJars := (assembly / fullClasspath).value.filter { jar =>
      val name = jar.data.getName
      (name.startsWith("flink-") && !name.startsWith("flink-connector-aws")) ||
      Seq("slf4j-", "log4j-", "reload4j-", "logback-").exists(name.startsWith)
    }
  )
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.mockito
    ) ++ Dependencies.decline
      ++ Dependencies
      .flink
      .map(
        _.excludeAll(
          ExclusionRule(organization = "commons-logging", name = "commons-logging"),
          ExclusionRule(organization = "org.springframework", name = "spring-jcl"),
        )
      ) ++ Dependencies.circe
      ++ Dependencies.testContainers
      ++ Dependencies.scalatestIT
      :+ Dependencies.kinesis.excludeAll(
          ExclusionRule(organization = "commons-logging", name = "commons-logging"),
          ExclusionRule(organization = "org.springframework", name = "spring-jcl"),
        ),
    dependencyOverrides += Dependencies.jackson
  )
