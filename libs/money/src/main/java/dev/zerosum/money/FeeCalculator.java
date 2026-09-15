package dev.zerosum.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Basis-point fee arithmetic with HALF_EVEN rounding (D01-3). The only class in {@code libs/money} allowed to use
 * {@link BigDecimal} (ArchUnit Rule B); every scale change passes {@link RoundingMode#HALF_EVEN} explicitly (Rule C).
 * Fee schedules are parameters: no provider schedule is embedded here.
 */
public final class FeeCalculator {

    /** decision: D01-3 — docs/step_01_domain_contracts.md#decisions-and-outputs */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    /** decision: D01-3 — basis points per whole (10 000 bps = 100%). */
    public static final long BPS_SCALE = 10_000;

    private static final BigDecimal SCALE = BigDecimal.valueOf(BPS_SCALE);

    private FeeCalculator() {
    }

    /**
     * {@code round(base × rateBps / 10 000) + fixed}. Fees are computed on captures and never refunded, so the base,
     * rate and fixed component must be non-negative (D01-3).
     *
     * @throws IllegalArgumentException for a negative base, rate or fixed component
     * @throws CurrencyMismatchException when {@code fixed} is in another currency
     * @throws ArithmeticException when the result does not fit in a {@code long}
     */
    public static Money fee(Money base, long rateBps, Money fixed) {
        if (base.amountMinor() < 0) {
            throw new IllegalArgumentException("fee base must not be negative: " + base.amountMinor());
        }
        if (rateBps < 0) {
            throw new IllegalArgumentException("fee rate must not be negative: " + rateBps + " bps");
        }
        if (!fixed.currency().equals(base.currency())) {
            throw new CurrencyMismatchException(base.currency(), fixed.currency());
        }
        if (fixed.amountMinor() < 0) {
            throw new IllegalArgumentException("fixed fee component must not be negative: " + fixed.amountMinor());
        }
        return Money.of(roundedShare(base.amountMinor(), rateBps), base.currency()).plus(fixed);
    }

    /**
     * {@code round(amountMinor × bps / 10 000)} with HALF_EVEN for any sign; symmetric, so
     * {@code roundedShare(-a, b) == -roundedShare(a, b)}. Used by {@link FareSplitter}.
     *
     * @throws ArithmeticException when the result does not fit in a {@code long}
     */
    public static long roundedShare(long amountMinor, long bps) {
        return BigDecimal.valueOf(amountMinor)
                .multiply(BigDecimal.valueOf(bps))
                .divide(SCALE, 0, ROUNDING)
                .longValueExact();
    }
}
