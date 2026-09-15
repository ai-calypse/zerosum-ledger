package dev.zerosum.spike.sp3;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** SP3: HTTP POST → JDBC insert → Kafka send → @KafkaListener → JDBC insert, with one custom meter. */
@SpringBootApplication
public class Sp3Application {

    static final String TOPIC = "sp3.spike";

    public static void main(String[] args) {
        SpringApplication.run(Sp3Application.class, args);
    }

    /** The broker has auto topic creation disabled (D00-3), so the spike creates its topic explicitly. */
    @Bean
    NewTopic spikeTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
    }

    @RestController
    static class SpikeController {

        private final JdbcClient jdbc;
        private final KafkaTemplate<String, String> kafka;

        SpikeController(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
            this.jdbc = jdbc;
            this.kafka = kafka;
        }

        @PostMapping("/spike/messages")
        Map<String, Object> post(@RequestBody String body) throws Exception {
            UUID id = UUID.randomUUID();
            jdbc.sql("INSERT INTO sp3_spike.inbound (id, body) VALUES (:id, :body)")
                    .param("id", id)
                    .param("body", body)
                    .update();
            var result = kafka.send(TOPIC, id.toString(), body).get(10, TimeUnit.SECONDS);
            return Map.of("id", id, "offset", result.getRecordMetadata().offset());
        }
    }

    @Component
    static class SpikeListener {

        private final JdbcClient jdbc;
        private final Counter consumed;

        SpikeListener(JdbcClient jdbc, MeterRegistry registry) {
            this.jdbc = jdbc;
            this.consumed = Counter.builder("sp3.spike.consumed").register(registry);
        }

        @KafkaListener(topics = TOPIC, groupId = "sp3-spike")
        void onMessage(ConsumerRecord<String, String> record) {
            jdbc.sql("INSERT INTO sp3_spike.consumed (id, body) VALUES (:id, :body)")
                    .param("id", UUID.fromString(record.key()))
                    .param("body", record.value())
                    .update();
            consumed.increment();
        }
    }
}
