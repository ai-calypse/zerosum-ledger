// decision: D06-2 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
package dev.zerosum.instrument.recon;

/**
 * What kind of disagreement a break records (D06-2, M11(b)).
 *
 * <p><strong>The type is the deliverable.</strong> A matcher that classified every disagreement as a single generic
 * "mismatch" would pass any test that merely counts breaks, while telling an operator nothing about what to do — a
 * missing line is chased with the provider, an amount mismatch is a fee or rounding question, and a duplicate line is
 * money claimed twice. Each of the three discrepancies the simulator can inject therefore maps to exactly one value
 * here, and the tests assert the value rather than the count.
 */
public enum BreakType {

    /** The provider settled something we have no attempt for at all. */
    MISSING_IN_LEDGER(BreakStatus.UNEXPLAINED),

    /**
     * We hold a successful attempt the report does not mention — what the {@code report_missing_line} knob injects.
     *
     * <p>{@code OPEN} rather than {@code UNEXPLAINED}: within a single run a line that is genuinely late and a line
     * that is genuinely lost look identical, and only a later cycle can tell them apart. Calling it unexplained here
     * would raise an alert every time a capture settled near the day boundary.
     */
    MISSING_IN_REPORT(BreakStatus.OPEN),

    /** Both sides know the movement and disagree about the amount — what {@code report_off_by_one} injects. */
    AMOUNT_MISMATCH(BreakStatus.UNEXPLAINED),

    /** The same provider reference settled twice — what {@code report_duplicate_line} injects. */
    DUPLICATE_LINE(BreakStatus.UNEXPLAINED),

    /** A refund line matched a charge attempt, or the reverse. */
    KIND_MISMATCH(BreakStatus.UNEXPLAINED),

    CURRENCY_MISMATCH(BreakStatus.UNEXPLAINED),

    /** The line's attempt has not resolved yet (F7). Not a mismatch: nobody has claimed a final outcome. */
    TIMING(BreakStatus.OPEN),

    /** The provider settled money for an attempt we believe failed, which is worse than either side missing one. */
    LINE_WITHOUT_SUCCESS(BreakStatus.UNEXPLAINED),

    /** The report disagrees with its own arithmetic. Report-level, and it blocks settlement entirely. */
    REPORT_ARITHMETIC(BreakStatus.UNEXPLAINED);

    private final BreakStatus defaultStatus;

    BreakType(BreakStatus defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public BreakStatus defaultStatus() {
        return defaultStatus;
    }

    /** Whether a break of this type must stop the settlement event being emitted. */
    public boolean blocksSettlement() {
        return this == REPORT_ARITHMETIC;
    }

    /** How settled a break is. A status change appends a row rather than updating one (D02-2). */
    public enum BreakStatus {
        /** Cannot yet be called wrong; a later settlement cycle may resolve it. */
        OPEN,
        /** A real disagreement with no benign explanation left. */
        UNEXPLAINED
    }
}
