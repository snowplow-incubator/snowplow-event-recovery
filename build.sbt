
lazy val compilerOptions = Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Ypartial-unification",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Xfuture",
  "-Xlint"
)

lazy val javaCompilerOptions = Seq(
  "-source", "1.8",
  "-target", "1.8"
)

lazy val buildSettings = Seq(
  organization := "com.snowplowanalytics",
  scalaVersion := "2.11.12",
  version := "0.1.0",
  scalacOptions := compilerOptions,
  javacOptions := javaCompilerOptions,
  initialize ~= { _ => makeColorConsole() },
  resolvers ++= Seq("Snowplow Analytics Maven repo" at "http://maven.snplow.com/releases/")
)

lazy val snowplowEventRecovery = (project.in(file(".")))
  .settings(buildSettings)
  .aggregate(core, spark)
  .dependsOn(core)

lazy val thriftSchemaVersion = "0.0.0"
lazy val catsVersion = "1.4.0"
lazy val scalaUriVersion = "1.4.0"
lazy val circeOpticsVersion = "0.10.0"
lazy val slf4jVersion = "1.7.25"
lazy val scalatestVersion = "3.0.5"
lazy val scalacheckVersion = "1.14.0"
lazy val scalacheckSchemaVersion = "0.1.0"

lazy val circeVersion = "0.10.1"
lazy val circeDependencies = Seq(
  "circe-generic-extras",
  "circe-parser"
).map("io.circe" %% _ % circeVersion) ++ Seq(
  "circe-literal"
).map("io.circe" %% _ % circeVersion % "test")

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
      "org.scalatest" %% "scalatest" % scalatestVersion % "test",
      "org.scalacheck" %% "scalacheck" % scalacheckVersion % "test",
      "com.snowplowanalytics" %% "scalacheck-schema" % scalacheckSchemaVersion % "test",
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
lazy val sceVersion = "0.35.0"

lazy val spark = project
  .settings(moduleName := "snowplow-event-recovery-spark")
  .settings(buildSettings)
  .settings(
    resolvers += "Twitter Maven Repo" at "http://maven.twttr.com/",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "frameless-dataset" % framelessVersion,
      "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
      "org.apache.spark" %% "spark-sql"  % sparkVersion % "provided",
      "com.github.benfradet" %% "struct-type-encoder" % structTypeEncoderVersion,
      "com.monovore" %% "decline" % declineVersion,
      "com.hadoop.gplcompression" % "hadoop-lzo" % hadoopLzoVersion,
      "com.twitter.elephantbird" % "elephant-bird-core" % elephantBirdVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % "test",
      ("com.snowplowanalytics" %% "snowplow-common-enrich" % sceVersion % "test")
        .exclude("com.maxmind.geoip2", "geoip2")
    )
  ).settings(
    initialCommands in console :=
      """
        |import org.apache.spark.{SparkConf, SparkContext}
        |import org.apache.spark.sql.SparkSession
        |import frameless.functions.aggregate._
        |import frameless.syntax._
        |
        |val conf = new SparkConf().setMaster("local[*]").setAppName("frameless-repl").set("spark.ui.enabled", "false")
        |implicit val spark = SparkSession.builder().config(conf).appName("recovery").getOrCreate()
        |
        |import spark.implicits._
        |
        |spark.sparkContext.setLogLevel("WARN")
        |
        |import frameless.TypedDataset
      """.stripMargin,
    cleanupCommands in console :=
      """
        |spark.stop()
      """.stripMargin
  ).settings(
    assemblyJarName in assembly := { moduleName.value + "-" + version.value + ".jar" },
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

def makeColorConsole() = {
  val ansi = System.getProperty("sbt.log.noformat", "false") != "true"
  if (ansi) System.setProperty("scala.color", "true")
}
