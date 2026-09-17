package dev.zerosum.fakeproviders.shared;

/**
 * The instrument tokens that drive deterministic outcomes (master §5.9, inspired by Stripe's test cards).
 *
 * <p>Only these tokens are special. Anything else succeeds, which keeps generated test data usable without having to
 * mint a magic token for every case.
 */
public final class MagicTokens {

    public static final String CARD_OK = "tok_card_ok";
    public static final String CARD_DECLINE_INSUFFICIENT_FUNDS = "tok_card_decline_insufficient_funds";
    public static final String CARD_PROCESSING_ERROR = "tok_card_processing_error";

    public static final String BANK_OK = "tok_bank_ok";
    public static final String BANK_RETURN_R01 = "tok_bank_return_R01";
    public static final String BANK_FAIL_ACCOUNT_CLOSED = "tok_bank_fail_account_closed";

    private MagicTokens() {
    }

    /** The decline code a charge token produces, or null when the charge succeeds. */
    public static String cardDeclineCode(String token) {
        return switch (token) {
            case CARD_DECLINE_INSUFFICIENT_FUNDS -> "insufficient_funds";
            // Treated as a definitive decline rather than a 5xx: a processing error that resolved differently on
            // retry would make FakeCard non-deterministic, and the uncertain-outcome path is exercised by the fault
            // knobs (S05-T03), which own non-determinism deliberately.
            case CARD_PROCESSING_ERROR -> "processing_error";
            default -> null;
        };
    }
}
