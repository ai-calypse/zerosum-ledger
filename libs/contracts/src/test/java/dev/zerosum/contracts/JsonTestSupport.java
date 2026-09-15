package dev.zerosum.contracts;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** JSON helpers for contract tests: parsing and the tiny patch language used by the negative fixtures. */
final class JsonTestSupport {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonTestSupport() {
    }

    static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    /** Applies a negative case ({@code remove}, {@code set}, {@code keep_entries}) to a copy of {@code base}. */
    static String applyCase(String base, JsonNode testCase) {
        ObjectNode doc = (ObjectNode) parse(base);
        if (testCase.has("remove")) {
            String pointer = testCase.get("remove").asString();
            ((ObjectNode) doc.at(parent(pointer))).remove(leaf(pointer));
        }
        if (testCase.has("set")) {
            String pointer = testCase.get("set").get("pointer").asString();
            ((ObjectNode) doc.at(parent(pointer))).set(leaf(pointer), testCase.get("set").get("value"));
        }
        if (testCase.has("keep_entries")) {
            ArrayNode entries = (ArrayNode) doc.get("entries");
            while (entries.size() > testCase.get("keep_entries").asInt()) {
                entries.remove(entries.size() - 1);
            }
        }
        return MAPPER.writeValueAsString(doc);
    }

    private static String parent(String pointer) {
        int slash = pointer.lastIndexOf('/');
        return slash == 0 ? "" : pointer.substring(0, slash);
    }

    private static String leaf(String pointer) {
        return pointer.substring(pointer.lastIndexOf('/') + 1);
    }
}
