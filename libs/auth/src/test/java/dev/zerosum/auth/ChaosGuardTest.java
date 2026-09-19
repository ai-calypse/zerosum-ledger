package dev.zerosum.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D08-3: the chaos startup guard (master §8.5 "How the ablation flags are guarded"; S08-T03 guard tests). */
class ChaosGuardTest {

    private static final List<String> IDS = List.of("A1", "A4", "F2");

    @Test
    void noSwitchStartsInAnyProfileAndSaysSo() {
        var active = ChaosGuard.check("ledger-service", IDS, Map.<String, String>of()::get);
        assertTrue(active.switches().isEmpty(), "defaults are off");
        assertEquals("ZS-CHAOS service=ledger-service active=[]", active.logLine());
        // An explicit false is off too, and needs no opt-in.
        assertTrue(ChaosGuard.check("x", IDS, Map.of("zs.chaos.a1", "false")::get, "demo-public").switches().isEmpty());
    }

    @Test
    void aSwitchWithoutTheChaosProfileRefuses() {
        var failure = assertThrows(IllegalStateException.class, () -> ChaosGuard.check("ledger-service", IDS,
                Map.of("zs.chaos.a1", "true", "ZS_ALLOW_CHAOS", "true")::get, "default"));
        assertTrue(failure.getMessage().contains("[A1]") && failure.getMessage().contains("profile"),
                failure.getMessage());
    }

    @Test
    void aSwitchUnderTheChaosProfileWithoutTheOptInRefuses() {
        assertThrows(IllegalStateException.class,
                () -> ChaosGuard.check("x", IDS, Map.of("zs.chaos.a4", "true")::get, "chaos"));
        assertThrows(IllegalStateException.class, () -> ChaosGuard.check("x", IDS,
                Map.of("zs.chaos.a4", "true", "ZS_ALLOW_CHAOS", "yes")::get, "chaos"));
    }

    @Test
    void demoPublicRefusesEvenWithEverythingElseInPlace() {
        assertThrows(IllegalStateException.class, () -> ChaosGuard.check("x", IDS,
                Map.of("zs.chaos.f2", "true", "ZS_ALLOW_CHAOS", "true")::get, "chaos", "demo-public"));
    }

    @Test
    void chaosProfileAndOptInStartAndTheLogLineListsTheActiveSet() {
        var active = ChaosGuard.check("ledger-service", IDS,
                Map.of("zs.chaos.f2", "true", "zs.chaos.a1", "true", "zs.chaos.a4", "false",
                        "ZS_ALLOW_CHAOS", "true")::get, "chaos");
        assertEquals(List.of("A1", "F2"), active.switches());
        assertTrue(active.on("A1") && active.on("F2"));
        assertFalse(active.on("A4"));
        assertEquals("ZS-CHAOS service=ledger-service active=[A1, F2]", active.logLine());
    }

    @Test
    void anUnarmedHookDoesNotFire() {
        assertFalse(ChaosGuard.consumeArm("never-armed-" + System.nanoTime()));
    }
}
