package dev.zerosum.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static bearer tokens per role, read from the environment (D00-8, master §5.11).
 *
 * <p>Writer tokens arrive as {@code system:token,system:token}, so each one names the source system it authenticates
 * as. Reader and admin tokens are single values.
 *
 * <p>Comparison uses {@link MessageDigest#isEqual} and <strong>checks every configured token</strong> rather than
 * returning on the first match: stopping early would leak, through timing, how far down the list a presented token
 * matched.
 */
public final class TokenRegistry {

    private final Map<String, String> writerSystemsByToken;
    private final List<String> readerTokens;
    private final List<String> adminTokens;

    private TokenRegistry(Map<String, String> writerSystemsByToken, List<String> readerTokens, List<String> adminTokens) {
        this.writerSystemsByToken = writerSystemsByToken;
        this.readerTokens = readerTokens;
        this.adminTokens = adminTokens;
    }

    /**
     * @param writerTokens {@code system:token} pairs, comma separated; blank when no writer may call this service
     * @param readerToken  a single reader token, or blank
     * @param adminToken   a single admin token, or blank
     */
    public static TokenRegistry of(String writerTokens, String readerToken, String adminToken) {
        Map<String, String> writers = new LinkedHashMap<>();
        if (writerTokens != null && !writerTokens.isBlank()) {
            for (String pair : writerTokens.split(",")) {
                String entry = pair.strip();
                if (entry.isEmpty()) {
                    continue;
                }
                int separator = entry.indexOf(':');
                if (separator <= 0 || separator == entry.length() - 1) {
                    throw new IllegalArgumentException(
                            "writer tokens must be system:token pairs; one entry was malformed");
                }
                writers.put(entry.substring(separator + 1), entry.substring(0, separator));
            }
        }
        return new TokenRegistry(Map.copyOf(writers), single(readerToken), single(adminToken));
    }

    private static List<String> single(String token) {
        return token == null || token.isBlank() ? List.of() : List.of(token.strip());
    }

    /** The principal this token authenticates, or empty when it matches nothing configured. */
    public Optional<Principal> authenticate(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        // Every candidate is compared, and the result is only read afterwards, so the work does not depend on where a
        // match occurs.
        Principal match = null;
        for (Map.Entry<String, String> writer : writerSystemsByToken.entrySet()) {
            if (constantTimeEquals(presented, writer.getKey())) {
                match = new Principal(Role.WRITER, writer.getValue());
            }
        }
        for (String reader : readerTokens) {
            if (constantTimeEquals(presented, reader)) {
                match = new Principal(Role.READER, null);
            }
        }
        for (String admin : adminTokens) {
            if (constantTimeEquals(presented, admin)) {
                match = new Principal(Role.ADMIN, null);
            }
        }
        return Optional.ofNullable(match);
    }

    /** True when at least one token is configured; a service with none would accept nobody. */
    public boolean isEmpty() {
        return writerSystemsByToken.isEmpty() && readerTokens.isEmpty() && adminTokens.isEmpty();
    }

    private static boolean constantTimeEquals(String presented, String configured) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Configured source systems, sorted, for diagnostics and startup logging. Never returns a token.
     *
     * <p>Sorted rather than in configuration order: {@code Map.copyOf} does not preserve insertion order, so an
     * unsorted view would vary between runs for no reason.
     */
    public List<String> writerSystems() {
        List<String> systems = new ArrayList<>(writerSystemsByToken.values());
        systems.sort(String::compareTo);
        return systems;
    }
}
