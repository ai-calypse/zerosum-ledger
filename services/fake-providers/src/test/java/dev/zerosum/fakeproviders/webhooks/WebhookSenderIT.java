package dev.zerosum.fakeproviders.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.zerosum.fakeproviders.admin.AdminApi.Delivery;
import dev.zerosum.fakeproviders.admin.AdminApi.Truth;
import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;

/**
 * S05-T03 evidence: signed delivery, duplicate and drop simulation, and redelivery against a real receiver.
 *
 * <p>The receiver is a JDK {@code HttpServer} rather than a mock, so the bytes on the wire are the bytes that get
 * signed and verified. A mock would have agreed with the sender by construction, which is precisely the kind of
 * evidence this project has already been burned by.
 *
 * <p>The redelivery schedule is compressed to milliseconds here. The production-shaped schedule (1 s … 10 min) is in
 * {@code application.yml}; a test that waited it out would be a test nobody runs.
 */
@TestPropertySource(properties = {
        "zs.webhooks.secrets=test-current-secret,test-previous-secret",
        "zs.webhooks.poll-interval=200ms",
        "zs.webhooks.redelivery=300ms,300ms,500ms",
        "zs.webhooks.reorder-hold=600ms",
        "zs.webhooks.timeout=1s",
        "zs.fakebank.banking-day=0s",
        "zs.fakebank.lifecycle-interval=100ms"})
class WebhookSenderIT extends FakeProvidersIT {

    private static final String CURRENT_SECRET = "test-current-secret";
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final HttpServer RECEIVER = startReceiver();

    private record Received(String signature, byte[] body, Map<String, Object> event) {
    }

    private static HttpServer startReceiver() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/webhooks", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                RECEIVED.add(new Received(exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER), body,
                        JSON.readValue(body, new TypeReference<Map<String, Object>>() {
                        })));
                exchange.sendResponseHeaders(RESPONSE_STATUS.get(), -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void receiver(DynamicPropertyRegistry registry) {
        registry.add("zs.webhooks.receiver-url",
                () -> "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/webhooks");
    }

    @BeforeEach
    void healthyReceiver() {
        RESPONSE_STATUS.set(200);
    }

    @AfterEach
    void clearProfiles() {
        // The fault profiles outlive this context in the database; a drop rate left at 1 would silence every later
        // test's webhooks.
        put("/admin/faults/fakecard", Map.of(), ADMIN_TOKEN);
        put("/admin/faults/fakebank", Map.of(), ADMIN_TOKEN);
        RESPONSE_STATUS.set(200);
    }

    @Test
    @DisplayName("a delivered event is signed with the current secret, and a tampered body fails verification")
    void deliveriesAreSigned() {
        String reference = charge();

        Received delivery = awaitOne(reference);

        assertThat(WebhookSigner.verify(delivery.signature(), delivery.body(),
                List.of(CURRENT_SECRET, "test-previous-secret"), Instant.now())).isTrue();
        byte[] tampered = new String(delivery.body(), StandardCharsets.UTF_8)
                .replace("\"amount_minor\":4200", "\"amount_minor\":9900").getBytes(StandardCharsets.UTF_8);
        assertThat(tampered).isNotEqualTo(delivery.body());
        assertThat(WebhookSigner.verify(delivery.signature(), tampered, List.of(CURRENT_SECRET), Instant.now()))
                .isFalse();
        assertThat(delivery.event().get("event_type")).isEqualTo("charge.succeeded");
        assertThat(delivery.event().get("client_reference")).isEqualTo(reference);
    }

    @Test
    @DisplayName("the duplicate knob delivers one event id twice; deduplication is the receiver's job")
    void duplicateRateDeliversTwice() {
        activate("fakecard", Map.of("webhook_duplicate_rate", 1, "seed", 21));

        String reference = charge();

        await(() -> deliveries(reference).size() >= 2);
        List<Received> delivered = deliveries(reference);
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(1).event().get("event_id")).isEqualTo(delivered.getFirst().event().get("event_id"));
    }

    @Test
    @DisplayName("a dropped webhook is not lost: it stays queued and is redelivered once the knob is off")
    void dropIsRetriedUntilItLands() {
        activate("fakecard", Map.of("webhook_drop_rate", 1, "seed", 33));
        String reference = charge();

        // The attempt never left the process, so the event is still owed and the queue says so.
        await(() -> queued(reference).map(entry -> entry.attempts() >= 1).orElse(false));
        assertThat(deliveries(reference)).isEmpty();

        activate("fakecard", Map.of("seed", 33));

        await(() -> !deliveries(reference).isEmpty());
        await(() -> queued(reference).isEmpty());
    }

    @Test
    @DisplayName("a receiver answering 500 keeps the event in the redelivery queue until it recovers")
    void receiverDownDrainsAfterRecovery() {
        RESPONSE_STATUS.set(500);
        String reference = charge();

        await(() -> queued(reference).map(entry -> entry.attempts() >= 1 && entry.last_status() != null
                && entry.last_status() == 500).orElse(false));
        // Attempted, repeatedly, and still not delivered: nothing is marked delivered without a 2xx.
        assertThat(delivered(reference)).isFalse();

        RESPONSE_STATUS.set(200);

        await(() -> delivered(reference));
        // §0.3 E3: empty is the only honest definition of drained, and quiesce waits for exactly this.
        await(() -> queued(reference).isEmpty());
    }

    @Test
    @DisplayName("the reorder knob delivers a later event for the same payout before the earlier one")
    void reorderDeliversTheLaterEventFirst() {
        activate("fakebank", Map.of("webhook_reorder_rate", 1, "simulated_banking_day_seconds", 0, "seed", 44));
        String reference = "reorder-" + UUID.randomUUID();

        post("/fakebank/v1/payouts", new PayoutRequest(reference, MagicTokens.BANK_RETURN_R01, 7_000L, "USD"), null);

        await(() -> deliveries(reference).size() >= 2);
        List<String> order = deliveries(reference).stream()
                .map(received -> String.valueOf(received.event().get("event_type")))
                .toList();

        // The payout settled before it was returned; the receiver hears about it the other way round, which is what
        // the ahead-of-state rule in the receiver has to survive.
        assertThat(order).containsExactly("payout.returned", "payout.settled");
    }

    private String charge() {
        String reference = "hook-" + UUID.randomUUID();
        post("/fakecard/v1/charges", new ChargeRequest(reference, MagicTokens.CARD_OK, 4_200L, "USD"), null);
        return reference;
    }

    private void activate(String provider, Map<String, Object> knobs) {
        assertThat(put("/admin/faults/" + provider, knobs, ADMIN_TOKEN).status()).isEqualTo(200);
    }

    private List<Received> deliveries(String clientReference) {
        return RECEIVED.stream()
                .filter(received -> clientReference.equals(received.event().get("client_reference")))
                .toList();
    }

    private Received awaitOne(String clientReference) {
        await(() -> !deliveries(clientReference).isEmpty());
        return deliveries(clientReference).getFirst();
    }

    /** The redelivery-queue entry for this reference, read through the admin API (§0.3 E3). */
    private java.util.Optional<Delivery> queued(String clientReference) {
        Truth truth = get("/admin/truth?client_reference=" + clientReference, ADMIN_TOKEN).as(Truth.class);
        List<String> refs = java.util.stream.Stream.concat(
                        truth.charges().stream().map(charge -> charge.charge_id()),
                        truth.payouts().stream().map(payout -> payout.payout_id()))
                .toList();
        return truth.webhook_queue().deliveries().stream()
                .filter(delivery -> refs.contains(delivery.provider_ref()))
                .findFirst();
    }

    private boolean delivered(String clientReference) {
        return db.sql("SELECT count(*) FROM provider_events WHERE client_reference = ? AND delivered_at IS NOT NULL")
                .param(clientReference).query(Long.class).single() > 0;
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("condition never held within " + PATIENCE);
    }
}
