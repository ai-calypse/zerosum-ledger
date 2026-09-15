package dev.zerosum.contracts;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates JSON text against the contract schemas in {@code schemas/} (D01-8). Schemas are resolved by their
 * {@code $id}, mapped to the classpath, so cross-schema {@code $ref}s work without network access.
 */
public final class ContractSchemas {

    private static final String ID_PREFIX = "https://zerosum.dev/schemas";

    /** decision: D01-8 — docs/step_01_domain_contracts.md#decisions-and-outputs */
    public static final String MONEY_ORDER_V1 = ID_PREFIX + "/zerosum.money_order.v1.schema.json";

    public static final String PAYMENT_EVENT_V1 = ID_PREFIX + "/zerosum.payment_event.v1.schema.json";

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(ID_PREFIX, "classpath:schemas")));

    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();

    private ContractSchemas() {
    }

    /** One schema violation: the failing keyword, the JSON pointer-like instance location and a message. */
    public record SchemaError(String keyword, String instanceLocation, String message) {
    }

    /** Errors for {@code json} against the schema identified by {@code schemaId}; empty means valid. */
    public static List<SchemaError> validate(String schemaId, String json) {
        return schema(schemaId).validate(json, InputFormat.JSON).stream()
                .map(e -> new SchemaError(e.getKeyword(), String.valueOf(e.getInstanceLocation()), e.getMessage()))
                .toList();
    }

    private static Schema schema(String schemaId) {
        if (!MONEY_ORDER_V1.equals(schemaId) && !PAYMENT_EVENT_V1.equals(schemaId)) {
            throw new IllegalArgumentException("unknown contract schema: " + schemaId);
        }
        return SCHEMAS.computeIfAbsent(schemaId, id -> {
            Schema schema = REGISTRY.getSchema(SchemaLocation.of(id));
            schema.initializeValidators(); // surfaces unresolvable $refs now instead of during validation
            return schema;
        });
    }
}
