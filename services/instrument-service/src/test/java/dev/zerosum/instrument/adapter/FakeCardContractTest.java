package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.PaymentInstrument;

/** The shared contract, run against FakeCard (S05-T06). */
class FakeCardContractTest extends PaymentInstrumentContractSuite {

    @Override
    protected PaymentInstrument instrument(ProviderHttp http) {
        return new FakeCardInstrument(http);
    }
}
