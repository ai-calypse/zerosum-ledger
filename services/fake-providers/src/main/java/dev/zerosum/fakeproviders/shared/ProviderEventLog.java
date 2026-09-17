package dev.zerosum.fakeproviders.shared;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The outgoing event log both providers write to (D05-2).
 *
 * <p>Every event is written in the same transaction as the state change that produced it, so the log can never claim
 * an outcome the provider did not reach, nor miss one it did.
 *
 * <p>Nothing delivers these yet: the webhook sender is S05-T03, which is deferred, so instrument adapters resolve
 * outcomes by lookup. The log is written regardless, so turning delivery on later is not a schema change.
 */
@Component
public class ProviderEventLog {

    private final JdbcClient db;

    ProviderEventLog(JdbcClient db) {
        this.db = db;
    }

    public void record(String provider, String eventType, String providerRef, String clientReference,
            long amountMinor, String currency, String failureCode) {
        db.sql("""
                INSERT INTO provider_events (event_id, provider, event_type, provider_ref, client_reference,
                                             amount_minor, currency, failure_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params("evt_" + UUID.randomUUID(), provider, eventType, providerRef, clientReference,
                        amountMinor, currency, failureCode)
                .update();
    }
}
