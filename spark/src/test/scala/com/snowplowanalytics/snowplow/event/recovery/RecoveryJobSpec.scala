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

import com.amazonaws.regions.Regions
import org.apache.spark.sql.{Dataset, Encoder, SparkSession}
import scala.collection.JavaConverters._
import domain._
import shapeless.syntax.sized._
import org.scalatest.Matchers._

class RecoveryJobSpec extends SparkSpec {
  implicit val session = spark
  object RecoveryJobTest extends RecoveryJob {
    val Some((recovered, failed, unrecoverable)) =
      Result.partitions.toList.map(_ => collection.mutable.ListBuffer.empty[String]).sized(3).map(_.tupled)

    override def load(input: String)(implicit spark: SparkSession): Dataset[String] = {
      import spark.implicits._
      spark.createDataset(badRows)
    }

    override def sink(
      output: String,
      failedOutput: String,
      unrecoverableOutput: String,
      region: Regions,
      batchSize: Int,
      v: Dataset[SparkResult],
      summary: Summary
    )(implicit encoder: Encoder[String]) = {
      println(s"$output,$failedOutput,$unrecoverableOutput,$region,$batchSize")
      recovered ++= v
        .filter(_.isInstanceOf[SparkSuccess])
        .map { r =>
          summary.successful.add(1)
          r.message
        }
        .collectAsList
        .asScala
        .toList
      failed ++= v
        .filter(_.isInstanceOf[SparkFailure])
        .map { r =>
          summary.failed.add(1)
          r.message
        }
        .collectAsList
        .asScala
        .toList
      unrecoverable ++= v
        .filter(_.isInstanceOf[SparkUnrecoverable])
        .map { r =>
          summary.unrecoverable.add(1)
          r.message
        }
        .collectAsList
        .asScala
        .toList

      summary
    }

  }
  import json._
  import config._
  import io.circe.parser.decode
  val cfg: Config = decode("""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/2-0-0",
      "data": {
        "iglu:com.snowplowanalytics.snowplow.badrows/enrichment_failures/jsonschema/1-0-*": [
          {
            "name": "default",
            "conditions": [],
            "steps": []
          }
        ]
      }
    }
  """)(confD).map(_.data).right.get

  val badRows = Seq(
    """{"schema":"iglu:com.snowplowanalytics.snowplow.badrows/enrichment_failures/jsonschema/1-0-0","data":{"processor":{"artifact":"beam-enrich","version":"1.0.0-rc5"},"failure":{"timestamp":"2020-02-17T09:28:18.100Z","messages":[{"enrichment":{"schemaKey":"iglu:com.snowplowanalytics.snowplow.enrichments/api_request_enrichment_config/jsonschema/1-0-0","identifier":"api-request"},"message":{"error":"Error accessing POJO input field [user]: [java.lang.NoSuchMethodException: com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent.user_id-foo()]"}}]},"payload":{"enriched":{"app_id":"console","platform":"web","etl_tstamp":"2020-02-17 09:28:18.095","collector_tstamp":"2020-02-17 09:28:16.560","dvce_created_tstamp":"2020-02-17 09:28:16.114","event":"page_view","event_id":"2dfeb9b7-5a87-4214-8a97-a8b23176856b","txn_id":null,"name_tracker":"msc-gcp-stg1","v_tracker":"js-2.10.2","v_collector":"c","v_etl":"beam-enrich-1.0.0-rc5-common-1.0.0","user_id":null,"user_ipaddress":"18.194.133.57","user_fingerprint":null,"domain_userid":"d6c468de-0aed-4785-9052-b6bb77b6dddb","domain_sessionidx":13,"network_userid":"510b2f05-27e3-4fd3-b449-a2702926da5e","geo_country":"DE","geo_region":"HE","geo_city":"Frankfurt am Main","geo_zipcode":"60313","geo_latitude":50.1188,"geo_longitude":8.6843,"geo_region_name":"Hesse","ip_isp":null,"ip_organization":null,"ip_domain":null,"ip_netspeed":null,"page_url":"https://console.snowplowanalytics.com/","page_title":"Snowplow Insights","page_referrer":null,"page_urlscheme":"https","page_urlhost":"console.snowplowanalytics.com","page_urlport":443,"page_urlpath":"/","page_urlquery":null,"page_urlfragment":null,"refr_urlscheme":null,"refr_urlhost":null,"refr_urlport":0,"refr_urlpath":null,"refr_urlquery":null,"refr_urlfragment":null,"refr_medium":null,"refr_source":null,"refr_term":null,"mkt_medium":null,"mkt_source":null,"mkt_term":null,"mkt_content":null,"mkt_campaign":null,"contexts":"{\"schema\":\"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0\",\"data\":[{\"schema\":\"iglu:com.snowplowanalytics.snowplow/web_page/jsonschema/1-0-0\",\"data\":{\"id\":\"39a9934a-ddd3-4581-a4ea-d0ba20e63b92\"}},{\"schema\":\"iglu:org.w3/PerformanceTiming/jsonschema/1-0-0\",\"data\":{\"navigationStart\":1581931694397,\"unloadEventStart\":1581931696046,\"unloadEventEnd\":1581931694764,\"redirectStart\":0,\"redirectEnd\":0,\"fetchStart\":1581931694397,\"domainLookupStart\":1581931694440,\"domainLookupEnd\":1581931694513,\"connectStart\":1581931694513,\"connectEnd\":1581931694665,\"secureConnectionStart\":1581931694572,\"requestStart\":1581931694665,\"responseStart\":1581931694750,\"responseEnd\":1581931694750,\"domLoading\":1581931694762,\"domInteractive\":1581931695963,\"domContentLoadedEventStart\":1581931696039,\"domContentLoadedEventEnd\":1581931696039,\"domComplete\":0,\"loadEventStart\":0,\"loadEventEnd\":0}}]}","se_category":null,"se_action":null,"se_label":null,"se_property":null,"se_value":null,"unstruct_event":null,"tr_orderid":null,"tr_affiliation":null,"tr_total":null,"tr_tax":null,"tr_shipping":null,"tr_city":null,"tr_state":null,"tr_country":null,"ti_orderid":null,"ti_sku":null,"ti_name":null,"ti_category":null,"ti_price":null,"ti_quantity":0,"pp_xoffset_min":0,"pp_xoffset_max":0,"pp_yoffset_min":0,"pp_yoffset_max":0,"br_name":null,"br_family":null,"br_version":null,"br_type":null,"br_renderengine":null,"br_lang":"en-US","br_features_pdf":0,"br_features_flash":0,"br_features_java":0,"br_features_director":0,"br_features_quicktime":0,"br_features_realplayer":0,"br_features_windowsmedia":0,"br_features_gears":0,"br_features_silverlight":0,"br_cookies":1,"br_colordepth":"24","br_viewwidth":1918,"br_viewheight":982,"os_name":null,"os_family":null,"os_manufacturer":null,"os_timezone":"Europe/Berlin","dvce_type":null,"dvce_ismobile":0,"dvce_screenwidth":1920,"dvce_screenheight":1080,"doc_charset":"UTF-8","doc_width":1918,"doc_height":982,"tr_currency":null,"tr_total_base":null,"tr_tax_base":null,"tr_shipping_base":null,"ti_currency":null,"ti_price_base":null,"base_currency":null,"geo_timezone":"Europe/Berlin","mkt_clickid":null,"mkt_network":null,"etl_tags":null,"dvce_sent_tstamp":"2020-02-17 09:28:16.507","refr_domain_userid":null,"refr_dvce_tstamp":null,"derived_contexts":"{\"schema\":\"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1\",\"data\":[{\"schema\":\"iglu:com.snowplowanalytics.snowplow/ua_parser_context/jsonschema/1-0-0\",\"data\":{\"useragentFamily\":\"Firefox\",\"useragentMajor\":\"72\",\"useragentMinor\":\"0\",\"useragentPatch\":null,\"useragentVersion\":\"Firefox 72.0\",\"osFamily\":\"Linux\",\"osMajor\":null,\"osMinor\":null,\"osPatch\":null,\"osPatchMinor\":null,\"osVersion\":\"Linux\",\"deviceFamily\":\"Other\"}}]}","domain_sessionid":"96958bf6-a8bf-4be8-9c67-fd957b6bc8d2","derived_tstamp":"2020-02-17 09:28:16.167","event_vendor":"com.snowplowanalytics.snowplow","event_name":"page_view","event_format":"jsonschema","event_version":"1-0-0","event_fingerprint":"5acdc8f85f9530081d1a71ec430c8756","true_tstamp":null},"raw":{"vendor":"com.snowplowanalytics.snowplow","version":"tp2","parameters":{"e":"pv","page":"DemoPageTitle"},"loaderName":"c","encoding":"UTF-8","headers":["Timeout-Access: <function1>","Host: gcp-sandbox-prod1.collector.snplow.net","Accept: */*","Accept-Language: en-US, en;q=0.5","Accept-Encoding: gzip, deflate, br","Origin: https://console.snowplowanalytics.com","Cookie: sp=510b2f05-27e3-4fd3-b449-a2702926da5e","X-Cloud-Trace-Context: 958285ba723e212998af29cec405e002/12535615945289151925","Via: 1.1 google","X-Forwarded-For: 18.194.133.57, 35.201.76.62","X-Forwarded-Proto: https","Connection: Keep-Alive","application/json"]}}}}"""
  )

  "RecoveryJob" must {
    "filter" should {
      "should filter based on the criteria passed as arguments" in {
        RecoveryJobTest.run("input", "output", "failed", "unrecoverable", Regions.AP_EAST_1, 1, cfg)
        RecoveryJobTest.recovered.size == 1
        RecoveryJobTest.recovered should contain(
          "CgDIAAAAAAAAAAALANIAAAAFVVRGLTgLANwAAAABYwsBQAAAACMvY29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L3RwMgsBSgAAABdlPXB2JnBhZ2U9RGVtb1BhZ2VUaXRsZQt6aQAAAEFpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy9Db2xsZWN0b3JQYXlsb2FkL3RocmlmdC8xLTAtMAA="
        )
        RecoveryJobTest.failed.size == 0
        RecoveryJobTest.unrecoverable.size == 0
      }
    }
  }
}
