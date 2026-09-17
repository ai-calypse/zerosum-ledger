package dev.zerosum.instrument.core;

import java.time.LocalDate;

/**
 * A provider's settlement report for one simulated day (D05-1, §0.3 C23).
 *
 * <p><strong>Declared, not implemented.</strong> S05 defines only what the interface method needs; the report's
 * lines, totals and discrepancy knobs belong to S06 (D06-1), which extends this through the change procedure. Adding
 * speculative fields here would freeze a shape S06 has not designed yet.
 */
public record SettlementReport(ProviderId provider, LocalDate reportDate) {

    public SettlementReport {
        if (provider == null || reportDate == null) {
            throw new IllegalArgumentException("a settlement report needs a provider and a date");
        }
    }
}
