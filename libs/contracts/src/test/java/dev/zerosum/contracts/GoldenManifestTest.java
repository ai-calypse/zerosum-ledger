package dev.zerosum.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class GoldenManifestTest {

    @Test
    void manifestListsExactlyTheGoldenFiles() throws IOException, URISyntaxException {
        JsonNode manifest = JsonTestSupport.parse(GoldenPayloads.manifest());
        TreeSet<String> listed = new TreeSet<>();
        manifest.get("orders").forEach(o -> listed.add(o.get("file").asString()));
        manifest.get("events").forEach(e -> listed.add(e.get("file").asString()));
        listed.add(manifest.get("expected_balances").asString());
        listed.add("golden/manifest.json");

        Path goldenDir = Path.of(GoldenPayloads.class.getClassLoader().getResource("golden").toURI());
        TreeSet<String> present = new TreeSet<>();
        try (Stream<Path> files = Files.walk(goldenDir)) {
            files.filter(Files::isRegularFile)
                    .forEach(f -> present.add("golden/" + goldenDir.relativize(f).toString().replace('\\', '/')));
        }
        assertEquals(present, listed, "listed vs present golden files");
        for (String file : listed) {
            assertTrue(GoldenPayloads.resource(file).length() > 0, file);
        }
    }

    @Test
    void manifestIdsResolveThroughTheLoaderAndPairingsPointToExistingEntries() {
        JsonNode manifest = JsonTestSupport.parse(GoldenPayloads.manifest());
        TreeSet<String> eventIds = new TreeSet<>();
        manifest.get("events").forEach(e -> {
            String id = e.get("id").asString();
            eventIds.add(id);
            assertEquals(GoldenPayloads.resource(e.get("file").asString()), GoldenPayloads.byId(id), id);
        });
        manifest.get("orders").forEach(o -> {
            String id = o.get("id").asString();
            assertEquals(GoldenPayloads.resource(o.get("file").asString()), GoldenPayloads.byId(id), id);
            if (!o.get("paired_event").isNull()) {
                assertTrue(eventIds.contains(o.get("paired_event").asString()), id);
            }
        });
    }
}
