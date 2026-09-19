package dev.zerosum.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.auth.ChaosGuard;
import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.money.Money;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** D08-3: every chaos switch this service owns ships off, and the shipped configuration starts in any profile. */
class ChaosDefaultsTest {

    @Test
    void everyChaosSwitchIsOffInTheShippedConfiguration() throws IOException {
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .getFirst();
        var ids = List.of("A3", "A5", "F3");
        ids.forEach(id -> assertEquals("false", String.valueOf(yaml.getProperty("zs.chaos." + id.toLowerCase())),
                id + " is declared off"));
        var active = ChaosGuard.check("instrument-service", ids, key -> Objects.toString(yaml.getProperty(key), null),
                "default");
        assertEquals(List.of(), active.switches());
    }

    @Test
    void withoutTheA3SeamAChargeIsKeyedByItsAttemptId() {
        UUID attempt = UUID.randomUUID();
        var command = new ChargeCommand(attempt, "tok_card_ok", Money.of(100, "USD"), Instant.now());
        assertEquals(attempt.toString(), command.idempotencyKey(), "the provider dedupes on the attempt id (D05-1)");
    }
}
