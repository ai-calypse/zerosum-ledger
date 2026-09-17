package dev.zerosum.instrument.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Every service that authenticates callers must actually receive its tokens in the deployed stack (D03-4, §0.3 C9).
 *
 * <p>This exists because the repository has already shipped the defect twice: {@code ZS_WRITER_TOKENS},
 * {@code ZS_READER_TOKEN} and {@code ZS_ADMIN_TOKEN} were in {@code .env.example} but not in the service's Compose
 * block, so the container authenticated nobody while reporting healthy and every test passed — tests set the
 * properties themselves and never look at {@code docker-compose.yml}. instrument-service gained its first
 * authenticated endpoints in S05-T08 and started out with the same hole.
 *
 * <p>Both ends are checked: that {@code application.yml} reads the {@code ZS_*} variable, and that Compose passes it.
 * A typo at either end is the same outage.
 */
class AuthWiringTest {

    private static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));

    private static final List<String> TOKEN_VARIABLES =
            List.of("ZS_WRITER_TOKENS", "ZS_READER_TOKEN", "ZS_ADMIN_TOKEN");

    @Test
    @DisplayName("a service that declares zs.auth is passed its tokens by docker-compose.yml")
    void authenticatedServicesReceiveTheirTokens() {
        Map<String, Object> composeServices = composeServices();
        List<String> authenticated = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> service : serviceConfigurations().entrySet()) {
            Map<String, Object> auth = nested(service.getValue(), "zs", "auth");
            if (auth == null) {
                continue;
            }
            String name = service.getKey();
            authenticated.add(name);

            // The placeholders in application.yml must name the variables Compose passes; a mismatch at either end
            // leaves the service authenticating nobody.
            assertThat(auth.values().toString()).as("%s reads the ZS_* token variables", name)
                    .contains(TOKEN_VARIABLES.toArray(String[]::new));

            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) composeServices.get(name);
            assertThat(block).as("%s must have a docker-compose.yml service block", name).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> environment = (Map<String, Object>) block.get("environment");
            assertThat(environment).as("%s environment", name).isNotNull();
            assertThat(environment.keySet()).as("%s is passed every role's token", name)
                    .containsAll(TOKEN_VARIABLES);
        }

        // A literal list, so a service that silently loses its auth block fails here rather than shipping open.
        // fake-providers joined in S05-T03: its admin endpoints are role-checked even though it holds no real money.
        assertThat(authenticated).as("the services that enforce roles today")
                .containsExactlyInAnyOrder("order-service", "ledger-service", "instrument-service", "fake-providers");
    }

    private static Map<String, Object> composeServices() {
        Map<String, Object> compose = load(ROOT.resolve("docker-compose.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return services;
    }

    /** Each service's {@code application.yml}, keyed by the directory name, which is also its Compose service name. */
    private static Map<String, Map<String, Object>> serviceConfigurations() {
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<>();
        try (Stream<Path> services = Files.list(ROOT.resolve("services"))) {
            services.filter(Files::isDirectory).sorted().forEach(service -> {
                Path configuration = service.resolve("src/main/resources/application.yml");
                if (Files.exists(configuration)) {
                    configurations.put(service.getFileName().toString(), load(configuration));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return configurations;
    }

    private static Map<String, Object> load(Path file) {
        try {
            // Parsed, not grepped: the block that binds to nothing because it sits at the wrong indent is this
            // repository's recurring silent failure, and it looks perfectly fine to a regular expression.
            return new Yaml().load(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> root, String... path) {
        Map<String, Object> at = root;
        for (String key : path) {
            Object value = at == null ? null : at.get(key);
            at = value instanceof Map ? (Map<String, Object>) value : null;
        }
        return at;
    }
}
