package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.PaymentInstrument;
import java.time.Duration;

/** The shared contract, run against FakeBank (S05-T06). */
class FakeBankContractTest extends PaymentInstrumentContractSuite {

    @Override
    protected PaymentInstrument instrument(ProviderHttp http) {
        return new FakeBankInstrument(http, Duration.ofSeconds(60));
    }
}
