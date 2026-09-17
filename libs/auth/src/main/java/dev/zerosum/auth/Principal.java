package dev.zerosum.auth;

/**
 * An authenticated caller (D03-4).
 *
 * @param role         what the token authorises
 * @param sourceSystem the system this principal writes as. Present for writers, so {@code source.system} is derived
 *                     from the token and never from a request body (D01-5 rule 8); null for reader and admin tokens,
 *                     which do not create money orders
 */
public record Principal(Role role, String sourceSystem) {

    public Principal {
        if (role == null) {
            throw new IllegalArgumentException("a principal must carry a role");
        }
        if (role == Role.WRITER && (sourceSystem == null || sourceSystem.isBlank())) {
            throw new IllegalArgumentException("a writer principal must carry the source system it writes as");
        }
    }
}
