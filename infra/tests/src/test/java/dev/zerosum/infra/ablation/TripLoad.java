package dev.zerosum.infra.ablation;

import dev.zerosum.infra.Stack;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The ablation experiment's seeded workload: COMMERCE trip orders through the real order API (TB1), paced at a fixed
 * rate, each retried with its own idempotency key until the API gives a definitive answer, exactly as a client of a
 * crashing service must (master §8.4 F1: "callers retry with the same keys").
 *
 * <p><strong>Deterministic.</strong> Every trip is drawn up front from one {@link SplittableRandom}, and every draw is
 * taken whether or not its toggle is on, so switching bug injection on does not move any other trip's fare.
 *
 * <p><strong>A4's fare-split bug</strong> (master §8.5, D08-1): with probability {@code bugRate} the rider leg is one
 * minor unit high or low and the other legs are unchanged, so the order does not sum to zero. With every protection on,
 * the API refuses it with 422; with A4 it is accepted. <strong>F11</strong>: after a definitive answer, a trip is sent
 * again with the same key and body ({@code resendRate}, expected replay) or with the same key and a different body
 * ({@code keyReuseRate}, expected 422).
 */
final class TripLoad {

    /** One workload profile; the numbers are recorded in every run JSON. */
    record Workload(int trips, double tripsPerSecond, int riders, int drivers, boolean cards, double bugRate,
            double resendRate, double keyReuseRate) {
    }

    private record Trip(int index, String key, String body, String reusedBody, int bug, boolean resend,
            boolean reuse) {
    }

    /** A client gives up on one trip after this long; an abandoned trip is reported, never silently dropped. */
    private static final Duration PER_TRIP_DEADLINE = Duration.ofMinutes(4);

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> unexpected = new ConcurrentHashMap<>();

    static String rider(String run, int k) {
        return "rider:" + run + "r" + k;
    }

    /** Registers a card for every rider, so each trip is also collected through instrument-service and FakeCard. */
    static void registerCards(String run, int riders) {
        for (int k = 0; k < riders; k++) {
            HttpResponse<String> registered = Stack.post(Stack.INSTRUMENTS + "/v1/instrument-tokens",
                    Stack.writerToken(), null, "{\"entity_id\":\"%s\",\"provider\":\"fakecard\",\"token\":\"tok_card_ok\"}"
                            .formatted(rider(run, k)));
            if (registered.statusCode() != 200) {
                throw new IllegalStateException("card registration: " + registered.statusCode() + " "
                        + registered.body());
            }
        }
    }

    /** Runs the whole workload and returns once every trip has a definitive answer or was abandoned. */
    Map<String, Object> run(String run, long seed, Workload workload) throws InterruptedException {
        List<Trip> trips = plan(run, seed, workload);
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Trip trip : trips) {
                long dueNanos = startNanos + (long) (trip.index() * 1e9 / workload.tripsPerSecond());
                long wait = dueNanos - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                executor.submit(() -> drive(trip));
            }
            executor.shutdown();
            if (!executor.awaitTermination(PER_TRIP_DEADLINE.toMinutes() * 3, TimeUnit.MINUTES)) {
                throw new IllegalStateException("the load did not finish");
            }
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("workload", workload);
        summary.put("started_at", startedAt.toString());
        summary.put("finished_at", Instant.now().toString());
        summary.put("generation_seconds", Duration.between(startedAt, Instant.now()).toMillis() / 1000.0);
        summary.put("counts", snapshot(counts));
        summary.put("unexpected_statuses", snapshot(unexpected));
        return summary;
    }

    private static List<Trip> plan(String run, long seed, Workload workload) {
        var random = new SplittableRandom(seed);
        var trips = new ArrayList<Trip>(workload.trips());
        String effectiveAt = Instant.now().toString();
        for (int i = 0; i < workload.trips(); i++) {
            int rider = random.nextInt(workload.riders());
            int driver = random.nextInt(workload.drivers());
            long fare = 500 + random.nextInt(4_501);
            boolean bugDraw = random.nextDouble() < workload.bugRate();
            int bugSign = random.nextBoolean() ? 1 : -1;
            boolean resend = random.nextDouble() < workload.resendRate();
            boolean reuse = random.nextDouble() < workload.keyReuseRate();
            int bug = bugDraw ? bugSign : 0;
            long fee = fare / 5;
            String group = "trip_" + run + "_" + i;
            String body = body(group, rider(run, rider), fare + bug, "driver:" + run + "d" + driver, fare - fee, fee,
                    effectiveAt);
            // Same key, different body: the fare is changed by 7 minor units on every leg, so it still balances.
            String reused = body(group, rider(run, rider), fare + 7 + bug, "driver:" + run + "d" + driver,
                    fare + 7 - fee, fee, effectiveAt);
            trips.add(new Trip(i, run + "-" + i, body, reused, bug, resend, reuse));
        }
        return trips;
    }

    /** Built once per trip: a retry must send the identical body, or a committed order would look like key reuse. */
    private static String body(String group, String rider, long riderMinor, String driver, long driverMinor, long fee,
            String effectiveAt) {
        return """
                {"order_group_id":"%s","type":"COMMERCE","reason":"trip.completed","adjusts_order_id":null,
                 "entries":[{"entity_id":"%s","account":"receivable","currency":"USD","amount_minor":%d},
                            {"entity_id":"%s","account":"payable","currency":"USD","amount_minor":%d},
                            {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":%d}],
                 "metadata":{"trip_id":"%s"},"effective_at":"%s"}
                """.formatted(group, rider, riderMinor, driver, -driverMinor, -fee, group, effectiveAt);
    }

    private void drive(Trip trip) {
        if (trip.bug() != 0) {
            count("bug_orders_sent");
        }
        Integer status = send(trip.key(), trip.body());
        if (status == null) {
            count("abandoned");
            return;
        }
        switch (status) {
            case 201 -> count("created");
            case 200 -> count("replayed_on_retry");
            case 422 -> count(trip.bug() != 0 ? "bug_orders_rejected_422" : "rejected_422_unexpected");
            default -> unexpected.computeIfAbsent("first:" + status, k -> new AtomicLong()).incrementAndGet();
        }
        if (trip.bug() != 0 && (status == 201 || status == 200)) {
            count("bug_orders_accepted");
        }
        if (trip.resend() && (status == 201 || status == 200)) {
            Integer again = send(trip.key(), trip.body());
            count(again != null && again == 200 ? "f11_resend_replayed" : "f11_resend_unexpected");
        }
        if (trip.reuse() && (status == 201 || status == 200)) {
            Integer reused = send(trip.key(), trip.reusedBody());
            count(reused != null && reused == 422 ? "f11_key_reuse_rejected_422" : "f11_key_reuse_unexpected");
        }
    }

    /** Sends until the API gives a definitive answer; transport failures, 409 in-progress and 5xx are retried. */
    private Integer send(String key, String body) {
        Instant deadline = Instant.now().plus(PER_TRIP_DEADLINE);
        long backoffMillis = 200;
        while (Instant.now().isBefore(deadline)) {
            try {
                int status = Stack.post(Stack.ORDERS + "/v1/money-orders", Stack.writerToken(), key, body).statusCode();
                if (status != 409 && status < 500) {
                    return status;
                }
                count("retried_status_" + status);
            } catch (UncheckedIOException transport) {
                count("retried_transport");
            }
            Stack.sleep(Duration.ofMillis(backoffMillis));
            backoffMillis = Math.min(2_000, backoffMillis * 2);
        }
        return null;
    }

    private void count(String name) {
        counts.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    private static Map<String, Long> snapshot(Map<String, AtomicLong> source) {
        var copy = new java.util.TreeMap<String, Long>();
        source.forEach((k, v) -> copy.put(k, v.get()));
        return copy;
    }
}
