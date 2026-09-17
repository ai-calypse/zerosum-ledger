package dev.zerosum.auth;

/**
 * Roles a bearer token can carry (D03-4, TB1).
 *
 * <p>The hierarchy is deliberate and narrow: a writer or admin token also satisfies a reader endpoint, because both
 * are strictly more privileged, but a reader token never satisfies a writer or admin endpoint. Nothing implies
 * anything else, so a writer cannot reach admin operations.
 */
public enum Role {
    READER,
    WRITER,
    ADMIN;

    /** Whether a principal holding this role may call an endpoint requiring {@code required}. */
    public boolean satisfies(Role required) {
        return this == required || required == READER;
    }
}
