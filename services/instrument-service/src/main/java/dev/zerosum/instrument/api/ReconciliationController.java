// decision: D06-4 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import dev.zerosum.auth.Role;
import dev.zerosum.instrument.core.InstrumentExceptions.ProviderUnavailableException;
import dev.zerosum.instrument.core.InstrumentExceptions.ReportNotReadyException;
import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.InstrumentExceptions.UnsupportedCapabilityException;
import dev.zerosum.instrument.recon.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation runs and their breaks (D06-4, master §5.6).
 *
 * <p>Starting a run is <strong>admin</strong>: it books a settlement, which moves money. Listing breaks is
 * <strong>reader</strong>: it is the operator's view of what disagreed.
 *
 * <p>Core failures are translated here rather than left to leak. Each becomes a distinct problem code, because the
 * caller's next action differs: a day that is not ready is retried next cycle, an unavailable provider is retried now,
 * and a provider without settlement reports will never succeed however often it is retried.
 */
@RestController
class ReconciliationController {

    /** A page cap, so a run with mass corruption cannot be asked for in one unbounded response. */
    private static final int MAX_BREAKS = 500;

    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    record ReconciliationRequest(String provider, String report_date) {
    }

    @PostMapping("/v1/reconciliation-runs")
    ResponseEntity<ReconciliationService.RunSummary> create(HttpServletRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReconciliationRequest body) {
        InstrumentAuthorization.require(request, Role.ADMIN);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw InstrumentApiException.idempotencyKeyMissing();
        }
        if (body == null || body.provider() == null || body.provider().isBlank()) {
            throw InstrumentApiException.unknownProvider(String.valueOf(body == null ? null : body.provider()));
        }

        var command = new ReconciliationService.Command(body.provider(), reportDate(body), idempotencyKey);
        ReconciliationService.RunSummary run = run(command);

        // A replay is 200 with the header, a new run is 201 — the same shape as every other idempotent POST here.
        return run.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(run)
                : ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    @GetMapping("/v1/reconciliation-runs/{runId}/breaks")
    List<ReconciliationService.BreakView> breaks(HttpServletRequest request, @PathVariable String runId,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false) Integer limit) {
        InstrumentAuthorization.require(request, Role.READER);
        UUID id = runId(runId);
        if (!reconciliation.runExists(id)) {
            // Distinguished from an empty list on purpose: "this run found nothing" and "there is no such run" are
            // the same bytes and opposite facts.
            throw InstrumentApiException.runNotFound(runId);
        }
        return reconciliation.breaks(id, type, status, limit == null ? MAX_BREAKS : Math.min(limit, MAX_BREAKS));
    }

    private ReconciliationService.RunSummary run(ReconciliationService.Command command) {
        try {
            return reconciliation.reconcile(command);
        } catch (UnknownProviderException unknown) {
            throw InstrumentApiException.unknownProvider(command.provider());
        } catch (UnsupportedCapabilityException unsupported) {
            throw InstrumentApiException.settlementReportsUnsupported(unsupported.getMessage());
        } catch (ReportNotReadyException notReady) {
            throw InstrumentApiException.reportNotReady(notReady.getMessage());
        } catch (ProviderUnavailableException unavailable) {
            throw InstrumentApiException.providerUnavailable(unavailable.getMessage());
        } catch (ReconciliationService.IdempotencyKeyReusedException reused) {
            throw InstrumentApiException.idempotencyKeyReused(reused.getMessage());
        } catch (ReconciliationService.UnsupportedCurrencyException currency) {
            throw InstrumentApiException.unsupportedCurrency(currency.getMessage());
        }
    }

    private static LocalDate reportDate(ReconciliationRequest body) {
        try {
            return LocalDate.parse(body.report_date());
        } catch (DateTimeParseException | NullPointerException malformed) {
            throw InstrumentApiException.invalidReportDate(
                    "report_date must be an ISO date (YYYY-MM-DD), was " + body.report_date());
        }
    }

    private static UUID runId(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException malformed) {
            throw InstrumentApiException.invalidReportDate("run id must be a UUID, was " + runId);
        }
    }
}
