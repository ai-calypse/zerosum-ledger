package dev.zerosum.fakeproviders.faults;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The active fault profile per provider and the seeded random streams that apply it (D05-2).
 *
 * <p><strong>Reproducibility boundary.</strong> Identical seed plus identical request order gives identical
 * provider-side outcomes. Concurrent interleaving is not controlled: two requests racing each other draw from the
 * same stream in whatever order they reach it, so a chaos run is reproducible by seed and request sequence, not
 * bit-exact under concurrency. Pretending otherwise would be the more dangerous claim.
 *
 * <p>A profile is swapped as one immutable object. A request that has already read the profile keeps the old one for
 * its whole lifetime, so no request can ever see half of one profile and half of another.
 */
@Component
public class FaultProfiles {

    private static final Logger log = LoggerFactory.getLogger(FaultProfiles.class);

    /** decision: D01-10 — the same pinned algorithm the generative tests use; the default may change between JDKs. */
    private static final String ALGORITHM = "L64X128MixRandom";

    /** The 95th percentile of the standard normal, the lognormal latency shape is solved against. */
    private static final double Z95 = 1.6448536269514722;

    public static final Set<String> PROVIDERS = Set.of("fakecard", "fakebank");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient db;
    private final FaultLog faults;
    private final Duration processingDelayLimit;
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

    FaultProfiles(JdbcClient db, FaultLog faults,
            // decision: D05-9 (ADR-0010) — the safe-resubmit quiet period. A processing delay at or above it would
            // make FakeBank duplicate payouts that the resolver believed were safe to resubmit.
            @Value("${zs.faults.processing-delay-limit:60s}") Duration processingDelayLimit) {
        this.db = db;
        this.faults = faults;
        this.processingDelayLimit = processingDelayLimit;
        PROVIDERS.forEach(provider -> profiles.put(provider, new Profile(FaultKnobs.NONE)));
    }

    @PostConstruct
    void load() {
        db.sql("SELECT provider, knobs FROM fault_profiles")
                .query((rs, rowNum) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .forEach(row -> {
                    if (PROVIDERS.contains(row.getKey())) {
                        profiles.put(row.getKey(), new Profile(read(row.getValue())));
                    }
                });
        profiles.forEach((provider, profile) -> logActivation(provider, profile.knobs()));
    }

    public Duration processingDelayLimit() {
        return processingDelayLimit;
    }

    /** Replaces a provider's profile atomically and persists it, so a restart resumes the same simulation. */
    public FaultKnobs activate(String provider, FaultKnobs knobs) {
        requireKnownProvider(provider);
        db.sql("""
                INSERT INTO fault_profiles (provider, knobs, seed, activated_at)
                VALUES (?, ?::jsonb, ?, now())
                ON CONFLICT (provider) DO UPDATE SET knobs = excluded.knobs, seed = excluded.seed,
                                                     activated_at = excluded.activated_at
                """)
                .params(provider, JSON.writeValueAsString(knobs.toMap()), knobs.seed())
                .update();
        // One reference swap: in-flight requests keep the profile they started with, and the new one is complete
        // the moment it becomes visible. Fresh streams, so the same seed replays from the same point.
        profiles.put(provider, new Profile(knobs));
        logActivation(provider, knobs);
        return knobs;
    }

    public FaultKnobs knobs(String provider) {
        Profile profile = profiles.get(provider);
        return profile == null ? FaultKnobs.NONE : profile.knobs();
    }

    /**
     * Draws the decision and records it in the fault log when it fires (§0.3 E2).
     *
     * <p>The draw and the log entry are one call on purpose: a caller that could draw without recording is how a
     * fault ends up injected but invisible.
     */
    public boolean fires(String provider, Decision decision, String target) {
        Profile profile = profiles.get(provider);
        if (profile == null) {
            return false;
        }
        boolean fired = profile.next(decision) < decision.rate(profile.knobs());
        if (fired) {
            faults.record(provider, decision.faultType(), target, profile.knobs().seed());
        }
        return fired;
    }

    /**
     * Records a fault that was sampled rather than drawn against a rate (§0.3 E2).
     *
     * <p>Latency and processing delay have no rate to compare against, so {@link #fires} would never log them and
     * the fault log would quietly under-report what was injected.
     */
    public void record(String provider, Decision decision, String target) {
        Profile profile = profiles.get(provider);
        if (profile != null) {
            faults.record(provider, decision.faultType(), target, profile.knobs().seed());
        }
    }

    /** A lognormal latency sample in milliseconds, or zero when the provider has no latency configured. */
    public long latencyMillis(String provider) {
        Profile profile = profiles.get(provider);
        if (profile == null) {
            return 0;
        }
        // Drawn even when latency is off, so the stream advances once per request either way and a profile that
        // only changes the latency knobs leaves every other decision where it was.
        double deviate = profile.nextGaussian();
        FaultKnobs knobs = profile.knobs();
        if (knobs.latencyP50Ms() <= 0) {
            return 0;
        }
        double mu = Math.log(knobs.latencyP50Ms());
        double sigma = knobs.latencyP95Ms() <= knobs.latencyP50Ms() ? 0
                : (Math.log(knobs.latencyP95Ms()) - mu) / Z95;
        double sample = Math.exp(mu + sigma * deviate);
        // Capped: a lognormal tail can produce a sleep measured in hours, which would look like a hung provider
        // rather than a slow one and would hold a test open until its own timeout.
        return (long) Math.min(sample, 10 * Math.max(knobs.latencyP50Ms(), knobs.latencyP95Ms()));
    }

    /** A processing delay within the configured bound, drawn from the seeded stream (D05-9). */
    public long processingDelayMillis(String provider) {
        Profile profile = profiles.get(provider);
        if (profile == null) {
            return 0;
        }
        double fraction = profile.next(Decision.PROCESSING_DELAY);
        return (long) (fraction * profile.knobs().maxProcessingDelayMs());
    }

    public static void requireKnownProvider(String provider) {
        if (!PROVIDERS.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such provider: " + provider);
        }
    }

    private void logActivation(String provider, FaultKnobs knobs) {
        // decision: D01-10 — the greppable seed line, so a run that produced a surprising outcome can be replayed.
        log.info("ZS-SEED provider={} seed={} knobs={}", provider, knobs.seed(), knobs.toMap());
    }

    private static FaultKnobs read(String json) {
        return FaultKnobs.from(JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        }), Duration.ofDays(365));
    }

    /** One provider's knobs and its streams: replaced together, never mutated. */
    private static final class Profile {

        private final FaultKnobs knobs;
        private final Map<Decision, RandomGenerator> streams = new EnumMap<>(Decision.class);

        Profile(FaultKnobs knobs) {
            this.knobs = knobs;
            RandomGeneratorFactory<RandomGenerator> factory = RandomGeneratorFactory.of(ALGORITHM);
            for (Decision decision : Decision.values()) {
                // Mixed with the decision's name rather than its ordinal, so inserting a value into the enum does
                // not renumber every stream and invalidate recorded runs.
                streams.put(decision, factory.create(knobs.seed() ^ (decision.name().hashCode() * 0x9E3779B97F4A7C15L)));
            }
        }

        FaultKnobs knobs() {
            return knobs;
        }

        double next(Decision decision) {
            RandomGenerator stream = streams.get(decision);
            synchronized (stream) {
                return stream.nextDouble();
            }
        }

        double nextGaussian() {
            RandomGenerator stream = streams.get(Decision.LATENCY);
            synchronized (stream) {
                return stream.nextGaussian();
            }
        }
    }
}
