package dev.zerosum.spike.sp3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** SP3 evidence C: the same flow against Testcontainers PostgreSQL and Kafka, without the agent. */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ZS_ORDERS_OWNER_DB_PASSWORD=unused", "management.otlp.metrics.export.enabled=false"})
class Sp3FlowIT {

    @Container
    @ServiceConnection(name = "postgres")
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(composeImage("postgres")).asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection(name = "apache/kafka")
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse(composeImage("apache/kafka")).asCompatibleSubstituteFor("apache/kafka"));

    @Autowired
    Environment env;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry registry;

    @Test
    void httpToKafkaToDatabaseFlowWorks() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + env.getProperty("local.server.port")
                                + "/spike/messages"))
                        .POST(HttpRequest.BodyPublishers.ofString("hello-sp3"))
                        .header("Content-Type", "text/plain")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());

        long deadline = System.currentTimeMillis() + 30_000;
        long consumed = 0;
        while (consumed == 0 && System.currentTimeMillis() < deadline) {
            consumed = jdbc.sql("SELECT count(*) FROM sp3_spike.consumed WHERE body = 'hello-sp3'")
                    .query(Long.class).single();
            Thread.sleep(200);
        }
        assertEquals(1, consumed, "listener row");
        assertEquals(1, jdbc.sql("SELECT count(*) FROM sp3_spike.flyway_schema_history WHERE version = '1' AND success")
                .query(Long.class).single(), "Flyway migration applied at startup");
        assertTrue(registry.get("sp3.spike.consumed").counter().count() >= 1, "custom meter incremented");
    }

    private static String composeImage(String repository) {
        try {
            var pattern = Pattern.compile("^\\s*image:\\s*(" + Pattern.quote(repository) + ":\\S+)\\s*$");
            return Files.readAllLines(Path.of(System.getProperty("zs.rootDir"), "docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
