package dev.zerosum.fakeproviders.bank;

import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutRequest;
import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The FakeBank API (master §5.6). Internal network only, like FakeCard. */
@RestController
@RequestMapping("/fakebank/v1")
class FakeBankController {

    private final FakeBankService service;

    FakeBankController(FakeBankService service) {
        this.service = service;
    }

    /** 202, never 200: the payout has been accepted, and accepting is not the same as paying. */
    @PostMapping("/payouts")
    ResponseEntity<PayoutResponse> submit(@RequestBody PayoutRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.submit(request));
    }

    @GetMapping("/payouts")
    List<PayoutResponse> byClientReference(@RequestParam("client_reference") String clientReference) {
        return service.byClientReference(clientReference);
    }
}
