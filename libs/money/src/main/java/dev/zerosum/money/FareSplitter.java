package dev.zerosum.money;

/**
 * Splits a fare into driver share and platform commission so the parts always sum exactly to the fare (D01-4).
 * Returns amounts only; turning them into signed entries per ADR-0003 is the caller's job.
 */
public final class FareSplitter {

    private FareSplitter() {
    }

    /** Driver share and platform commission of one fare or adjustment. */
    public record Split(Money driverShare, Money platformCommission) {

        public Split {
            if (!driverShare.currency().equals(platformCommission.currency())) {
                throw new CurrencyMismatchException(driverShare.currency(), platformCommission.currency());
            }
        }

        public Money total() {
            return driverShare.plus(platformCommission);
        }

        Split minus(Split other) {
            return new Split(driverShare.minus(other.driverShare), platformCommission.minus(other.platformCommission));
        }
    }

    /**
     * Commission is the HALF_EVEN-rounded share of the fare; the driver gets {@code fare − commission}, so any
     * rounding remainder goes to the driver (D01-4). Negative fares are allowed and split symmetrically.
     *
     * @param commissionBps 0 to 10 000 inclusive
     */
    public static Split split(Money fare, long commissionBps) {
        if (commissionBps < 0 || commissionBps > FeeCalculator.BPS_SCALE) {
            throw new IllegalArgumentException("commission must be 0..10000 bps: " + commissionBps);
        }
        Money commission = Money.of(FeeCalculator.roundedShare(fare.amountMinor(), commissionBps), fare.currency());
        return new Split(fare.minus(commission), commission);
    }

    /**
     * The adjustment from {@code oldFare} to {@code newFare}: {@code split(newFare) − split(oldFare)} component-wise,
     * never {@code split(newFare − oldFare)}. After any number of adjustments the cumulative shares equal a direct
     * split of the final fare; splitting each delta could drift by one minor unit per adjustment (D01-4).
     */
    public static Split adjustment(Money oldFare, Money newFare, long commissionBps) {
        if (!oldFare.currency().equals(newFare.currency())) {
            throw new CurrencyMismatchException(oldFare.currency(), newFare.currency());
        }
        return split(newFare, commissionBps).minus(split(oldFare, commissionBps));
    }
}
