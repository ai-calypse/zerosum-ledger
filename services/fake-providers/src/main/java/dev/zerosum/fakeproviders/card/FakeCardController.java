package dev.zerosum.fakeproviders.card;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundResponse;
import dev.zerosum.fakeproviders.shared.Idempotency;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The FakeCard API (master §5.6). Internal network only: this simulates an external system and holds no real value,
 * so it carries no token auth of its own (D00-3 keeps it off the public network).
 */
@RestController
@RequestMapping("/fakecard/v1")
class FakeCardController {

    private final FakeCardService service;
    private final SettlementReports settlements;

    FakeCardController(FakeCardService service, SettlementReports settlements) {
        this.service = service;
        this.settlements = settlements;
    }

    @PostMapping("/charges")
    ResponseEntity<ChargeResponse> charge(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody ChargeRequest request) {
        return replayable(service.charge(key, request));
    }

    @PostMapping("/refunds")
    ResponseEntity<RefundResponse> refund(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody RefundRequest request) {
        return replayable(service.refund(key, request));
    }

    @GetMapping("/charges/{chargeId}")
    ChargeResponse charge(@PathVariable String chargeId) {
        return service.chargeById(chargeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such charge"));
    }

    @GetMapping("/charges")
    List<ChargeResponse> charges(@RequestParam("client_reference") String clientReference) {
        return service.chargesByClientReference(clientReference);
    }

    /**
     * The settlement report for one closed simulated day (D06-1, master §5.6).
     *
     * <p>A day that has not closed is a 409, never an empty report: an empty report is indistinguishable from a day on
     * which nothing happened, and a reconciler would book a settlement of zero against real captures.
     */
    @GetMapping("/settlement-reports/{date}")
    FakeCardApi.SettlementReportResponse settlementReport(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlements.forDate(date);
    }

    /** A replay is flagged in a header rather than a different status, so the body stays byte-identical. */
    private static <T> ResponseEntity<T> replayable(Idempotency.Outcome<T> outcome) {
        return outcome.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(outcome.body())
                : ResponseEntity.ok(outcome.body());
    }
}
