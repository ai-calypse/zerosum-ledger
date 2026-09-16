package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real responses validate against the response schemas in {@code openapi/ledger-service.yaml}, using the same JSON
 * Schema validator pinned in D00-1 and used by D01-8. Every schema in that file sets {@code additionalProperties:
 * false}, so an unexpected or misnamed field fails here.
 */
class LedgerOpenApiContractIT extends LedgerApiTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private static JsonNode openApi;

    @BeforeAll
    static void loadSpecAndApplyGoldens() {
        Path spec = LedgerTestDatabase.ROOT.resolve("openapi/ledger-service.yaml");
        assertTrue(Files.exists(spec), "openapi/ledger-service.yaml must exist");
        try {
            openApi = JSON.valueToTree(new Yaml().load(Files.readString(spec)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        ApplyTestDriver driver = applyDriver();
        for (int i = 1; i <= 7; i++) {
            driver.applyOne(GoldenPayloads.byId("O" + i));
        }
    }

    @Test
    void theBalancesResponseValidatesAgainstTheSpecification() {
        String body = http().get().uri("/v1/entities/{id}/balances", "platform:main").retrieve().body(String.class);
        assertEquals(List.of(), errors(responseSchemaRef("/v1/entities/{entity_id}/balances"), body));
    }

    @Test
    void theChangelogResponseValidatesAgainstTheSpecification() {
        String body = http().get().uri("/v1/entities/{id}/changelog?limit=5", "platform:main")
                .retrieve().body(String.class);
        assertEquals(List.of(), errors(responseSchemaRef("/v1/entities/{entity_id}/changelog"), body));
    }

    @Test
    void problemResponsesValidateAgainstTheSpecification() {
        String body = http().get().uri("/v1/entities/rider:NOBODY/balances")
                .exchange((request, response) -> new String(response.getBody().readAllBytes()), false);
        assertEquals(List.of(), errors("#/components/schemas/Problem", body));
    }

    /** The 200 response schema reference of an operation, as written in the specification. */
    private static String responseSchemaRef(String path) {
        JsonNode schema = openApi.get("paths").get(path).get("get").get("responses").get("200")
                .get("content").get("application/json").get("schema");
        return schema.get("$ref").asString();
    }

    /**
     * Validates against a schema document whose root is the operation's {@code $ref} and which carries the whole
     * {@code components} block, so internal references resolve without a network lookup.
     */
    private static List<String> errors(String ref, String json) {
        var document = JSON.createObjectNode();
        document.put("$ref", ref);
        document.set("components", openApi.get("components"));
        return REGISTRY.getSchema(document).validate(json, InputFormat.JSON).stream()
                .map(e -> e.getKeyword() + " at " + e.getInstanceLocation() + ": " + e.getMessage())
                .toList();
    }
}
