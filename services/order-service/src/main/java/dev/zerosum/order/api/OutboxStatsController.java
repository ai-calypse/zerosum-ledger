package dev.zerosum.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.zerosum.auth.Role;
import dev.zerosum.outbox.OutboxStatsQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How far behind the outbox is (D03-7). Reader role, which writer and admin also satisfy.
 *
 * <p>This is the staleness signal the master's degraded-mode section calls for: when Kafka is down the API keeps
 * accepting orders, and this endpoint is how a caller sees that what it reads downstream is behind. It reports the
 * backlog rather than hiding it, so it stays honest in exactly the situation it exists for.
 */
@RestController
class OutboxStatsController {

    private final OutboxStatsQuery stats;

    OutboxStatsController(OutboxStatsQuery stats) {
        this.stats = stats;
    }

    /**
     * @param unpublishedCount            rows still waiting for the relay
     * @param oldestUnpublishedAgeSeconds age of the oldest waiting row, zero when the outbox is empty
     */
    record OutboxStatsResponse(
            @JsonProperty("unpublished_count") long unpublishedCount,
            @JsonProperty("oldest_unpublished_age_seconds") double oldestUnpublishedAgeSeconds) {
    }

    @GetMapping("/v1/outbox/stats")
    OutboxStatsResponse read(HttpServletRequest request) {
        ApiAuthorization.require(request, Role.READER);
        OutboxStatsQuery.Stats current = stats.read();
        return new OutboxStatsResponse(current.unpublishedCount(), current.oldestUnpublishedAgeSeconds());
    }
}
