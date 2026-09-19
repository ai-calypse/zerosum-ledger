package dev.zerosum.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The startup guard for chaos-only switches: the ablations A1–A5 and the F2/F3 crash hooks (master §8.5, TB4).
 *
 * <p>decision: D08-3 — docs/step_08_fault_injection_ablation.md#s08-t03. One class, reused by the three services that
 * own a switch, placed in {@code libs/auth} as the step document proposes rather than in a new module.
 *
 * <p><strong>The rule.</strong> A switch is read from {@code zs.chaos.<id>} (environment {@code ZS_CHAOS_<ID>}),
 * default off. A service with any switch on refuses to start unless the {@code chaos} profile is active, the
 * {@code demo-public} profile is not, and {@code ZS_ALLOW_CHAOS=true}. Every start logs one line naming the switches
 * that are on, so an orchestrator can assert the set per service and an empty set is visible, not assumed.
 *
 * <p>Plain Java on purpose: this module carries no Spring dependency, so a service passes its
 * {@code Environment::getProperty} and active profiles in.
 */
public final class ChaosGuard {

    public static final String PROFILE = "chaos";
    public static final String DEMO_PUBLIC = "demo-public";
    public static final String OPT_IN = "ZS_ALLOW_CHAOS";

    /** Exit status of a crash hook, distinct from 137 (SIGKILL) and 143 (SIGTERM) so the evidence can tell them apart. */
    public static final int HOOK_EXIT = 86;

    private ChaosGuard() {
    }

    /** The switches the guard accepted for one service. Read once at startup; seams hold the boolean they need. */
    public record Active(String service, List<String> switches) {

        public Active {
            switches = List.copyOf(switches);
        }

        public boolean on(String id) {
            return switches.contains(id);
        }

        /** The one startup line; the experiment harness greps containers for it. */
        public String logLine() {
            return "ZS-CHAOS service=" + service + " active=" + switches;
        }
    }

    /**
     * @param ids      the switches this service owns, e.g. {@code A1}, {@code F2}
     * @param property property lookup, normally {@code Environment::getProperty}
     * @param profiles the active Spring profiles
     * @throws IllegalStateException when a switch is on outside the guard conditions; the service must not start
     */
    public static Active check(String service, List<String> ids, Function<String, String> property,
            String... profiles) {
        List<String> on = ids.stream()
                .filter(id -> Boolean.parseBoolean(property.apply("zs.chaos." + id.toLowerCase(Locale.ROOT))))
                .sorted()
                .toList();
        if (!on.isEmpty()) {
            List<String> active = Arrays.asList(profiles);
            if (active.contains(DEMO_PUBLIC)) {
                throw refuse(service, on, "the " + DEMO_PUBLIC + " profile refuses every chaos switch");
            }
            if (!active.contains(PROFILE)) {
                throw refuse(service, on, "the '" + PROFILE + "' profile is not active (active: " + active + ")");
            }
            if (!"true".equals(property.apply(OPT_IN))) {
                throw refuse(service, on, OPT_IN + " is not 'true'");
            }
        }
        return new Active(service, on);
    }

    /**
     * A crash hook fires once per arming: the harness creates {@code /tmp/zs-chaos-<id>} inside the container, and the
     * hook deletes it before halting, so the restarted container does not halt again on its first batch.
     */
    public static boolean consumeArm(String id) {
        try {
            return Files.deleteIfExists(Path.of("/tmp/zs-chaos-" + id.toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A real process halt: no shutdown hooks, no graceful Kafka close, exactly what a crash leaves behind. */
    public static void halt() {
        Runtime.getRuntime().halt(HOOK_EXIT);
    }

    private static IllegalStateException refuse(String service, List<String> on, String why) {
        return new IllegalStateException("ZS-CHAOS " + service + " refuses to start with " + on + ": " + why);
    }
}
