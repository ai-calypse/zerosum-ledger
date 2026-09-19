package dev.zerosum.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.auth.ChaosGuard;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** D08-3: every chaos switch this service owns ships off, and the shipped configuration starts in any profile. */
class ChaosDefaultsTest {

    @Test
    void everyChaosSwitchIsOffInTheShippedConfiguration() throws IOException {
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .getFirst();
        var ids = List.of("A2", "A4");
        ids.forEach(id -> assertEquals("false", String.valueOf(yaml.getProperty("zs.chaos." + id.toLowerCase())),
                id + " is declared off"));
        var active = ChaosGuard.check("order-service", ids, key -> Objects.toString(yaml.getProperty(key), null),
                "default");
        assertEquals(List.of(), active.switches());
    }
}
