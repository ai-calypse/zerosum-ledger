package dev.zerosum.ledger.api;

import dev.zerosum.money.ValidationLimits;

/** Entity-ID validation at the trust boundary (TB1), shared by every read endpoint so the rule lives in one place. */
final class EntityIds {

    private EntityIds() {
    }

    /** Rule 3 of D01-5, checked before any query runs. */
    static String validated(String entityId) {
        if (entityId == null || entityId.length() > ValidationLimits.ENTITY_ID_MAX_LENGTH
                || !ValidationLimits.ENTITY_ID_PATTERN.matcher(entityId).matches()) {
            throw LedgerApiException.invalidEntityId(entityId);
        }
        return entityId;
    }
}
