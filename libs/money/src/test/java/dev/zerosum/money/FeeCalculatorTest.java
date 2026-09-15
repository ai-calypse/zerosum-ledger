package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {

    @Test
    void reproducesTheWorkedExampleCaptureFee() {
        // docs/zerosum_ledger_mvp_plan.md#worked-example: FakeCard schedule 290 bps + 30 minor units (simulation
        // parameter); 2500 × 0.029 = 72.5 → HALF_EVEN 72, + 30 = 102.
        long scheduleBps = 290;
        Money fixed = Money.of(30, "USD");
        assertEquals(Money.of(102, "USD"), FeeCalculator.fee(Money.of(2500, "USD"), scheduleBps, fixed));
    }

    @ParameterizedTest(name = "{0} × {1} bps → {2}")
    @CsvSource({
        "2500, 290, 72",    // 72.5 tie rounds down to even
        "2700, 250, 68",    // 67.5 tie rounds up to even
        "-2500, 290, -72",  // symmetric in sign
        "-2700, 250, -68",
        "2501, 290, 73",    // 72.529 not a tie
        "1, 5000, 0",       // 0.5 tie → 0
        "3, 5000, 2",       // 1.5 tie → 2
        "0, 290, 0",
    })
    void roundsHalfEvenInBothSigns(long amount, long bps, long expected) {
        assertEquals(expected, FeeCalculator.roundedShare(amount, bps));
    }

    @Test
    void zeroAndThreeDigitCurrenciesHaveNoHiddenScale() {
        assertEquals(Money.of(102, "JPY"), FeeCalculator.fee(Money.of(2500, "JPY"), 290, Money.of(30, "JPY")));
        assertEquals(Money.of(102, "KWD"), FeeCalculator.fee(Money.of(2500, "KWD"), 290, Money.of(30, "KWD")));
    }

    @Test
    void rejectsInputsOutsideTheFeeDomain() {
        Money zero = Money.of(0, "USD");
        var base = assertThrows(IllegalArgumentException.class, () -> FeeCalculator.fee(Money.of(-1, "USD"), 290, zero));
        assertTrue(base.getMessage().contains("-1"), base.getMessage());
        var rate = assertThrows(IllegalArgumentException.class, () -> FeeCalculator.fee(Money.of(1, "USD"), -5, zero));
        assertTrue(rate.getMessage().contains("-5"), rate.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FeeCalculator.fee(Money.of(1, "USD"), 290, Money.of(-30, "USD")));
        assertThrows(CurrencyMismatchException.class,
                () -> FeeCalculator.fee(Money.of(2500, "USD"), 290, Money.of(30, "EUR")));
    }

    @Test
    void resultsOutsideLongRangeThrowInsteadOfTruncating() {
        assertThrows(ArithmeticException.class, () -> FeeCalculator.roundedShare(Long.MAX_VALUE, 20_000));
        assertThrows(ArithmeticException.class,
                () -> FeeCalculator.fee(Money.of(Long.MAX_VALUE, "USD"), 10_000, Money.of(1, "USD")));
        assertEquals(Long.MAX_VALUE, FeeCalculator.roundedShare(Long.MAX_VALUE, 10_000));
    }
}
