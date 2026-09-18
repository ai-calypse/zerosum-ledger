package dev.zerosum.fakeproviders.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/**
 * S05-T03 evidence: under {@code demo-public} the admin endpoints do not exist (TB4, D00-3).
 *
 * <p>Not "exist but reject": on a public demo, an endpoint that can inject faults or dump every charge is not
 * something to protect with a token, and a 404 tells a prober nothing about what is normally here.
 */
@ActiveProfiles("demo-public")
class DemoPublicAdminIT extends FakeProvidersIT {

    @Test
    @DisplayName("the fault and truth endpoints are absent, even with the admin token")
    void adminEndpointsAreAbsent() {
        assertThat(put("/admin/faults/fakecard", Map.of("http_500_rate", 1), ADMIN_TOKEN).status()).isEqualTo(404);
        assertThat(get("/admin/truth", ADMIN_TOKEN).status()).isEqualTo(404);
        assertThat(get("/admin/summary", ADMIN_TOKEN).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the providers themselves still work: the demo needs them, it just cannot break them")
    void providersStillWork() {
        ChargeResponse charge = post("/fakecard/v1/charges",
                new ChargeRequest(UUID.randomUUID().toString(), MagicTokens.CARD_OK, 2_000L, "USD"), null)
                .as(ChargeResponse.class);

        assertThat(charge.status()).isEqualTo("SUCCEEDED");
    }
}
