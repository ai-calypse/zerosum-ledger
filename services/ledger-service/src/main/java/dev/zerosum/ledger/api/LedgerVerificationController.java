package dev.zerosum.ledger.api;

import dev.zerosum.ledger.api.LedgerReadResponses.ClearingBalance;
import dev.zerosum.ledger.api.LedgerReadResponses.I5Status;
import dev.zerosum.ledger.api.LedgerReadResponses.Invariants;
import dev.zerosum.ledger.api.LedgerReadResponses.Verification;
import dev.zerosum.ledger.invariants.InvariantsService;
import dev.zerosum.ledger.invariants.VerifyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational verification endpoints (D02-8): global invariants from one snapshot, and per-entity verify that rebuilds
 * balances from the changelog and checks the hash chain. Both are read-only; verify is a POST because the master's REST
 * table defines it that way, not because it writes.
 */
@RestController
class LedgerVerificationController {

    /**
     * Why I5 is not evaluated for every entity here: verifying every chain rehashes the entire changelog, which cannot
     * meet the P4 target for this endpoint on a large ledger. Per-entity verify and the S06 verifier own I5 (D02-8).
     */
    private static final I5Status I5_NOT_EVALUATED = new I5Status(false,
            "I5 is verified per entity through POST /v1/entities/{entity_id}/verify and across the ledger by the S06 "
                    + "verifier; scanning every chain here would exceed the audit-read budget on a large ledger.");

    private final InvariantsService invariants;
    private final VerifyService verify;

    LedgerVerificationController(InvariantsService invariants, VerifyService verify) {
        this.invariants = invariants;
        this.verify = verify;
    }

    @GetMapping("/v1/invariants")
    Invariants invariants() {
        InvariantsService.Report report = invariants.report();
        return new Invariants(report.consistent(), report.i2Currencies(), report.i3Accounts(), report.i4Entities(),
                I5_NOT_EVALUATED, report.unresolvedQuarantinedCount(),
                report.nonZeroClearingBalances().stream()
                        .map(b -> new ClearingBalance(b.entityId(), b.accountCode(), b.currency(), b.signedMinor()))
                        .toList());
    }

    @PostMapping("/v1/entities/{entityId}/verify")
    Verification verify(@PathVariable String entityId) {
        String id = EntityIds.validated(entityId);
        VerifyService.Result result = verify.verify(id).orElseThrow(() -> LedgerApiException.entityNotFound(id));
        return new Verification(result.entityId(), result.consistent(), result.rowsChecked(), result.firstBadSeq(),
                result.failure(), result.detail());
    }
}
