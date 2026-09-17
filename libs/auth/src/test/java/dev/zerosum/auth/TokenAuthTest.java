package dev.zerosum.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** D03-4: token parsing, source-system derivation and the role hierarchy (TB1). */
class TokenAuthTest {

    private static final TokenRegistry TOKENS =
            TokenRegistry.of("trip-simulator:writer-a,instrument-service:writer-b", "reader-x", "admin-y");

    @Test
    void aWriterTokenCarriesTheSourceSystemItWritesAs() {
        // source.system comes from the token, never from a request body (D01-5 rule 8).
        Principal simulator = TOKENS.authenticate("writer-a").orElseThrow();
        assertEquals(Role.WRITER, simulator.role());
        assertEquals("trip-simulator", simulator.sourceSystem());

        Principal mapper = TOKENS.authenticate("writer-b").orElseThrow();
        assertEquals("instrument-service", mapper.sourceSystem(), "each writer token names its own system");
    }

    @Test
    void readerAndAdminTokensCarryNoSourceSystem() {
        assertEquals(Role.READER, TOKENS.authenticate("reader-x").orElseThrow().role());
        assertEquals(null, TOKENS.authenticate("reader-x").orElseThrow().sourceSystem());
        assertEquals(Role.ADMIN, TOKENS.authenticate("admin-y").orElseThrow().role());
        assertEquals(null, TOKENS.authenticate("admin-y").orElseThrow().sourceSystem());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "writer-a ", " writer-a", "WRITER-A", "reader-x-extra", ""})
    void anUnrecognisedTokenAuthenticatesNobody(String presented) {
        assertEquals(Optional.empty(), TOKENS.authenticate(presented), "no near-miss is accepted");
    }

    @Test
    void anAbsentTokenAuthenticatesNobody() {
        assertEquals(Optional.empty(), TOKENS.authenticate(null));
    }

    @Test
    void theRoleHierarchyIsNarrow() {
        // Writer and admin are strictly more privileged than reader, so both satisfy reader endpoints. Nothing else
        // implies anything: a writer must not reach admin operations.
        assertTrue(Role.WRITER.satisfies(Role.READER));
        assertTrue(Role.ADMIN.satisfies(Role.READER));
        assertTrue(Role.READER.satisfies(Role.READER));
        assertFalse(Role.READER.satisfies(Role.WRITER));
        assertFalse(Role.READER.satisfies(Role.ADMIN));
        assertFalse(Role.WRITER.satisfies(Role.ADMIN), "a writer is not an admin");
        assertFalse(Role.ADMIN.satisfies(Role.WRITER), "an admin is not a writer: writing money needs a writer token");
    }

    @Test
    void aWriterPrincipalWithoutASourceSystemIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Principal(Role.WRITER, null));
        assertThrows(IllegalArgumentException.class, () -> new Principal(Role.WRITER, "  "));
        assertThrows(IllegalArgumentException.class, () -> new Principal(null, "trip-simulator"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-colon", ":token", "system:", "trip-simulator:a,malformed"})
    void malformedWriterTokenConfigurationFailsFast(String writerTokens) {
        // A misconfigured deployment must not start with tokens it silently ignored.
        assertThrows(IllegalArgumentException.class, () -> TokenRegistry.of(writerTokens, "r", "a"));
    }

    @Test
    void anEmptyRegistryIsDetectable() {
        assertTrue(TokenRegistry.of("", "", "").isEmpty(), "a service with no tokens would accept nobody");
        assertFalse(TOKENS.isEmpty());
        assertEquals(java.util.List.of("instrument-service", "trip-simulator"), TOKENS.writerSystems(), "sorted");
    }
}
