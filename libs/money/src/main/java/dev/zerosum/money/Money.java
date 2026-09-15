package dev.zerosum.money;

import java.util.regex.Pattern;

/**
 * An exact amount of integer minor units in an ISO 4217 currency (D01-1).
 *
 * <p>Arithmetic never wraps or saturates: overflow throws {@link ArithmeticException}. The per-entry magnitude cap
 * is a validation rule (D01-5), not an invariant of this type, because balances accumulate beyond one entry's cap.
 * There is deliberately no decimal, floating-point or string-parsing constructor and no major-unit formatting.
 */
public record Money(long amountMinor, String currency) {

    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Z]{3}");

    public Money {
        if (currency == null || !CODE_SHAPE.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be three uppercase ASCII letters: " + currency);
        }
        if (!CurrencyRules.defaults().isKnown(currency)) {
            throw new IllegalArgumentException("currency not in the ISO 4217 minor-unit table: " + currency);
        }
    }

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
