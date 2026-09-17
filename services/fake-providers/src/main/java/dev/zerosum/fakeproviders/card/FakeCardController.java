package dev.zerosum.fakeproviders.card;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundResponse;
import dev.zerosum.fakeproviders.shared.Idempotency;
import java.util.List;
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

    FakeCardController(FakeCardService service) {
        this.service = service;
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

    /** A replay is flagged in a header rather than a different status, so the body stays byte-identical. */
    private static <T> ResponseEntity<T> replayable(Idempotency.Outcome<T> outcome) {
        return outcome.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(outcome.body())
                : ResponseEntity.ok(outcome.body());
    }
}
