package dev.zerosum.instrument.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A provider's settlement report for one simulated day (D05-1, D06-1, §0.3 C23).
 *
 * <p>S05 declared this type with only a provider and a date, leaving its content to S06; the lines and totals below
 * are that content (D06-1), and the discrepancy knobs that corrupt them live in the provider, not here.
 *
 * <p><strong>This type validates shape, never arithmetic.</strong> A report is untrusted input even though it comes
 * from a simulated provider, and a report whose totals disagree with its own lines is exactly the condition
 * reconciliation exists to detect. Enforcing {@code net + fee = gross} in the constructor would make such a report
 * impossible to represent, so the reconciler would throw where it is supposed to raise a typed break — turning a
 * finding into a crash.
 *
 * @param reportId the provider's own identifier for the report, which is also part of the settlement event's
 *                 currency-qualified identity (§0.3 C6)
 */
public record SettlementReport(ProviderId provider, LocalDate reportDate, String reportId,
        List<Line> lines, List<Totals> totals) {

    public SettlementReport {
        if (provider == null || reportDate == null || reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("a settlement report needs a provider, a date and a report id");
        }
        if (lines == null || totals == null) {
            throw new IllegalArgumentException("a settlement report needs lines and totals, even when both are empty");
        }
        lines = List.copyOf(lines);
        totals = List.copyOf(totals);
    }

    /**
     * One settled movement, as the provider reports it.
     *
     * @param providerRef      the provider's own reference, the primary match key
     * @param clientReference  the attempt id we sent, the fallback match key
     * @param kind             {@code CHARGE} or {@code REFUND}; a line matching an attempt of the other kind is a break
     * @param grossMinor       negative on a refund line, which reduces the day's gross and returns no fee
     */
    public record Line(String providerRef, String clientReference, String kind, String currency,
            long grossMinor, long feeMinor) {
    }

    /** The day's totals for one currency. One settlement event is emitted per entry (§0.3 C6). */
    public record Totals(String currency, long grossMinor, long feeMinor, long netMinor) {
    }

    public Optional<Totals> totalsFor(String currency) {
        return totals.stream().filter(entry -> entry.currency().equals(currency)).findFirst();
    }
}
