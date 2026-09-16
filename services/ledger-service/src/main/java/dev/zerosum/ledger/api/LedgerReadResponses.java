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

    record ChangelogPage(String entityId, List<ChangelogRow> rows, Long nextAfterSeq) {
    }

    record ChangelogRow(long seq, UUID orderId, String account, String currency, long deltaMinor,
            long balanceAfterMinor, Instant recordedAt, ChangelogSource source) {
    }

    /** The M6 (a) link back to the money order that caused the row (master §0.3 C17). */
    record ChangelogSource(String system, String idempotencyKey) {
    }
}
