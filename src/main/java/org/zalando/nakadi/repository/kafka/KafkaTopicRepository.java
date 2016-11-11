package org.zalando.nakadi.repository.kafka;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import kafka.admin.AdminUtils;
import kafka.common.TopicExistsException;
import kafka.utils.ZkUtils;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.zalando.nakadi.config.NakadiSettings;
import org.zalando.nakadi.domain.BatchItem;
import org.zalando.nakadi.domain.Cursor;
import org.zalando.nakadi.domain.EventPublishingStatus;
import org.zalando.nakadi.domain.EventPublishingStep;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.EventTypeStatistics;
import org.zalando.nakadi.domain.SubscriptionBase;
import org.zalando.nakadi.domain.Topic;
import org.zalando.nakadi.domain.TopicPartition;
import org.zalando.nakadi.exceptions.DuplicatedEventTypeNameException;
import org.zalando.nakadi.exceptions.EventPublishingException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.exceptions.TopicCreationException;
import org.zalando.nakadi.exceptions.TopicDeletionException;
import org.zalando.nakadi.repository.EventConsumer;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.repository.zookeeper.ZooKeeperHolder;
import org.zalando.nakadi.repository.zookeeper.ZookeeperSettings;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toList;
import static org.zalando.nakadi.domain.CursorError.EMPTY_PARTITION;
import static org.zalando.nakadi.domain.CursorError.INVALID_FORMAT;
import static org.zalando.nakadi.domain.CursorError.NULL_OFFSET;
import static org.zalando.nakadi.domain.CursorError.NULL_PARTITION;
import static org.zalando.nakadi.domain.CursorError.PARTITION_NOT_FOUND;
import static org.zalando.nakadi.domain.CursorError.UNAVAILABLE;
import static org.zalando.nakadi.repository.kafka.KafkaCursor.fromNakadiCursor;
import static org.zalando.nakadi.repository.kafka.KafkaCursor.kafkaCursor;
import static org.zalando.nakadi.repository.kafka.KafkaCursor.toKafkaOffset;
import static org.zalando.nakadi.repository.kafka.KafkaCursor.toKafkaPartition;
import static org.zalando.nakadi.repository.kafka.KafkaCursor.toNakadiOffset;
import static org.zalando.nakadi.repository.kafka.KafkaCursor.toNakadiPartition;

@Component
@Profile("!test")
public class KafkaTopicRepository implements TopicRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicRepository.class);

    private final ZooKeeperHolder zkFactory;
    private final KafkaFactory kafkaFactory;
    private final NakadiSettings nakadiSettings;
    private final KafkaSettings kafkaSettings;
    private final ZookeeperSettings zookeeperSettings;
    private final KafkaPartitionsCalculator partitionsCalculator;

    @Autowired
    public KafkaTopicRepository(final ZooKeeperHolder zkFactory,
                                final KafkaFactory kafkaFactory,
                                final NakadiSettings nakadiSettings,
                                final KafkaSettings kafkaSettings,
                                final ZookeeperSettings zookeeperSettings,
                                final KafkaPartitionsCalculator partitionsCalculator) {
        this.zkFactory = zkFactory;
        this.kafkaFactory = kafkaFactory;
        this.nakadiSettings = nakadiSettings;
        this.kafkaSettings = kafkaSettings;
        this.zookeeperSettings = zookeeperSettings;
        this.partitionsCalculator = partitionsCalculator;
    }

    @Override
    public List<Topic> listTopics() throws ServiceUnavailableException {
        try {
            return zkFactory.get()
                    .getChildren()
                    .forPath("/brokers/topics")
                    .stream()
                    .map(Topic::new)
                    .collect(toList());
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Failed to list topics", e);
        }
    }

    @Override
    public void createTopic(final EventType eventType) throws TopicCreationException, DuplicatedEventTypeNameException {
        if (eventType.getOptions().getRetentionTime() == null) {
            throw new IllegalArgumentException("Retention time can not be null");
        }
        createTopic(eventType.getTopic(),
                calculateKafkaPartitionCount(eventType.getDefaultStatistic()),
                nakadiSettings.getDefaultTopicReplicaFactor(),
                eventType.getOptions().getRetentionTime(),
                nakadiSettings.getDefaultTopicRotationMs());
    }

    private void createTopic(final String topic, final int partitionsNum, final int replicaFactor,
                             final long retentionMs, final long rotationMs)
            throws TopicCreationException, DuplicatedEventTypeNameException {
        try {
            doWithZkUtils(zkUtils -> {
                final Properties topicConfig = new Properties();
                topicConfig.setProperty("retention.ms", Long.toString(retentionMs));
                topicConfig.setProperty("segment.ms", Long.toString(rotationMs));
                AdminUtils.createTopic(zkUtils, topic, partitionsNum, replicaFactor, topicConfig);
            });
        } catch (final TopicExistsException e) {
            throw new DuplicatedEventTypeNameException("EventType with name " + topic +
                    " already exists (or wasn't completely removed yet)");
        } catch (final Exception e) {
            throw new TopicCreationException("Unable to create topic " + topic, e);
        }
    }

    @Override
    public void deleteTopic(final String topic) throws TopicDeletionException {
        try {
            // this will only trigger topic deletion, but the actual deletion is asynchronous
            doWithZkUtils(zkUtils -> AdminUtils.deleteTopic(zkUtils, topic));
        } catch (final Exception e) {
            throw new TopicDeletionException("Unable to delete topic " + topic, e);
        }
    }

    @Override
    public boolean topicExists(final String topic) throws ServiceUnavailableException {
        return listTopics()
                .stream()
                .map(Topic::getName)
                .anyMatch(t -> t.equals(topic));
    }

    @Override
    public boolean partitionExists(final String topic, final String partition) throws NakadiException {
        return listPartitionNames(topic).stream()
                .anyMatch(partition::equals);
    }

    @Override
    public void syncPostBatch(final String topicId, final List<BatchItem> batchItems) throws EventPublishingException {
        final Producer<String, String> producer = kafkaFactory.takeProducer();
        try {
            final Map<String, String> partitionToBroker = producer.partitionsFor(topicId).stream()
                    .collect(Collectors.toMap(p -> String.valueOf(p.partition()),p -> String.valueOf(p.leader().id())));
            batchItems.forEach(item -> {
                Preconditions.checkNotNull(
                        item.getPartition(), "BatchItem partition can't be null at the moment of publishing!");
                item.setBrokerId(partitionToBroker.get(item.getPartition()));
                item.setTopic(topicId);
            });

            final List<ProducerSendCommand.NakadiCallback> callbacks = batchItems.stream()
                    .peek(item -> item.setStep(EventPublishingStep.PUBLISHING))
                    .map(item -> new ProducerSendCommand(producer, item).execute())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            waitCallbacksCompleted(producer, callbacks, createSendTimeout());

            for (final BatchItem batchItem: batchItems) {
                if (batchItem.getResponse().getPublishingStatus() == EventPublishingStatus.FAILED)
                    throw new EventPublishingException("Error publishing message to kafka");
            }
        } finally {
            kafkaFactory.releaseProducer(producer);
        }
    }

    private void waitCallbacksCompleted(final Producer<String, String> producer,
                                        final List<ProducerSendCommand.NakadiCallback> callbacks,
                                        final long timeout)
            throws EventPublishingException {
        long commandTimeout = timeout;
        for (final ProducerSendCommand.NakadiCallback callback : callbacks) {
            final long start = System.currentTimeMillis();
            try {
                new ProducerSentCallbackCommand(callback,
                        () -> kafkaFactory.terminateProducer(producer),
                        commandTimeout).execute();
            } catch (final HystrixRuntimeException hre) {
                if (hre.getFailureType() == HystrixRuntimeException.FailureType.TIMEOUT) {
                    final BatchItem batchItem = callback.getBatchItem();
                    LOG.error("Kafka timeout: {} on broker {}", batchItem.getTopic(), batchItem.getBrokerId(), hre);
                    batchItem.updateStatusAndDetail(EventPublishingStatus.FAILED, "timed out");
                    throw new EventPublishingException("Error publishing message to kafka", hre);
                }
            }
            commandTimeout -= System.currentTimeMillis() - start;
        }
    }

    private long createSendTimeout() {
        return nakadiSettings.getKafkaSendTimeoutMs() + kafkaSettings.getRequestTimeoutMs();
    }

    @Override
    public List<TopicPartition> listPartitions(final String topicId) throws ServiceUnavailableException {
        try (final Consumer<String, String> consumer = kafkaFactory.getConsumer()) {
            final List<org.apache.kafka.common.TopicPartition> kafkaTPs = consumer
                    .partitionsFor(topicId)
                    .stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(topicId, p.partition()))
                    .collect(toList());

            return toNakadiTopicPartition(consumer, kafkaTPs);
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Error occurred when fetching partitions offsets", e);
        }
    }

    @Override
    public List<TopicPartition> listPartitions(final Set<String> topics) throws ServiceUnavailableException {
        try (final Consumer<String, String> consumer = kafkaFactory.getConsumer()) {
            final List<org.apache.kafka.common.TopicPartition> kafkaTPs = consumer.listTopics().entrySet().stream()
                    .filter(entry -> topics.contains(entry.getKey()))
                    .flatMap(entry -> entry.getValue().stream()
                            .map(partitionInfo -> new org.apache.kafka.common.TopicPartition(entry.getKey(),
                                    partitionInfo.partition()))
                    )
                    .collect(toList());

            return toNakadiTopicPartition(consumer, kafkaTPs);
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Error occurred when fetching partitions offsets", e);
        }
    }

    private List<TopicPartition> toNakadiTopicPartition(final Consumer<String, String> consumer,
                                                        final List<org.apache.kafka.common.TopicPartition> kafkaTPs) {
        consumer.assign(kafkaTPs);

        final org.apache.kafka.common.TopicPartition[] tpArray =
                kafkaTPs.toArray(new org.apache.kafka.common.TopicPartition[kafkaTPs.size()]);

        consumer.seekToBeginning(tpArray);
        final Map<Integer, Long> earliestOffsets = getPositions(consumer, kafkaTPs);

        consumer.seekToEnd(tpArray);
        final Map<Integer, Long> latestOffsets = getPositions(consumer, kafkaTPs);

        return kafkaTPs
                .stream()
                .map(tp -> {
                    final int partition = tp.partition();
                    final String topic = tp.topic();
                    final TopicPartition topicPartition = new TopicPartition(topic, toNakadiPartition(partition));

                    final Long latestOffset = latestOffsets.get(partition);
                    topicPartition.setNewestAvailableOffset(transformNewestOffset(latestOffset));

                    topicPartition.setOldestAvailableOffset(toNakadiOffset(earliestOffsets.get(partition)));
                    return topicPartition;
                })
                .collect(toList());
    }

    @Override
    public Map<String, Long> materializePositions(final String topicId, final SubscriptionBase.InitialPosition position)
            throws ServiceUnavailableException {
        try (final Consumer<String, String> consumer = kafkaFactory.getConsumer()) {

            final org.apache.kafka.common.TopicPartition[] kafkaTPs = consumer
                    .partitionsFor(topicId)
                    .stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(topicId, p.partition()))
                    .toArray(org.apache.kafka.common.TopicPartition[]::new);
            consumer.assign(Arrays.asList(kafkaTPs));
            if (position == SubscriptionBase.InitialPosition.BEGIN) {
                consumer.seekToBeginning(kafkaTPs);
            } else if (position == SubscriptionBase.InitialPosition.END) {
                consumer.seekToEnd(kafkaTPs);
            } else {
                throw new IllegalArgumentException("Bad offset specification " + position + " for topic " + topicId);
            }
            return Stream.of(kafkaTPs).collect(Collectors.toMap(
                    tp -> String.valueOf(tp.partition()),
                    consumer::position));
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Error occurred when fetching partitions offsets", e);
        }

    }

    @Override
    public List<String> listPartitionNames(final String topicId) {
        final Producer<String, String> producer = kafkaFactory.takeProducer();
        try {
            return unmodifiableList(producer.partitionsFor(topicId)
                    .stream()
                    .map(partitionInfo -> toNakadiPartition(partitionInfo.partition()))
                    .collect(toList()));
        } finally {
            kafkaFactory.releaseProducer(producer);
        }
    }

    private String transformNewestOffset(final Long newestOffset) {
        return newestOffset == 0 ? Cursor.BEFORE_OLDEST_OFFSET : toNakadiOffset(newestOffset - 1);
    }

    private Map<Integer, Long> getPositions(final Consumer<String, String> consumer,
                                            final List<org.apache.kafka.common.TopicPartition> kafkaTPs) {
        return kafkaTPs
                .stream()
                .collect(Collectors.toMap(
                        org.apache.kafka.common.TopicPartition::partition,
                        consumer::position
                ));
    }

    @Override
    public TopicPartition getPartition(final String topicId, final String partition)
            throws ServiceUnavailableException {
        try (final Consumer<String, String> consumer = kafkaFactory.getConsumer()) {

            final org.apache.kafka.common.TopicPartition tp =
                    new org.apache.kafka.common.TopicPartition(topicId, toKafkaPartition(partition));

            consumer.assign(ImmutableList.of(tp));

            final TopicPartition topicPartition = new TopicPartition(topicId, partition);

            consumer.seekToBeginning(tp);
            topicPartition.setOldestAvailableOffset(toNakadiOffset(consumer.position(tp)));

            consumer.seekToEnd(tp);
            final Long latestOffset = consumer.position(tp);
            topicPartition.setNewestAvailableOffset(transformNewestOffset(latestOffset));

            return topicPartition;
        } catch (final Exception e) {
            throw new ServiceUnavailableException("Error occurred when fetching partition offsets", e);
        }
    }

    public Consumer<String, String> createKafkaConsumer() {
        return kafkaFactory.getConsumer();
    }

    @Override
    public EventConsumer createEventConsumer(final String topic, final List<Cursor> cursors)
            throws ServiceUnavailableException, InvalidCursorException {
        this.validateCursors(topic, cursors);

        final List<KafkaCursor> kafkaCursors = Lists.newArrayListWithCapacity(cursors.size());

        for (final Cursor cursor : cursors) {
            final String offset = cursor.getOffset();
            final String partition = cursor.getPartition();

            final long kafkaOffset;
            if (Cursor.BEFORE_OLDEST_OFFSET.equals(offset)) {
                final TopicPartition tp = getPartition(topic, partition);
                kafkaOffset = toKafkaOffset(tp.getOldestAvailableOffset());
            } else {
                kafkaOffset = toKafkaOffset(offset) + 1L;
            }

            final KafkaCursor kafkaCursor = kafkaCursor(toKafkaPartition(partition), kafkaOffset);
            kafkaCursors.add(kafkaCursor);
        }

        return kafkaFactory.createNakadiConsumer(topic, kafkaCursors, nakadiSettings.getKafkaPollTimeoutMs());
    }

    public int compareOffsets(final String firstOffset, final String secondOffset) {
        try {
            final long first = toKafkaOffset(firstOffset);
            final long second = toKafkaOffset(secondOffset);
            return Long.compare(first, second);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Incorrect offset format, should be long", e);
        }
    }

    private void validateCursors(final String topic, final List<Cursor> cursors) throws ServiceUnavailableException,
            InvalidCursorException {
        final List<TopicPartition> partitions = listPartitions(topic);

        for (final Cursor cursor : cursors) {
            validateCursorForNulls(cursor);

            final TopicPartition topicPartition = partitions
                    .stream()
                    .filter(tp -> tp.getPartitionId().equals(cursor.getPartition()))
                    .findFirst()
                    .orElseThrow(() -> new InvalidCursorException(PARTITION_NOT_FOUND, cursor));

            if (Cursor.BEFORE_OLDEST_OFFSET.equals(cursor.getOffset())) {
                continue;
            } else if (Cursor.BEFORE_OLDEST_OFFSET.equals(topicPartition.getNewestAvailableOffset())) {
                throw new InvalidCursorException(EMPTY_PARTITION, cursor);
            }

            final long newestOffset = toKafkaOffset(topicPartition.getNewestAvailableOffset());
            final long oldestOffset = toKafkaOffset(topicPartition.getOldestAvailableOffset());
            try {
                final long offset = fromNakadiCursor(cursor).getOffset();
                if (offset < oldestOffset - 1 || offset > newestOffset) {
                    throw new InvalidCursorException(UNAVAILABLE, cursor);
                }
            } catch (final NumberFormatException e) {
                throw new InvalidCursorException(INVALID_FORMAT, cursor);
            }
        }
    }

    @Override
    public void validateCommitCursors(final String topic, final List<? extends Cursor> cursors)
            throws InvalidCursorException {
        final List<String> partitions = this.listPartitionNames(topic);
        for (final Cursor cursor : cursors) {
            validateCursorForNulls(cursor);
            if (!partitions.contains(cursor.getPartition())) {
                throw new InvalidCursorException(PARTITION_NOT_FOUND, cursor);
            }
            try {
                fromNakadiCursor(cursor);
            } catch (final NumberFormatException e) {
                throw new InvalidCursorException(INVALID_FORMAT, cursor);
            }
        }
    }

    private void validateCursorForNulls(final Cursor cursor) throws InvalidCursorException {
        if (cursor.getPartition() == null) {
            throw new InvalidCursorException(NULL_PARTITION, cursor);
        }
        if (cursor.getOffset() == null) {
            throw new InvalidCursorException(NULL_OFFSET, cursor);
        }
    }

    @FunctionalInterface
    private interface ZkUtilsAction {
        void execute(ZkUtils zkUtils) throws Exception;
    }

    private void doWithZkUtils(final ZkUtilsAction action) throws Exception {
        ZkUtils zkUtils = null;
        try {
            final String connectionString = zkFactory.get().getZookeeperClient().getCurrentConnectionString();
            zkUtils = ZkUtils.apply(connectionString, zookeeperSettings.getZkSessionTimeoutMs(),
                    zookeeperSettings.getZkConnectionTimeoutMs(), false);
            action.execute(zkUtils);
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
    }

    @VisibleForTesting
    Integer calculateKafkaPartitionCount(final EventTypeStatistics stat) {
        if (null == stat) {
            return nakadiSettings.getDefaultTopicPartitionCount();
        }
        final int maxPartitionsDueParallelism = Math.max(stat.getReadParallelism(), stat.getWriteParallelism());
        if (maxPartitionsDueParallelism >= nakadiSettings.getMaxTopicPartitionCount()) {
            return nakadiSettings.getMaxTopicPartitionCount();
        }
        return Math.min(nakadiSettings.getMaxTopicPartitionCount(), Math.max(
                maxPartitionsDueParallelism,
                calculatePartitionsAccordingLoad(stat.getMessagesPerMinute(), stat.getMessageSize())));
    }

    private int calculatePartitionsAccordingLoad(final int messagesPerMinute, final int avgEventSizeBytes) {
        final float throughoutputMbPerSec = ((float) messagesPerMinute * (float) avgEventSizeBytes)
                / (1024.f * 1024.f * 60.f);
        return partitionsCalculator.getBestPartitionsCount(avgEventSizeBytes, throughoutputMbPerSec);
    }
}
