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
package com.snowplowanalytics.snowplow.event.recovery

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

import org.scalatest.{BeforeAndAfterAll, WordSpec}

trait SparkSpec extends WordSpec with BeforeAndAfterAll {
  // local[1] means the tests will run locally on one thread
  val conf = new SparkConf().setMaster("local[1]")
  var spark: SparkSession =
    SparkSession.builder().config(conf).getOrCreate()

  val hadoopConfig = {
    val conf = spark.sparkContext.hadoopConfiguration
    conf.set("io.compression.codecs", classOf[com.hadoop.compression.lzo.LzopCodec].getName())
    conf.set("io.compression.codec.lzo.class", classOf[com.hadoop.compression.lzo.LzoCodec].getName())
    conf
  }

  override def beforeAll(): Unit =
    spark = SparkSession.builder().config(conf).getOrCreate()

  override def afterAll(): Unit = {
    if (spark != null) spark.stop()
    spark = null
  }
}
