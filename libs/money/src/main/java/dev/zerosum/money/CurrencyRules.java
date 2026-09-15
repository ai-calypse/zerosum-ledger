package dev.zerosum.money;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ISO 4217 minor-unit digits from the checked-in table generated from List One (D01-2). Never consults the JDK's
 * built-in currency data, which can lag ISO amendments (enforced by ArchUnit Rule E). Immutable and thread-safe.
 */
public final class CurrencyRules {

    /** decision: D01-2 — docs/step_01_domain_contracts.md#decisions-and-outputs */
    public static final String TABLE_RESOURCE = "dev/zerosum/money/iso4217-minor-units.csv";

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");
    private static volatile CurrencyRules defaults;

    private final Map<String, Integer> digits;

    private CurrencyRules(Map<String, Integer> digits) {
        this.digits = Collections.unmodifiableMap(digits);
    }

    /** The rules loaded from {@link #TABLE_RESOURCE}; loaded once, on first use. */
    public static CurrencyRules defaults() {
        CurrencyRules rules = defaults;
        if (rules == null) {
            synchronized (CurrencyRules.class) {
                rules = defaults;
                if (rules == null) {
                    rules = load(TABLE_RESOURCE);
                    defaults = rules;
                }
            }
        }
        return rules;
    }

    /**
     * Loads a table resource. Fails with an {@link IllegalStateException} naming the resource when it is missing,
     * empty, malformed, unsorted or contains duplicates, instead of an opaque class-initialization error.
     */
    public static CurrencyRules load(String resource) {
        InputStream in = CurrencyRules.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("currency table resource not found: " + resource);
        }
        Map<String, Integer> table = new LinkedHashMap<>();
        String previous = null;
        int lineNumber = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length != 2 || !CODE.matcher(parts[0]).matches() || !parts[1].matches("[0-9]")) {
                    throw new IllegalStateException(
                            "malformed line " + lineNumber + " in currency table resource " + resource + ": " + line);
                }
                if (previous != null && parts[0].compareTo(previous) <= 0) {
                    throw new IllegalStateException("currency table resource " + resource + " is not strictly sorted at line "
                            + lineNumber + " (" + parts[0] + " after " + previous + ")");
                }
                table.put(parts[0], Integer.parseInt(parts[1]));
                previous = parts[0];
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read currency table resource " + resource, e);
        }
        if (table.isEmpty()) {
            throw new IllegalStateException("currency table resource is empty: " + resource);
        }
        return new CurrencyRules(table);
    }

    /** True if {@code code} is an alphabetic code with numeric minor units in the table. */
    public boolean isKnown(String code) {
        return code != null && digits.containsKey(code);
    }

    /** Minor-unit digits for a known code. */
    public int minorUnitDigits(String code) {
        Integer value = code == null ? null : digits.get(code);
        if (value == null) {
            throw new IllegalArgumentException("unknown currency code: " + code);
        }
        return value;
    }

    /** Codes in the table, sorted. */
    public Iterable<String> codes() {
        return digits.keySet();
    }
}
