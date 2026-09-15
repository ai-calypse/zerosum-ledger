package dev.zerosum.ledger.apply;

import java.sql.SQLException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Classifies a failure as transient or not (D02-4, ADR-0005) by the SQLSTATE of the root {@link SQLException}, never by
 * the Spring exception subclass, because translation varies between drivers and versions.
 *
 * <p>Transient: {@code 40P01} deadlock detected, {@code 40001} serialization failure, {@code 55P03} lock not available
 * (the {@code lock_timeout} expiry), and every {@code 08xxx} connection class. Everything else is non-transient: the
 * record is isolated and quarantined, never silently skipped.
 */
@Component
public class RetryClassifier {

    /**
     * decision: D02-4 — transient SQLSTATEs, retried with the configured backoff: deadlock detected, serialization
     * failure, lock not available (the {@code lock_timeout} expiry), and the operator-initiated disconnects
     * {@code 57P01} admin shutdown and {@code 57P03} cannot connect now. The last two are added to the master's list
     * because a terminated backend must never cause money to be quarantined; {@code 57014} query canceled (the
     * statement timeout) stays non-transient.
     */
    public static final Set<String> TRANSIENT_STATES = Set.of("40P01", "40001", "55P03", "57P01", "57P03");

    /** decision: D02-4 — every SQLSTATE class retried in full. */
    public static final String TRANSIENT_CONNECTION_CLASS = "08";

    public boolean isTransient(Throwable failure) {
        SQLException sqlException = rootSqlException(failure);
        if (sqlException == null) {
            return false;
        }
        String state = sqlException.getSQLState();
        if (state == null) {
            return false;
        }
        return TRANSIENT_STATES.contains(state) || state.startsWith(TRANSIENT_CONNECTION_CLASS);
    }

    /**
     * The deepest {@link SQLException} in the cause chain, or null. The walk is bounded, because a driver or wrapper can
     * produce a cyclic chain (a causes b, b causes a) that a self-reference check alone would not stop.
     */
    public SQLException rootSqlException(Throwable failure) {
        SQLException found = null;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sql) {
                found = sql;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return found;
    }

    /** Guards against cyclic cause chains; real chains are far shorter. */
    private static final int MAX_CAUSE_DEPTH = 64;
}
