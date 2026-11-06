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

package com.snowplowanalytics.snowplow.event.recovery

import org.scalacheck._

case class Field(name: String, value: Any) {
  val strValue = value match {
    case Some(v) => v.toString
    case v       => v.toString
  }
}

object Field {
  def apply[A <: Product](payload: A): Field = {
    val fields = payload
      .getClass
      .getDeclaredFields
      .toList
      .map(_.getName)
      .zipWithIndex
      .filterNot { case (v, _) => Seq("querystring", "headers", "networkUserId", "userId", "timestamp").contains(v) }
      .toMap
    val filteredFieldId = Gen.chooseNum(0, fields.size - 1).sample.get
    val fieldId         = fields.values.toList(filteredFieldId)
    val field           = fields.keys.toList(filteredFieldId)
    val fieldValue      = payload.productIterator.toList(fieldId)
    Field(field, fieldValue)
  }
  def extract[A <: Product](payload: A, name: String): Option[Field] =
    payload
      .getClass
      .getDeclaredFields
      .toList
      .map(_.getName)
      .zip(payload.productIterator.toList)
      .toMap
      .get(name)
      .map(Field(name, _))
}
