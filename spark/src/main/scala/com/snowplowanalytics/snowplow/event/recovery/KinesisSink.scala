/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.event.recovery

import java.nio.ByteBuffer
import java.util.concurrent.ScheduledExecutorService
import com.amazonaws.auth._
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.model._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContextExecutorService, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import cats.syntax.either._
import org.apache.spark.TaskContext

class KinesisSink private (
  client: AmazonKinesis,
  kinesisConfig: KinesisSink.Config,
  streamName: String,
  executorService: ScheduledExecutorService
) extends Serializable {
  import KinesisSink._
  lazy val log = LoggerFactory.getLogger(getClass)

  val MaxBytes: Int = 1000000

  private val ByteThreshold   = kinesisConfig.bufferConfig.map(_.byteLimit).getOrElse(1000L)
  private val RecordThreshold = kinesisConfig.bufferConfig.map(_.recordLimit).getOrElse(100L)
  private val TimeThreshold   = kinesisConfig.bufferConfig.map(_.timeLimit).getOrElse(10L)

  private val maxBackoff      = kinesisConfig.backoffPolicy.map(_.maxBackoff).getOrElse(100000L)
  private val minBackoff      = kinesisConfig.backoffPolicy.map(_.minBackoff).getOrElse(10L)
  private val randomGenerator = new java.util.Random()

  implicit lazy val ec: ExecutionContextExecutorService =
    concurrent.ExecutionContext.fromExecutorService(executorService)

  def write(task: TaskContext, data: Iterator[Array[Byte]]): Unit =
    storeRawEvents(data.toList, task.partitionId().toString)

  def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    events.foreach(e => EventStorage.store(e, key))

  object EventStorage extends Serializable {
    private val storedEvents              = ListBuffer.empty[Events]
    private var byteCount                 = 0L
    @volatile private var lastFlushedTime = 0L

    def store(event: Array[Byte], key: String): Unit = {
      log.debug("Storing event in buffer")
      val eventBytes = ByteBuffer.wrap(event)
      val eventSize  = eventBytes.capacity

      synchronized {
        if (storedEvents.size + 1 > RecordThreshold || byteCount + eventSize > ByteThreshold) {
          flush()
        }
        storedEvents += Events(eventBytes.array(), key)
        byteCount += eventSize
      }
    }

    def flush(): Unit = {
      val eventsToSend = synchronized {
        val evts = storedEvents.result
        storedEvents.clear()
        byteCount = 0
        evts
      }
      lastFlushedTime = System.currentTimeMillis()
      sinkBatch(eventsToSend, minBackoff)
    }

    def getLastFlushTime: Long = lastFlushedTime

    def scheduleFlush(interval: Long = TimeThreshold): Unit = {
      executorService.schedule(
        new Runnable {
          override def run(): Unit = {
            val lastFlushed = getLastFlushTime
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFlushed >= TimeThreshold) {
              flush()
              scheduleFlush(TimeThreshold)
            } else {
              scheduleFlush(TimeThreshold + lastFlushed - currentTime)
            }
          }
        },
        interval,
        MILLISECONDS
      )
      ()
    }
  }

  def sinkBatch(batch: List[Events], nextBackoff: Long): Unit = {
    log.info(s"Writing ${batch.size} Thrift records to Kinesis stream $streamName.")
    writeBatchToKinesis(batch).onComplete {
      case Success(s) =>
        val results      = s.getRecords.asScala.toList
        val failurePairs = batch.zip(results).filter(_._2.getErrorMessage != null)
        log.info(
          s"Successfully wrote ${batch.size - failurePairs.size} out of ${batch.size} records to Kinesis stream $streamName."
        )
        if (failurePairs.nonEmpty) {
          failurePairs.foreach(f =>
            log.error(
              s"Failed writing record to Kinesis stream $streamName, with error code [${f._2.getErrorCode}] and message [${f._2.getErrorMessage}]."
            )
          )
          val failures = failurePairs.map(_._1)
          val errorMessage =
            s"Retrying all records that could not be written to Kinesis stream $streamName in $nextBackoff milliseconds..."
          log.error(errorMessage)
          scheduleWrite(failures, nextBackoff)
        }
      case Failure(f) =>
        log.error("Writing failed with error:", f)
        log.error(s"Retrying writing batch to Kinesis stream $streamName in $nextBackoff milliseconds...")

        scheduleWrite(batch, nextBackoff)
    }
  }

  def writeBatchToKinesis(batch: List[Events]): Future[PutRecordsResult] =
    Future {
      val putRecordsRequest = {
        val prr = new PutRecordsRequest()
        prr.setStreamName(streamName)
        val putRecordsRequestEntryList = batch.map { event =>
          val prre = new PutRecordsRequestEntry()
          prre.setPartitionKey(event.key)
          prre.setData(ByteBuffer.wrap(event.payloads))
          prre
        }
        prr.setRecords(putRecordsRequestEntryList.asJava)
        prr
      }
      log.debug("Writing records to Kinesis")
      client.putRecords(putRecordsRequest)
    }

  def scheduleWrite(batch: List[Events], lastBackoff: Long = minBackoff): Unit = {
    val nextBackoff = getNextBackoff(lastBackoff)
    executorService.schedule(
      new Runnable {
        override def run(): Unit = sinkBatch(batch, nextBackoff)
      },
      lastBackoff,
      MILLISECONDS
    )
    ()
  }

  private def getNextBackoff(lastBackoff: Long): Long = {
    val diff = (maxBackoff - minBackoff + 1).toInt
    (minBackoff + randomGenerator.nextInt(diff)).max(lastBackoff / 3 * 2)
  }

  def shutdown(): Unit = {
    EventStorage.flush()
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)
    ()
  }
}

object KinesisSink {

  final case class Config(
    region: String,
    endpoint: Option[String] = None,
    backoffPolicy: Option[BackoffPolicy] = None,
    bufferConfig: Option[BufferConfig] = None,
    credentials: Option[Credentials] = None,
    threadpool: Option[Int] = Some(8)
  )
  final case class Credentials(accessKey: String, secretKey: String)
  final case class BackoffPolicy(minBackoff: Long, maxBackoff: Long)
  final case class BufferConfig(byteLimit: Long, recordLimit: Long, timeLimit: Long)

  final case class Events(payloads: Array[Byte], key: String)

  def createAndInitialize(
    kinesisConfig: Config,
    streamName: String,
    enableStartupChecks: Boolean,
    executorService: ScheduledExecutorService
  ): Either[Throwable, KinesisSink] = {
    val client = for {
      kinesisClient <- createKinesisClient(
        kinesisConfig.region,
        kinesisConfig.endpoint,
        kinesisConfig.credentials.map(_.accessKey),
        kinesisConfig.credentials.map(_.secretKey)
      )
      _ = if (enableStartupChecks) runChecks(kinesisClient, streamName)
    } yield kinesisClient

    client.map { kinesisClient =>
      val ks =
        new KinesisSink(
          kinesisClient,
          kinesisConfig,
          streamName,
          executorService
        )
      ks.EventStorage.scheduleFlush()
      ks
    }
  }

  private def createKinesisClient(
    region: String,
    endpoint: Option[String] = None,
    accessKey: Option[String] = None,
    secretKey: Option[String] = None
  ): Either[Throwable, AmazonKinesis] = Either.catchNonFatal {
    (endpoint, accessKey, secretKey) match {
      case (Some(endpointValue), Some(accessValue), Some(secretValue)) =>
        AmazonKinesisClientBuilder
          .standard()
          .withCredentials(
            new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessValue, secretValue))
          )
          .withEndpointConfiguration(new EndpointConfiguration(endpointValue, region))
          .build()
      case _ =>
        AmazonKinesisClientBuilder.defaultClient()
    }
  }

  private def runChecks(
    kinesisClient: AmazonKinesis,
    streamName: String
  ): Unit = {
    lazy val log = LoggerFactory.getLogger(getClass)
    val kExists  = streamExists(kinesisClient, streamName)

    kExists match {
      case Success(true) =>
        ()
      case Success(false) =>
        log.error(s"Kinesis stream $streamName doesn't exist or isn't available.")
      case Failure(t) =>
        log.error(s"Error checking if stream $streamName exists", t)
    }
  }

  private def streamExists(client: AmazonKinesis, name: String): Try[Boolean] =
    Try {
      val describeStreamResult = client.describeStream(name)
      val status               = describeStreamResult.getStreamDescription.getStreamStatus
      status == "ACTIVE" || status == "UPDATING"
    }.recover { case _: ResourceNotFoundException =>
      false
    }

  def split(
    batch: List[Events],
    getByteSize: Events => Int,
    maxRecords: Int,
    maxBytes: Int
  ): List[List[Events]] = {
    var bytes = 0L
    @scala.annotation.tailrec
    def go(originalBatch: List[Events], tmpBatch: List[Events], newBatch: List[List[Events]]): List[List[Events]] =
      (originalBatch, tmpBatch) match {
        case (Nil, Nil) => newBatch
        case (Nil, acc) => acc :: newBatch
        case (h :: t, acc) if acc.size + 1 > maxRecords || getByteSize(h) + bytes > maxBytes =>
          bytes = getByteSize(h).toLong
          go(t, h :: Nil, acc :: newBatch)
        case (h :: t, acc) =>
          bytes += getByteSize(h)
          go(t, h :: acc, newBatch)
      }
    go(batch, Nil, Nil).map(_.reverse).reverse.filter(_.nonEmpty)
  }

  def getByteSize(events: Events): Int = ByteBuffer.wrap(events.payloads).capacity
}
