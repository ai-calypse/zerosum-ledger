package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.ProviderId;
import dev.zerosum.instrument.core.SubmitResult;

/**
 * Turns an HTTP status into an outcome, for the cases that do not depend on the body (D05-1).
 *
 * <p>Returns {@code null} when the status carries no verdict of its own and the body must be read. That is the only
 * case where the adapter looks at what the provider actually said.
 */
final class Classification {

    private Classification() {
    }

    static SubmitResult of(ProviderHttp.Response response, ProviderId provider) {
        int status = response.status();
        if (status >= 500) {
            // The provider may have applied this before failing to answer. Anything other than Unknown here would be
            // a guess about money.
            return new SubmitResult.Unknown("provider returned " + status);
        }
        if (status == 422) {
            // We reused an idempotency key with a different body. That is our bug, and swallowing it as a decline
            // would leave it to be discovered by an accountant.
            throw new IllegalStateException(provider + " rejected the request as an idempotency key reuse: "
                    + response.body());
        }
        if (status >= 400) {
            // Definitively refused before anything happened, so it is final rather than uncertain.
            return new SubmitResult.Declined("http_" + status);
        }
        return null;
    }
}
