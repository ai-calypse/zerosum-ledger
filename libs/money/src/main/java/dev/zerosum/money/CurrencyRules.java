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

    /** decision: D01-7 — currencies orders may use; no runtime override in the MVP. */
    public static final String ALLOW_LIST_RESOURCE = "dev/zerosum/money/allowed-currencies.txt";

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");
    private static volatile CurrencyRules defaults;

    private final Map<String, Integer> digits;
    private final java.util.Set<String> allowed;

    private CurrencyRules(Map<String, Integer> digits, java.util.Set<String> allowed) {
        this.digits = Collections.unmodifiableMap(digits);
        this.allowed = Collections.unmodifiableSet(allowed);
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

    /** Loads a table resource with the default allow-list. See {@link #load(String, String)}. */
    public static CurrencyRules load(String resource) {
        return load(resource, ALLOW_LIST_RESOURCE);
    }

    /**
     * Loads a table and an allow-list. Fails with an {@link IllegalStateException} naming the resource when either is
     * missing, empty, malformed, unsorted (table) or contains duplicates, or when the allow-list names a currency that
     * is absent from the table — instead of an opaque class-initialization error.
     */
    public static CurrencyRules load(String resource, String allowListResource) {
        Map<String, Integer> table = loadTable(resource);
        java.util.Set<String> allowed = new java.util.TreeSet<>();
        try (BufferedReader reader = open(allowListResource, "currency allow-list")) {
            int lineNumber = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String code = line.strip();
                if (!CODE.matcher(code).matches()) {
                    throw new IllegalStateException("malformed line " + lineNumber + " in currency allow-list resource "
                            + allowListResource + ": " + line);
                }
                if (!table.containsKey(code)) {
                    throw new IllegalStateException("currency allow-list resource " + allowListResource + " lists " + code
                            + ", which is absent from the minor-unit table " + resource);
                }
                if (!allowed.add(code)) {
                    throw new IllegalStateException(
                            "duplicate " + code + " in currency allow-list resource " + allowListResource);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read currency allow-list resource " + allowListResource, e);
        }
        if (allowed.isEmpty()) {
            throw new IllegalStateException("currency allow-list resource is empty: " + allowListResource);
        }
        return new CurrencyRules(table, allowed);
    }

    private static BufferedReader open(String resource, String what) {
        InputStream in = CurrencyRules.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(what + " resource not found: " + resource);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static Map<String, Integer> loadTable(String resource) {
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
        return table;
    }

    /** True if orders may use {@code code} (D01-7). Every allowed code is also known. */
    public boolean isAllowed(String code) {
        return code != null && allowed.contains(code);
    }

    /** Allowed codes, sorted. */
    public java.util.Set<String> allowedCodes() {
        return allowed;
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
