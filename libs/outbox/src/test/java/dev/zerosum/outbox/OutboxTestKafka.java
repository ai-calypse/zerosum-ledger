package dev.zerosum.outbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A Kafka broker for the library's relay tests, pinned to the same image the compose stack runs (D00-1, D00-3).
 *
 * <p>Pausing the container is how the "Kafka is down" cases are exercised: a paused container keeps the TCP connection
 * open but never answers, which is closer to a real broker outage than stopping it (a stopped container refuses
 * connections immediately, so a send fails fast rather than hanging until the send timeout — the behaviour the relay's
 * backoff actually has to survive).
 */
final class OutboxTestKafka implements AutoCloseable {

    private final KafkaContainer container;

    private OutboxTestKafka() {
        container = new KafkaContainer(DockerImageName.parse(composeImage()).asCompatibleSubstituteFor("apache/kafka"));
    }

    static OutboxTestKafka start() {
        OutboxTestKafka kafka = new OutboxTestKafka();
        kafka.container.start();
        return kafka;
    }

    String bootstrapServers() {
        return container.getBootstrapServers();
    }

    /**
     * A producer configured the way the relay's is in order-service: idempotent, waiting for all in-sync replicas.
     * Anything weaker would let a send be acknowledged before the record is durable, which would make "marked only
     * after acknowledgement" a weaker guarantee than it reads.
     */
    KafkaTemplate<String, String> template() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Without a bound here a send against a paused broker blocks far past the relay's send timeout, and the test
        // would measure Kafka's default 2-minute delivery timeout instead of the relay's configured one.
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    /**
     * Creates the topic explicitly with more than one partition. Relying on auto-creation would give a single
     * partition, and a single partition orders every record regardless of key — which would make the per-key ordering
     * assertions pass without the relay doing anything to earn them.
     */
    void createTopic(String topic, int partitions) {
        Map<String, Object> config = Map.of("bootstrap.servers", bootstrapServers());
        try (var admin = org.apache.kafka.clients.admin.Admin.create(config)) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, partitions, (short) 1)))
                    .all()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted creating " + topic, interrupted);
        } catch (Exception failure) {
            if (failure.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
                return;
            }
            throw new IllegalStateException("could not create " + topic, failure);
        }
    }

    /** Reads everything currently on the topic, from the beginning, and stops when no more arrives. */
    List<ConsumerRecord<String, String>> drain(String topic, Duration patience) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + patience.toNanos();
            int emptyPolls = 0;
            while (System.nanoTime() < deadline && emptyPolls < 3) {
                var polled = consumer.poll(Duration.ofMillis(500));
                if (polled.isEmpty()) {
                    emptyPolls++;
                } else {
                    emptyPolls = 0;
                    polled.forEach(records::add);
                }
            }
        }
        return records;
    }

    void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(container.getContainerId()).exec();
    }

    void unpause() {
        DockerClientFactory.instance().client().unpauseContainerCmd(container.getContainerId()).exec();
    }

    @Override
    public void close() {
        container.stop();
    }

    /** The compose image, so the tests and the deployed stack never drift onto different broker versions. */
    private static String composeImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(apache/kafka:\\S+)\\s*$");
        try {
            return Files.readAllLines(OutboxTestDatabase.ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no apache/kafka image in docker-compose.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
