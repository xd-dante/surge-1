// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.streams

import java.util.Properties
import akka.actor.ActorSystem
import akka.kafka.{ AutoSubscription, ConsumerSettings, Subscriptions }
import com.typesafe.config.{ Config, ConfigFactory }
import io.opentelemetry.api.trace.Tracer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.IsolationLevel
import org.apache.kafka.common.serialization.Deserializer
import surge.internal.streams.{ KafkaOffsetManagementSubscriptionProvider, KafkaStreamManager, ManagedDataPipeline, ManualOffsetManagementSubscriptionProvider }
import surge.internal.tracing.NoopTracerFactory
import surge.kafka.{ KafkaConsumerHelper, KafkaTopic, UltiKafkaConsumerConfig }
import surge.metrics.Metrics
import surge.streams.replay.{ DefaultEventReplaySettings, EventReplaySettings, EventReplayStrategy, NoOpEventReplayStrategy }

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

private[surge] object KafkaDataSourceConfigHelper {
  def consumerSettingsFromConfig[Key, Value](
      actorSystem: ActorSystem,
      config: Config,
      kafkaBrokers: String,
      consumerGroup: String,
      additionalProps: Map[String, String] = Map.empty)(implicit keyDeser: Deserializer[Key], valueDeser: Deserializer[Value]): ConsumerSettings[Key, Value] = {
    val consumerSessionTimeout = config.getDuration("surge.kafka-event-source.consumer.session-timeout")
    val autoOffsetReset = config.getString("surge.kafka-event-source.consumer.auto-offset-reset")

    val props = KafkaConsumerHelper.consumerPropsFromConfig(config, UltiKafkaConsumerConfig(consumerGroup, autoOffsetReset))
    ConsumerSettings[Key, Value](actorSystem, Some(keyDeser), Some(valueDeser))
      .withBootstrapServers(kafkaBrokers)
      .withProperty(ConsumerConfig.ISOLATION_LEVEL_CONFIG, IsolationLevel.READ_COMMITTED.toString.toLowerCase)
      .withProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, consumerSessionTimeout.toMillis.toString)
      .withProperties(props.asScala.toMap)
      .withProperties(additionalProps)
  }

}

trait DataSource[Key, Value] {
  private val defaultReplayStrategy = new NoOpEventReplayStrategy
  def replayStrategy: EventReplayStrategy = defaultReplayStrategy
  def replaySettings: EventReplaySettings = DefaultEventReplaySettings
}

trait KafkaDataSource[Key, Value] extends DataSource[Key, Value] {
  protected val config: Config = ConfigFactory.load()
  private lazy val defaultBrokers = config.getString("kafka.brokers")

  def kafkaBrokers: String = defaultBrokers
  def kafkaTopic: KafkaTopic
  def subscription: AutoSubscription = Subscriptions.topics(kafkaTopic.name)

  def actorSystem: ActorSystem

  def keyDeserializer: Deserializer[Key]
  def valueDeserializer: Deserializer[Value]

  def metrics: Metrics = Metrics.globalMetricRegistry

  def tracer: Tracer = NoopTracerFactory.create()

  def additionalKafkaProperties: Properties = new Properties()

  def offsetManager: OffsetManager = new DefaultKafkaOffsetManager

  def to(sink: DataHandler[Key, Value], consumerGroup: String): DataPipeline = {
    to(sink, consumerGroup, autoStart = true)
  }

  def to(sink: DataHandler[Key, Value], consumerGroup: String, autoStart: Boolean): DataPipeline = {
    val consumerSettings =
      KafkaDataSourceConfigHelper.consumerSettingsFromConfig(
        actorSystem = actorSystem,
        config = config,
        kafkaBrokers = kafkaBrokers,
        consumerGroup = consumerGroup,
        additionalProps = additionalKafkaProperties.asScala.toMap)(keyDeserializer, valueDeserializer)
    to(consumerSettings)(sink, autoStart)
  }

  private def getStreamManager(consumerSettings: ConsumerSettings[Key, Value], sink: DataHandler[Key, Value])(
      implicit actorSystem: ActorSystem): KafkaStreamManager[Key, Value] = {
    val topicName = kafkaTopic.name
    val subscriptionProvider = offsetManager match {
      case _: DefaultKafkaOffsetManager =>
        new KafkaOffsetManagementSubscriptionProvider[Key, Value](config, topicName, subscription, consumerSettings, sink)(tracer)
      case _ =>
        new ManualOffsetManagementSubscriptionProvider[Key, Value](config, topicName, subscription, consumerSettings, sink, offsetManager)(tracer)
    }
    new KafkaStreamManager[Key, Value](
      topicName = topicName,
      consumerSettings = consumerSettings,
      subscriptionProvider = subscriptionProvider,
      keyDeserializer = keyDeserializer,
      valueDeserializer = valueDeserializer,
      replayStrategy = replayStrategy,
      replaySettings = replaySettings,
      config = config)(tracer)
  }

  private[streams] def to(consumerSettings: ConsumerSettings[Key, Value])(sink: DataHandler[Key, Value], autoStart: Boolean): DataPipeline = {
    implicit val system: ActorSystem = actorSystem
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    val enableMetrics = config.getBoolean("surge.kafka-event-source.enable-kafka-metrics")
    val pipeline = new ManagedDataPipeline(getStreamManager(consumerSettings, sink), metrics, enableMetrics)
    if (autoStart) {
      pipeline.start()
    }
    pipeline
  }
}
