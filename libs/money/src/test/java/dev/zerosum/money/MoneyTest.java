package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void arithmeticIsExact() {
        Money a = Money.of(2500, "USD");
        Money b = Money.of(-2000, "USD");
        assertEquals(Money.of(500, "USD"), a.plus(b));
        assertEquals(Money.of(4500, "USD"), a.minus(b));
        assertEquals(Money.of(-2500, "USD"), a.negate());
        assertTrue(a.plus(a.negate()).isZero());
    }

    @Test
    void zeroAndThreeDigitCurrenciesUseTheSameMinorUnitArithmetic() {
        assertEquals(Money.of(1001, "JPY"), Money.of(1000, "JPY").plus(Money.of(1, "JPY")));
        assertEquals(Money.of(1001, "KWD"), Money.of(1000, "KWD").plus(Money.of(1, "KWD")));
    }

    @Test
    void overflowThrowsAtBothLongBoundaries() {
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MAX_VALUE, "USD").plus(Money.of(1, "USD")));
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MIN_VALUE, "USD").plus(Money.of(-1, "USD")));
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MIN_VALUE, "USD").minus(Money.of(1, "USD")));
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MAX_VALUE, "USD").minus(Money.of(-1, "USD")));
        assertThrows(ArithmeticException.class, () -> Money.of(Long.MIN_VALUE, "USD").negate());
        assertEquals(Money.of(-Long.MAX_VALUE, "USD"), Money.of(Long.MAX_VALUE, "USD").negate());
    }

    @Test
    void currencyMismatchIsRejected() {
        var e = assertThrows(CurrencyMismatchException.class, () -> Money.of(1, "USD").plus(Money.of(1, "EUR")));
        assertTrue(e.getMessage().contains("USD") && e.getMessage().contains("EUR"), e.getMessage());
        assertThrows(CurrencyMismatchException.class, () -> Money.of(1, "USD").minus(Money.of(1, "JPY")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"usd", "US", "USDX", "U1D", "", " USD", "ZZZ", "XAU"})
    void malformedOrUnlistedCodesAreRejectedWithTheCodeInTheMessage(String code) {
        var e = assertThrows(IllegalArgumentException.class, () -> Money.of(1, code));
        assertTrue(e.getMessage().contains(code), e.getMessage());
    }

    @ParameterizedTest
    @NullSource
    void nullCurrencyIsRejected(String code) {
        assertThrows(IllegalArgumentException.class, () -> Money.of(1, code));
    }
}
