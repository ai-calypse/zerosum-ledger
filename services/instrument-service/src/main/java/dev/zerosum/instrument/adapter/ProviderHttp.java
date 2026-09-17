package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.SubmitResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP call every adapter makes, and the one place a transport failure is classified (D05-1).
 *
 * <p>The classification is the whole reason this is shared. "The provider refused" and "I never learned what the
 * provider did" look similar at the socket and mean opposite things to the ledger: one is final, the other must be
 * resolved by lookup before anyone touches money again.
 *
 * <p><strong>Every transport failure leaves here as {@link ProviderUnreachable}.</strong> A read timeout can surface
 * either as {@code ResourceAccessException} while sending or as an {@code IOException} while reading the response
 * body, and the second one escaped an earlier version of this class as an {@code UncheckedIOException} — so a timed
 * out charge threw instead of becoming {@code Unknown}, which is the one outcome this design exists to produce.
 * Normalizing both at the single point every call passes through is what keeps that from depending on which adapter
 * remembered to catch what.
 */
class ProviderHttp {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;

    ProviderHttp(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        // Without this, a provider that accepts the connection and then goes quiet holds the caller indefinitely.
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** One response, kept whole: the status matters as much as the body when classifying an outcome. */
    record Response(int status, String body) {

        <T> T as(Class<T> type) {
            return JSON.readValue(body, type);
        }
    }

    /** The provider could not be reached, or did not finish answering. Never means "nothing happened". */
    static class ProviderUnreachable extends RuntimeException {

        private final boolean timeout;

        ProviderUnreachable(String reason, boolean timeout, Throwable cause) {
            super(reason, cause);
            this.timeout = timeout;
        }

        /** A timeout means the request was sent, so the provider may well have applied it. */
        boolean timeout() {
            return timeout;
        }

        String reason() {
            return getMessage();
        }
    }

    Response post(String path, Object body, String idempotencyKey) {
        return guarded(() -> client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (idempotencyKey != null) {
                        headers.add("Idempotency-Key", idempotencyKey);
                    }
                })
                .body(JSON.writeValueAsString(body))
                .exchange((request, response) -> capture(response)));
    }

    Response get(String path) {
        return guarded(() -> client.get().uri(path).exchange((request, response) -> capture(response)));
    }

    private static Response guarded(java.util.function.Supplier<Response> call) {
        try {
            return call.get();
        } catch (ResourceAccessException | UncheckedIOException transportFailure) {
            throw unreachable(transportFailure);
        }
    }

    private static ProviderUnreachable unreachable(RuntimeException failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        boolean timeout = root instanceof SocketTimeoutException;
        String reason = timeout
                ? "read timeout: the provider may or may not have applied this request"
                : "transport failure: " + root.getMessage();
        return new ProviderUnreachable(reason, timeout, failure);
    }

    /**
     * Runs a submission and converts an unreachable provider into {@link SubmitResult.Unknown} rather than an
     * exception, because the request was already on the wire.
     */
    static SubmitResult submitting(Supplier call) {
        try {
            return call.get();
        } catch (ProviderUnreachable unreachable) {
            return new SubmitResult.Unknown(unreachable.reason());
        }
    }

    /** A call that produces a submit result, so {@link #submitting} can wrap it without generics noise. */
    @FunctionalInterface
    interface Supplier {
        SubmitResult get();
    }

    private static Response capture(ClientHttpResponse response) {
        try {
            return new Response(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Wrapped, not swallowed: guarded() turns this into ProviderUnreachable with the timeout flag intact.
            throw new UncheckedIOException(e);
        }
    }
}
