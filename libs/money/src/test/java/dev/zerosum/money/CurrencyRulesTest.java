package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyRulesTest {

    private final CurrencyRules rules = CurrencyRules.defaults();

    @Test
    void digitsForTwoZeroAndThreeDigitCurrenciesComeFromTheTable() {
        // M1(b): JPY (0 digits) and KWD (3 digits), plus USD (2 digits) — docs/zerosum_ledger_mvp_plan.md#must-have
        assertEquals(2, rules.minorUnitDigits("USD"));
        assertEquals(2, rules.minorUnitDigits("EUR"));
        assertEquals(0, rules.minorUnitDigits("JPY"));
        assertEquals(3, rules.minorUnitDigits("KWD"));
    }

    @Test
    void fundsAndMetalsWithoutNumericMinorUnitsAreNotKnown() {
        assertFalse(rules.isKnown("XAU"));
        assertFalse(rules.isKnown("XXX"));
        assertFalse(rules.isKnown(null));
        assertThrows(IllegalArgumentException.class, () -> rules.minorUnitDigits("XAU"));
        // Codes with more than three digits stay in the table; the allow-list (D01-7) decides what orders may use.
        assertEquals(4, rules.minorUnitDigits("CLF"));
    }

    @Test
    void checkedInTableIsStrictlySortedWithoutDuplicates() throws IOException {
        List<String> codes;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CurrencyRules.TABLE_RESOURCE)) {
            codes = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#"))
                    .map(l -> l.substring(0, l.indexOf(',')))
                    .toList();
        }
        assertTrue(codes.size() > 100, "table has " + codes.size() + " codes");
        for (int i = 1; i < codes.size(); i++) {
            assertTrue(codes.get(i).compareTo(codes.get(i - 1)) > 0, codes.get(i - 1) + " then " + codes.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing.csv", "empty.csv", "unsorted.csv", "duplicate.csv", "malformed.csv"})
    void invalidTableResourcesFailWithAMessageNamingTheResource(String name) {
        String resource = "dev/zerosum/money/test-tables/" + name;
        var e = assertThrows(IllegalStateException.class, () -> CurrencyRules.load(resource));
        assertTrue(e.getMessage().contains(resource), e.getMessage());
    }
}
