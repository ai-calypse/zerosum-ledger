package dev.zerosum.fakeproviders.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotent request handling for providers that support it (D05-2, master §5.9 FakeCard column).
 *
 * <p>The key is reserved by an INSERT before the work runs, inside the caller's transaction. A concurrent request with
 * the same key blocks on the primary key until the first transaction commits and then sees a conflict, so a burst with
 * one key produces exactly one charge. A check-then-insert would pass single-threaded tests and duplicate under load.
 *
 * <p>The first response is stored whole and replayed verbatim. Re-rendering it would let a later code change alter
 * what a replay returns, which is precisely the guarantee an idempotency key is supposed to give.
 */
@Component
public class Idempotency {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient db;

    Idempotency(JdbcClient db) {
        this.db = db;
    }

    /** The result of an idempotent operation, and whether it came from the store rather than from running the work. */
    public record Outcome<T>(T body, boolean replayed) {
    }

    public <T> Outcome<T> run(String provider, String key, String canonicalRequest, Supplier<T> work, Class<T> type) {
        if (key == null || key.isBlank()) {
            // No key offered. The provider still performs the work — refusing would be stricter than the real card
            // networks, and the caller's exposure to duplicates is exactly the point being simulated.
            return new Outcome<>(work.get(), false);
        }
        byte[] fingerprint = sha256(canonicalRequest);
        boolean reserved = db.sql("""
                INSERT INTO idempotency_records (provider, idempotency_key, request_fingerprint, response_status,
                                                 response_body)
                VALUES (?, ?, ?, 0, '{}'::jsonb)
                ON CONFLICT (provider, idempotency_key) DO NOTHING
                """).params(provider, key, fingerprint).update() == 1;

        if (!reserved) {
            return new Outcome<>(replay(provider, key, fingerprint, type), true);
        }

        T result = work.get();
        db.sql("UPDATE idempotency_records SET response_status = ?, response_body = ?::jsonb "
                        + "WHERE provider = ? AND idempotency_key = ?")
                .params(HttpStatus.OK.value(), JSON.writeValueAsString(result), provider, key)
                .update();
        return new Outcome<>(result, false);
    }

    private <T> T replay(String provider, String key, byte[] fingerprint, Class<T> type) {
        Optional<Stored> stored = db.sql(
                        "SELECT request_fingerprint, response_status, response_body FROM idempotency_records "
                                + "WHERE provider = ? AND idempotency_key = ?")
                .params(provider, key)
                .query((rs, rowNum) -> new Stored(rs.getBytes(1), rs.getInt(2), rs.getString(3)))
                .optional();

        Stored record = stored.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "idempotency key is in flight"));

        if (!Arrays.equals(record.fingerprint(), fingerprint)) {
            // Same key, different request. Replaying the first response would hide the caller's bug behind a success.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "idempotency_key_reuse: this key was used with a different request");
        }
        if (record.status() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency key is in flight");
        }
        return JSON.readValue(record.body(), type);
    }

    private record Stored(byte[] fingerprint, int status, String body) {
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
