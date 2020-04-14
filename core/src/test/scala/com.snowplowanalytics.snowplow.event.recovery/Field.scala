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
