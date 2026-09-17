package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.PaymentInstrument;
import java.time.Clock;
import java.util.List;

/** The shared contract, run against FakeCard (S05-T06). */
class FakeCardContractTest extends PaymentInstrumentContractSuite {

    @Override
    protected PaymentInstrument instrument(ProviderHttp http) {
        return new FakeCardInstrument(http, List.of(WEBHOOK_SECRET, PREVIOUS_WEBHOOK_SECRET), Clock.systemUTC());
    }
}
