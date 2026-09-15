package dev.zerosum.archcanary;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Deliberately violates Rule C (HALF_UP, and a setScale overload without a rounding mode); excluded from the real scan. */
public class RoundingModeCanary {

    public long roundUp(long amount) {
        return BigDecimal.valueOf(amount).movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public BigDecimal implicit(BigDecimal value) {
        return value.setScale(2);
    }
}
