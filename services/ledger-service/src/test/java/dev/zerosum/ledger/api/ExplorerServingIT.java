package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpResponse;

/** D09-4: the Explorer is served, with the headers that make its inline script safe to run. */
class ExplorerServingIT extends LedgerApiTestBase {

    private static final Pattern SCRIPT = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL);

    private record Page(int status, String contentType, String body, String csp, String nosniff, String frame) {
    }

    private Page fetchExplorer() {
        return http().get().uri("/explorer.html").exchange((request, response) -> read(response));
    }

    private static Page read(ClientHttpResponse response) {
        try {
            return new Page(response.getStatusCode().value(),
                    String.valueOf(response.getHeaders().getFirst("Content-Type")),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                    response.getHeaders().getFirst("Content-Security-Policy"),
                    response.getHeaders().getFirst("X-Content-Type-Options"),
                    response.getHeaders().getFirst("X-Frame-Options"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("the page is served as HTML with its security headers")
    void servesThePage() {
        Page page = fetchExplorer();

        assertEquals(200, page.status());
        assertTrue(page.contentType().startsWith("text/html"), "content type was " + page.contentType());
        assertTrue(page.body().contains("ZeroSum Explorer"), "the body is the Explorer");
        assertEquals("nosniff", page.nosniff());
        assertEquals("DENY", page.frame());
        assertTrue(page.csp().contains("default-src 'none'"), page.csp());
        assertTrue(page.csp().contains("connect-src 'self'"), page.csp());
        assertTrue(page.csp().contains("frame-ancestors 'none'"), page.csp());
    }

    @Test
    @DisplayName("the CSP hash matches the script actually served, so an edit cannot silently break the page")
    void cspHashMatchesTheServedScript() {
        Page page = fetchExplorer();

        Matcher matcher = SCRIPT.matcher(page.body());
        assertTrue(matcher.find(), "the page carries an inline script");
        String expected = "'sha256-" + ExplorerHeaders.sha256(matcher.group(1)) + "'";

        // Recomputed from the bytes the browser would hash. Without this, editing the page would leave a stale hash
        // and the only symptom would be a blank page in someone else's browser.
        assertTrue(page.csp().contains(expected),
                "CSP should contain " + expected + " but was: " + page.csp());
    }

    @Test
    @DisplayName("the page itself needs no token, but it ships none either")
    void pageIsUnauthenticatedAndCarriesNoSecret() {
        Page page = fetchExplorer();

        assertEquals(200, page.status(), "static assets are not behind the /v1 auth filter");
        // The reader token is typed by the operator and held in a JS variable. A token baked into the page would be
        // readable by anyone who could load it.
        assertFalse(page.body().contains("Bearer test-reader-token"), "no token is embedded in the page");
        // Both credential inputs are password fields: the reader token, and the admin token the provider panel needs.
        assertEquals(2, occurrences(page.body(), "type=\"password\""), "both token inputs are password fields");
    }

    @Test
    @DisplayName("the page keeps its safety contract: no unsafe sink, no third-party subresource")
    void pageCarriesNoUnsafeSinkOrThirdPartyResource() {
        String body = fetchExplorer().body();

        Matcher matcher = SCRIPT.matcher(body);
        assertTrue(matcher.find(), "the page carries an inline script");
        // Scoped to the script rather than the whole file on purpose: the header comment names these sinks in prose
        // to explain why they are absent, and a test that failed on its own documentation would be deleted.
        String script = matcher.group(1);

        // Every value is written with textContent (D09-4). One innerHTML would turn an idempotency key containing
        // an <img onerror> back into markup, and no CSP hash would notice: the script's own hash still matches.
        assertFalse(script.contains("innerHTML"), "values are rendered with textContent, never innerHTML");
        assertFalse(script.contains("outerHTML"), "values are rendered with textContent, never outerHTML");
        assertFalse(script.contains("insertAdjacentHTML"), "no markup is parsed from a string");
        assertFalse(script.contains("document.write"), "nothing is written into the parser");
        assertFalse(script.contains("eval("), "nothing is evaluated from a string");

        // The CSP names hashes rather than origins, so a third-party script, stylesheet or font would simply be
        // blocked — silently, in someone else's browser. Staying self-contained is what makes that policy honest.
        assertFalse(body.contains("<script src"), "no third-party script");
        assertFalse(body.contains("<link"), "no third-party stylesheet or font");

        // Exactly one of each, because every additional block needs its own hash in the policy and the test above
        // only recomputes the first.
        assertEquals(1, occurrences(body, "<script"), "one inline script");
        assertEquals(1, occurrences(body, "<style"), "one inline style");
    }

    private static int occurrences(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
