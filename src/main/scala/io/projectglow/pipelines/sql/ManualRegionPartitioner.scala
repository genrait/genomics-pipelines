/*
 * Copyright 2019 The Glow Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.projectglow.pipelines.sql

import org.apache.spark.Partitioner

class ManualRegionPartitioner[V](partitions: Int) extends Partitioner {

  override def numPartitions: Int = partitions

  def getPartition(key: Any): Int = {
    key match {
      case (_, f2: Int) => f2
      case i: Int => i
      case _ =>
        throw new Exception(
          "Unable to partition key %s without destination assignment.".format(key)
        )
    }
  }
}
