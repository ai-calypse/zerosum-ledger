package dev.zerosum.order.order;

import dev.zerosum.auth.ChaosGuard;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * decision: D08-3 — A4's database layer (master §8.5, §0.3 E8; S08-T03 instruction 7).
 *
 * <p>The two deferred zero-sum constraint triggers (D03-1) must be enabled exactly when A4 is off. Trigger state lives
 * in the database, so a volume that once ran A4 would otherwise carry the disabled triggers into a later run that
 * believes it has every protection. At startup, once Flyway has migrated (this bean needs the {@link JdbcTemplate}, which
 * Boot orders after the migration), the state is read with the runtime role; only on a mismatch does it connect as the
 * owner Flyway uses and run {@code ALTER TABLE ... ENABLE|DISABLE TRIGGER} on those two triggers and nothing else. The
 * append-only triggers are never touched. The state is re-read, and a service that still disagrees does not start.
 */
@Component
class ZeroSumTriggers {

    private static final Logger log = LoggerFactory.getLogger(ZeroSumTriggers.class);

    /** Trigger to its table (V3__orders_immutability.sql). */
    private static final Map<String, String> TRIGGERS = Map.of(
            "money_order_entries_zero_sum", "money_order_entries",
            "money_orders_have_entries", "money_orders");

    ZeroSumTriggers(JdbcTemplate template, ChaosGuard.Active chaos, Environment environment) throws SQLException {
        boolean wanted = !chaos.on("A4");
        Map<String, Boolean> state = read(template);
        if (!state.keySet().equals(TRIGGERS.keySet())) {
            throw new IllegalStateException("ZS-CHAOS expected the zero-sum triggers " + TRIGGERS.keySet()
                    + ", found " + state);
        }
        if (state.containsValue(!wanted)) {
            log.warn("ZS-CHAOS zero-sum triggers are {} but A4 is {}; setting them to match", state,
                    wanted ? "off" : "on");
            try (Connection owner = DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"),
                    environment.getRequiredProperty("spring.flyway.user"),
                    environment.getRequiredProperty("spring.flyway.password"));
                    Statement statement = owner.createStatement()) {
                for (var trigger : new TreeMap<>(TRIGGERS).entrySet()) {
                    statement.execute("ALTER TABLE " + trigger.getValue() + (wanted ? " ENABLE" : " DISABLE")
                            + " TRIGGER " + trigger.getKey());
                }
            }
            state = read(template);
            if (state.containsValue(!wanted)) {
                throw new IllegalStateException("ZS-CHAOS zero-sum triggers are " + state + " and could not be set");
            }
        }
        log.info("ZS-CHAOS service=order-service zero-sum-triggers-enabled={} {}", wanted, new TreeMap<>(state));
    }

    /** Trigger name to enabled; {@code tgenabled = 'D'} is the only disabled state. */
    private static Map<String, Boolean> read(JdbcTemplate template) {
        var state = new TreeMap<String, Boolean>();
        template.query("SELECT tgname, tgenabled::text AS enabled FROM pg_trigger WHERE tgname = ANY (?)",
                rs -> {
                    state.put(rs.getString("tgname"), !"D".equals(rs.getString("enabled")));
                }, (Object) TRIGGERS.keySet().toArray(String[]::new));
        return state;
    }
}
