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

    @GetMapping("/truth")
    Truth truth(HttpServletRequest request, @RequestParam(name = "entity_id", required = false) String entityId) {
        requireAdmin(request);
        return truth.truth(entityId);
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
