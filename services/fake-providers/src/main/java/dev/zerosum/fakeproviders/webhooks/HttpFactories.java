package dev.zerosum.fakeproviders.webhooks;

import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** A request factory with both timeouts stated; library defaults are never relied on (master §5.11). */
final class HttpFactories {

    private HttpFactories() {
    }

    static ClientHttpRequestFactory withTimeouts(Duration connect, Duration read) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }
}
