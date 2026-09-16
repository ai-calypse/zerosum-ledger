package dev.zerosum.ledger.api;

import org.springframework.http.HttpStatus;

/** A read-API failure carrying the stable {@code code} the master's REST conventions require. */
class LedgerApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private LedgerApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    static LedgerApiException invalidEntityId(String entityId) {
        return new LedgerApiException(HttpStatus.BAD_REQUEST, "invalid_entity_id",
                "entity_id must match " + dev.zerosum.money.ValidationLimits.ENTITY_ID_PATTERN.pattern());
    }

    static LedgerApiException invalidCursor(Long afterSeq) {
        return new LedgerApiException(HttpStatus.BAD_REQUEST, "invalid_cursor",
                "after_seq must be zero or greater, was " + afterSeq);
    }

    static LedgerApiException invalidLimit(Integer limit, int maxPageSize) {
        return new LedgerApiException(HttpStatus.BAD_REQUEST, "invalid_limit",
                "limit must be between 1 and " + maxPageSize + ", was " + limit);
    }

    static LedgerApiException entityNotFound(String entityId) {
        return new LedgerApiException(HttpStatus.NOT_FOUND, "entity_not_found", "no such entity: " + entityId);
    }
}
