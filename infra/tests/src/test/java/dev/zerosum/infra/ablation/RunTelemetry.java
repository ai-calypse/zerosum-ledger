package dev.zerosum.infra.ablation;

import dev.zerosum.infra.Stack;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * What a reader needs to replay a run afterwards, without changing it: each service's log, saved before the run's
 * containers are removed, and Grafana annotations marking the run and every fault on the dashboards' timelines.
 * Observability only. Every call here is best-effort and never changes a run's outcome or classification.
 */
final class RunTelemetry {

    static final String GRAFANA = "http://127.0.0.1:3000";
    static final String RUN_TAG = "zs-run";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private RunTelemetry() {
    }

    /** One Grafana annotation (a region when {@code end} is given), tagged so the dashboards' run layer shows it. */
    static void annotate(Instant start, Instant end, String text, List<String> tags) {
        var body = Stack.JSON.createObjectNode();
        body.put("time", start.toEpochMilli());
        if (end != null) {
            body.put("timeEnd", end.toEpochMilli());
        }
        body.put("text", text);
        var tagArray = body.putArray("tags");
        tagArray.add(RUN_TAG);
        tags.forEach(tagArray::add);
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(GRAFANA + "/api/annotations"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("ZS-ABLATION annotation refused " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException | InterruptedException failure) {
            System.out.println("ZS-ABLATION annotation not posted: " + failure);
        }
    }

    /** The flow dashboard over the run's own window, padded a little either side. */
    static String grafanaUrl(Instant start, Instant end) {
        return GRAFANA + "/d/zs-flow?from=" + start.minusSeconds(30).toEpochMilli() + "&to="
                + end.plusSeconds(30).toEpochMilli();
    }

    /**
     * Each service's full log (all restarts of the container, since a crash-and-start keeps the container), gzipped,
     * saved before teardown removes the containers and their logs with them.
     */
    static void saveLogs(Path dir, String label) {
        for (String service : ChaosStack.SERVICES) {
            try {
                String log = ChaosStack.docker(Duration.ofSeconds(60), "logs", "--timestamps",
                        ChaosStack.container(service));
                Path file = dir.resolve(label).resolve(service + ".log.gz");
                Files.createDirectories(file.getParent());
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
                    out.write(log.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException failure) {
                System.out.println("ZS-ABLATION logs of " + service + " not saved for " + label + ": " + failure);
            }
        }
    }
}
