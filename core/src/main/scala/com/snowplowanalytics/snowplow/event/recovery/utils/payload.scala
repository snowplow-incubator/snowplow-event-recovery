/*
 * Copyright (c) 2018-2019 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow
package event.recovery
package util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.JavaConverters._
import cats.syntax.either._
import domain._
import badrows._
import CollectorPayload.thrift.model1.CollectorPayload
import org.joda.time.DateTime
import java.util.UUID
import java.net.URLDecoder

object payload {

  private[this] val coercePF: PartialFunction[Payload, CollectorPayload] = {
    case p: Payload.CollectorPayload => {
      val cp = new CollectorPayload(
        thriftSchema,
        p.ipAddress.orNull,
        p.timestamp.map(_.getMillis).getOrElse(0),
        p.encoding,
        p.collector
      )
      cp.path          = mkPath(p.vendor, p.version)
      cp.userAgent     = p.useragent.orNull
      cp.refererUri    = p.refererUri.orNull
      cp.querystring   = toQuerystring(p.querystring)
      cp.body          = p.body.orNull
      cp.headers       = p.headers.asJava
      cp.contentType   = p.contentType.orNull
      cp.hostname      = p.hostname.orNull
      cp.networkUserId = p.networkUserId.map(_.toString).orNull
      cp
    }
    case Payload.EnrichmentPayload(_, p) => {
      val cp = new CollectorPayload(
        thriftSchema,
        p.ipAddress.orNull,
        p.timestamp.map(_.getMillis).getOrElse(0),
        p.encoding,
        p.loaderName
      )
      cp.path          = mkPath(p.vendor, p.version)
      cp.userAgent     = p.useragent.orNull
      cp.refererUri    = p.refererUri.orNull
      cp.querystring   = toQuerystring(p.parameters)
      cp.hostname      = p.hostname.orNull
      cp.networkUserId = p.userId.map(_.toString).orNull
      cp
    }
  }

  private[this] val mkPath = (vendor: String, version: String) => s"/$vendor/$version"

  /**
    * A homomorphic transformation from a `Payload` of a known-type to `CollectorPayload`.
    * @param a payload of a bad row
    * @return optionally a derived `CollectorPayload`
    */
  val coerce: Payload => Recovering[CollectorPayload] =
    (a: Payload) => coercePF.lift(a).toRight(UncoerciblePayload(a))

  private[this] val toQuerystring = (l: List[NVP]) => {
    val urlEncode = (str: String) => URLEncoder.encode(str, UTF_8.toString)
    val show      = (n: NVP) => s"${n.name}=${urlEncode(n.value.getOrElse(""))}"

    l.map(show).mkString("&")
  }

  private[this] val thriftSchema =
    "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"

  private[this] val cocoercePF: PartialFunction[CollectorPayload, Payload.CollectorPayload] = {
    case cp: CollectorPayload => {
      val vendorVersion = toVendorVersion(cp.path)
      Payload.CollectorPayload(
        vendor        = vendorVersion.vendor,
        version       = vendorVersion.version,
        querystring   = fromQuerystring(cp.querystring),
        contentType   = Option(cp.contentType),
        body          = Option(cp.body),
        collector     = Option(cp.collector).getOrElse(""),
        encoding      = Option(cp.encoding).getOrElse(""),
        hostname      = Option(cp.hostname),
        timestamp     = Option(cp.timestamp).filterNot(_ == 0L).map(new DateTime(_)),
        ipAddress     = Option(cp.ipAddress),
        useragent     = Option(cp.userAgent),
        refererUri    = Option(cp.refererUri),
        headers       = Either.catchNonFatal(cp.headers.asScala.toList).getOrElse(List.empty),
        networkUserId = Option(cp.networkUserId).map(UUID.fromString)
      )
    }
  }

  private[this] case class VendorVersion(vendor: String, version: String)

  private[this] val toVendorVersion = (path: String) => {
    val Empty = VendorVersion("", "")
    val split = (s: String) =>
      s.split("/").toList match {
        case _ :: "" :: version :: Nil     => VendorVersion("", version)
        case _ :: vendor :: Nil            => VendorVersion(vendor, "")
        case _ :: vendor :: version :: Nil => VendorVersion(vendor, version)
        case _                             => Empty
      }

    Option(path).map(split).getOrElse(Empty)
  }

  private[this] val fromQuerystring = (s: String) => {
    val Empty      = List.empty
    val urlDecoded = (s: String) => URLDecoder.decode(s, UTF_8.toString())
    val toNVP = (s: String) =>
      s.split("=").toList match {
        case Nil                  => List(NVP("", Some("")))
        case "" :: "" :: Nil      => List(NVP("", Some("")))
        case "" :: value :: Nil   => List(NVP("", Some(urlDecoded(value))))
        case name :: "" :: Nil    => List(NVP(name, None))
        case "" :: Nil            => Empty
        case name :: Nil          => List(NVP(name, None))
        case name :: value :: Nil => List(NVP(name, Some(urlDecoded(value))))
        case _                    => Empty
      }
    val split = (s: String) => s.split("&").toList.flatMap(toNVP)

    Option(s).map(split).getOrElse(Empty)
  }

  val cocoerce: CollectorPayload => Recovering[Payload.CollectorPayload] =
    (a: CollectorPayload) => cocoercePF.lift(a).toRight(UncocoerciblePayload(a.toString))
}
