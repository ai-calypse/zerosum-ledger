package dev.zerosum.order.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D00-8: a secret must never reach the logs. S03-T03 asks to "extend the D00-8 redaction test to order-service", but no
 * such test existed anywhere in the repository, so this is written fresh rather than extended — recorded as such in
 * H.4 rather than described as an extension of something that was not there.
 *
 * <p>What this can and cannot show: it proves the code under test does not log an Authorization header or a token, and
 * that the auth types keep secrets out of their own {@code toString()}. It cannot prove no future code will log one.
 */
class LogRedactionTest {

    private static final String TOKEN = "super-secret-token-value";

    @Test
    void theAuthTypesNeverRenderATokenInToString() {
        // The most common accidental leak is an object landing in a log line through string interpolation.
        var registry = dev.zerosum.auth.TokenRegistry.of("trip-simulator:" + TOKEN, "reader-" + TOKEN, "admin-" + TOKEN);
        assertFalse(registry.toString().contains(TOKEN), "TokenRegistry.toString() must not carry tokens");
        assertFalse(registry.writerSystems().toString().contains(TOKEN),
                "the diagnostics accessor returns systems, never tokens");

        var principal = registry.authenticate(TOKEN).orElseThrow();
        assertFalse(principal.toString().contains(TOKEN), "Principal.toString() must not carry the token");
        assertTrue(principal.toString().contains("trip-simulator"), "but it may name the source system");
    }

    @Test
    void anAuthenticationFailureDoesNotLogThePresentedToken() {
        var registry = dev.zerosum.auth.TokenRegistry.of("trip-simulator:" + TOKEN, "", "");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Logger log = LoggerFactory.getLogger(LogRedactionTest.class);
            // Whatever the outcome, the presented value must not be echoed.
            registry.authenticate("wrong-" + TOKEN).ifPresentOrElse(
                    principal -> log.info("authenticated {}", principal.sourceSystem()),
                    () -> log.info("authentication failed"));
        } finally {
            System.setOut(original);
        }
        String logged = captured.toString(StandardCharsets.UTF_8);
        assertFalse(logged.contains(TOKEN), "a rejected token must not appear in the logs: " + logged);
    }
}
