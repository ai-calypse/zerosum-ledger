package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplyRetryClassifierTest {

    private final RetryClassifier classifier = new RetryClassifier();

    @ParameterizedTest
    @ValueSource(strings = {"40P01", "40001", "55P03", "57P01", "57P03", "08000", "08003", "08006", "08001", "08007"})
    void transientStatesAreRetried(String sqlState) {
        assertTrue(classifier.isTransient(new SQLException("boom", sqlState)), sqlState);
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23514", "22003", "42501", "42P01", "53100", "57014", "XX000"})
    void everyOtherStateIsNonTransient(String sqlState) {
        // 57014 is the statement timeout: a retry would just hit it again, so the record is isolated instead.
        assertFalse(classifier.isTransient(new SQLException("boom", sqlState)), sqlState);
    }

    @Test
    void classifiesTheDeepestSqlExceptionInTheCauseChain() {
        SQLException root = new SQLException("deadlock detected", "40P01");
        RuntimeException wrapped = new RuntimeException("apply failed", new IllegalStateException("layer", root));
        assertTrue(classifier.isTransient(wrapped));
        assertEquals(root, classifier.rootSqlException(wrapped));
    }

    @Test
    void failuresWithoutASqlStateAreNonTransient() {
        assertFalse(classifier.isTransient(new RuntimeException("no database involved")));
        assertFalse(classifier.isTransient(new SQLException("no state")));
        assertFalse(classifier.isTransient(new ArithmeticException("long overflow")));
        assertNull(classifier.rootSqlException(new RuntimeException("no database involved")));
    }

    @Test
    void cyclicCauseChainTerminates() {
        // A driver or wrapper can produce a cycle: a causes b, b causes a. The walk must still finish.
        SQLException first = new SQLException("cyclic", "40001");
        SQLException second = new SQLException("cyclic partner", "23505");
        first.initCause(second);
        second.initCause(first);
        assertEquals(second, classifier.rootSqlException(first));
        assertFalse(classifier.isTransient(first), "the deepest reachable SQLException decides");
    }
}
