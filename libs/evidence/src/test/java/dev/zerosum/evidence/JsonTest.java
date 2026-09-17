package dev.zerosum.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Escaping, which is the only reason this class exists.
 *
 * <p>Evidence files are built as strings, following the convention {@code docs/results/sp1/sp1-runs.json} already
 * set. That is fine for numbers and fixed keys, and it is exactly wrong for free text: a quote or a newline inside a
 * scenario description, a git SHA line or a PostgreSQL {@code version()} banner would produce a file that no longer
 * parses. A results file that cannot be read is worse than no results file, because the failure shows up long after
 * the run it recorded.
 */
class JsonTest {

    @Test
    void plainTextIsQuoted() {
        assertEquals("\"plain\"", Json.quote("plain"));
        assertEquals("\"\"", Json.quote(""));
    }

    @Test
    @DisplayName("a quote or a backslash in free text does not break out of the string")
    void quotesAndBackslashesAreEscaped() {
        assertEquals("\"a\\\"b\"", Json.quote("a\"b"));
        assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
        // Both at once: a Windows-style path inside a description, which is where this would first be noticed.
        assertEquals("\"C:\\\\tmp\\\\\\\"x\\\"\"", Json.quote("C:\\tmp\\\"x\""));
    }

    @Test
    void controlCharactersAreEscaped() {
        assertEquals("\"line\\nbreak\"", Json.quote("line\nbreak"));
        assertEquals("\"a\\tb\"", Json.quote("a\tb"));
        assertEquals("\"a\\rb\"", Json.quote("a\rb"));
        // Below 0x20 with no short form: a raw byte here makes the whole document unparseable.
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    }

    @Test
    void nullBecomesTheJsonNullLiteralRatherThanTheTextNull() {
        // Not "\"null\"": a missing description is absent, not the four-letter word.
        assertEquals("null", Json.quote(null));
    }
}
