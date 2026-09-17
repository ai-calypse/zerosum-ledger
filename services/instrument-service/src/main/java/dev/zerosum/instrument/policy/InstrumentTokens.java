// decision: D05-6 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Which instrument an entity's money moves through (D05-6).
 *
 * <p>One owner for the {@code instrument_tokens} table, because both the registration endpoint and the policy read
 * and write it, and a second copy of "which token does this rider use" would eventually answer differently.
 *
 * <p>Registration replaces the token. It does not touch attempts: an attempt copies the token when it is created, so
 * re-registration affects future attempts only — which is what stops a refund going back to a card that never paid.
 */
@Service
public class InstrumentTokens {

    private final JdbcClient db;

    InstrumentTokens(JdbcClient db) {
        this.db = db;
    }

    /** A registered instrument. The token never leaves this service (D00-8). */
    public record TokenRow(String entityId, String provider, String token) {
    }

    public void register(String entityId, String provider, String token) {
        db.sql("""
                INSERT INTO instrument_tokens (entity_id, provider, token) VALUES (:entity, :provider, :token)
                ON CONFLICT (entity_id, provider) DO UPDATE SET token = :token, registered_at = now()
                """)
                .param("entity", entityId)
                .param("provider", provider)
                .param("token", token)
                .update();
    }

    /** The entity's tokens, most recently registered first, so the newest usable instrument wins. */
    List<TokenRow> forEntity(String entityId) {
        return db.sql("""
                SELECT entity_id, provider, token FROM instrument_tokens
                 WHERE entity_id = :entity ORDER BY registered_at DESC, provider
                """)
                .param("entity", entityId)
                .query((rs, rowNum) -> new TokenRow(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
    }
}
