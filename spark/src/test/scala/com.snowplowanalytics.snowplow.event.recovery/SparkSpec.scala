package com.snowplowanalytics.snowplow.event.recovery

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

import org.scalatest.{BeforeAndAfterAll, FreeSpec}

trait SparkSpec extends FreeSpec with BeforeAndAfterAll {
  // local[1] means the tests will run locally on one thread
  val conf = new SparkConf()
    .setMaster("local[1]")
  var spark: SparkSession =
    SparkSession
      .builder()
      .config(conf)
      .getOrCreate()

  val hadoopConfig = {
    val conf = spark.sparkContext.hadoopConfiguration
    conf.set(
      "io.compression.codecs", classOf[com.hadoop.compression.lzo.LzopCodec].getName())
    conf.set(
      "io.compression.codec.lzo.class", classOf[com.hadoop.compression.lzo.LzoCodec].getName())
    conf
  }

  override def beforeAll(): Unit =
    SparkSession
      .builder()
      .config(conf)
      .getOrCreate()

  override def afterAll(): Unit = {
    if (spark != null) spark.stop()
    spark = null
  }
}
