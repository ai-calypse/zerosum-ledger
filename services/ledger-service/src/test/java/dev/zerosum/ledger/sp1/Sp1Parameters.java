package dev.zerosum.ledger.sp1;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * SP1 study parameters from {@code sp1/sp1.properties} (D02-10). Every value comes from the master; the runner reads
 * them here and the report copies the file, so no number is restated by hand.
 */
record Sp1Parameters(List<Integer> writers, int windowSeconds, int warmupSeconds, int repetitions, int payloadPoolSize,
        int riderPool, int driverPool) {

    private static final String RESOURCE = "sp1/sp1.properties";

    static Sp1Parameters fromClasspath() {
        Properties properties = new Properties();
        try (InputStream in = Sp1Parameters.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the test classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Sp1Parameters(
                Arrays.stream(value(properties, "writers").split(",")).map(String::strip).map(Integer::parseInt).toList(),
                intValue(properties, "windowSeconds"),
                intValue(properties, "warmupSeconds"),
                intValue(properties, "repetitions"),
                intValue(properties, "payloadPoolSize"),
                intValue(properties, "riderPool"),
                intValue(properties, "driverPool"));
    }

    /** Overrides for a smoke run of the harness itself; a real study never uses them. */
    Sp1Parameters withOverrides(String writersOverride, String windowOverride, String warmupOverride,
            String repetitionsOverride) {
        return new Sp1Parameters(
                writersOverride == null ? writers
                        : Arrays.stream(writersOverride.split(",")).map(String::strip).map(Integer::parseInt).toList(),
                windowOverride == null ? windowSeconds : Integer.parseInt(windowOverride),
                warmupOverride == null ? warmupSeconds : Integer.parseInt(warmupOverride),
                repetitionsOverride == null ? repetitions : Integer.parseInt(repetitionsOverride),
                payloadPoolSize, riderPool, driverPool);
    }

    boolean isFullStudy(Sp1Parameters fromFile) {
        return writers.equals(fromFile.writers) && windowSeconds == fromFile.windowSeconds
                && warmupSeconds == fromFile.warmupSeconds && repetitions == fromFile.repetitions;
    }

    private static String value(Properties properties, String key) {
        String v = properties.getProperty(key);
        if (v == null) {
            throw new IllegalStateException("missing SP1 parameter in " + RESOURCE + ": " + key);
        }
        return v.strip();
    }

    private static int intValue(Properties properties, String key) {
        return Integer.parseInt(value(properties, key));
    }
}
