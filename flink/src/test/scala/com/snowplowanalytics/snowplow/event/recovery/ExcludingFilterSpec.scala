/*
 * Copyright (c) 2026-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.event.recovery

import org.apache.flink.core.fs.Path
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

class ExcludingFilterSpec extends AnyWordSpec {
  "ExcludingFilter" should {
    val filter = new ExcludingFilter(List("s3a://bucket/in/unrecovered", "file:///tmp/in/out/"))

    "skip files under an excluded path, whatever the s3 scheme or trailing slash" in {
      filter.test(new Path("s3://bucket/in/unrecovered/part-0")) shouldBe false
      filter.test(new Path("s3a://bucket/in/unrecovered/a/b")) shouldBe false
      filter.test(new Path("s3a://bucket/in/unrecovered")) shouldBe false
      filter.test(new Path("file:/tmp/in/out/x")) shouldBe false
    }
    "keep other files, including ones whose names only start like an excluded path" in {
      filter.test(new Path("s3a://bucket/in/rows.gz")) shouldBe true
      filter.test(new Path("s3a://bucket/in/unrecovered-old/rows.gz")) shouldBe true
      filter.test(new Path("s3a://other/in/unrecovered/rows.gz")) shouldBe true
      filter.test(new Path("file:/tmp/in/rows.txt")) shouldBe true
    }
    "skip hdfs files whether or not the namenode is given" in {
      val hdfs = new ExcludingFilter(List("hdfs:///local/to-recover/unrecovered"))
      hdfs.test(new Path("hdfs://ip-10-0-0-1.ec2.internal:8020/local/to-recover/unrecovered/part-0")) shouldBe false
      hdfs.test(new Path("hdfs:///local/to-recover/unrecovered/part-0")) shouldBe false
      hdfs.test(new Path("hdfs://ip-10-0-0-1.ec2.internal:8020/local/to-recover/rows.gz")) shouldBe true
    }
    "skip hidden files like FileSource's default filter" in {
      filter.test(new Path("s3a://bucket/in/_SUCCESS")) shouldBe false
      filter.test(new Path("s3a://bucket/in/.part-0.inprogress")) shouldBe false
    }
  }
}
