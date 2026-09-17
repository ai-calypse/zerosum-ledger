package dev.zerosum.instrument.core;

import java.util.Map;

/**
 * A raw inbound webhook, before any parsing (D05-1).
 *
 * <p>The body is kept as bytes because the signature is computed over exactly what arrived. Re-serializing parsed
 * JSON would change whitespace and key order and break verification of a legitimate request.
 */
public record WebhookRequest(byte[] body, Map<String, String> headers) {

    public WebhookRequest {
        if (body == null) {
            throw new IllegalArgumentException("webhook body must not be null");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public String header(String name) {
        // Header names are case-insensitive in HTTP; a signature check that misses because of casing would reject a
        // valid request.
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
