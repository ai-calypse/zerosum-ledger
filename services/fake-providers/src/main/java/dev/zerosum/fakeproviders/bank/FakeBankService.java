package dev.zerosum.fakeproviders.bank;

import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutRequest;
import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutResponse;
import dev.zerosum.fakeproviders.faults.FaultProfiles;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FakeBank: asynchronous payouts with no idempotency keys (S05-T02, master §5.9).
 *
 * <p>Two submissions of one client reference create two payouts, deliberately. This is the asymmetry with FakeCard
 * that the whole design turns on: a caller that retries blindly against FakeBank pays twice, which is why the resolver
 * must look up first and wait out a quiet period (ADR-0010).
 */
@Service
public class FakeBankService {

    static final String PROVIDER = "fakebank";

    private final JdbcClient db;
    private final Clock clock;
    private final FaultProfiles faults;
    private final Duration bankingDay;

    // No ProviderEventLog here: accepting a payout emits no event, because acceptance is not an outcome. Events are
    // written by PayoutTransitions when the simulated bank actually reaches one.
    FakeBankService(JdbcClient db, Clock clock, FaultProfiles faults,
            @Value("${zs.fakebank.banking-day:30s}") Duration bankingDay) {
        this.db = db;
        this.clock = clock;
        this.faults = faults;
        this.bankingDay = bankingDay;
    }

    /**
     * Accepts a payout and returns immediately. The outcome is decided later by the lifecycle, so this never reports
     * settled — an API that answered synchronously would not be simulating a bank.
     */
    @Transactional
    public PayoutResponse submit(PayoutRequest request) {
        validate(request);
        String payoutId = "po_" + UUID.randomUUID();
        Instant now = clock.instant();
        // decision: D05-2 — the knob profile wins over the configured banking day when it sets one, and the seeded
        // processing delay is added on top, bounded by max_processing_delay_ms (kept under ADR-0010's quiet period
        // by the knob validation). A real bank does not process every payout at the same instant.
        Instant processAt = now.plus(faults.knobs(PROVIDER).bankingDay(bankingDay))
                .plusMillis(faults.processingDelayMillis(PROVIDER));

        db.sql("""
                INSERT INTO bank_payouts (payout_id, client_reference, destination_token, amount_minor, currency,
                                          status, accepted_at, process_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """)
                .params(payoutId, request.client_reference(), request.destination_token(), request.amount_minor(),
                        request.currency(), Timestamp.from(now), Timestamp.from(processAt))
                .update();

        // No event yet: acceptance is not an outcome, and emitting one here would let a consumer treat a payout that
        // later fails as money already delivered.
        return new PayoutResponse(payoutId, request.client_reference(), "PENDING", null, request.amount_minor(),
                request.currency(), now, null, null);
    }

    /** Every payout for the reference, including duplicates. Surfacing them is the point (I7). */
    public List<PayoutResponse> byClientReference(String clientReference) {
        return db.sql(SELECT + " WHERE client_reference = ? ORDER BY accepted_at")
                .param(clientReference)
                .query(MAPPER)
                .list();
    }

    /**
     * A payout whose next simulated transition is due, with everything the transition needs to decide.
     *
     * @param returnCode the return the bank has already decided on, set when the payout settled (D05-2). Carried
     *                   here so the settled → returned step reuses that decision instead of drawing a second one
     *                   that could disagree with the first.
     */
    public record Due(String payoutId, String clientReference, String status, long amountMinor, String currency,
            String destinationToken, String returnCode) {
    }

    public List<Due> due(Instant now) {
        return db.sql("""
                SELECT payout_id, client_reference, status, amount_minor, currency, destination_token, return_code
                FROM bank_payouts
                WHERE status IN ('PENDING','SETTLED') AND process_at <= ?
                ORDER BY process_at
                """)
                .param(Timestamp.from(now))
                .query((rs, rowNum) -> new Due(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)))
                .list();
    }

    private void validate(PayoutRequest request) {
        if (request == null || request.client_reference() == null || request.destination_token() == null
                || request.amount_minor() == null || request.currency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "client_reference, destination_token, amount_minor and currency are required");
        }
        if (request.amount_minor() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount_minor must be positive");
        }
    }

    private static final String SELECT = """
            SELECT payout_id, client_reference, status, return_code, amount_minor, currency, accepted_at, settled_at,
                   returned_at, destination_token
            FROM bank_payouts
            """;

    static final RowMapper<PayoutResponse> MAPPER = (rs, rowNum) -> new PayoutResponse(rs.getString(1),
            rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
            rs.getTimestamp(7).toInstant(), instantOrNull(rs.getTimestamp(8)), instantOrNull(rs.getTimestamp(9)));

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
