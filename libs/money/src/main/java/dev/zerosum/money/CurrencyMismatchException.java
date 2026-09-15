package dev.zerosum.money;

/** Thrown when an operation combines amounts in different currencies (D01-1). */
public final class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(String left, String right) {
        super("currency mismatch: " + left + " vs " + right);
    }
}
