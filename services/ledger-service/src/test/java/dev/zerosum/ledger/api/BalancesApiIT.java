package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.ApplyTestDriver;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M6: balances are returned on each account's normal side with an as-of sequence number (D02-7). */
class BalancesApiIT extends LedgerApiTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void applyTheWorkedExample() {
        ApplyTestDriver driver = applyDriver();
        for (int i = 1; i <= 7; i++) {
            driver.applyOne(GoldenPayloads.byId("O" + i));
        }
    }

    @Test
    void everyAccountShowsTheExpectedBalanceOnItsNormalSide() {
        JsonNode expected = scenario("after_O1_O7");
        TreeMap<String, String> want = new TreeMap<>();
        for (JsonNode balance : expected.get("balances")) {
            want.put(balance.get("entity_id").asString() + "/" + balance.get("account").asString() + "/"
                            + balance.get("currency").asString(),
                    balance.get("presented_minor").asLong() + "/" + balance.get("signed_minor").asLong());
        }

        TreeMap<String, String> got = new TreeMap<>();
        for (String entityId : want.keySet().stream().map(k -> k.substring(0, k.indexOf('/'))).distinct().toList()) {
            JsonNode body = JSON.readTree(get(entityId, String.class));
            assertEquals(entityId, body.get("entity_id").asString());
            for (JsonNode account : body.get("accounts")) {
                got.put(entityId + "/" + account.get("account").asString() + "/" + account.get("currency").asString(),
                        account.get("presented_minor").asLong() + "/" + account.get("signed_minor").asLong());
            }
        }
        assertEquals(want, got, "presented and signed balances must match the D01-9 goldens");
    }

    @Test
    void asOfSequenceEqualsTheEntitysLastSequence() {
        JsonNode body = JSON.readTree(get("platform:main", String.class));
        long rows = 0;
        for (JsonNode ignored : JSON.readTree(http().get()
                .uri("/v1/entities/{id}/changelog?limit=500", "platform:main")
                .retrieve().body(String.class)).get("rows")) {
            rows++;
        }
        assertEquals(rows, body.get("as_of_seq").asLong(), "as_of_seq is the entity's last changelog sequence");
        assertTrue(rows > 0);
    }

    @Test
    void normalSideIsReportedPerAccount() {
        JsonNode body = JSON.readTree(get("driver:D1", String.class));
        for (JsonNode account : body.get("accounts")) {
            if (account.get("account").asString().equals("payable")) {
                assertEquals("CREDIT", account.get("normal_side").asString());
            }
        }
    }

    @Test
    void anEncodedColonAddressesTheSameEntity() {
        // A java.net.URI is passed through as-is. The String overload would treat this as a URI template and encode the
        // percent sign itself, sending %253A, which is a different (and invalid) entity ID.
        String encoded = http().get()
                .uri(java.net.URI.create(baseUrl() + "/v1/entities/rider%3AR1/balances"))
                .retrieve().body(String.class);
        assertEquals(JSON.readTree(get("rider:R1", String.class)), JSON.readTree(encoded),
                "the literal and percent-encoded colon must address one entity");
    }

    @Test
    void anUnknownEntityIsNotFoundAndAMalformedOneIsRejected() {
        assertEquals(404, status("/v1/entities/rider:NOBODY/balances"));
        assertEquals("entity_not_found", problemCode("/v1/entities/rider:NOBODY/balances"));
        assertEquals(400, status("/v1/entities/not-an-entity/balances"));
        assertEquals("invalid_entity_id", problemCode("/v1/entities/not-an-entity/balances"));
    }

    private <T> T get(String entityId, Class<T> type) {
        return http().get().uri("/v1/entities/{id}/balances", entityId).retrieve().body(type);
    }

    private int status(String uri) {
        return http().get().uri(uri).exchange((request, response) -> response.getStatusCode().value(), false);
    }

    private String problemCode(String uri) {
        return http().get().uri(uri).exchange((request, response) -> {
            HttpStatusCode ignored = response.getStatusCode();
            return JSON.readTree(new String(response.getBody().readAllBytes())).get("code").asString();
        }, false);
    }

    private static JsonNode scenario(String id) {
        for (JsonNode candidate : JSON.readTree(GoldenPayloads.expectedBalances()).get("scenarios")) {
            if (candidate.get("id").asString().equals(id)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no scenario " + id);
    }
}
