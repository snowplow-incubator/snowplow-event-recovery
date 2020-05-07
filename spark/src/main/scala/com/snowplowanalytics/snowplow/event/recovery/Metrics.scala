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
package org.apache.spark.metrics.source

import com.codahale.metrics._
import com.snowplowanalytics.snowplow.event.recovery.domain.Result

class Metrics extends Source with Serializable {
  override val sourceName     = "summary"
  override val metricRegistry = new MetricRegistry

  val Vector(recovered, failed, unrecoverable) =
    Result.partitions.map(p => metricRegistry.counter(p.toString)).toVector

}
