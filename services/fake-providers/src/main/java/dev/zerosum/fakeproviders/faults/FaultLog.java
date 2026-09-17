package dev.zerosum.fakeproviders.faults;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every fault the simulator injected, with the seed that chose it (§0.3 E2, D05-2).
 *
 * <p>Written on the way out of the decision, not inferred afterwards from behaviour: a test that asserts the system
 * survived a fault has to be able to show the fault happened, or a knob that quietly stopped firing would turn the
 * whole chaos suite green.
 */
@Component
public class FaultLog {

    private final JdbcClient db;

    FaultLog(JdbcClient db) {
        this.db = db;
    }

    public void record(String provider, String faultType, String target, long seed) {
        db.sql("INSERT INTO fault_log (fault_id, provider, fault_type, target, seed) VALUES (?, ?, ?, ?, ?)")
                .params("flt_" + UUID.randomUUID(), provider, faultType, target, seed)
                .update();
    }

    /** One injected fault, as the admin API reports it. */
    public record Entry(String fault_id, String provider, String fault_type, String target, long seed,
            Instant occurred_at) {
    }

    public List<Entry> entries() {
        return db.sql("SELECT fault_id, provider, fault_type, target, seed, occurred_at FROM fault_log "
                        + "ORDER BY occurred_at, fault_id")
                .query((rs, rowNum) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getTimestamp(6).toInstant()))
                .list();
    }

    /**
     * Counts by fault type, for the ground-truth response.
     *
     * <p>Derived from the same rows the log returns rather than from separate counters, so the two can never
     * disagree — a counter that drifted from the log would make both useless as evidence.
     */
    public Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.sql("SELECT fault_type, count(*) FROM fault_log GROUP BY fault_type ORDER BY fault_type")
                .query((rs, rowNum) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }
}
