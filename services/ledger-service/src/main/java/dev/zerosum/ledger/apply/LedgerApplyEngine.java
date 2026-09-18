package dev.zerosum.ledger.apply;

import dev.zerosum.ledger.apply.ApplyRecord.SourcePosition;
import dev.zerosum.ledger.changelog.ChangelogHasher;
import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.store.LedgerStore.AccountKey;
import dev.zerosum.ledger.store.LedgerStore.ChangelogRow;
import dev.zerosum.ledger.store.LedgerStore.EntityRow;
import dev.zerosum.money.ChartOfAccounts;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only write path into the ledger tables (D02-3). One call applies an ordered batch in a single transaction:
 * quarantine invalid records, insert applied-order records with conflict skipping so redeliveries drop out, provision
 * entities and accounts, lock entities in sorted order (ADR-0005), compute balances, sequence numbers and hash-chain
 * rows in memory, then write them in batched statements.
 *
 * <p>Only the D02-4 transient failure classes are retried, each attempt re-running the whole transaction from fresh
 * reads. A non-transient failure in a multi-order batch is isolated by re-running the records one at a time, so only the
 * offending record is quarantined; money is never skipped.
 */
@Component
public class LedgerApplyEngine {

    private static final Logger log = LoggerFactory.getLogger(LedgerApplyEngine.class);

    private final OrderDecoder decoder;
    private final LedgerStore store;
    private final ChangelogHasher hasher;
    private final RetryClassifier classifier;
    private final LedgerApplyProperties properties;
    private final TransactionTemplate transactions;

    public LedgerApplyEngine(OrderDecoder decoder, LedgerStore store, ChangelogHasher hasher, RetryClassifier classifier,
            LedgerApplyProperties properties, PlatformTransactionManager transactionManager) {
        this.decoder = decoder;
        this.store = store;
        this.hasher = hasher;
        this.classifier = classifier;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Applies an ordered batch; the default mode is {@code ledger.apply.default-batch-size} (D02-3). */
    public ApplyBatchResult apply(List<ApplyRecord> records) {
        long startedAt = System.nanoTime();
        if (records.isEmpty()) {
            return new ApplyBatchResult(List.of(), Duration.ZERO, Duration.ZERO, 0, 0, 0, 0);
        }
        Attempt attempt = new Attempt(records);
        int deadlocks = 0;
        int lockTimeouts = 0;
        int connectionFailures = 0;
        RuntimeException lastFailure = null;

        for (int number = 1; number <= properties.maxAttempts(); number++) {
            try {
                List<ApplyOutcome> outcomes = transactions.execute(status -> attempt.run());
                return new ApplyBatchResult(outcomes, elapsedSince(startedAt), Duration.ofNanos(attempt.lockWaitNanos),
                        number, deadlocks, lockTimeouts, connectionFailures);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (!classifier.isTransient(failure)) {
                    List<ApplyOutcome> isolated = isolate(records, failure);
                    return new ApplyBatchResult(isolated, elapsedSince(startedAt),
                            Duration.ofNanos(attempt.lockWaitNanos), number, deadlocks, lockTimeouts, connectionFailures);
                }
                SQLException sql = classifier.rootSqlException(failure);
                String state = sql == null ? "" : String.valueOf(sql.getSQLState());
                if ("40P01".equals(state)) {
                    deadlocks++;
                } else if ("55P03".equals(state) || failure instanceof RetryClassifier.LockQueueTimeout) {
                    lockTimeouts++;
                } else {
                    connectionFailures++;
                }
                if (number == properties.maxAttempts()) {
                    break;
                }
                backoff(number);
            }
        }
        throw new RetriesExhaustedException(records, properties.maxAttempts(), lastFailure);
    }

    public ApplyBatchResult applyOne(ApplyRecord record) {
        return apply(List.of(record));
    }

    /**
     * Re-runs a failed multi-order batch one record at a time so only the offending record is quarantined (D02-3). In
     * per-order mode the record is quarantined directly.
     */
    private List<ApplyOutcome> isolate(List<ApplyRecord> records, RuntimeException failure) {
        if (records.size() > 1) {
            log.warn("non-transient failure in a batch of {}; isolating records one at a time", records.size(), failure);
            List<ApplyOutcome> outcomes = new ArrayList<>(records.size());
            for (int i = 0; i < records.size(); i++) {
                ApplyOutcome single = apply(List.of(records.get(i))).outcomes().get(0);
                outcomes.add(new ApplyOutcome(i, single.orderId(), single.status(), single.errorCode(), single.detail()));
            }
            return outcomes;
        }
        ApplyRecord record = records.get(0);
        QuarantineCode code = failure.getCause() instanceof ArithmeticException || failure instanceof ArithmeticException
                ? QuarantineCode.ARITHMETIC_OVERFLOW
                : QuarantineCode.UNEXPECTED_DATABASE_ERROR;
        UUID orderId = decoder.decode(record.payload()) instanceof OrderDecoder.Result.Decoded decoded
                ? decoded.order().orderId()
                : null;
        log.warn("quarantining a record after a non-transient failure: {}", code, failure);
        transactions.executeWithoutResult(status ->
                store.insertQuarantine(orderId, record.payload(), code, message(failure), record.position()));
        return List.of(ApplyOutcome.quarantined(0, orderId, code, message(failure)));
    }

    private void backoff(int attemptNumber) {
        long min = properties.backoffMin().toMillis();
        long max = properties.backoffMax().toMillis();
        long target = Math.min(max, min * (1L << Math.min(attemptNumber - 1, 20)));
        long sleep = ThreadLocalRandom.current().nextLong(min, Math.max(min + 1, target + 1));
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutdown: stop retrying, nothing was committed
            throw new IllegalStateException("apply interrupted during backoff", e);
        }
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static String message(Throwable failure) {
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    /** One attempt's work: decoding is done once, the transaction body re-runs from fresh reads on every retry. */
    private final class Attempt {

        private final List<ApplyRecord> records;
        private final Map<Integer, OrderDecoder.Result> decoded = new LinkedHashMap<>();
        private long lockWaitNanos;

        private Attempt(List<ApplyRecord> records) {
            this.records = records;
            for (int i = 0; i < records.size(); i++) {
                decoded.put(i, decoder.decode(records.get(i).payload()));
            }
        }

        private List<ApplyOutcome> run() {
            store.applyTransactionTimeouts(properties.lockTimeout(), properties.statementTimeout());

            Map<Integer, ApplyOutcome> outcomes = new TreeMap<>();
            List<DecodedOrder> candidates = new ArrayList<>();
            Map<UUID, Integer> firstIndexOf = new LinkedHashMap<>();
            Map<UUID, SourcePosition> positions = new HashMap<>();

            for (Map.Entry<Integer, OrderDecoder.Result> entry : decoded.entrySet()) {
                int index = entry.getKey();
                if (entry.getValue() instanceof OrderDecoder.Result.Rejected rejected) {
                    store.insertQuarantine(rejected.orderId(), records.get(index).payload(), rejected.code(),
                            rejected.detail(), records.get(index).position());
                    outcomes.put(index, ApplyOutcome.quarantined(index, rejected.orderId(), rejected.code(), rejected.detail()));
                    continue;
                }
                DecodedOrder order = ((OrderDecoder.Result.Decoded) entry.getValue()).order();
                if (firstIndexOf.putIfAbsent(order.orderId(), index) != null) {
                    // Repeated inside this batch: the first occurrence wins (D02-3).
                    outcomes.put(index, ApplyOutcome.duplicate(index, order.orderId()));
                    continue;
                }
                candidates.add(order);
                positions.put(order.orderId(), records.get(index).position());
            }

            Set<UUID> fresh = candidates.isEmpty() ? Set.of() : store.insertAppliedOrders(candidates, positions);
            List<DecodedOrder> orders = candidates.stream().filter(o -> fresh.contains(o.orderId())).toList();
            for (DecodedOrder order : candidates) {
                int index = firstIndexOf.get(order.orderId());
                outcomes.put(index, fresh.contains(order.orderId())
                        ? ApplyOutcome.applied(index, order.orderId())
                        : ApplyOutcome.duplicate(index, order.orderId()));
            }

            if (!orders.isEmpty()) {
                applyOrders(orders);
            }
            return List.copyOf(outcomes.values());
        }

        private void applyOrders(List<DecodedOrder> orders) {
            TreeSet<String> entityIds = new TreeSet<>();
            TreeSet<AccountKey> accountKeys = new TreeSet<>(java.util.Comparator
                    .comparing(AccountKey::entityId).thenComparing(AccountKey::accountCode).thenComparing(AccountKey::currency));
            Map<String, String> kinds = new HashMap<>();
            Map<AccountKey, String> normalSides = new HashMap<>();
            for (DecodedOrder order : orders) {
                for (DecodedOrder.Entry entry : order.entries()) {
                    entityIds.add(entry.entityId());
                    kinds.put(entry.entityId(), ChartOfAccounts.kindOf(entry.entityId()).orElseThrow().prefix());
                    AccountKey key = new AccountKey(entry.entityId(), entry.accountCode(), entry.currency());
                    accountKeys.add(key);
                    normalSides.put(key, ChartOfAccounts.normalSide(entry.accountCode()).name());
                }
            }
            List<String> sortedEntities = List.copyOf(entityIds);
            store.provisionEntities(sortedEntities, kinds);

            // Lock the entities before provisioning accounts. accounts.entity_id references entities (D02-1), so an
            // account insert takes FOR KEY SHARE on its parent entity row; locking afterwards would upgrade that
            // shared lock to FOR UPDATE, and two batches sharing an entity then deadlock whatever order they use.
            // Sorted locking (ADR-0005) prevents ordering cycles, never a lock-strength upgrade.
            long lockStart = System.nanoTime();
            Map<String, EntityRow> locked;
            try {
                locked = store.lockEntities(sortedEntities);
            } catch (RuntimeException failure) {
                SQLException sql = classifier.rootSqlException(failure);
                // CR-S07-01: this statement only looks up rows by primary key, so a timeout here is time spent queueing.
                if (sql != null && RetryClassifier.STATEMENT_TIMEOUT.equals(sql.getSQLState())) {
                    throw new RetryClassifier.LockQueueTimeout(failure);
                }
                throw failure;
            } finally {
                lockWaitNanos += System.nanoTime() - lockStart;
            }

            store.provisionAccounts(List.copyOf(accountKeys), normalSides);

            Map<AccountKey, Long> balances = new HashMap<>(store.readBalances(sortedEntities));
            accountKeys.forEach(key -> balances.putIfAbsent(key, 0L));
            Map<String, EntityRow> heads = new HashMap<>(locked);
            List<ChangelogRow> changelog = new ArrayList<>();

            for (DecodedOrder order : orders) {
                for (DecodedOrder.Entry entry : order.entries()) {
                    AccountKey key = new AccountKey(entry.entityId(), entry.accountCode(), entry.currency());
                    long balanceAfter = Math.addExact(balances.get(key), entry.amountMinor());
                    balances.put(key, balanceAfter);

                    EntityRow head = heads.get(entry.entityId());
                    long seq = Math.addExact(head.lastSeq(), 1);
                    byte[] rowHash = hasher.hash(head.lastHash(), entry.entityId(), seq, order.orderId(),
                            entry.accountCode(), entry.currency(), entry.amountMinor(), balanceAfter);
                    changelog.add(new ChangelogRow(entry.entityId(), seq, order.orderId(), entry.accountCode(),
                            entry.currency(), entry.amountMinor(), balanceAfter, ChangelogHasher.FORM_VERSION,
                            head.lastHash(), rowHash));
                    heads.put(entry.entityId(), new EntityRow(entry.entityId(), seq, rowHash));
                }
            }

            store.updateBalances(balances);
            store.updateEntityHeads(List.copyOf(heads.values()));
            store.insertChangelog(changelog);
        }
    }
}
