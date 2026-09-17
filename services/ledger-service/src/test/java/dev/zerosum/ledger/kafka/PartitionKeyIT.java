package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ADR-0007 at the broker: one order group is confined to one partition.
 *
 * <p>Carried over from S04-T01, where it could not run because nothing published yet. No ledger or Spring context is
 * needed — the claim is entirely about how the producer maps keys to partitions.
 */
@Tag("integration")
class PartitionKeyIT {

    private static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    private static final String TOPIC = TopicDefinitions.MONEY_ORDERS.name();
    private static KafkaContainer kafka;

    @BeforeAll
    static void start() throws Exception {
        kafka = new KafkaContainer(DockerImageName.parse(composeKafkaImage())
                .asCompatibleSubstituteFor("apache/kafka"));
        kafka.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, TopicDefinitions.MONEY_ORDERS.partitions(),
                    TopicDefinitions.LOCAL_REPLICATION_FACTOR))).all().get(60, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stop() {
        kafka.stop();
    }

    @Test
    void everyRecordForOneOrderGroupLandsOnOnePartition() {
        // A trip's original order and its adjustments must not overtake one another, and that holds only if they
        // share a partition.
        var partitions = new HashSet<Integer>();
        try (var producer = producer()) {
            for (int i = 0; i < 20; i++) {
                partitions.add(send(producer, "trip_8f2c", "{\"n\":" + i + "}"));
            }
        }
        assertEquals(1, partitions.size(),
                "one order group must occupy exactly one partition (ADR-0007), found " + partitions);
    }

    @Test
    void differentOrderGroupsAreSpreadRatherThanForcedOntoOnePartition() {
        // The other half of the decision: keying by group must not collapse the topic to a single hot partition,
        // which would cap throughput at one consumer for no ordering benefit.
        var partitions = new HashSet<Integer>();
        try (var producer = producer()) {
            for (int i = 0; i < 60; i++) {
                partitions.add(send(producer, "trip_" + i, "{}"));
            }
        }
        assertTrue(partitions.size() > 1,
                "60 distinct groups should spread across the topic's partitions, found " + partitions.size());
        assertTrue(partitions.size() <= TopicDefinitions.MONEY_ORDERS.partitions(),
                "records cannot land outside the contracted partition count");
    }

    private static int send(KafkaProducer<String, String> producer, String key, String payload) {
        try {
            return producer.send(new ProducerRecord<>(TOPIC, key, payload)).get(30, TimeUnit.SECONDS).partition();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("could not publish", failure);
        }
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(), "acks", "all"),
                new StringSerializer(), new StringSerializer());
    }

    private static String composeKafkaImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(apache/kafka:\\S+)\\s*$");
        try {
            return Files.readAllLines(ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no apache/kafka image in docker-compose.yml"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
