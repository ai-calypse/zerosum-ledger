// decision: D06-2 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
package dev.zerosum.instrument.recon;

import dev.zerosum.instrument.core.SettlementReport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares a provider's settlement report against our own attempts (D06-2, M11(b)).
 *
 * <p>A pure function: no Spring, no database, no clock, no network. Report and candidates in, matches and typed breaks
 * out. That is what makes the rules table-testable, and the rules are the part of reconciliation that has to be right
 * — a reconciler that reports "no breaks" because it compared nothing is worse than no reconciler, since it converts
 * an absence of checking into a positive claim of correctness.
 *
 * <p><strong>Amounts are compared as magnitudes.</strong> A report states a refund as negative gross because it
 * reduces the day's takings; an attempt stores a positive magnitude and carries direction in its {@code kind}. The
 * signs are two different conventions for the same money, so the kinds are compared directly and the amounts by
 * absolute value.
 */
public final class SettlementMatcher {

    private SettlementMatcher() {
    }

    /**
     * An attempt the report could be talking about.
     *
     * @param status the D05-5 attempt status; it decides whether an unmatched line is a timing candidate or a break
     */
    public record Candidate(String attemptId, String kind, String currency, long amountMinor, String status,
            String providerRef) {

        /** The provider confirmed this money moved. Only these are expected to appear in a report. */
        public boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }

        /**
         * No final outcome yet, so the provider may legitimately be ahead of us (F7). {@code CREATED} counts: the
         * submission may already be on the wire.
         */
        public boolean inFlight() {
            return switch (status) {
                case "CREATED", "SUBMITTING", "PENDING", "UNKNOWN" -> true;
                default -> false;
            };
        }
    }

    /** One disagreement. Amounts are nullable because a break on one side has no amount on the other. */
    public record Break(BreakType type, BreakType.BreakStatus status, String providerRef, String clientReference,
            String currency, Long reportGrossMinor, Long ledgerGrossMinor, String attemptId, String detail) {

        static Break of(BreakType type, SettlementReport.Line line, Candidate candidate, String detail) {
            return new Break(type, type.defaultStatus(), line == null ? null : line.providerRef(),
                    line != null ? line.clientReference() : candidate == null ? null : candidate.attemptId(),
                    line == null ? candidate == null ? null : candidate.currency() : line.currency(),
                    line == null ? null : line.grossMinor(),
                    candidate == null ? null : candidate.amountMinor(),
                    candidate == null ? null : candidate.attemptId(), detail);
        }
    }

    /** @param linesMatched lines that agreed with an attempt on kind, currency and amount */
    public record Result(int linesMatched, List<Break> breaks) {

        /** A report that disagrees with its own arithmetic must not be booked (D06-3). */
        public boolean blocksSettlement() {
            return breaks.stream().map(Break::type).anyMatch(BreakType::blocksSettlement);
        }
    }

    /**
     * Matches every line and reports what disagreed.
     *
     * @param candidates          charge and refund attempts for this provider in the report's window, plus any still
     *                            open from earlier cycles
     * @param alreadySettledRefs  provider references matched by earlier runs, so the same capture reported on two
     *                            different days is a duplicate rather than a second legitimate settlement
     */
    public static Result match(SettlementReport report, List<Candidate> candidates, Set<String> alreadySettledRefs) {
        List<Break> breaks = new ArrayList<>(arithmeticBreaks(report));

        Map<String, Candidate> byProviderRef = new HashMap<>();
        Map<String, Candidate> byAttemptId = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate.providerRef() != null) {
                byProviderRef.putIfAbsent(candidate.providerRef(), candidate);
            }
            byAttemptId.putIfAbsent(candidate.attemptId(), candidate);
        }

        Set<String> seenRefs = new HashSet<>();
        Set<String> matchedAttempts = new LinkedHashSet<>();
        int matched = 0;

        for (SettlementReport.Line line : report.lines()) {
            // Checked before matching, so the second occurrence is the duplicate and the first still settles normally.
            // Reversing this would turn one duplicated capture into two breaks and no match.
            if (!seenRefs.add(line.providerRef()) || alreadySettledRefs.contains(line.providerRef())) {
                Candidate duplicated = byProviderRef.get(line.providerRef());
                breaks.add(Break.of(BreakType.DUPLICATE_LINE, line, duplicated,
                        "provider reference " + line.providerRef() + " settles more than once"));
                if (duplicated != null) {
                    // The attempt IS in the report — twice, or already on an earlier day. Leaving it unmarked would
                    // additionally report it missing, turning one duplicated capture into two unrelated breaks.
                    matchedAttempts.add(duplicated.attemptId());
                }
                continue;
            }

            // Provider reference first, client reference second (D06-2): the provider's own id is the stronger key,
            // and a report that lost our reference can still be matched by it.
            Candidate candidate = byProviderRef.get(line.providerRef());
            if (candidate == null) {
                candidate = byAttemptId.get(line.clientReference());
            }
            if (candidate == null) {
                breaks.add(Break.of(BreakType.MISSING_IN_LEDGER, line, null,
                        "the report settles money with no matching attempt"));
                continue;
            }

            matchedAttempts.add(candidate.attemptId());

            if (!candidate.kind().equals(line.kind())) {
                breaks.add(Break.of(BreakType.KIND_MISMATCH, line, candidate,
                        "the report calls this a " + line.kind() + "; the attempt is a " + candidate.kind()));
                continue;
            }
            if (!candidate.currency().equals(line.currency())) {
                breaks.add(Break.of(BreakType.CURRENCY_MISMATCH, line, candidate,
                        "the report settles " + line.currency() + "; the attempt is " + candidate.currency()));
                continue;
            }
            if (candidate.inFlight()) {
                // The provider is simply ahead of us. Classifying this as a mismatch would raise an alert on every
                // capture that settled while its webhook was still in flight.
                breaks.add(Break.of(BreakType.TIMING, line, candidate,
                        "the attempt is still " + candidate.status() + ", so no outcome is claimed yet"));
                continue;
            }
            if (!candidate.succeeded()) {
                breaks.add(Break.of(BreakType.LINE_WITHOUT_SUCCESS, line, candidate,
                        "the provider settled this, but the attempt is " + candidate.status()));
                continue;
            }
            if (Math.abs(line.grossMinor()) != candidate.amountMinor()) {
                breaks.add(Break.of(BreakType.AMOUNT_MISMATCH, line, candidate,
                        "the report settles " + line.grossMinor() + "; the attempt is " + candidate.amountMinor()));
                continue;
            }
            matched++;
        }

        // The other direction: money we believe moved that the report never mentions.
        for (Candidate candidate : candidates) {
            if (candidate.succeeded() && !matchedAttempts.contains(candidate.attemptId())) {
                breaks.add(Break.of(BreakType.MISSING_IN_REPORT, null, candidate,
                        "the attempt succeeded, but the report has no line for it"));
            }
        }

        return new Result(matched, List.copyOf(breaks));
    }

    /**
     * The report's own arithmetic: {@code net + fee = gross} per currency, and totals equal to the sum of the lines.
     *
     * <p>Checked here rather than in {@link SettlementReport} so a self-contradictory report is a typed break instead
     * of an exception — a report is untrusted input, and its disagreeing with itself is a finding, not a crash.
     */
    private static List<Break> arithmeticBreaks(SettlementReport report) {
        List<Break> breaks = new ArrayList<>();
        Map<String, long[]> summed = new HashMap<>();
        for (SettlementReport.Line line : report.lines()) {
            long[] sums = summed.computeIfAbsent(line.currency(), currency -> new long[2]);
            // addExact: a wrapped sum would compare equal to a plausible total and book money that does not exist.
            sums[0] = Math.addExact(sums[0], line.grossMinor());
            sums[1] = Math.addExact(sums[1], line.feeMinor());
        }

        for (SettlementReport.Totals totals : report.totals()) {
            if (Math.subtractExact(totals.grossMinor(), totals.feeMinor()) != totals.netMinor()) {
                breaks.add(arithmetic(totals.currency(), totals.grossMinor(),
                        "net + fee must equal gross: " + totals.netMinor() + " + " + totals.feeMinor()
                                + " != " + totals.grossMinor()));
            }
            long[] sums = summed.getOrDefault(totals.currency(), new long[2]);
            if (sums[0] != totals.grossMinor() || sums[1] != totals.feeMinor()) {
                breaks.add(arithmetic(totals.currency(), totals.grossMinor(),
                        "totals disagree with the sum of the lines: reported gross " + totals.grossMinor() + " fee "
                                + totals.feeMinor() + ", lines sum to gross " + sums[0] + " fee " + sums[1]));
            }
        }

        // A currency present in the lines but absent from the totals settles nothing and would be booked as missing
        // money rather than as a disagreement.
        for (Map.Entry<String, long[]> entry : summed.entrySet()) {
            if (report.totalsFor(entry.getKey()).isEmpty()) {
                breaks.add(arithmetic(entry.getKey(), entry.getValue()[0],
                        "the report has " + entry.getKey() + " lines but no " + entry.getKey() + " totals"));
            }
        }
        return breaks;
    }

    private static Break arithmetic(String currency, long gross, String detail) {
        return new Break(BreakType.REPORT_ARITHMETIC, BreakType.REPORT_ARITHMETIC.defaultStatus(), null, null,
                currency, gross, null, null, detail);
    }
}
