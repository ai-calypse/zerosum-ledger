package dev.zerosum.evidence;

/**
 * Minimal JSON rendering for evidence files.
 *
 * <p>Deliberately not a serialization library. The evidence files this project commits are hand-shaped, one record
 * per line, so they read as a diff (see {@code docs/results/sp1/sp1-runs.json}, written the same way). What a
 * hand-built string does get wrong is escaping, so that part — and only that part — lives here rather than being
 * repeated at every call site.
 */
public final class Json {

    private Json() {
    }

    /** A JSON string literal, quotes included, with the six characters JSON requires escaped. */
    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Everything below 0x20 must be escaped; a raw control character makes the file unparseable.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
