package dev.zerosum.archcanary;

/** Deliberately violates Rule A (floating point); excluded from the real scan. */
public class FloatingPointCanary {

    private double rate = 0.029;

    public long fee(long amount) {
        return Math.round(amount * rate);
    }
}
