package dev.zerosum.fakeproviders.faults;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the request-handling fault knobs to the two provider APIs (D05-2, master §5.9).
 *
 * <p>A filter rather than logic inside each handler: the faults are properties of the simulated provider, not of the
 * charge or the payout, and a handler that had to remember to inject them would eventually forget in one branch.
 *
 * <p>Order matters and is fixed. Latency, then the two pre-commit faults, run before the handler, so a 500 or a reset
 * genuinely commits nothing. Timeout-after-commit runs after the handler has committed, which is the case the whole
 * uncertain-outcome design exists for: the provider did the work and the client cannot know it.
 *
 * <p>Every decision is drawn on every request, whatever the rates are, so each decision's stream advances once per
 * request and a profile that changes one knob does not shift the others.
 */
public class FaultInjectionFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(FaultInjectionFilter.class);

    /** Bigger than any error page the container could fit into the promise, so an aborted client always runs short. */
    private static final long PROMISED_BODY_BYTES = 1_000_000;

    private final FaultProfiles profiles;
    private final Duration withhold;

    public FaultInjectionFilter(FaultProfiles profiles, Duration withhold) {
        this.profiles = profiles;
        this.withhold = withhold;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        String path = http.getRequestURI();
        String provider = path.startsWith("/fakebank") ? "fakebank" : "fakecard";

        long latency = profiles.latencyMillis(provider);
        boolean fail = profiles.fires(provider, Decision.HTTP_500, path);
        boolean reset = profiles.fires(provider, Decision.RESET_BEFORE_COMMIT, path);
        boolean timeout = profiles.fires(provider, Decision.TIMEOUT_AFTER_COMMIT, path);

        if (latency > 0) {
            // Recorded, not drawn: latency is a sample rather than a yes/no rate, and fires() compares against a
            // rate that does not exist for it, so routing it there would leave every injected delay out of the log.
            profiles.record(provider, Decision.LATENCY, path);
            sleep(latency);
        }
        if (fail) {
            out.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "injected fault: http_500");
            return;
        }
        if (reset) {
            // Nothing ran, so nothing committed; the caller sees a transport failure and must treat the outcome as
            // unknown rather than as a decline.
            abort(out, "reset_before_commit");
        }

        chain.doFilter(request, response);

        if (timeout) {
            // The work is committed. Withholding the response past the client's read timeout is what makes the
            // caller's view and the provider's truth disagree, which is the state the resolver has to repair.
            sleep(withhold.toMillis());
            abort(out, "timeout_after_commit");
        }
    }

    /**
     * Ends the exchange without a usable response.
     *
     * <p>Simulation ceiling: a servlet filter cannot reach the socket to send a real RST, so the connection is broken
     * by promising a body, flushing the headers and then failing. The client sees a premature end of stream, which is
     * the same transport-level failure it would classify a reset as; a packet-level reset would need a proxy (the
     * {@code chaos} profile's toxiproxy, S08).
     *
     * <p>The throw is the part that matters. Flushing a short response and returning normally left the container
     * holding the connection open until a socket timeout — measured at 60 s in {@code FaultKnobDeterminismIT} — and
     * a reset the client does not notice for a minute is not simulating a reset, it is simulating a hang.
     */
    private static void abort(HttpServletResponse response, String fault) throws IOException {
        try {
            if (!response.isCommitted()) {
                response.resetBuffer();
                response.setStatus(HttpServletResponse.SC_OK);
                // A body far larger than anything the container could put here is promised and never sent, so the
                // client always runs out of stream. Promising one byte was not enough: the container truncated its
                // own error page to exactly that byte and the client read it as a complete, valid response.
                response.setContentLengthLong(PROMISED_BODY_BYTES);
                response.flushBuffer();
            }
        } catch (IOException | IllegalStateException alreadyGone) {
            // The client has already given up. That is the outcome this fault was arranging anyway.
            log.debug("could not prepare the aborted response", alreadyGone);
        }
        throw new IOException("injected fault: " + fault);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
