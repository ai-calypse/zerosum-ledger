# decision: D00-3, D00-6 — docs/step_00_foundations.md#decisions-and-outputs
# Shared image definition for all four services. Build context: services/<service>, after `./gradlew assemble`
# has produced build/libs/app.jar (fixed boot jar name, zs.service-conventions) and build/otel/ (the agent).
# decision: D00-1 — base image tag and digest
FROM eclipse-temurin:25.0.4_7-jre@sha256:15090d159279e5c158473eccb48cd87f57b3e3a47511a797eb5a7a7ea6f86b0f

RUN useradd --system --uid 10001 --no-create-home app

COPY build/otel/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
COPY build/libs/app.jar /app/app.jar

# decision: D00-6, D00-7 — the agent exports traces only. Its metrics and logs exporters are disabled with system
# properties: OTEL_*_EXPORTER environment variables would also switch off Boot's Micrometer OTLP export (SP3).
# Heap is a share of the container memory limit, leaving room for metaspace, threads and the agent.
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar -Dotel.metrics.exporter=none -Dotel.logs.exporter=none -XX:MaxRAMPercentage=60"
# glibc gives busy threads their own malloc arenas (up to 8 x cores), each reserving and fragmenting its own memory,
# outside anything the JVM reports. Measured: JVM-accounted memory ~385 MB against ~750 MB resident in a 768 MB
# container. Two arenas is the usual bound for a JVM, which does its own heap management.
ENV MALLOC_ARENA_MAX=2

USER app

# Exec form: the JVM is PID 1 and receives SIGTERM directly, so graceful shutdown runs (exit 143, not 137).
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
