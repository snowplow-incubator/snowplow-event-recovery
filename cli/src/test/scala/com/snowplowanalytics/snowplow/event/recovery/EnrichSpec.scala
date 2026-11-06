/*
 * Copyright (c) 2024-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.event.recovery

import java.util.Base64

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.EitherValues._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec

class EnrichSpec extends AnyWordSpec {
  private val resolver = parse(data.resolverConfig).toOption.get

  private def enrichments(configs: String*): Json = parse(data.enrichmentsConfig(configs.mkString(","))).toOption.get

  private val anonIp =
    """{"schema":"iglu:com.snowplowanalytics.snowplow/anon_ip/jsonschema/1-0-1","data":{"name":"anon_ip","vendor":"com.snowplowanalytics.snowplow","enabled":true,"parameters":{"anonOctets":2}}}"""

  private val javascript = {
    val script = Base64.getEncoder.encodeToString("function process(event) { return []; }".getBytes)
    s"""{"schema":"iglu:com.snowplowanalytics.snowplow/javascript_script_config/jsonschema/1-0-0","data":{"vendor":"com.snowplowanalytics.snowplow","name":"javascript_script_config","enabled":true,"parameters":{"script":"$script"}}}"""
  }

  private val ipLookups =
    """{"schema":"iglu:com.snowplowanalytics.snowplow/ip_lookups/jsonschema/2-0-0","data":{"name":"ip_lookups","vendor":"com.snowplowanalytics.snowplow","enabled":true,"parameters":{"geo":{"database":"GeoLite2-City.mmdb","uri":"http://snowplow-hosted-assets.s3.amazonaws.com/third-party/maxmind"}}}}"""

  "pipeline" should {
    "set up enrichments that need no external resources" in {
      val pipeline = Enrich.pipeline(resolver, enrichments(anonIp, javascript)).unsafeRunSync().value
      pipeline.enrichments.anonIp shouldBe defined
      pipeline.enrichments.javascriptScript should have size 1
    }

    "reject enrichments that need external resources" in {
      Enrich.pipeline(resolver, enrichments(anonIp, ipLookups)).unsafeRunSync().left.value should include(
        "iglu:com.snowplowanalytics.snowplow/ip_lookups/jsonschema/2-0-0 needs external resources"
      )
    }
  }
}
