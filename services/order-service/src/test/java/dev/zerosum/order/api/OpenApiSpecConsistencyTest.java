package dev.zerosum.order.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.order.support.OrderTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * The specification is hand-written (D03-2), so this test is what keeps it honest: it compares the file with Spring's
 * registered handler map in both directions and with the {@link ApiException.Code} enum, so an endpoint or a problem
 * code cannot be added on one side and forgotten on the other.
 *
 * <p>Until S03-T06 it deliberately failed while the outbox stats operation was missing from the specification. That
 * assertion did its job — the endpoint could not be added without documenting it — and is now inverted.
 */
@Tag("integration")
@SpringBootTest
class OpenApiSpecConsistencyTest {

    private static final OrderTestDatabase DB = OrderTestDatabase.start();

    // Actuator contributes a second RequestMappingHandlerMapping, so this is qualified by name rather than by type.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> OrderTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(OrderTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:w");
        registry.add("zs.auth.reader-token", () -> "r");
        registry.add("zs.auth.admin-token", () -> "a");
        // No broker in this test, so the payment-event listener stays stopped rather than dialling one.
        registry.add("zs.consumer.enabled", () -> "false");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() {
        Path path = OrderTestDatabase.ROOT.resolve("openapi/order-service.yaml");
        assertTrue(Files.exists(path), "openapi/order-service.yaml must exist");
        try {
            return (Map<String, Object>) new Yaml().load(Files.readString(path));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Every operation in the specification, as {@code METHOD path}. */
    @SuppressWarnings("unchecked")
    private static Set<String> specOperations() {
        var operations = new TreeSet<String>();
        var paths = (Map<String, Object>) spec().get("paths");
        paths.forEach((path, byMethod) -> ((Map<String, Object>) byMethod).keySet()
                .forEach(method -> operations.add(method.toUpperCase() + " " + path)));
        return operations;
    }

    /** Every handler this service registers under /v1, in the same form. */
    private Set<String> handlerOperations() {
        var operations = new TreeSet<String>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                continue;
            }
            for (var pattern : patterns.getPatterns()) {
                String path = pattern.getPatternString();
                if (!path.startsWith("/v1")) {
                    continue;   // actuator and error handling are not part of this contract
                }
                for (var method : info.getMethodsCondition().getMethods()) {
                    operations.add(method.name() + " " + path);
                }
            }
        }
        return operations;
    }

    @Test
    void everyHandlerHasAnOperationAndEveryOperationHasAHandler() {
        assertEquals(handlerOperations(), specOperations(),
                "the specification and the registered handlers must match exactly, in both directions");
    }

    @Test
    void everyProblemCodeTheServiceCanReturnIsDocumented() {
        var documented = new LinkedHashSet<>(codeEnumFromSpec());
        var produced = Arrays.stream(ApiException.Code.values())
                .map(ApiException.Code::wireValue)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertEquals(produced, documented,
                "the Problem.code enum in the specification must match ApiException.Code exactly");
    }

    /** {@code components.schemas.Problem.properties.code.enum}, read one step at a time so a shape change is obvious. */
    @SuppressWarnings("unchecked")
    private static List<String> codeEnumFromSpec() {
        Map<String, Object> node = spec();
        for (String segment : List.of("components", "schemas", "Problem", "properties", "code")) {
            Object next = node.get(segment);
            assertTrue(next instanceof Map, "expected an object at " + segment + " but found: " + next);
            node = (Map<String, Object>) next;
        }
        Object values = node.get("enum");
        assertTrue(values instanceof List, "Problem.code must declare an enum of codes, found: " + values);
        return (List<String>) values;
    }

    @Test
    void theSpecificationParsesAndDeclaresWhatCallersNeed() {
        var spec = spec();
        assertEquals("3.1.0", spec.get("openapi"));
        assertTrue(spec.containsKey("info") && spec.containsKey("paths") && spec.containsKey("components"));
        assertTrue(spec.toString().contains("bearerAuth"), "the security scheme must be declared");
        assertTrue(spec.toString().contains("Idempotency-Key"), "the idempotency header must be documented");
        assertTrue(spec.toString().contains("Idempotent-Replayed"), "the replay header must be documented");
    }

    @Test
    void everyInternalRefResolves() {
        String text = readSpec();
        var unresolved = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("\\$ref:\\s*'([^']+)'").matcher(text);
        var spec = spec();
        while (matcher.find()) {
            String ref = matcher.group(1);
            assertTrue(ref.startsWith("#/"), "only internal refs are used, so the file resolves standalone: " + ref);
            Object node = spec;
            for (String segment : ref.substring(2).split("/")) {
                node = node instanceof Map<?, ?> map ? map.get(segment) : null;
                if (node == null) {
                    unresolved.add(ref);
                    break;
                }
            }
        }
        assertEquals(List.of(), unresolved, "every $ref must resolve");
    }

    @Test
    void theStatsOperationIsDocumented() {
        // Was the inverse assertion until S03-T06: it failed the moment the endpoint existed, which is what forced the
        // specification to be updated in the same change rather than drifting behind the code.
        assertTrue(specOperations().contains("GET /v1/outbox/stats"),
                "the stats endpoint exists, so it must be documented");
    }

    private static String readSpec() {
        try {
            return Files.readString(OrderTestDatabase.ROOT.resolve("openapi/order-service.yaml"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
