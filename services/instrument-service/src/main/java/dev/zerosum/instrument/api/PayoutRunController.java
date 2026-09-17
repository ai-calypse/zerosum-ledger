// decision: D05-7, D05-13 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import dev.zerosum.auth.Role;
import dev.zerosum.instrument.payouts.PayoutRunService;
import dev.zerosum.money.CurrencyRules;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts a payout run (D05-7, master §5.6).
 *
 * <p><strong>Writer role</strong>, and an {@code Idempotency-Key} is required: this is the endpoint that sends money
 * out, and a retried POST without a key would pay every driver twice.
 *
 * <p>A refusal is a <strong>409</strong> carrying the recorded code — {@code ledger_stale} when the pipeline is
 * behind or could not be measured (M10(c)), {@code payouts_disabled} when the kill switch is off (D05-11). The run
 * itself is still recorded, so the refusal is evidence; the detail names the run id for that reason. A retry of the
 * same key therefore replays the same refusal, which is what D03-3 means by a key identifying one request — a fresh
 * key is what asks the question again once the pipeline has caught up.
 */
@RestController
class PayoutRunController {

    private final PayoutRunService payouts;

    PayoutRunController(PayoutRunService payouts) {
        this.payouts = payouts;
    }

    /** Field names are snake_case through this service's global mapper setting, so the component stays plain. */
    record PayoutRunRequest(String currency) {
    }

    @PostMapping("/v1/payout-runs")
    ResponseEntity<PayoutRunService.RunSummary> create(HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) PayoutRunRequest body) {
        InstrumentAuthorization.require(request, Role.WRITER);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw InstrumentApiException.idempotencyKeyMissing();
        }

        String currency = body == null ? null : body.currency();
        // decision: D01-7 — refused rather than normalized. A run keyed on "usd" would hash differently from the
        // same run keyed on "USD", so a caller alternating between them would get two runs, not one replay.
        if (currency == null || !CurrencyRules.defaults().isAllowed(currency)) {
            throw InstrumentApiException.unsupportedCurrency(
                    "currency must be one of the D01-7 allow-list, was " + currency);
        }

        PayoutRunService.RunSummary run = run(new PayoutRunService.Command(currency, idempotencyKey));

        if ("REFUSED".equals(run.status())) {
            throw PayoutRunService.PAYOUTS_DISABLED.equals(run.refusalCode())
                    ? InstrumentApiException.payoutsDisabled(run.runId().toString())
                    : InstrumentApiException.ledgerStale(run.runId().toString());
        }
        // A replay is 200 with the header, a new run is 201 — the same shape as every other idempotent POST here.
        return run.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(run)
                : ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    private PayoutRunService.RunSummary run(PayoutRunService.Command command) {
        try {
            return payouts.run(command);
        } catch (PayoutRunService.IdempotencyKeyReusedException reused) {
            throw InstrumentApiException.idempotencyKeyReused(reused.getMessage());
        }
    }
}
