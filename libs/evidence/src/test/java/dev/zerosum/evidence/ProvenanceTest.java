package dev.zerosum.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The M13 (c) block: hardware, versions, git SHA and seeds, on every result.
 *
 * <p>The rendering is pinned rather than the captured values, because the values are properties of whatever machine
 * runs this. What must not drift is that all four items are present and that an empty seed list says so explicitly:
 * a results file missing the seeds is not reproducible, and one that silently omits the row looks complete.
 */
class ProvenanceTest {

    private static final Provenance SAMPLE = new Provenance(Instant.parse("2026-09-17T07:57:55Z"),
            "4e7d942b676417d8a25c9aa83926cc94bcc6beeb", "clean (0 changes)", "25.0.4.1+1-LTS",
            "Mac16,12, 10 cores, 24.0 GiB RAM, Mac OS X 26.5.2, aarch64", List.of(4273L, 4304L),
            Map.of("PostgreSQL", "18.6"));

    @Test
    @DisplayName("the markdown block carries all four M13 (c) items")
    void theMarkdownTableCarriesEveryRequiredItem() {
        String table = SAMPLE.markdownTable("../../adr/0002-stack-and-pinned-versions.md");

        assertTrue(table.contains("| Git commit SHA |"), table);
        assertTrue(table.contains("| Versions |"), table);
        assertTrue(table.contains("| Seeds |"), table);
        assertTrue(table.contains("| Hardware |"), table);

        assertTrue(table.contains("4e7d942b676417d8a25c9aa83926cc94bcc6beeb"), "the SHA itself, not a summary");
        assertTrue(table.contains("clean (0 changes)"), "whether the tree was clean is part of the SHA's meaning");
        assertTrue(table.contains(Provenance.VERSIONS_DOCUMENT), "versions are referenced, never restated");
        assertTrue(table.contains("PostgreSQL 18.6"), "runtime-reported versions describe what actually answered");
    }

    @Test
    void everySeedIsListedSoTheRunCanBeReproduced() {
        assertTrue(SAMPLE.seedsText().contains("4273") && SAMPLE.seedsText().contains("4304"), SAMPLE.seedsText());
        assertTrue(SAMPLE.toJson().contains("\"seeds\": [4273, 4304]"), SAMPLE.toJson());
    }

    @Test
    @DisplayName("no seeds says so, rather than leaving the row blank")
    void anEmptySeedListIsStatedExplicitly() {
        Provenance noSeeds = new Provenance(Instant.EPOCH, "sha", "clean (0 changes)", "25", "hw", List.of(), Map.of());

        assertTrue(noSeeds.seedsText().contains("none"), noSeeds.seedsText());
        assertTrue(noSeeds.markdownTable("x").contains("| Seeds | none"), noSeeds.markdownTable("x"));
    }

    @Test
    @DisplayName("capture never throws, and never substitutes a value it could not read")
    void captureDegradesToUnavailableRatherThanGuessing() {
        Provenance captured = Provenance.capture(Path.of("."), List.of(1L), Map.of());

        // The point is that provenance capture cannot be the reason a measured run dies, and that a value the host
        // will not give up is reported as absent. Runtime.maxMemory() is the JVM heap, not the machine's RAM, and
        // printing it under "Hardware" would be a fabricated measurement in the block that says what the run ran on.
        assertNotNull(captured.gitSha());
        assertNotNull(captured.hardware());
        assertNotNull(captured.workingTree());
        assertTrue(captured.hardware().contains("cores"), captured.hardware());
        assertTrue(captured.runtimeVersions().containsKey("JVM"), captured.runtimeVersions().toString());
        assertFalse(captured.markdownTable("x").contains("| Hardware |  |"), "an empty hardware cell is never written");
    }
}
