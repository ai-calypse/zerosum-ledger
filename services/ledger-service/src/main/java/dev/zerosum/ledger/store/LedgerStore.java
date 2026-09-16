package dev.zerosum.ledger.store;

import dev.zerosum.ledger.apply.ApplyRecord.SourcePosition;
import dev.zerosum.ledger.apply.DecodedOrder;
import dev.zerosum.ledger.apply.QuarantineCode;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Explicit SQL for the ledger tables (D02-1, D02-3). No JPA: locking, batching and conflict handling stay visible. */
@Component
public class LedgerStore {

    /** An entity's locked row: its sequence head and hash-chain head. */
    public record EntityRow(String entityId, long lastSeq, byte[] lastHash) {
    }

    /** One balance key. */
    public record AccountKey(String entityId, String accountCode, String currency) {
    }

    /** One changelog row to append. */
    public record ChangelogRow(String entityId, long seq, UUID orderId, String accountCode, String currency,
            long deltaMinor, long balanceAfterMinor, short hashVersion, byte[] prevHash, byte[] rowHash) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public LedgerStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** Transaction-local timeouts (D02-4); values come from configuration, never from a literal here. */
    public void applyTransactionTimeouts(Duration lockTimeout, Duration statementTimeout) {
        template.execute("SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'");
        template.execute("SET LOCAL statement_timeout = '" + statementTimeout.toMillis() + "ms'");
    }

    /**
     * Inserts a quarantine row, skipping the insert when the same Kafka position was already quarantined (§0.3 C25).
     *
     * @return true when a row was written
     */
    public boolean insertQuarantine(UUID orderId, byte[] payload, QuarantineCode code, String detail, SourcePosition position) {
        return jdbc.sql("""
                INSERT INTO quarantined_orders (order_id, payload, error_code, error_detail, kafka_topic, kafka_partition, kafka_offset)
                VALUES (:orderId, :payload, :errorCode, :detail, :topic, :partition, :offset)
                ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) DO NOTHING""")
                .param("orderId", orderId, Types.OTHER)
                .param("payload", payload)
                .param("errorCode", code.name())
                .param("detail", detail)
                .param("topic", position == null ? null : position.topic())
                .param("partition", position == null ? null : position.partition(), Types.INTEGER)
                .param("offset", position == null ? null : position.offset(), Types.BIGINT)
                .update() > 0;
    }

    /**
     * Inserts the applied-order records, skipping ones already present. Redeliveries drop out here (D02-3).
     *
     * @return the IDs actually inserted
     */
    public Set<UUID> insertAppliedOrders(List<DecodedOrder> orders, Map<UUID, SourcePosition> positions) {
        Set<UUID> inserted = new HashSet<>();
        // Insert in order-ID order rather than batch order. ON CONFLICT DO NOTHING makes a concurrent batch wait on
        // the speculative insert, so two batches carrying the same order IDs in opposite order would wait on each
        // other and deadlock. One deterministic key order removes the cycle, as it does for entity locks (ADR-0005).
        for (DecodedOrder order : orders.stream().sorted(java.util.Comparator.comparing(DecodedOrder::orderId)).toList()) {
            SourcePosition position = positions.get(order.orderId());
            List<UUID> returned = jdbc.sql("""
                    INSERT INTO applied_orders (order_id, order_group_id, source_system, idempotency_key,
                                                kafka_topic, kafka_partition, kafka_offset, order_created_at)
                    VALUES (:orderId, :groupId, :sourceSystem, :idempotencyKey, :topic, :partition, :offset, :createdAt)
                    ON CONFLICT (order_id) DO NOTHING
                    RETURNING order_id""")
                    .param("orderId", order.orderId(), Types.OTHER)
                    .param("groupId", order.orderGroupId())
                    .param("sourceSystem", order.sourceSystem())
                    .param("idempotencyKey", order.idempotencyKey())
                    .param("topic", position == null ? null : position.topic())
                    .param("partition", position == null ? null : position.partition(), Types.INTEGER)
                    .param("offset", position == null ? null : position.offset(), Types.BIGINT)
                    .param("createdAt", java.sql.Timestamp.from(order.createdAt()))
                    .query(UUID.class)
                    .list();
            inserted.addAll(returned);
        }
        return inserted;
    }

    /** Provisions entities in sorted order, skipping existing ones (D02-5). */
    public void provisionEntities(List<String> sortedEntityIds, Map<String, String> kindByEntityId) {
        template.batchUpdate("INSERT INTO entities (entity_id, kind) VALUES (?, ?) ON CONFLICT (entity_id) DO NOTHING",
                sortedEntityIds.stream().map(id -> new Object[] {id, kindByEntityId.get(id)}).toList());
    }

    /** Provisions accounts in sorted order, skipping existing ones (D02-5). */
    public void provisionAccounts(List<AccountKey> sortedKeys, Map<AccountKey, String> normalSides) {
        template.batchUpdate("""
                INSERT INTO accounts (entity_id, account_code, currency, normal_side)
                VALUES (?, ?, ?, ?) ON CONFLICT (entity_id, account_code, currency) DO NOTHING""",
                sortedKeys.stream()
                        .map(k -> new Object[] {k.entityId(), k.accountCode(), k.currency(), normalSides.get(k)})
                        .toList());
    }

    /** Locks the entity rows in ascending entity-ID order; the sort matches the D02-1 collation (ADR-0005). */
    public Map<String, EntityRow> lockEntities(List<String> sortedEntityIds) {
        Map<String, EntityRow> rows = new HashMap<>();
        template.query(connection -> {
            var ps = connection.prepareStatement("""
                    SELECT entity_id, last_seq, last_hash FROM entities
                    WHERE entity_id = ANY(?) ORDER BY entity_id FOR UPDATE""");
            ps.setArray(1, connection.createArrayOf("text", sortedEntityIds.toArray()));
            return ps;
        }, rs -> {
            rows.put(rs.getString("entity_id"),
                    new EntityRow(rs.getString("entity_id"), rs.getLong("last_seq"), rs.getBytes("last_hash")));
        });
        return rows;
    }

    /** Current balances for the given entities; already serialized by the entity locks. */
    public Map<AccountKey, Long> readBalances(List<String> entityIds) {
        Map<AccountKey, Long> balances = new HashMap<>();
        template.query(connection -> {
            var ps = connection.prepareStatement("""
                    SELECT entity_id, account_code, currency, balance_minor FROM accounts WHERE entity_id = ANY(?)""");
            ps.setArray(1, connection.createArrayOf("text", entityIds.toArray()));
            return ps;
        }, rs -> {
            balances.put(new AccountKey(rs.getString("entity_id"), rs.getString("account_code"), rs.getString("currency")),
                    rs.getLong("balance_minor"));
        });
        return balances;
    }

    public void updateBalances(Map<AccountKey, Long> balances) {
        List<Object[]> args = new ArrayList<>(balances.size());
        balances.forEach((key, balance) ->
                args.add(new Object[] {balance, key.entityId(), key.accountCode(), key.currency()}));
        template.batchUpdate(
                "UPDATE accounts SET balance_minor = ? WHERE entity_id = ? AND account_code = ? AND currency = ?", args);
    }

    public void updateEntityHeads(List<EntityRow> rows) {
        template.batchUpdate("UPDATE entities SET last_seq = ?, last_hash = ? WHERE entity_id = ?",
                rows.stream().map(r -> new Object[] {r.lastSeq(), r.lastHash(), r.entityId()}).toList());
    }

    public void insertChangelog(List<ChangelogRow> rows) {
        template.batchUpdate("""
                INSERT INTO entity_changelog (entity_id, seq, order_id, account_code, currency, delta_minor,
                                              balance_after_minor, hash_version, prev_hash, row_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                rows.stream().map(r -> new Object[] {r.entityId(), r.seq(), r.orderId(), r.accountCode(), r.currency(),
                        r.deltaMinor(), r.balanceAfterMinor(), r.hashVersion(), r.prevHash(), r.rowHash()}).toList());
    }
}
