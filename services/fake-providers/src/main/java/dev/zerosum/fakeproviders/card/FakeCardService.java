package dev.zerosum.fakeproviders.card;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundResponse;
import dev.zerosum.fakeproviders.shared.Idempotency;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.shared.ProviderEventLog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * FakeCard: synchronous charges and refunds with idempotency keys (S05-T01, master §5.9).
 *
 * <p>The outcome of a charge is decided entirely by the instrument token, so a test that needs a decline asks for one
 * rather than arranging state. Determinism is the property that makes this useful as a fixture.
 */
@Service
public class FakeCardService {

    static final String PROVIDER = "fakecard";

    /** decision: D01-3 — 290 bps plus 30 minor units per capture, HALF_EVEN, and never returned on refund. */
    private static final BigDecimal FEE_RATE = new BigDecimal("0.029");
    private static final long FEE_FIXED_MINOR = 30;

    private final JdbcClient db;
    private final Idempotency idempotency;
    private final ProviderEventLog events;

    FakeCardService(JdbcClient db, Idempotency idempotency, ProviderEventLog events) {
        this.db = db;
        this.idempotency = idempotency;
        this.events = events;
    }

    @Transactional
    public Idempotency.Outcome<ChargeResponse> charge(String idempotencyKey, ChargeRequest request) {
        validate(request);
        String canonical = String.join("|", PROVIDER, "charge", request.client_reference(),
                request.instrument_token(), String.valueOf(request.amount_minor()), request.currency());
        return idempotency.run(PROVIDER, idempotencyKey, canonical, () -> insertCharge(request), ChargeResponse.class);
    }

    private ChargeResponse insertCharge(ChargeRequest request) {
        String declineCode = MagicTokens.cardDeclineCode(request.instrument_token());
        boolean succeeded = declineCode == null;
        String chargeId = "ch_" + UUID.randomUUID();
        // A declined charge collects no fee: nothing was captured.
        long fee = succeeded ? fee(request.amount_minor()) : 0;

        db.sql("""
                INSERT INTO card_charges (charge_id, client_reference, instrument_token, amount_minor, currency,
                                          status, decline_code, fee_minor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(chargeId, request.client_reference(), request.instrument_token(), request.amount_minor(),
                        request.currency(), succeeded ? "SUCCEEDED" : "DECLINED", declineCode, fee)
                .update();

        events.record(PROVIDER, succeeded ? "charge.succeeded" : "charge.declined", chargeId,
                request.client_reference(), request.amount_minor(), request.currency(), declineCode);

        return new ChargeResponse(chargeId, request.client_reference(), succeeded ? "SUCCEEDED" : "DECLINED",
                declineCode, request.amount_minor(), request.currency(), fee, 0);
    }

    static long fee(long amountMinor) {
        return BigDecimal.valueOf(amountMinor).multiply(FEE_RATE).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
                + FEE_FIXED_MINOR;
    }

    @Transactional
    public Idempotency.Outcome<RefundResponse> refund(String idempotencyKey, RefundRequest request) {
        if (request == null || request.charge_id() == null || request.amount_minor() == null
                || request.amount_minor() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "charge_id and a positive amount are required");
        }
        String canonical = String.join("|", PROVIDER, "refund", request.charge_id(),
                String.valueOf(request.client_reference()), String.valueOf(request.amount_minor()));
        return idempotency.run(PROVIDER, idempotencyKey, canonical, () -> insertRefund(request), RefundResponse.class);
    }

    private RefundResponse insertRefund(RefundRequest request) {
        Charge charge = findCharge(request.charge_id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such charge"));
        String refundId = "re_" + UUID.randomUUID();

        // The guard and the write are one statement, so two concurrent refunds cannot both observe enough headroom and
        // then both take it. Checking first and updating second is the classic way to refund more than was captured.
        boolean allowed = charge.status().equals("SUCCEEDED") && db.sql(
                        "UPDATE card_charges SET refunded_minor = refunded_minor + ? "
                                + "WHERE charge_id = ? AND refunded_minor + ? <= amount_minor")
                .params(request.amount_minor(), request.charge_id(), request.amount_minor())
                .update() == 1;

        String failureCode = allowed ? null : charge.status().equals("SUCCEEDED") ? "amount_exceeds_capture"
                : "charge_not_captured";
        String clientReference = request.client_reference() == null ? charge.clientReference()
                : request.client_reference();

        db.sql("""
                INSERT INTO card_refunds (refund_id, charge_id, client_reference, amount_minor, status, failure_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(refundId, request.charge_id(), clientReference, request.amount_minor(),
                        allowed ? "SUCCEEDED" : "FAILED", failureCode)
                .update();

        events.record(PROVIDER, allowed ? "refund.succeeded" : "refund.failed", refundId, clientReference,
                request.amount_minor(), charge.currency(), failureCode);

        return new RefundResponse(refundId, request.charge_id(), clientReference,
                allowed ? "SUCCEEDED" : "FAILED", failureCode, request.amount_minor());
    }

    public Optional<ChargeResponse> chargeById(String chargeId) {
        return findCharge(chargeId).map(Charge::toResponse);
    }

    /** Lookup by client reference. Returns every match: hiding duplicates would defeat the point of ground truth. */
    public List<ChargeResponse> chargesByClientReference(String clientReference) {
        return db.sql(selectCharge() + " WHERE client_reference = ? ORDER BY created_at")
                .param(clientReference)
                .query(Charge.MAPPER)
                .list()
                .stream()
                .map(Charge::toResponse)
                .toList();
    }

    private Optional<Charge> findCharge(String chargeId) {
        return db.sql(selectCharge() + " WHERE charge_id = ?").param(chargeId).query(Charge.MAPPER).optional();
    }

    private static String selectCharge() {
        return "SELECT charge_id, client_reference, status, decline_code, amount_minor, currency, fee_minor, "
                + "refunded_minor FROM card_charges";
    }

    private void validate(ChargeRequest request) {
        if (request == null || request.client_reference() == null || request.instrument_token() == null
                || request.amount_minor() == null || request.currency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "client_reference, instrument_token, amount_minor and currency are required");
        }
        if (request.amount_minor() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount_minor must be positive");
        }
    }

    private record Charge(String chargeId, String clientReference, String status, String declineCode, long amountMinor,
            String currency, long feeMinor, long refundedMinor) {

        static final org.springframework.jdbc.core.RowMapper<Charge> MAPPER = (rs, rowNum) -> new Charge(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getLong(7), rs.getLong(8));

        ChargeResponse toResponse() {
            return new ChargeResponse(chargeId, clientReference, status, declineCode, amountMinor, currency, feeMinor,
                    refundedMinor);
        }
    }
}
