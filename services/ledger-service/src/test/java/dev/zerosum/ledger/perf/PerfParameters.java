package dev.zerosum.ledger.perf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * S07-T04/T05/T06 study parameters from {@code perf/perf.properties} (D07-5, D07-6).
 *
 * <p>Modelled on {@code Sp1Parameters}: the runner reads the values here and the report copies the file, so no number
 * is restated by hand in an assertion or in prose. Overrides exist only to smoke-check the harness, and
 * {@link #isFullStudy} makes a smoke run announce itself rather than pass as evidence.
 */
record PerfParameters(int windowSeconds, int warmupSeconds, int repetitions, int payloadPoolSize, int riderPool,
        int driverPool, List<Integer> batchSizes, List<Integer> batchWriters, List<Integer> entityCounts,
        int entityWriters, int entityBatchSize, List<Integer> e2eRates, int e2eAppenders, int e2eWindowSeconds,
        int e2eWarmupSeconds, int e2eLateThresholdMillis, int e2eMaxLatePercent) {

    private static final String RESOURCE = "perf/perf.properties";

    /** Smoke overrides, forwarded to the test JVM by the ledger build; a real study never sets them. */
    static final String WINDOW_PROPERTY = "zs.perf.windowSeconds";
    static final String WARMUP_PROPERTY = "zs.perf.warmupSeconds";
    static final String REPETITIONS_PROPERTY = "zs.perf.repetitions";
    static final String BATCH_SIZES_PROPERTY = "zs.perf.batchSizes";
    static final String BATCH_WRITERS_PROPERTY = "zs.perf.batchWriters";
    static final String ENTITY_COUNTS_PROPERTY = "zs.perf.entityCounts";
    static final String E2E_RATES_PROPERTY = "zs.perf.e2eRates";

    static PerfParameters fromClasspath() {
        Properties properties = new Properties();
        try (InputStream in = PerfParameters.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the test classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new PerfParameters(
                intValue(properties, "windowSeconds"),
                intValue(properties, "warmupSeconds"),
                intValue(properties, "repetitions"),
                intValue(properties, "payloadPoolSize"),
                intValue(properties, "riderPool"),
                intValue(properties, "driverPool"),
                intList(properties, "batchSizes"),
                intList(properties, "batchWriters"),
                intList(properties, "entityCounts"),
                intValue(properties, "entityWriters"),
                intValue(properties, "entityBatchSize"),
                intList(properties, "e2eRates"),
                intValue(properties, "e2eAppenders"),
                intValue(properties, "e2eWindowSeconds"),
                intValue(properties, "e2eWarmupSeconds"),
                intValue(properties, "e2eLateThresholdMillis"),
                intValue(properties, "e2eMaxLatePercent"));
    }

    /** The same parameters with any {@code zs.perf.*} system property applied. */
    PerfParameters withOverrides() {
        return new PerfParameters(
                intOverride(WINDOW_PROPERTY, windowSeconds),
                intOverride(WARMUP_PROPERTY, warmupSeconds),
                intOverride(REPETITIONS_PROPERTY, repetitions),
                payloadPoolSize, riderPool, driverPool,
                listOverride(BATCH_SIZES_PROPERTY, batchSizes),
                listOverride(BATCH_WRITERS_PROPERTY, batchWriters),
                listOverride(ENTITY_COUNTS_PROPERTY, entityCounts),
                entityWriters, entityBatchSize,
                listOverride(E2E_RATES_PROPERTY, e2eRates),
                e2eAppenders,
                intOverride(WINDOW_PROPERTY, e2eWindowSeconds),
                intOverride(WARMUP_PROPERTY, e2eWarmupSeconds),
                e2eLateThresholdMillis, e2eMaxLatePercent);
    }

    /** True when nothing was overridden, so the run may be reported as evidence. */
    boolean isFullStudy(PerfParameters fromFile) {
        return equals(fromFile);
    }

    /**
     * Whether any override was requested at all. Checked against {@link #isFullStudy} so a {@code -D} that never
     * reached the forked test JVM cannot silently turn a smoke check into a six-hour study, or the reverse — the
     * failure SP1 hit and guarded against (D02-10).
     */
    static boolean overridesRequested() {
        return List.of(WINDOW_PROPERTY, WARMUP_PROPERTY, REPETITIONS_PROPERTY, BATCH_SIZES_PROPERTY,
                        BATCH_WRITERS_PROPERTY, ENTITY_COUNTS_PROPERTY, E2E_RATES_PROPERTY).stream()
                .anyMatch(name -> System.getProperty(name) != null);
    }

    private static int intOverride(String property, int fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Integer.parseInt(value.strip());
    }

    private static List<Integer> listOverride(String property, List<Integer> fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : parseList(value);
    }

    private static List<Integer> intList(Properties properties, String key) {
        return parseList(value(properties, key));
    }

    private static List<Integer> parseList(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).map(Integer::parseInt).toList();
    }

    private static String value(Properties properties, String key) {
        String v = properties.getProperty(key);
        if (v == null) {
            throw new IllegalStateException("missing perf parameter in " + RESOURCE + ": " + key);
        }
        return v.strip();
    }

    private static int intValue(Properties properties, String key) {
        return Integer.parseInt(value(properties, key));
    }
}
