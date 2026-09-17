package dev.zerosum.instrument.core;

import dev.zerosum.instrument.adapter.FakeCardInstrument;

/**
 * A deliberate violation of the boundary rule (S05-T06, M7(b)).
 *
 * <p>It lives in the core package and names an adapter, which is exactly what the rule forbids. Its only job is to
 * fail the rule: a rule that has never been seen to fire is not known to work.
 */
@SuppressWarnings("unused")
public class CoreImportingAdapterCanary {

    private final Class<?> adapter = FakeCardInstrument.class;
}
