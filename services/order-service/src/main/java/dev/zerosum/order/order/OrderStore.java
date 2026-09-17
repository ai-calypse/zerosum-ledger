package dev.zerosum.order.order;

import dev.zerosum.money.OrderCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only write path for money orders (D03-1). S03-T03's API and S03-T07's mapper both call {@link #create}, each
 * supplying a source system derived from its principal.
 *
 * <p>Idempotency is a database property, never a check-then-insert: the header insert is
 * {@code ON CONFLICT DO NOTHING RETURNING}, so a concurrent duplicate either wins the insert or reads the winner's row
 * inside the same transaction. A unique violation is never allowed to abort the transaction and leave a read to follow.
 */
@Component
public class OrderStore {

    /** What happened to a create call. */
    public enum Status {
        /** The order was new and was written by this call. */
        CREATED,
        /** The key was already used with the same request; the stored order is returned unchanged. */
        REPLAYED,
        /** The key was already used with a different request (M3 (b)). */
        KEY_REUSED,
        /** Another transaction holds the key and had not committed within the lock-wait bound (M3 (c)). */
        IN_PROGRESS,
        /**
         * The deferred trigger rejected the order at COMMIT (M2 (b)): entries do not sum to zero per currency, or the
         * header has fewer than two entries. Application validation should have caught it first, so reaching here means
         * validation was bypassed — it is still a client error, never a 500 (D03-2).
         */
        NOT_ZERO_SUM
    }

    /** {@code order} is present for CREATED and REPLAYED, and absent for KEY_REUSED and IN_PROGRESS. */
    public record Result(Status status, StoredOrder order) {

        public static Result of(Status status) {
            return new Result(status, null);
        }
    }

    /** A money order as stored, including the server-assigned identity a replay must return unchanged. */
    public record StoredOrder(UUID orderId, String orderGroupId, String type, String reason, UUID adjustsOrderId,
            String sourceSystem, String idempotencyKey, String metadataJson, Instant effectiveAt, Instant createdAt,
            List<OrderCandidate.Entry> entries) {
    }

    /** Raised when an adjustment points outside its own order group (§0.3 C2); the API maps it to its own code. */
    public static class AdjustmentGroupMismatchException extends RuntimeException {
        public AdjustmentGroupMismatchException(String message) {
            super(message);
        }
    }

    /** Raised when {@code adjusts_order_id} names an order that does not exist (M2 (c)). */
    public static class UnknownAdjustedOrderException extends RuntimeException {
        public UnknownAdjustedOrderException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final RequestHasher hasher;
    private final OrderStoreProperties properties;
    private final TransactionTemplate transactions;

    public OrderStore(JdbcClient jdbc, JdbcTemplate template, RequestHasher hasher, OrderStoreProperties properties,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.template = template;
        this.hasher = hasher;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates the order, or reports what the key already refers to. One transaction throughout, so the decision between
     * a replay and a key reuse is made against a consistent view.
     */
    public Result create(NewOrder order) {
        byte[] requestHash = hasher.hash(order);
        try {
            return transactions.execute(status -> {
                // A concurrent same-key insert makes this transaction wait on the unique index. The bound turns an
                // indefinite wait into IN_PROGRESS rather than holding the caller's HTTP request open (D03-3).
                template.execute("SET LOCAL lock_timeout = '" + properties.lockTimeout().toMillis() + "ms'");
                assertAdjustmentIsInTheSameGroup(order);

                Optional<UUID> inserted = insertHeader(order, requestHash);
                if (inserted.isPresent()) {
                    insertEntries(inserted.get(), order.entries());
                    return new Result(Status.CREATED, read(inserted.get()).orElseThrow());
                }

                StoredOrder existing = readByKey(order.sourceSystem(), order.idempotencyKey()).orElseThrow(
                        () -> new IllegalStateException("the key neither inserted nor resolved: "
                                + order.sourceSystem() + "/" + order.idempotencyKey()));
                return storedHashMatches(existing.orderId(), requestHash)
                        ? new Result(Status.REPLAYED, existing)
                        : Result.of(Status.KEY_REUSED);
            });
        } catch (RuntimeException failure) {
            // Classify by the SQLSTATE in the cause chain, not by which Spring exception happens to wrap it. A deferred
            // trigger fires at COMMIT, so the rejection arrives as TransactionSystemException ("JDBC commit failed"),
            // not the DataIntegrityViolationException a statement-time violation would produce.
            if (zeroSumViolation(failure)) {
                return Result.of(Status.NOT_ZERO_SUM);
            }
            if (failure instanceof QueryTimeoutException) {
                // Someone else holds the key and has not committed. IN_PROGRESS is honest: the outcome is undecided,
                // and the caller may safely retry the same key.
                return Result.of(Status.IN_PROGRESS);
            }
            throw failure;
        }
    }

    /** decision: D03-1 — the deferred trigger raises SQLSTATE 23514 (check_violation) at COMMIT. */
    private static boolean zeroSumViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql && "23514".equals(sql.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private void assertAdjustmentIsInTheSameGroup(NewOrder order) {
        if (order.adjustsOrderId() == null) {
            return;
        }
        // The foreign key proves existence, but only after the insert; reading first gives the caller the specific
        // error instead of a constraint violation, and the same-group rule cannot be expressed as a foreign key (C2).
        String group = jdbc.sql("SELECT order_group_id FROM money_orders WHERE order_id = :id")
                .param("id", order.adjustsOrderId(), java.sql.Types.OTHER)
                .query(String.class).optional()
                .orElseThrow(() -> new UnknownAdjustedOrderException(
                        "adjusts_order_id does not exist: " + order.adjustsOrderId()));
        if (!group.equals(order.orderGroupId())) {
            throw new AdjustmentGroupMismatchException("adjusts_order_id " + order.adjustsOrderId()
                    + " belongs to order group " + group + ", not " + order.orderGroupId());
        }
    }

    private Optional<UUID> insertHeader(NewOrder order, byte[] requestHash) {
        return jdbc.sql("""
                INSERT INTO money_orders (order_group_id, type, reason, adjusts_order_id, source_system,
                                          idempotency_key, request_hash, request_hash_version, metadata, effective_at)
                VALUES (:groupId, :type, :reason, :adjusts, :sourceSystem, :idempotencyKey, :hash, :hashVersion,
                        cast(:metadata as jsonb), :effectiveAt)
                ON CONFLICT (source_system, idempotency_key) DO NOTHING
                RETURNING order_id""")
                .param("groupId", order.orderGroupId())
                .param("type", order.type())
                .param("reason", order.reason())
                .param("adjusts", order.adjustsOrderId(), java.sql.Types.OTHER)
                .param("sourceSystem", order.sourceSystem())
                .param("idempotencyKey", order.idempotencyKey())
                .param("hash", requestHash)
                .param("hashVersion", RequestHasher.FORM_VERSION)
                .param("metadata", RequestHasher.canonicalJson(order.metadataJson()))
                .param("effectiveAt", java.sql.Timestamp.from(order.effectiveAt()))
                .query(UUID.class).optional();
    }

    private void insertEntries(UUID orderId, List<OrderCandidate.Entry> entries) {
        List<Object[]> rows = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            OrderCandidate.Entry entry = entries.get(i);
            rows.add(new Object[] {orderId, (short) (i + 1), entry.entityId(), entry.account(), entry.currency(),
                    entry.amountMinor()});
        }
        template.batchUpdate("""
                INSERT INTO money_order_entries (order_id, line_no, entity_id, account_code, currency, amount_minor)
                VALUES (?, ?, ?, ?, ?, ?)""", rows);
    }

    private boolean storedHashMatches(UUID orderId, byte[] requestHash) {
        // Compared under the version the row was written with: a later form version must never reinterpret old rows.
        var stored = jdbc.sql("SELECT request_hash, request_hash_version FROM money_orders WHERE order_id = :id")
                .param("id", orderId, java.sql.Types.OTHER)
                .query((rs, rowNumber) -> new Object[] {rs.getBytes("request_hash"), rs.getShort("request_hash_version")})
                .single();
        short storedVersion = (short) stored[1];
        if (storedVersion != RequestHasher.FORM_VERSION) {
            // No older version exists yet. When one does, this is where its hasher is selected; until then, refusing is
            // safer than comparing digests computed under different rules.
            throw new IllegalStateException("stored request hash uses canonical form version " + storedVersion
                    + ", which this build cannot recompute");
        }
        return java.util.Arrays.equals((byte[]) stored[0], requestHash);
    }

    /** The order and its entries, for the create path and for S03-T03's fetch-by-id. */
    public Optional<StoredOrder> read(UUID orderId) {
        return jdbc.sql(SELECT_HEADER + " WHERE order_id = :id")
                .param("id", orderId, java.sql.Types.OTHER)
                .query(OrderStore::mapHeader).optional()
                .map(this::withEntries);
    }

    public Optional<StoredOrder> readByKey(String sourceSystem, String idempotencyKey) {
        return jdbc.sql(SELECT_HEADER + " WHERE source_system = :sourceSystem AND idempotency_key = :idempotencyKey")
                .param("sourceSystem", sourceSystem)
                .param("idempotencyKey", idempotencyKey)
                .query(OrderStore::mapHeader).optional()
                .map(this::withEntries);
    }

    /** A group's orders in creation order, which is a trip's full history (S03-T03). */
    public List<StoredOrder> readByGroup(String orderGroupId) {
        return jdbc.sql(SELECT_HEADER + " WHERE order_group_id = :groupId ORDER BY created_at, order_id")
                .param("groupId", orderGroupId)
                .query(OrderStore::mapHeader).list()
                .stream().map(this::withEntries).toList();
    }

    private static final String SELECT_HEADER = """
            SELECT order_id, order_group_id, type, reason, adjusts_order_id, source_system, idempotency_key,
                   metadata::text AS metadata, effective_at, created_at
            FROM money_orders""";

    private static StoredOrder mapHeader(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        return new StoredOrder(rs.getObject("order_id", UUID.class), rs.getString("order_group_id"),
                rs.getString("type"), rs.getString("reason"), rs.getObject("adjusts_order_id", UUID.class),
                rs.getString("source_system"), rs.getString("idempotency_key"), rs.getString("metadata"),
                rs.getTimestamp("effective_at").toInstant(), rs.getTimestamp("created_at").toInstant(), List.of());
    }

    private StoredOrder withEntries(StoredOrder header) {
        List<OrderCandidate.Entry> entries = jdbc.sql("""
                SELECT entity_id, account_code, currency, amount_minor FROM money_order_entries
                WHERE order_id = :id ORDER BY line_no""")
                .param("id", header.orderId(), java.sql.Types.OTHER)
                .query((rs, rowNumber) -> OrderCandidate.Entry.of(rs.getString("entity_id"),
                        rs.getString("account_code"), rs.getString("currency").strip(), rs.getLong("amount_minor")))
                .list();
        return new StoredOrder(header.orderId(), header.orderGroupId(), header.type(), header.reason(),
                header.adjustsOrderId(), header.sourceSystem(), header.idempotencyKey(), header.metadataJson(),
                header.effectiveAt(), header.createdAt(), entries);
    }
}
