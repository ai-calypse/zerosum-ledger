package dev.zerosum.evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the git-ignored {@code .env} that {@code tools/dev/generate-env.sh} writes (D00-8).
 *
 * <p><strong>Why a file and not the environment.</strong> Nothing in this repository exports {@code .env} into a
 * process: Compose reads it itself, and CI starts the stack and then runs Gradle without exporting anything. A tool
 * reading {@code System.getenv} would therefore work on a developer's shell — where the variables happen to be
 * exported — and fail everywhere else. {@code MoneyPathE2ETest} reads credentials the same way, for the same reason.
 *
 * <p>The environment still wins when it is set, because an operator running against a stack that is not this
 * checkout's needs a way in that does not involve editing a secrets file.
 */
public final class DotEnv {

    private final Map<String, String> values;

    private DotEnv(Map<String, String> values) {
        this.values = values;
    }

    /** Reads {@code file} if it exists; an absent file is not an error, because the environment may carry everything. */
    public static DotEnv read(Path file) {
        var values = new LinkedHashMap<String, String>();
        if (file != null && Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int split = trimmed.indexOf('=');
                    if (split > 0) {
                        values.put(trimmed.substring(0, split), trimmed.substring(split + 1));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + file, e);
            }
        }
        return new DotEnv(values);
    }

    /**
     * The value of {@code name} from the environment, else from the file.
     *
     * @throws IllegalStateException with the variable named, because a tool that fails on a missing secret should say
     *                               which one rather than dying inside a driver
     */
    public String require(String name, Path sourceFile) {
        String fromEnvironment = System.getenv(name);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        String fromFile = values.get(name);
        if (fromFile == null || fromFile.isBlank()) {
            throw new IllegalStateException(name + " is set neither in the environment nor in " + sourceFile
                    + "; run tools/dev/generate-env.sh, or export it");
        }
        return fromFile;
    }

    /**
     * The first token of a {@code system:token,system:token} list (master §5.11).
     *
     * <p>The writer token carries the source system it authenticates as, because {@code source.system} is derived
     * from the principal and never from a request body (D01-5 rule 8).
     */
    public String requireFirstWriterToken(String name, Path sourceFile) {
        String pair = require(name, sourceFile).split(",")[0];
        int colon = pair.indexOf(':');
        if (colon < 0) {
            throw new IllegalStateException(name + " must hold system:token pairs, found: " + pair);
        }
        return pair.substring(colon + 1);
    }
}
