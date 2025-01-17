// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.streams.replay

import akka.Done
import akka.actor.{ Actor, ActorRef, ActorSystem, Props, Stash }
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.kafka.clients.consumer.{ ConsumerConfig, ConsumerRebalanceListener, KafkaConsumer, OffsetAndMetadata }
import org.apache.kafka.common.TopicPartition
import surge.internal.streams.PartitionAssignorConfig
import surge.internal.utils.Logging
import surge.kafka.{ KafkaBytesConsumer, UltiKafkaConsumerConfig }
import surge.streams.replay.TopicResetActor._

import java.util
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

trait KafkaForeverReplayStrategy extends EventReplayStrategy

object KafkaForeverReplayStrategy {
  def apply(
      config: Config,
      actorSystem: ActorSystem,
      settings: KafkaForeverReplaySettings,
      preReplay: () => Future[Any] = { () => Future.successful(true) },
      postReplay: () => Unit = { () => () })(implicit executionContext: ExecutionContext): KafkaForeverReplayStrategy = {
    new KafkaForeverReplayStrategyImpl(config, actorSystem, settings, preReplay, postReplay)
  }

  @deprecated("Use KafkaForeverReplayStrategy.apply instead", "0.5.17")
  def create[Key, Value](
      actorSystem: ActorSystem,
      settings: KafkaForeverReplaySettings,
      preReplay: () => Future[Any] = { () => Future.successful(true) },
      postReplay: () => Unit = { () => () }): KafkaForeverReplayStrategy = {
    val config = ConfigFactory.load()
    KafkaForeverReplayStrategy(config, actorSystem, settings, preReplay, postReplay)(ExecutionContext.global)
  }
}

class KafkaForeverReplayStrategyImpl(
    config: Config,
    actorSystem: ActorSystem,
    settings: KafkaForeverReplaySettings,
    override val preReplay: () => Future[Any] = { () => Future.successful(true) },
    override val postReplay: () => Unit = { () => () })(implicit executionContext: ExecutionContext)
    extends KafkaForeverReplayStrategy {

  override def createReplayController[Key, Value](context: ReplayControlContext[Key, Value]): KafkaForeverReplayControl =
    new KafkaForeverReplayControl(actorSystem, settings, config, preReplay, postReplay)
}

class KafkaForeverReplayControl(
    actorSystem: ActorSystem,
    settings: KafkaForeverReplaySettings,
    config: Config,
    override val preReplay: () => Future[Any] = { () => Future.successful(true) },
    override val postReplay: () => Unit = { () => () })(implicit executionContext: ExecutionContext)
    extends ReplayControl {
  private val underlyingActor = actorSystem.actorOf(Props(new TopicResetActor(settings.brokers, settings.topic, config)))

  implicit val timeout: Timeout = Timeout(settings.resetTopicTimeout)
  override def fullReplay(consumerGroup: String, partitions: Iterable[Int]): Future[Done] = {
    underlyingActor.ask(TopicResetActor.ResetTopic(consumerGroup, partitions.toList)).mapTo[ResetTopicResult].map {
      case TopicResetSucceed     => Done
      case TopicResetFailed(err) => throw err
    }
  }
}

trait EventReplaySettings {
  val entireReplayTimeout: FiniteDuration
}

case class KafkaForeverReplaySettings(brokers: List[String], resetTopicTimeout: FiniteDuration, entireReplayTimeout: FiniteDuration, topic: String)
    extends EventReplaySettings

object KafkaForeverReplaySettings {
  def apply(config: Config, topic: String): KafkaForeverReplaySettings = {
    val brokers = config.getString("kafka.brokers").split(",").toList
    val resetTopicTimeout: FiniteDuration = config.getDuration("kafka.streams.replay.reset-topic-timeout", TimeUnit.MILLISECONDS).milliseconds
    val entireProcessTimeout: FiniteDuration = config.getDuration("kafka.streams.replay.entire-process-timeout", TimeUnit.MILLISECONDS).milliseconds
    new KafkaForeverReplaySettings(brokers, resetTopicTimeout, entireProcessTimeout, topic)
  }

  @deprecated("Use create(config, topic) instead", "0.5.17")
  def create(topic: String): KafkaForeverReplaySettings = create(ConfigFactory.load(), topic)

  def create(config: Config, topic: String): KafkaForeverReplaySettings = apply(config, topic)
}

class TopicResetActor(brokers: List[String], kafkaTopic: String, config: Config) extends Actor with Stash with Logging {

  def ready: Receive = { case ResetTopic(consumerGroup, partitions) =>
    try {
      initialize(consumerGroup, partitions)
    } catch {
      case err: Throwable =>
        handleError(err, sender())
    }
  }

  def initializing(replyTo: ActorRef, consumer: KafkaConsumer[String, Array[Byte]], kafkaTopic: String, topicPartitions: List[TopicPartition]): Receive = {
    case PartitionsAssigned =>
      try {
        val commits = buildEarliestCommits(consumer, topicPartitions).asJava
        consumer.commitSync(commits)
        replyTo ! TopicResetSucceed
        context.become(ready)
        unstashAll()
      } catch {
        case err: Throwable =>
          handleError(err, replyTo)
      } finally {
        consumer.close()
      }
      log.debug(s"Replay Finished for $kafkaTopic")
    case _: ResetTopic =>
      stash()
  }

  def handleError(err: Throwable, replyTo: ActorRef): Unit = {
    log.error(s"Topic reset failed for $kafkaTopic", err)
    replyTo ! TopicResetFailed(err)
    context.become(ready)
    unstashAll()
  }

  def initialize(consumerGroup: String, partitions: List[Int]): Unit = {
    log.debug(s"Replay started for $kafkaTopic")
    val topicPartitions = partitions.map { partition => new TopicPartition(kafkaTopic, partition) }
    val consumer = KafkaBytesConsumer(
      config,
      brokers,
      UltiKafkaConsumerConfig(consumerGroup),
      Map(
        ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG -> "60000",
        ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG -> PartitionAssignorConfig.assignorClassName(config))).consumer
    consumer.subscribe(
      List(kafkaTopic).asJava,
      new ConsumerRebalanceListener() {
        override def onPartitionsAssigned(partitions: util.Collection[TopicPartition]): Unit = {
          if (partitions.size != topicPartitions.size) {
            throw new IllegalStateException(
              s"There are (${topicPartitions.size - partitions.size})" +
                s" active consumers for topic $kafkaTopic we can't proceed with replaying")
          }
          self ! PartitionsAssigned
        }
        override def onPartitionsRevoked(partitions: util.Collection[TopicPartition]): Unit = {}
      })
    // NOTE: non-deprecated #poll(Duration) method doesn't work because it includesMetadataInTimeout opposite to this one
    consumer.poll(1000)
    context.become(initializing(sender(), consumer, kafkaTopic, topicPartitions))
  }

  def buildEarliestCommits(consumer: KafkaConsumer[String, Array[Byte]], topicPartitions: List[TopicPartition]): Map[TopicPartition, OffsetAndMetadata] = {
    val beginningOffsets = consumer.beginningOffsets(topicPartitions.asJava)
    topicPartitions.map { tp =>
      val firstOffset = beginningOffsets.getOrDefault(tp, 0L)
      val offset = new OffsetAndMetadata(firstOffset)
      tp -> offset
    }.toMap
  }

  override def receive: Receive = ready
}

private[streams] object TopicResetActor {
  sealed trait ResetTopicCommand
  case class ResetTopic(consumerGroup: String, partitions: List[Int]) extends ResetTopicCommand

  sealed trait ResetTopicEvent
  case object PartitionsAssigned extends ResetTopicEvent

  sealed trait ResetTopicResult
  case object TopicResetSucceed extends ResetTopicResult
  case class TopicResetFailed(err: Throwable) extends ResetTopicResult
}
