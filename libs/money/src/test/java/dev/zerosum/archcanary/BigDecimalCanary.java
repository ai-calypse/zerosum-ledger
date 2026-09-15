package dev.zerosum.archcanary;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Deliberately violates Rule B (BigDecimal outside FeeCalculator); excluded from the real scan. */
public class BigDecimalCanary {

    public long half(long amount) {
        return BigDecimal.valueOf(amount).divide(BigDecimal.TWO, 0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
