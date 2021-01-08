// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.kafka.streams

import org.apache.kafka.common.TopicPartition
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.state.StreamsMetadata
import surge.scala.core.kafka.HostPort

import scala.collection.JavaConverters._

abstract class KafkaStreamsPartitionTracker(kafkaStreams: KafkaStreams) {
  protected def allMetadata(): Iterable[StreamsMetadata] = kafkaStreams.allMetadata().asScala
  protected def metadataByInstance(): Map[HostPort, List[TopicPartition]] = allMetadata()
    .groupBy(meta => HostPort(meta.host(), meta.port()))
    .mapValues(meta => meta.toList.flatMap(_.topicPartitions().asScala))
    .toMap

  def update(): Unit
}

trait KafkaStreamsPartitionTrackerProvider {
  def create(streams: KafkaStreams): KafkaStreamsPartitionTracker
}
