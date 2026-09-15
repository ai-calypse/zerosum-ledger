package dev.zerosum.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Loads golden payloads (D01-9) as JSON text by ID: {@code O1}…{@code O8} are money orders, {@code EV-…} are payment
 * events. File name is the lower-case ID ({@code golden/manifest.json} lists every file and its pairing).
 */
public final class GoldenPayloads {

    private static final Pattern ORDER_ID = Pattern.compile("O[1-9]");
    private static final Pattern EVENT_ID = Pattern.compile("EV-[A-Z0-9_]+");

    private GoldenPayloads() {
    }

    /** The golden payload with this ID, for example {@code "O6"} or {@code "EV-O6"}. */
    public static String byId(String id) {
        if (id != null && ORDER_ID.matcher(id).matches()) {
            return resource("golden/orders/" + id.toLowerCase(Locale.ROOT) + ".json");
        }
        if (id != null && EVENT_ID.matcher(id).matches()) {
            return resource("golden/events/" + id.toLowerCase(Locale.ROOT) + ".json");
        }
        throw new IllegalArgumentException("not a golden payload ID: " + id);
    }

    public static String manifest() {
        return resource("golden/manifest.json");
    }

    public static String expectedBalances() {
        return resource("golden/expected-balances.json");
    }

    static String resource(String path) {
        try (InputStream in = GoldenPayloads.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("golden resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read golden resource " + path, e);
        }
    }
}
