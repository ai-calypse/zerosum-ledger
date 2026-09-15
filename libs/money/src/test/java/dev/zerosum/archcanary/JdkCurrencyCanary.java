package dev.zerosum.archcanary;

import java.util.Currency;

/** Deliberately violates Rule E (JDK currency data); excluded from the real scan. */
public class JdkCurrencyCanary {

    public int digits(String code) {
        return Currency.getInstance(code).getDefaultFractionDigits();
    }
}
