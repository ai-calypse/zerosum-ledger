package dev.zerosum.instrument.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every variable Compose declares as required must be something {@code .env} will actually contain (D00-8).
 *
 * <p>This exists because S05-T03 shipped the failure it prevents. {@code ZS_WEBHOOK_SECRETS} was added to
 * {@code docker-compose.yml} in the required <code>${VAR:?message}</code> form, which aborts the entire stack when
 * the variable is unset. It was correctly added to {@code .env.example} too — but anyone holding an older
 * {@code .env} got <em>"required variable ZS_WEBHOOK_SECRETS is missing a value"</em> and no stack at all, from
 * `make up`, `make demo` and the CI e2e job alike.
 *
 * <p>Nothing in CI would have caught it: the build and integration jobs never touch Compose, and the e2e job runs
 * only on a schedule. This test does, on every push, and costs nothing — it compares two files and starts no
 * container.
 *
 * <p>It deliberately checks {@code .env.example} rather than a developer's {@code .env}, because the template is
 * what {@code tools/dev/generate-env.sh} expands and is the only copy that exists on a fresh clone.
 */
class ComposeEnvTest {

    private static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));

    /** <code>${VAR:?…}</code> — the form that refuses to start rather than defaulting. */
    private static final Pattern REQUIRED = Pattern.compile("\\$\\{([A-Z0-9_]+):\\?");

    @Test
    @DisplayName("every required Compose variable is in .env.example, so a generated .env satisfies the stack")
    void requiredVariablesAreTemplated() {
        Set<String> required = requiredVariables(read(ROOT.resolve("docker-compose.yml")));
        assertThat(required).as("the compose file declares required variables at all").isNotEmpty();

        String template = read(ROOT.resolve(".env.example"));
        for (String variable : required) {
            // Anchored to a line start: a mention inside a comment is not a definition.
            assertThat(template)
                    .as("%s is declared required by docker-compose.yml, so .env.example must define it — "
                            + "otherwise a generated .env leaves the whole stack refusing to start", variable)
                    .containsPattern("(?m)^" + Pattern.quote(variable) + "=");
        }
    }

    @Test
    @DisplayName("the demo overlay introduces no required variable the template lacks")
    void overlayVariablesAreTemplatedToo() {
        String template = read(ROOT.resolve(".env.example"));

        for (String variable : requiredVariables(read(ROOT.resolve("docker-compose.demo.yml")))) {
            assertThat(template).as("%s, required by the demo overlay, must be in .env.example", variable)
                    .containsPattern("(?m)^" + Pattern.quote(variable) + "=");
        }
    }

    private static Set<String> requiredVariables(String compose) {
        var variables = new LinkedHashSet<String>();
        Matcher matcher = REQUIRED.matcher(compose);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
