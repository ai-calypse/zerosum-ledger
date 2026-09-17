// decision: D05-7 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.payouts;

import dev.zerosum.outbox.OutboxStatsQuery;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two reads a payout run makes outside this service: how stale the pipeline is, and what a driver is owed
 * (D03-7, D04-5, D02-7).
 *
 * <p>One class for both because they share the one thing worth stating once — the outbound reader token. Every call
 * here is a {@code GET} against another service's reader API, and the same {@code ZS_READER_TOKEN} the stack already
 * gives this container is what authenticates them, so there is no new secret and no second place for it to drift.
 *
 * <p><strong>Every failure is reported as absence, never as a zero.</strong> An unreachable order-service, a ledger
 * answering {@code status: error}, a malformed body — all return empty, and the caller refuses the run (fail closed).
 * A client that turned an outage into a comfortable {@code 0.0} would let a payout run proceed against balances
 * nobody had checked, which is the precise failure M10(c) exists to prevent.
 *
 * <p>Read timeouts are short and explicit. A payout run holds no transaction while it is here, but an operator
 * waiting on a hung freshness read learns nothing, and the honest answer after a timeout is "refuse".
 */
@Component
class PayoutClient {

    private static final Logger log = LoggerFactory.getLogger(PayoutClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient orders;
    private final RestClient ledger;
    private final OutboxStatsQuery localOutbox;
    private final String readerToken;

    PayoutClient(OutboxStatsQuery localOutbox,
            @Value("${zs.payouts.order-base-url}") String orderBaseUrl,
            @Value("${zs.payouts.ledger-base-url}") String ledgerBaseUrl,
            @Value("${zs.payouts.connect-timeout}") Duration connectTimeout,
            @Value("${zs.payouts.read-timeout}") Duration readTimeout,
            // decision: D03-4 — the same reader token the stack already passes this container; the freshness and
            // balance endpoints are reader-role, so a payout run needs no privilege of its own.
            @Value("${zs.auth.reader-token:}") String readerToken) {
        this.localOutbox = localOutbox;
        this.readerToken = readerToken;
        this.orders = client(orderBaseUrl, connectTimeout, readTimeout);
        this.ledger = client(ledgerBaseUrl, connectTimeout, readTimeout);
    }

    private static RestClient client(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        // Without this a service that accepts the connection and then goes quiet holds the run indefinitely.
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * The three-stage pipeline staleness, or empty when any stage could not be measured.
     *
     * <p>The stage that failed is named in the log, because "the pipeline is stale" and "order-service is down" call
     * for different actions and the refusal itself cannot tell them apart.
     */
    Optional<PipelineFreshness> pipelineFreshness() {
        Optional<JsonNode> outbox = get(orders, "/v1/outbox/stats");
        // The age must be present AND a number. A 404 comes back here as a body rather than as empty, and reading a
        // missing field would silently yield 0.0 — an order-service answering "no such endpoint" would then look
        // like an order-service with nothing waiting, which is the fail-open this whole method exists to avoid.
        if (outbox.isEmpty() || !outbox.get().path("oldest_unpublished_age_seconds").isNumber()) {
            log.warn("payout run refused: order-service outbox stats (D03-7) could not be read");
            return Optional.empty();
        }
        Optional<JsonNode> freshness = get(ledger, "/v1/freshness");
        if (freshness.isEmpty()) {
            log.warn("payout run refused: ledger freshness (D04-5) could not be read");
            return Optional.empty();
        }
        // The ledger reports `error` when it could not measure its own lag, and omits the numbers rather than
        // reporting them as zero. Treating that as fresh would be reading a figure nobody computed.
        JsonNode ledgerBody = freshness.get();
        if (!"ok".equals(ledgerBody.path("status").asString(null))
                || !ledgerBody.path("oldest_unapplied_age_seconds").isNumber()) {
            log.warn("payout run refused: ledger freshness reported status={} error={}",
                    ledgerBody.path("status").asString(null), ledgerBody.path("error").asString(null));
            return Optional.empty();
        }

        double instrumentAge;
        try {
            // decision: D03-5 — this service's own outbox, read locally through the same query order-service exposes.
            instrumentAge = localOutbox.read().oldestUnpublishedAgeSeconds();
        } catch (RuntimeException unavailable) {
            log.warn("payout run refused: the local instrument outbox age could not be read", unavailable);
            return Optional.empty();
        }

        return Optional.of(new PipelineFreshness(
                outbox.get().path("oldest_unpublished_age_seconds").asDouble(),
                ledgerBody.path("oldest_unapplied_age_seconds").asDouble(),
                instrumentAge));
    }

    /** What a driver is owed right now, on the account's normal side, with the sequence it was read at (D02-7). */
    record Payable(long presentedMinor, long asOfSeq) {
    }

    /**
     * The driver's {@code payable} balance in one currency, or empty when the ledger has no entries for them.
     *
     * <p>{@code presented_minor} rather than the signed balance: {@code payable} is credit-normal (ADR-0003), so the
     * presented figure is positive exactly when the platform owes the driver money. Reading the signed column here
     * would pay out on a negative number, which is driver debt.
     *
     * @throws LedgerUnavailable when the ledger could not be asked, which must refuse rather than skip the driver
     */
    Optional<Payable> payable(String entityId, String currency) {
        Optional<JsonNode> body = get(ledger, "/v1/entities/" + entityId + "/balances");
        if (body.isEmpty()) {
            // Distinguished from "no such entity" below: an outage must not be recorded as a driver who is owed
            // nothing, or an unreachable ledger would quietly pay nobody and call the run a success.
            throw new LedgerUnavailable(entityId);
        }
        JsonNode found = body.get();
        if (found.path("not_found").asBoolean(false)) {
            return Optional.empty();
        }
        long asOfSeq = found.path("as_of_seq").asLong();
        for (JsonNode account : found.path("accounts")) {
            if ("payable".equals(account.path("account").asString(null))
                    && currency.equals(account.path("currency").asString(null))) {
                return Optional.of(new Payable(account.path("presented_minor").asLong(), asOfSeq));
            }
        }
        // The entity exists but holds no payable in this currency: owed nothing, which is not an error.
        return Optional.of(new Payable(0, asOfSeq));
    }

    /** The ledger could not be asked. Never confused with "this driver is owed nothing". */
    static class LedgerUnavailable extends RuntimeException {
        LedgerUnavailable(String entityId) {
            super("the ledger could not be asked for " + entityId + "'s balance");
        }
    }

    /**
     * A reader GET, as JSON.
     *
     * <p>A {@code 404} comes back as a body carrying {@code not_found}, so the one caller that cares can tell a
     * missing entity from an outage without this method returning three different shapes of nothing.
     */
    private Optional<JsonNode> get(RestClient client, String uri) {
        try {
            return client.get().uri(uri)
                    .header("Authorization", "Bearer " + readerToken)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            return Optional.of((JsonNode) JSON.readTree("{\"not_found\":true}"));
                        }
                        if (status != 200) {
                            log.warn("GET {} answered {}", uri, status);
                            return Optional.<JsonNode>empty();
                        }
                        return Optional.of(JSON.readTree(response.getBody()));
                    }, false);
        } catch (RuntimeException unreachable) {
            log.warn("GET {} failed: {}", uri, unreachable.toString());
            return Optional.empty();
        }
    }
}
