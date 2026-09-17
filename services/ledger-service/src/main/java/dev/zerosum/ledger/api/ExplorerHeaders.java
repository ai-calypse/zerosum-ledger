// decision: D09-4 — docs/step_09_demo_docs_release.md#s09-t03
package dev.zerosum.ledger.api;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Security headers for the Ledger Explorer (D09-4).
 *
 * <p>The page is one file with inline script and style, so its Content-Security-Policy needs their hashes. Those
 * hashes are <strong>derived from the served file at startup</strong> rather than pasted into a constant. A
 * hard-coded hash goes stale the moment someone edits the page, and the symptom is a browser silently refusing to
 * run it — a failure that no test on this side of the wire would catch. Deriving them means the policy cannot
 * disagree with the bytes it is protecting.
 *
 * <p>{@code default-src 'none'} with an explicit {@code connect-src 'self'} keeps the page able to call this
 * service's own API and nothing else: no third-party script, font, image or beacon, and no exfiltration path for a
 * reader token if the page ever did render hostile input.
 */
@Configuration
class ExplorerHeaders {

    static final String PATH = "/explorer.html";

    private static final Pattern SCRIPT = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern STYLE = Pattern.compile("<style>(.*?)</style>", Pattern.DOTALL);

    @Bean
    FilterRegistrationBean<Filter> explorerSecurityHeaders() {
        String policy = contentSecurityPolicy();
        var registration = new FilterRegistrationBean<Filter>(new HeaderFilter(policy));
        registration.addUrlPatterns(PATH);
        return registration;
    }

    /** Built once, from the page as it will actually be served. */
    static String contentSecurityPolicy() {
        String page = readPage();
        List<String> scripts = hashesOf(SCRIPT, page);
        List<String> styles = hashesOf(STYLE, page);
        if (scripts.isEmpty()) {
            // The page is expected to carry its behaviour inline; an empty result means the markers moved and the
            // policy would silently block everything.
            throw new IllegalStateException("no inline <script> found in " + PATH + "; the CSP would be wrong");
        }
        return "default-src 'none'; "
                + "connect-src 'self'; "
                + "script-src " + String.join(" ", scripts) + "; "
                + "style-src " + String.join(" ", styles) + "; "
                + "base-uri 'none'; "
                + "form-action 'none'; "
                + "frame-ancestors 'none'";
    }

    private static List<String> hashesOf(Pattern pattern, String page) {
        var hashes = new ArrayList<String>();
        Matcher matcher = pattern.matcher(page);
        while (matcher.find()) {
            hashes.add("'sha256-" + sha256(matcher.group(1)) + "'");
        }
        return hashes;
    }

    /** Base64 of the SHA-256 over the exact bytes between the tags, which is what a browser hashes. */
    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }

    private static String readPage() {
        try (InputStream in = new ClassPathResource("static" + PATH).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("the explorer page is missing from the classpath", e);
        }
    }

    private record HeaderFilter(String policy) implements Filter {

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (response instanceof HttpServletResponse http) {
                http.setHeader("Content-Security-Policy", policy);
                http.setHeader("X-Content-Type-Options", "nosniff");
                http.setHeader("X-Frame-Options", "DENY");
                http.setHeader("Referrer-Policy", "no-referrer");
            }
            chain.doFilter(request, response);
        }
    }
}
