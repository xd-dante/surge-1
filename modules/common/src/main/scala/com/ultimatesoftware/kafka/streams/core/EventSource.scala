// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.kafka.streams.core

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import com.ultimatesoftware.akka.streams.kafka.KafkaStreamManager
import org.apache.kafka.common.serialization.{ ByteArrayDeserializer, Deserializer, StringDeserializer }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.Future

trait EventSource[Event, EvtMeta] extends DataSource[String, Array[Byte]] {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def formatting: SurgeEventReadFormatting[Event, EvtMeta]

  @deprecated("Used as the default consumer group when using to(sink), but to(sink, consumerGroup) should be used instead.", "0.4.11")
  def consumerGroup: String = {
    log.error(
      "Using deprecated default for consumerGroup from calling the EventSource.to(sink) method! " +
        "Business logic should use the EventSource.to(sink, consumerGroup) method to explicitly set a consumer group per data pipeline." +
        "This method may be removed in a future minor release of Surge.  The line calling this deprecated method can be found in the following stack trace:",
      new RuntimeException())

    "deprecated-default-surge-consumer-group-for-compatibility"
  }

  override val actorSystem: ActorSystem = ActorSystem()
  override val keyDeserializer: Deserializer[String] = new StringDeserializer()
  override val valueDeserializer: Deserializer[Array[Byte]] = new ByteArrayDeserializer()
  private lazy val envelopeUtils = new EnvelopeUtils(formatting)

  private def dataSink(eventSink: EventSink[Event, EvtMeta]): DataSink[String, Array[Byte]] = {
    (key: String, value: Array[Byte]) ⇒
      {
        envelopeUtils.eventFromBytes(value) match {
          case Some(eventPlusMeta) ⇒
            eventSink.handleEvent(eventPlusMeta.event, eventPlusMeta.meta)
          case None ⇒
            log.error(s"Unable to deserialize event from kafka value, key = $key, value = $value")
            Future.successful(Done)
        }
      }
  }

  @deprecated("Use to(sink, consumerGroup) instead to avoid sharing consumer groups across different data pipelines.  " +
    "You should also remove the `consumerGroup` override once you switch to using to(sink, consumerGroup)", "0.4.11")
  def to(sink: EventSink[Event, EvtMeta]): Unit = {
    to(sink, consumerGroup)
  }

  def to(sink: EventSink[Event, EvtMeta], consumerGroup: String): Unit = {
    super.to(dataSink(sink), consumerGroup)
  }

  private[core] def to(consumerSettings: ConsumerSettings[String, Array[Byte]])(sink: EventSink[Event, EvtMeta]): Unit = {
    super.to(consumerSettings)(dataSink(sink))
  }
}

class DataPipeline[Key, Value](underlyingManager: KafkaStreamManager[Key, Value]) {
  def stop(): Unit = {
    underlyingManager.stop()
  }
  def start(): Unit = {
    underlyingManager.start()
  }
}