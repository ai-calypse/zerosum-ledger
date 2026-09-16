package dev.zerosum.ledger.stress;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Stress parameters read from {@code stress/stress.properties} (D02-11). Assertions never hard-code them; the size is
 * selected with {@code -Pzs.stress.size=full|pr}, which the ledger build forwards to the test JVM.
 */
public record StressParameters(String size, int threads, int orders, int duplicatePercent, int batchSize,
        long samplerIntervalMillis, long commissionBps, int adjustmentPercent, int platformSplitPercent) {

    public static final String SIZE_PROPERTY = "zs.stress.size";

    private static final String RESOURCE = "stress/stress.properties";

    public static StressParameters fromClasspath() {
        Properties properties = new Properties();
        try (InputStream in = StressParameters.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " not found on the test classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String size = System.getProperty(SIZE_PROPERTY, "pr");
        if (!size.equals("pr") && !size.equals("full")) {
            throw new IllegalArgumentException(SIZE_PROPERTY + " must be 'pr' or 'full', was: " + size);
        }
        return new StressParameters(size,
                intValue(properties, size + ".threads"),
                intValue(properties, size + ".orders"),
                intValue(properties, size + ".duplicatePercent"),
                intValue(properties, "batchSize"),
                intValue(properties, "samplerIntervalMillis"),
                intValue(properties, "commissionBps"),
                intValue(properties, "adjustmentPercent"),
                intValue(properties, "platformSplitPercent"));
    }

    private static int intValue(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("missing stress parameter in " + RESOURCE + ": " + key);
        }
        return Integer.parseInt(value.strip());
    }
}
