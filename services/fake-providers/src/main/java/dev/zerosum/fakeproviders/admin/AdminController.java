package dev.zerosum.fakeproviders.admin;

import dev.zerosum.auth.Principal;
import dev.zerosum.auth.Role;
import dev.zerosum.auth.TokenAuthFilter;
import dev.zerosum.fakeproviders.admin.AdminApi.Truth;
import dev.zerosum.fakeproviders.faults.FaultKnobs;
import dev.zerosum.fakeproviders.faults.FaultProfiles;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fault knobs and ground truth (D05-2, TB4).
 *
 * <p>Admin token only, and <strong>absent entirely</strong> under {@code demo-public} (D00-3): on a public demo an
 * endpoint that injects faults or dumps every charge is not something to protect with a token, it is something that
 * should not exist. A missing bean is a 404, which tells a prober nothing about what is normally here.
 */
@RestController
@RequestMapping("/admin")
@Profile("!demo-public")
class AdminController {

    private final FaultProfiles profiles;
    private final TruthService truth;

    AdminController(FaultProfiles profiles, TruthService truth) {
        this.profiles = profiles;
        this.truth = truth;
    }

    /**
     * Replaces the provider's knob profile whole.
     *
     * <p>The body is taken as a map rather than bound to the record directly, so an unrecognised knob name is named
     * back to the caller instead of being dropped by whatever the JSON binder's unknown-field default happens to be.
     * A silently ignored typo is a chaos run that injects nothing and reports success.
     */
    @PutMapping("/faults/{provider}")
    Map<String, Object> faults(HttpServletRequest request, @PathVariable String provider,
            @RequestBody Map<String, Object> body) {
        requireAdmin(request);
        FaultProfiles.requireKnownProvider(provider);
        // Parsed and validated before anything is replaced: an invalid payload leaves the previous profile active.
        FaultKnobs knobs = FaultKnobs.from(body, profiles.processingDelayLimit());
        return profiles.activate(provider, knobs).toMap();
    }

    /**
     * Provider-side ground truth, filtered by <strong>client reference</strong> (CR-S05-02, change request against
     * master §5.6).
     *
     * <p>The parameter used to be {@code entity_id}, and it could never match: a payment provider is not told our
     * ledger entity ids, so it stores charges against the client reference the caller sent — which is the attempt
     * id. Filtering by entity returned an empty list for every entity, and an auditor reading it would have
     * concluded that no charge had occurred anywhere. Teaching this simulator about ledger entities would have
     * fixed the symptom by making it less faithful than the thing it simulates, so the caller resolves
     * entity → attempt ids from the instruments database first and asks here by reference.
     *
     * <p>{@code entity_id} is refused rather than ignored, because being quietly ignored is precisely how the
     * original defect produced a clean bill of health.
     */
    @GetMapping("/truth")
    Truth truth(HttpServletRequest request,
            @RequestParam(name = "client_reference", required = false) String clientReference,
            @RequestParam(name = "entity_id", required = false) String entityId) {
        requireAdmin(request);
        if (entityId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ground truth is queryable by client_reference, not entity_id: this provider is never told a "
                            + "ledger entity id, so filtering by one could only ever match nothing (CR-S05-02). "
                            + "Resolve the entity's attempt ids first, then query by client_reference.");
        }
        return truth.truth(clientReference);
    }

    /**
     * Ground truth counted rather than listed, plus each provider's active fault profile (S09 dashboard).
     *
     * <p>Admin, like the rest of this surface, and absent under {@code demo-public} with it: the profile says which
     * faults are being injected, which is exactly what a public prober should not learn.
     */
    @GetMapping("/summary")
    AdminApi.Summary summary(HttpServletRequest request) {
        requireAdmin(request);
        var active = new java.util.TreeMap<String, Map<String, Object>>();
        FaultProfiles.PROVIDERS.forEach(provider -> active.put(provider, profiles.knobs(provider).toMap()));
        return truth.summary(active);
    }

    private static Principal requireAdmin(HttpServletRequest request) {
        Principal principal = TokenAuthFilter.principal(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "a valid admin bearer token is required"));
        if (!principal.role().satisfies(Role.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "this endpoint requires the admin role");
        }
        return principal;
    }
}
