package dev.zerosum.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How far behind the outbox is (D03-7).
 *
 * <p>Lives here rather than in a service so instrument-service reuses it in S05 instead of writing its own SQL against
 * a table this library owns.
 *
 * <p>Age is measured from {@code created_at} with the database clock (§0.3 C12), not the application's: the row's age
 * is the thing operators alert on, and a service whose clock has drifted would otherwise report a comfortable number
 * while the backlog grew.
 */
public class OutboxStatsQuery {

    private final JdbcTemplate template;

    public OutboxStatsQuery(JdbcTemplate template) {
        this.template = template;
    }

    /**
     * @param unpublishedCount            rows still waiting for the relay
     * @param oldestUnpublishedAgeSeconds age of the oldest waiting row; <strong>zero when the outbox is empty</strong>,
     *                                    so the payout-run freshness sum in S05 is always defined rather than having to
     *                                    special-case an absent value
     */
    public record Stats(long unpublishedCount, double oldestUnpublishedAgeSeconds) {
    }

    public Stats read() {
        // One statement rather than two, so the count and the age describe the same instant. Both use the partial
        // index on unpublished rows.
        return template.queryForObject("""
                SELECT count(*) AS unpublished,
                       COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) AS oldest_age
                FROM outbox WHERE published_at IS NULL""",
                (rs, rowNumber) -> new Stats(rs.getLong("unpublished"), rs.getDouble("oldest_age")));
    }
}
