package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChartOfAccountsTest {

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @CsvSource({
        // docs/zerosum_ledger_mvp_plan.md#chart-of-accounts
        "rider:R1, receivable, DEBIT",
        "driver:D1, payable, CREDIT",
        "platform:main, revenue, CREDIT",
        "platform:main, cash, DEBIT",
        "platform:main, processing_fees, DEBIT",
        "provider:fakecard, clearing, DEBIT",
        "provider:fakebank, payout_clearing, CREDIT",
    })
    void everyMasterChartRowIsPresent(String entityId, String account, NormalSide side) {
        EntityKind kind = ChartOfAccounts.kindOf(entityId).orElseThrow();
        assertTrue(ChartOfAccounts.isAllowed(kind, account));
        assertEquals(side, ChartOfAccounts.normalSide(account));
    }

    @Test
    void allowedAccountsPerKindAreExactlyTheMasterRows() {
        assertEquals(Set.of("receivable"), ChartOfAccounts.allowedAccounts(EntityKind.RIDER));
        assertEquals(Set.of("payable"), ChartOfAccounts.allowedAccounts(EntityKind.DRIVER));
        assertEquals(Set.of("revenue", "cash", "processing_fees"), ChartOfAccounts.allowedAccounts(EntityKind.PLATFORM));
        assertEquals(Set.of("clearing", "payout_clearing"), ChartOfAccounts.allowedAccounts(EntityKind.PROVIDER));
    }

    @Test
    void disallowedKindAccountPairIsRejected() {
        assertFalse(ChartOfAccounts.isAllowed(EntityKind.RIDER, "payable"));
        assertFalse(ChartOfAccounts.isAllowed(EntityKind.DRIVER, "cash"));
        assertFalse(ChartOfAccounts.isAllowed(EntityKind.PLATFORM, "clearing"));
        assertFalse(ChartOfAccounts.isAllowed(EntityKind.PROVIDER, null));
        assertThrows(IllegalArgumentException.class, () -> ChartOfAccounts.normalSide("wallet"));
    }

    @Test
    void subEntityOfAnExistingKindNeedsNoChartChange() {
        // SP4 sharding of platform:main, and a new provider such as the H4 FakeWallet.
        assertEquals(Optional.of(EntityKind.PLATFORM), ChartOfAccounts.kindOf("platform:main_07"));
        assertTrue(ChartOfAccounts.isAllowed(EntityKind.PLATFORM, "revenue"));
        assertEquals(Optional.of(EntityKind.PROVIDER), ChartOfAccounts.kindOf("provider:fakewallet"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wallet:W1", "rider:", "rider", ":R1", "Rider:R1", "rider:R 1", "rider:R1:x", "rider:R.1",
        "rider:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidEntityIdsHaveNoKind(String entityId) {
        assertEquals(Optional.empty(), ChartOfAccounts.kindOf(entityId));
    }

    @Test
    void presentationFollowsAdr0003IncludingContraBalances() {
        // Balances after O1–O7 (docs/zerosum_ledger_mvp_plan.md#worked-example): revenue −440 shows 440, cash +338 shows 338.
        assertEquals(440, ChartOfAccounts.presentOnNormalSide("revenue", -440));
        assertEquals(338, ChartOfAccounts.presentOnNormalSide("cash", 338));
        assertEquals(102, ChartOfAccounts.presentOnNormalSide("processing_fees", 102));
        // Contra balances: driver debt (+500 on a credit-normal payable) and rider credit (−300 on a debit-normal
        // receivable) present as negative amounts on the normal side.
        assertEquals(-500, ChartOfAccounts.presentOnNormalSide("payable", 500));
        assertEquals(-300, ChartOfAccounts.presentOnNormalSide("receivable", -300));
        assertThrows(ArithmeticException.class, () -> ChartOfAccounts.presentOnNormalSide("payable", Long.MIN_VALUE));
    }
}
