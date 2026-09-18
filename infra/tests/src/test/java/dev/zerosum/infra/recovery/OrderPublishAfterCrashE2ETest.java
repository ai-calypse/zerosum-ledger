package dev.zerosum.infra.recovery;

import static dev.zerosum.infra.Stack.KAFKA;
import static dev.zerosum.infra.Stack.ORDER_SERVICE;
import static dev.zerosum.infra.Stack.docker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.zerosum.evidence.Provenance;
import dev.zerosum.infra.Stack;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M4 (a), for real: {@code kill -9} of the order-service CONTAINER between an order's commit and its publish, then a
 * restart, then the order is published and applied exactly once.
 *
 * <p><strong>Why the broker is paused.</strong> The relay polls every 50 ms, so the commit-to-publish window of a
 * healthy stack is tens of milliseconds and a kill aimed at it would land there only by luck. Pausing Kafka holds the
 * window open: orders commit (their outbox rows are written in the same transaction) but cannot be published. The
 * test then proves the precondition from the database — every row is committed and unpublished at the moment of the
 * kill — rather than assuming it. Kafka is unpaused BEFORE order-service restarts, so the measured recovery is the
 * restarted relay's, not the broker's.
 *
 * <p><strong>What "within 5 s of restart" is measured from.</strong> Both readings are recorded, because they differ
 * by the JVM's boot time and a result that quoted only the favourable one would be choosing its own threshold:
 * <ul>
 *   <li>from the container's {@code StartedAt} (docker's clock), and</li>
 *   <li>from Spring's "Started OrderServiceApplication" log line (same clock, via {@code docker logs --timestamps}).</li>
 * </ul>
 * {@code published_at} is PostgreSQL's clock; PostgreSQL and docker share the Docker VM's clock, so the three are
 * comparable. The assertion is on the second reading; the first is reported alongside it.
 *
 * <p><strong>Duplicates.</strong> Produce requests the killed producer had already written into the paused broker's
 * socket can still be appended once the broker resumes, and the restarted relay then sends those orders again. The
 * test counts the copies on the topic, so "no duplicate application" is shown against duplicates that actually
 * arrived rather than asserted over a topic that never held any.
 */
@Tag("e2e")
class OrderPublishAfterCrashE2ETest {

    private static final String TOPIC = "payments.money-orders.v1";
    private static final long FARE = 1_000;
    private static final long FEE = 200;
    private static final Duration BOUND = Duration.ofSeconds(5);
    private static final Pattern STARTED = Pattern.compile(
            "^(\\S+) .*Started OrderServiceApplication in ([0-9.]+) seconds", Pattern.MULTILINE);

    record Repetition(int index, String run, int orders, int unpublishedAtKill, int appliedBeforeRestart,
            Instant killedAt, Instant containerStartedAt, Instant appStartedAt, double springStartupSeconds,
            Instant lastPublishedAt, long publishAfterContainerStartMs, long publishAfterAppStartedMs,
            long appliedRows, long kafkaCopies, long riderBalance, long driverBalance, boolean invariantsConsistent) {
    }

    /** Whatever happened above, the shared stack is left with its broker running and order-service up. */
    @AfterEach
    void leaveTheStackRunning() {
        if ("true".equals(docker("inspect", "-f", "{{.State.Paused}}", KAFKA))) {
            docker("unpause", KAFKA);
        }
        if (!"true".equals(docker("inspect", "-f", "{{.State.Running}}", ORDER_SERVICE))) {
            docker("start", ORDER_SERVICE);
        }
    }

    @Test
    @DisplayName("M4(a): kill -9 between commit and publish; after restart every order is published and applied once")
    void killNineBetweenCommitAndPublish() {
        int repetitions = Integer.getInteger("zs.crash.repetitions", 3);
        int ordersPerRun = Integer.getInteger("zs.crash.orders", 20);
        var results = new ArrayList<Repetition>();
        for (int r = 1; r <= repetitions; r++) {
            results.add(repetition(r, ordersPerRun));
        }

        String report = report(results);
        System.out.println(report);
        Stack.writeEvidence("m4a-crash-recovery.json", json(results));
        Stack.writeEvidence("m4a-crash-recovery.txt", report);

        for (Repetition r : results) {
            assertEquals(r.orders(), r.unpublishedAtKill(), "precondition: every order committed and unpublished");
            assertEquals(r.orders(), r.appliedRows(), "every order applied exactly once (applied_orders rows)");
            assertEquals(r.orders() * FARE, r.riderBalance(), "rider receivable = N x fare: nothing lost, nothing doubled");
            assertEquals(-r.orders() * (FARE - FEE), r.driverBalance(), "driver payable = N x (fare - fee)");
            assertTrue(r.invariantsConsistent(), "ledger invariants hold");
            assertTrue(r.publishAfterAppStartedMs() <= BOUND.toMillis(),
                    "published within 5 s of the application having restarted: " + r);
        }
    }

    private Repetition repetition(int index, int orders) {
        String run = "crash" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String rider = "rider:" + run;
        String driver = "driver:" + run;
        // One group, so every copy lands on one partition and the duplicate count reads one partition.
        String group = "trip_" + run;

        requireRunning(ORDER_SERVICE);
        var orderIds = new ArrayList<String>();
        Instant killedAt;
        docker("pause", KAFKA);
        try {
            for (int i = 0; i < orders; i++) {
                HttpResponse<String> created = Stack.post(Stack.ORDERS + "/v1/money-orders", Stack.writerToken(),
                        run + "-" + i, Stack.tripOrder(group, rider, driver, FARE, FEE));
                assertEquals(201, created.statusCode(), created.body());
                orderIds.add(Stack.json(created.body()).get("order_id").asString());
            }
            // Precondition, from the database rather than assumed: committed, with an outbox row, not published.
            assertEquals(orders, unpublished(orderIds), "all rows committed and unpublished before the kill");
            docker("kill", "-s", "KILL", ORDER_SERVICE);
            killedAt = Instant.now();
        } finally {
            docker("unpause", KAFKA);
        }
        assertEquals("137", docker("inspect", "-f", "{{.State.ExitCode}}", ORDER_SERVICE), "SIGKILL, not a stop");
        int unpublishedAtKill = unpublished(orderIds);

        // Let the broker drain whatever the dead producer had already written into its socket, then record how many
        // orders reached the ledger WITHOUT the relay ever marking them: those are the duplicates-to-be.
        awaitBroker();
        Stack.sleep(Duration.ofSeconds(3));
        int appliedBeforeRestart = (int) applied(orderIds);

        docker("start", ORDER_SERVICE);
        Instant containerStartedAt = Instant.parse(docker("inspect", "-f", "{{.State.StartedAt}}", ORDER_SERVICE));

        Instant lastPublished = awaitPublished(orderIds, Duration.ofSeconds(90));
        Matcher started = awaitStartedLine(containerStartedAt);
        Instant appStartedAt = Instant.parse(started.group(1));

        awaitApplied(orderIds, Duration.ofSeconds(60));
        awaitHealthy(Duration.ofSeconds(90));
        long appliedRows = applied(orderIds);

        return new Repetition(index, run, orders, unpublishedAtKill, appliedBeforeRestart, killedAt,
                containerStartedAt, appStartedAt, Double.parseDouble(started.group(2)), lastPublished,
                Duration.between(containerStartedAt, lastPublished).toMillis(),
                Duration.between(appStartedAt, lastPublished).toMillis(), appliedRows, kafkaCopies(group, orderIds),
                awaitBalance(rider, "receivable", orders * FARE), awaitBalance(driver, "payable", -orders * (FARE - FEE)),
                Stack.ledgerInvariants().get("consistent").asBoolean());
    }

    // --- the evidence ---------------------------------------------------------------------------------------------

    private static int unpublished(List<String> orderIds) {
        return (int) Stack.count("orders", "SELECT count(*) FROM outbox WHERE headers->>'order_id' = ANY (?) "
                + "AND published_at IS NULL", sqlArray("orders", orderIds));
    }

    private static long applied(List<String> orderIds) {
        return Stack.count("ledger", "SELECT count(*) FROM applied_orders WHERE order_id::text = ANY (?)",
                sqlArray("ledger", orderIds));
    }

    private static Instant awaitPublished(List<String> orderIds, Duration patience) {
        Instant deadline = Instant.now().plus(patience);
        while (Instant.now().isBefore(deadline)) {
            var row = Stack.query("orders", "SELECT count(*) FILTER (WHERE published_at IS NULL) AS pending, "
                    + "count(*) AS total, max(published_at) AS last FROM outbox WHERE headers->>'order_id' = ANY (?)",
                    sqlArray("orders", orderIds)).getFirst();
            if (((Number) row.get("total")).intValue() != orderIds.size()) {
                // The cleanup job only deletes published rows past a one-hour retention; a vanished row is a defect.
                fail("outbox rows vanished: " + row);
            }
            if (((Number) row.get("pending")).intValue() == 0) {
                return ((Timestamp) row.get("last")).toInstant();
            }
            Stack.sleep(Duration.ofMillis(100));
        }
        return fail("the restarted relay did not publish all " + orderIds.size() + " rows within " + patience);
    }

    private static void awaitApplied(List<String> orderIds, Duration patience) {
        Instant deadline = Instant.now().plus(patience);
        while (Instant.now().isBefore(deadline)) {
            if (applied(orderIds) == orderIds.size()) {
                return;
            }
            Stack.sleep(Duration.ofMillis(250));
        }
        fail("the ledger applied " + applied(orderIds) + " of " + orderIds.size() + " within " + patience);
    }

    private static long awaitBalance(String entity, String account, long expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        Long seen = null;
        while (Instant.now().isBefore(deadline)) {
            seen = Stack.balance(entity, account);
            if (seen != null && seen == expected) {
                return seen;
            }
            Stack.sleep(Duration.ofMillis(250));
        }
        // Returned, not failed here: the caller's assertion reports the observed value next to the expected one.
        return seen == null ? Long.MIN_VALUE : seen;
    }

    /**
     * How many times each of this run's orders is on the topic. All share one group key, hence one partition; the
     * partition is taken from where the ledger says it applied them.
     */
    private static long kafkaCopies(String group, List<String> orderIds) {
        var positions = Stack.query("ledger", "SELECT min(kafka_partition) AS p, max(kafka_partition) AS q, "
                + "min(kafka_offset) AS o FROM applied_orders WHERE order_group_id = ?", group).getFirst();
        assertEquals(positions.get("p"), positions.get("q"), "one group key, one partition");
        // The ledger reads a partition in order, so the first applied copy is the earliest copy there is.
        long from = ((Number) positions.get("o")).longValue();
        String dump = docker("exec", KAFKA, "bash", "-c", "KAFKA_HEAP_OPTS=-Xmx256m "
                + "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic " + TOPIC
                + " --partition " + positions.get("p") + " --offset " + from
                + " --timeout-ms 8000 --property print.value=true 2>/dev/null | grep -c '" + group + "' || true");
        return Long.parseLong(dump.lines().reduce((a, b) -> b).orElse("0").strip());
    }

    // --- stack plumbing -------------------------------------------------------------------------------------------

    private static void requireRunning(String container) {
        assertEquals("true", docker("inspect", "-f", "{{.State.Running}}", container), container + " must be up");
    }

    private static void awaitBroker() {
        docker("exec", KAFKA, "bash", "-c", "KAFKA_HEAP_OPTS=-Xmx128m "
                + "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka:29092 > /dev/null");
    }

    private static Matcher awaitStartedLine(Instant since) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            Matcher m = STARTED.matcher(docker("logs", "--timestamps", "--since", since.toString(), ORDER_SERVICE));
            if (m.find()) {
                return m;
            }
            Stack.sleep(Duration.ofMillis(500));
        }
        return fail("order-service never logged its Started line");
    }

    private static void awaitHealthy(Duration patience) {
        Instant deadline = Instant.now().plus(patience);
        while (Instant.now().isBefore(deadline)) {
            if ("healthy".equals(docker("inspect", "-f", "{{.State.Health.Status}}", ORDER_SERVICE))) {
                return;
            }
            Stack.sleep(Duration.ofSeconds(1));
        }
        fail("order-service did not return to healthy within " + patience);
    }

    /** pgjdbc binds a String[] as text[]; typed Object so varargs does not spread it into separate parameters. */
    private static Object sqlArray(String database, List<String> values) {
        return values.toArray(String[]::new);
    }

    // --- report ---------------------------------------------------------------------------------------------------

    private static String report(List<Repetition> results) {
        var provenance = Provenance.capture(Stack.ROOT, List.of(), Map.of(
                "order-service image", docker("inspect", "-f", "{{.Image}}", ORDER_SERVICE),
                "kafka image", docker("inspect", "-f", "{{.Config.Image}}", KAFKA)));
        String rows = results.stream().map(r -> "| %d | %s | %d | %d | %d | %.3f | %d | %d | %d | %d | %d | %s |"
                        .formatted(r.index(), r.run(), r.orders(), r.unpublishedAtKill(), r.appliedBeforeRestart(),
                                r.springStartupSeconds(), r.publishAfterContainerStartMs(), r.publishAfterAppStartedMs(),
                                r.appliedRows(), r.kafkaCopies(), r.riderBalance(), r.invariantsConsistent()))
                .collect(Collectors.joining("\n"));
        return """
                M4(a) crash recovery — kill -9 order-service between commit and publish
                %s
                | rep | run | orders | unpublished at kill | applied before restart | spring startup s | publish - container start ms | publish - app started ms | applied rows | copies on topic | rider receivable | invariants |
                |---|---|---|---|---|---|---|---|---|---|---|---|
                %s
                """.formatted(provenance.markdownTable("../../adr/0002-stack-and-pinned-versions.md"), rows);
    }

    private static String json(List<Repetition> results) {
        return results.stream().map(r -> ("{\"rep\":%d,\"run\":\"%s\",\"orders\":%d,\"unpublished_at_kill\":%d,"
                        + "\"applied_before_restart\":%d,\"killed_at\":\"%s\",\"container_started_at\":\"%s\","
                        + "\"app_started_at\":\"%s\",\"spring_startup_seconds\":%s,\"last_published_at\":\"%s\","
                        + "\"publish_after_container_start_ms\":%d,\"publish_after_app_started_ms\":%d,"
                        + "\"applied_rows\":%d,\"kafka_copies\":%d,\"rider_receivable\":%d,\"driver_payable\":%d,"
                        + "\"invariants_consistent\":%s}").formatted(r.index(), r.run(), r.orders(),
                        r.unpublishedAtKill(), r.appliedBeforeRestart(), r.killedAt(), r.containerStartedAt(),
                        r.appStartedAt(), r.springStartupSeconds(), r.lastPublishedAt(),
                        r.publishAfterContainerStartMs(), r.publishAfterAppStartedMs(), r.appliedRows(),
                        r.kafkaCopies(), r.riderBalance(), r.driverBalance(), r.invariantsConsistent()))
                .collect(Collectors.joining(",\n", "[\n", "\n]\n"));
    }
}
