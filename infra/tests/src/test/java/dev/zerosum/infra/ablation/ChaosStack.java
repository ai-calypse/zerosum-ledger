package dev.zerosum.infra.ablation;

import dev.zerosum.infra.Stack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Compose stack under the chaos overlay, as the ablation experiment drives it: reset with a variant's switches,
 * crash and restart containers, read consumer lag, wait for quiesce, and run the verifier.
 *
 * <p>Every call is the {@code docker} CLI or read-only JDBC with a service's own runtime role (through {@link Stack}).
 * A non-zero exit is a harness failure, never a silent pass.
 */
final class ChaosStack {

    static final List<String> SWITCHES = List.of("A1", "A2", "A3", "A4", "A5", "F2", "F3");
    static final List<String> SERVICES = List.of("order-service", "ledger-service", "instrument-service",
            "fake-providers");
    private static final List<String> DATA = List.of("postgres", "kafka");
    private static final List<String> VOLUMES = List.of("zerosum-ledger_postgres-data", "zerosum-ledger_kafka-data");
    private static final List<String> FILES = List.of("docker-compose.yml", "docker-compose.demo.yml",
            "docker-compose.chaos.yml");

    private ChaosStack() {
    }

    static String container(String service) {
        return "zerosum-ledger-" + service + "-1";
    }

    // --- lifecycle --------------------------------------------------------------------------------------------------

    /** Builds the four service images from this checkout's jars; run once per test so images match HEAD. */
    static String build() {
        return compose(Map.of(), Duration.ofMinutes(10), concat(List.of("build"), SERVICES));
    }

    /**
     * Throws the data away and starts PostgreSQL, Kafka and the services from empty volumes, with exactly the given
     * switches on and every other switch explicitly off, so nothing in the caller's shell can leak into a run.
     */
    static void reset(List<String> on) {
        teardown();
        var env = new LinkedHashMap<String, String>();
        SWITCHES.forEach(id -> env.put("ZS_CHAOS_" + id, String.valueOf(on.contains(id))));
        var up = new ArrayList<>(List.of("up", "-d", "--wait", "otel-lgtm"));
        up.addAll(DATA);
        up.addAll(SERVICES);
        compose(env, Duration.ofMinutes(5), up);
    }

    /** Removes the data services and their volumes; otel-lgtm keeps running, it holds no run state that is read. */
    static void teardown() {
        var rm = new ArrayList<>(List.of("rm", "--stop", "--force", "--volumes"));
        rm.addAll(DATA);
        rm.addAll(SERVICES);
        compose(Map.of(), Duration.ofMinutes(3), rm);
        for (String volume : VOLUMES) {
            docker(Duration.ofMinutes(1), "volume", "rm", "--force", volume);
        }
    }

    /** Everything down, including otel-lgtm; the state the experiment leaves the machine in. */
    static void downAll() {
        compose(Map.of(), Duration.ofMinutes(3), List.of("down", "--volumes"));
    }

    // --- containers -------------------------------------------------------------------------------------------------

    static boolean running(String service) {
        return "true".equals(docker(Duration.ofSeconds(30), "inspect", "-f", "{{.State.Running}}", container(service)));
    }

    static int exitCode(String service) {
        return Integer.parseInt(docker(Duration.ofSeconds(30), "inspect", "-f", "{{.State.ExitCode}}",
                container(service)));
    }

    static void kill(String service) {
        docker(Duration.ofSeconds(30), "kill", "-s", "KILL", container(service));
    }

    static void start(String service) {
        docker(Duration.ofSeconds(60), "start", container(service));
    }

    /** Creates the arming file a crash hook consumes (ChaosGuard.consumeArm), as the image's own user. */
    static void arm(String service, String hook) {
        docker(Duration.ofSeconds(30), "exec", container(service), "touch", "/tmp/zs-chaos-" + hook.toLowerCase());
    }

    static void restartKafka() {
        docker(Duration.ofMinutes(2), "restart", container("kafka"));
    }

    /** Waits for an exited container, returning whether it exited within the patience. */
    static boolean awaitExit(String service, Duration patience) {
        Instant deadline = Instant.now().plus(patience);
        while (Instant.now().isBefore(deadline)) {
            if (!running(service)) {
                return true;
            }
            Stack.sleep(Duration.ofMillis(200));
        }
        return false;
    }

    static void awaitHealthy(String service, Duration patience) {
        Instant deadline = Instant.now().plus(patience);
        while (Instant.now().isBefore(deadline)) {
            if ("healthy".equals(docker(Duration.ofSeconds(30), "inspect", "-f", "{{.State.Health.Status}}",
                    container(service)))) {
                return;
            }
            Stack.sleep(Duration.ofSeconds(1));
        }
        throw new IllegalStateException(service + " did not return to healthy within " + patience);
    }

    /** The service's own startup line naming its active chaos switches (ChaosGuard.Active.logLine). */
    static String chaosLine(String service, String marker) {
        String logs = docker(Duration.ofSeconds(60), "logs", container(service));
        String found = null;
        for (String line : logs.split("\n")) {
            if (line.contains(marker)) {
                found = line.substring(line.indexOf("ZS-CHAOS")).strip();
            }
        }
        return found;
    }

    static String image(String service) {
        return docker(Duration.ofSeconds(30), "inspect", "-f", "{{.Image}}", container(service));
    }

    // --- quiesce ----------------------------------------------------------------------------------------------------

    /**
     * Total lag of every consumer group, or -1 when it cannot be read as a number (a group rebalancing, a partition
     * with records and no committed offset). Unknown is never reported as zero.
     */
    static long consumerLag() {
        String out = docker(Duration.ofSeconds(60), "exec", "-e", "KAFKA_HEAP_OPTS=-Xmx128m", container("kafka"),
                "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:29092", "--describe",
                "--all-groups");
        if (out.contains("rebalancing")) {
            return -1;
        }
        long total = 0;
        for (String line : out.split("\n")) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length < 6 || columns[0].equals("GROUP") || !columns[2].matches("\\d+")) {
                continue;
            }
            String current = columns[3];
            String end = columns[4];
            String lag = columns[5];
            if (lag.matches("\\d+")) {
                total += Long.parseLong(lag);
            } else if (end.matches("\\d+") && Long.parseLong(end) > 0 && !current.matches("\\d+")) {
                return -1;
            }
        }
        return total;
    }

    /** One look at every quiesce condition of master §8.3 that applies to the checks this experiment runs. */
    static Map<String, Long> quiesceProbe() {
        var probe = new LinkedHashMap<String, Long>();
        probe.put("orders_outbox_unpublished", Stack.count("orders",
                "SELECT count(*) FROM outbox WHERE published_at IS NULL"));
        probe.put("instruments_outbox_unpublished", Stack.count("instruments",
                "SELECT count(*) FROM outbox WHERE published_at IS NULL"));
        probe.put("consumer_lag", consumerLag());
        probe.put("attempts_not_terminal", Stack.count("instruments",
                "SELECT count(*) FROM payment_attempts WHERE status IN ('CREATED','SUBMITTING','UNKNOWN')"));
        probe.put("webhooks_undelivered", Stack.count("fakeproviders",
                "SELECT count(*) FROM provider_events WHERE delivered_at IS NULL"));
        probe.put("orders", Stack.count("orders", "SELECT count(*) FROM money_orders"));
        probe.put("applied_orders", Stack.count("ledger", "SELECT count(*) FROM applied_orders"));
        return probe;
    }

    static boolean quiet(Map<String, Long> probe) {
        return probe.get("orders_outbox_unpublished") == 0 && probe.get("instruments_outbox_unpublished") == 0
                && probe.get("consumer_lag") == 0 && probe.get("attempts_not_terminal") == 0
                && probe.get("webhooks_undelivered") == 0;
    }

    // --- verifier ---------------------------------------------------------------------------------------------------

    /** Runs tools/verifier (installDist) with the contract of the ablation plan; returns its exit status. */
    static int verify(String label, Path outDir, Path logFile) {
        String db = "jdbc:postgresql://127.0.0.1:5432/";
        var command = List.of(System.getProperty("zs.verifier"),
                "--jdbc-url", db + "ledger", "--orders-jdbc-url", db + "orders",
                "--instruments-jdbc-url", db + "instruments", "--providers-jdbc-url", db + "fakeproviders",
                "--checks", "I1,I2,I3,I4,I6,I6b,I7", "--env-file", Stack.ROOT.resolve(".env").toString(),
                "--out", outDir.toString(), "--label", label);
        try {
            Files.createDirectories(outDir);
            Process process = new ProcessBuilder(command).directory(Stack.ROOT.toFile()).redirectErrorStream(true)
                    .redirectOutput(logFile.toFile()).start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return 2;
            }
            return process.exitValue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // --- process plumbing -------------------------------------------------------------------------------------------

    private static String compose(Map<String, String> env, Duration timeout, List<String> args) {
        var command = new ArrayList<String>();
        command.add("compose");
        FILES.forEach(file -> {
            command.add("-f");
            command.add(Stack.ROOT.resolve(file).toString());
        });
        command.addAll(args);
        return run(env, timeout, command);
    }

    static String docker(Duration timeout, String... args) {
        return run(Map.of(), timeout, List.of(args));
    }

    private static String run(Map<String, String> env, Duration timeout, List<String> args) {
        var command = new ArrayList<String>();
        command.add(dockerBinary());
        command.addAll(args);
        try {
            var builder = new ProcessBuilder(command).directory(Stack.ROOT.toFile()).redirectErrorStream(true);
            builder.environment().putAll(env);
            Process process = builder.start();
            // Read on this thread before waiting: a chatty `compose up` would otherwise block on a full pipe.
            var output = new StringBuilder();
            var reader = Thread.ofVirtual().start(() -> {
                try {
                    output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the process was destroyed; its exit status below is the verdict
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker " + String.join(" ", args) + " timed out after " + timeout);
            }
            reader.join();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("docker " + String.join(" ", args) + " exited " + process.exitValue()
                        + ": " + output);
            }
            return output.toString().strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String dockerBinary() {
        String configured = System.getProperty("zs.docker");
        if (configured != null) {
            return configured;
        }
        for (String candidate : List.of(System.getProperty("user.home") + "/.docker/bin/docker",
                "/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/usr/bin/docker")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "docker";
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
