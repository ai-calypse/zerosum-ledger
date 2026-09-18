package dev.zerosum.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies of the ledger read API (D02-7). Field names are serialized in snake_case, matching
 * {@code openapi/ledger-service.yaml}; {@code LedgerOpenApiContractIT} validates real responses against that file, and
 * every schema there sets {@code additionalProperties: false}, so a naming slip fails the build.
 */
final class LedgerReadResponses {

    private LedgerReadResponses() {
    }

    record Balances(String entityId, String kind, long asOfSeq, List<AccountBalance> accounts) {
    }

    /**
     * @param presentedMinor the balance on the account's normal side (ADR-0003)
     * @param signedMinor    the stored signed balance, debit positive and credit negative
     */
    record AccountBalance(String account, String currency, String normalSide, long presentedMinor, long signedMinor) {
    }

    /**
     * @param classes        balances summed by entity kind, account and currency
     * @param currencyTotals the signed sum of every class per currency, from the same snapshot: zero is I2 holding
     */
    record AccountsSummary(List<AccountClass> classes, List<CurrencyTotal> currencyTotals) {
    }

    record AccountClass(String entityKind, String account, String currency, String normalSide, long accounts,
            long signedMinor, long presentedMinor) {
    }

    record CurrencyTotal(String currency, long signedMinor) {
    }

    record ChangelogPage(String entityId, List<ChangelogRow> rows, Long nextAfterSeq) {
    }

    record ChangelogRow(long seq, UUID orderId, String account, String currency, long deltaMinor,
            long balanceAfterMinor, Instant recordedAt, ChangelogSource source) {
    }

    /** The M6 (a) link back to the money order that caused the row (master §0.3 C17). */
    record ChangelogSource(String system, String idempotencyKey) {
    }

    /**
     * @param consistent                 true when I2, I3 and I4 all hold; I5 is reported separately
     * @param i5                         whether I5 was evaluated here, and where it is evaluated instead (D02-8)
     * @param unresolvedQuarantinedCount unresolved quarantine rows only (D02-9)
     * @param nonZeroClearingBalances    clearing accounts that have not returned to zero (§0.3 C13)
     */
    record Invariants(boolean consistent, List<String> i2NonZeroCurrencies, List<String> i3Violations,
            List<String> i4Violations, I5Status i5, long unresolvedQuarantinedCount,
            List<ClearingBalance> nonZeroClearingBalances) {
    }

    record I5Status(boolean evaluated, String reason) {
    }

    record ClearingBalance(String entityId, String account, String currency, long signedMinor) {
    }

    /** {@code firstBadSeq}, {@code failure} and {@code detail} are null exactly when {@code consistent} is true. */
    record Verification(String entityId, boolean consistent, long rowsChecked, Long firstBadSeq, String failure,
            String detail) {
    }
}
