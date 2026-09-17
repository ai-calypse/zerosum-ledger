package dev.zerosum.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The provenance block master §3.1 M13 (c) requires on every result: hardware, versions, git SHA and seeds.
 *
 * <p>decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness. Captured by the tool that writes the result,
 * never typed by hand into the result afterwards, because a hand-copied SHA is exactly the field that goes stale
 * first and nothing would catch it.
 *
 * <p><strong>Everything here is reported or it is absent.</strong> A value the host will not give up is recorded as
 * {@code unavailable} with the reason. It is never substituted: {@code Runtime.maxMemory()} is the JVM heap, not the
 * machine's RAM, and printing it under "Hardware" would be a fabricated measurement in the one block whose entire
 * purpose is to say what the measurement ran on.
 */
public record Provenance(Instant capturedAt, String gitSha, String workingTree, String javaRuntime,
        String hardware, List<Long> seeds, Map<String, String> runtimeVersions) {

    private static final String UNAVAILABLE = "unavailable";

    /** Where the pinned versions live; results reference it at the captured SHA rather than restating versions. */
    public static final String VERSIONS_DOCUMENT = "docs/adr/0002-stack-and-pinned-versions.md";

    public Provenance {
        seeds = List.copyOf(seeds);
        runtimeVersions = Map.copyOf(runtimeVersions);
    }

    /**
     * @param repoDir         any directory inside the repository; git is asked from there
     * @param seeds           every seed the run used, in run order; empty for non-generative evidence
     * @param runtimeVersions versions the tool learned at runtime (a broker build, a server's {@code version()}),
     *                        which are stronger evidence than a pin file because they describe what actually answered
     */
    public static Provenance capture(Path repoDir, List<Long> seeds, Map<String, String> runtimeVersions) {
        var versions = new LinkedHashMap<String, String>();
        versions.put("JVM", System.getProperty("java.vm.name", "?") + " " + System.getProperty("java.version", "?")
                + " (" + System.getProperty("java.vendor", "?") + ")");
        versions.putAll(runtimeVersions);

        return new Provenance(Instant.now(), gitSha(repoDir), workingTree(repoDir),
                System.getProperty("java.runtime.version", "?"), describeHardware(), seeds, versions);
    }

    /**
     * The M13 (c) table, as section 3 of {@code docs/results/TEMPLATE.md} lays it out.
     *
     * @param versionsDocumentLink link to {@link #VERSIONS_DOCUMENT}, relative to the directory the result is
     *                             written into (the caller knows that; this class does not)
     */
    public String markdownTable(String versionsDocumentLink) {
        return """
                | Item | Value |
                |---|---|
                | Git commit SHA | `%s`; working tree %s |
                | Versions | [%s](%s) at the SHA above. Runtime-reported: %s |
                | Seeds | %s |
                | Hardware | %s |
                """.formatted(gitSha, workingTree, VERSIONS_DOCUMENT, versionsDocumentLink,
                runtimeVersions.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining(", ")),
                seedsText(), hardware);
    }

    /** Every seed, never a summary: a seed list that elides entries is not a reproduction recipe. */
    public String seedsText() {
        return seeds.isEmpty() ? "none (non-generative evidence)"
                : seeds.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    public String toJson() {
        String versions = runtimeVersions.entrySet().stream()
                .map(e -> Json.quote(e.getKey()) + ": " + Json.quote(e.getValue()))
                .collect(Collectors.joining(", "));
        String seedList = seeds.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return "{"
                + "\"captured_at\": " + Json.quote(capturedAt.toString())
                + ", \"git_sha\": " + Json.quote(gitSha)
                + ", \"working_tree\": " + Json.quote(workingTree)
                + ", \"versions_document\": " + Json.quote(VERSIONS_DOCUMENT)
                + ", \"java_runtime\": " + Json.quote(javaRuntime)
                + ", \"hardware\": " + Json.quote(hardware)
                + ", \"seeds\": [" + seedList + "]"
                + ", \"runtime_versions\": {" + versions + "}"
                + "}";
    }

    private static String gitSha(Path repoDir) {
        return command(repoDir, "git", "-C", repoDir.toString(), "rev-parse", "HEAD")
                .orElse(UNAVAILABLE + " — git did not answer");
    }

    /**
     * Whether the tree was clean, and if not, how many files differed. A result produced from a dirty tree is still
     * evidence; it is just evidence of something that is not at that SHA, and the reader has to be told which.
     */
    private static String workingTree(Path repoDir) {
        return command(repoDir, "git", "-C", repoDir.toString(), "status", "--porcelain")
                .map(output -> {
                    long changed = output.isBlank() ? 0 : output.lines().count();
                    return changed == 0 ? "clean (0 changes)"
                            : "DIRTY — " + changed + " uncommitted file(s); this result is not reproducible from the SHA alone";
                })
                .orElse("unknown — git did not answer");
    }

    // Named describeHardware rather than hardware: a no-arg method matching a record component is its accessor, and
    // an accessor may not be private or static.
    private static String describeHardware() {
        String os = System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?")
                + ", " + System.getProperty("os.arch", "?");
        return hostModel() + ", " + Runtime.getRuntime().availableProcessors() + " cores, " + totalMemory() + ", " + os;
    }

    private static String hostModel() {
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            return command(null, "sysctl", "-n", "hw.model").orElse(UNAVAILABLE + " host model");
        }
        return readFirstLine(Path.of("/sys/devices/virtual/dmi/id/product_name"))
                .orElse(UNAVAILABLE + " host model");
    }

    /** Physical RAM of the host, which the JVM cannot see; asked of the operating system or reported as absent. */
    private static String totalMemory() {
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            return command(null, "sysctl", "-n", "hw.memsize")
                    .map(bytes -> gibibytes(Long.parseLong(bytes)))
                    .orElse(UNAVAILABLE + " RAM");
        }
        return readFirstLine(Path.of("/proc/meminfo"))
                // "MemTotal:       32793412 kB"
                .filter(line -> line.startsWith("MemTotal:"))
                .map(line -> gibibytes(Long.parseLong(line.replaceAll("\\D+", "")) * 1024))
                .orElse(UNAVAILABLE + " RAM");
    }

    private static String gibibytes(long bytes) {
        return String.format("%.1f GiB RAM (%d bytes)", bytes / (1024.0 * 1024 * 1024), bytes);
    }

    private static java.util.Optional<String> readFirstLine(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file).stream().findFirst() : java.util.Optional.empty();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Runs a short command and returns its trimmed output, or empty on any failure.
     *
     * <p>Failure is swallowed on purpose: provenance capture must never be the reason a measured run dies. The caller
     * records {@code unavailable} instead, which is honest and costs nothing.
     */
    private static java.util.Optional<String> command(Path workingDirectory, String... command) {
        Process process = null;
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(false);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            process = builder.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(output.strip());
        } catch (IOException e) {
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
