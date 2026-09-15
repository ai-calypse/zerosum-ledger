# ADR-0009 — Seeded generative JUnit tests instead of a property-based testing library

- **Status:** Accepted
- **Date:** 2026-09-15
- **Decision owner:** S01 (D01-10) — [docs/step_01_domain_contracts.md#decisions-and-outputs](../step_01_domain_contracts.md#decisions-and-outputs)

## Context

ZeroSum Ledger needs generative evidence:

- M1(a): thousands of random orders through the validator;
- S02: random apply sequences with duplicates and reordering;
- S08: seeded workloads whose seeds appear in results files (M13 c).

The obvious Java property-based testing library, jqwik, is unsuitable ([master §2.5](../zerosum_ledger_mvp_plan.md#platform-limits)):

- it is in pure maintenance mode;
- since 1.10 it carries an "Anti-AI Usage Clause", and this project is built with coding agents;
- it targets JUnit Platform 1.x, while Spring Boot 4.1 manages JUnit 6.

## Decision

Generative tests are plain JUnit tests driven by seeded generators in the `libs/money` test fixtures (`dev.zerosum.money.generate`).

1. **Random source.** `RandomGeneratorFactory.of("L64X128MixRandom").create(seed)`, with the algorithm named once in `SeededExtension.ALGORITHM`. `RandomGenerator.getDefault()`, `Math.random` and `ThreadLocalRandom` are never used, because their algorithm or seeding can change between JDK releases or runs. Generators draw only from ordered collections.
2. **Seed selection.** `SeededExtension` (`@ExtendWith`) takes the seed from the environment variable `ZS_TEST_SEED` if it is set, and otherwise draws a fresh one from `SecureRandom`. CI never sets the variable, so every CI run explores a new corpus.
3. **Seed line.** Before each test the extension prints exactly one line:

   ```text
   ZS-SEED test=<fully.qualified.TestClass>#<method> seed=<long>
   ```

   S08 results tooling parses this format.
4. **Failure message.** A failing test's message is extended with the seed line and a replay command:

   ```text
   replay: ZS_TEST_SEED=<seed> ./gradlew <project test task> --tests '<TestClass>.<method>' --rerun
   ```

5. **Determinism evidence.** `GeneratorDeterminismTest` prints `ZS-DIGEST seed=<long> sha256=<hex>` over a canonical rendering of every generator family. The same seed must reproduce the same digest.
6. **Oracle independence.** Invalid orders carry a label chosen by their mutation and confirmed by independent checks inside the generator, never by calling the validator under test.

## Alternatives considered

- **jqwik:** rejected for the reasons above.
- **junit-quickcheck:** unmaintained, and built for JUnit 4.
- **Unseeded random loops:** rejected. A failure couldn't be reproduced, which violates the "failing seed becomes a regression test" rule ([master §8.10](../zerosum_ledger_mvp_plan.md#test-failure-handling)).

## Consequences

- **No automatic shrinking.** A failing case is minimized by hand.
- **Failing seeds.** A seed that fails in CI becomes a committed regression test pinned to that seed *before* the fix. The test is never re-run until green.
- **Replay.** A failure is reproduced on any machine with the printed command. Changing the algorithm, or the order in which a generator draws values, changes the corpus for a given seed; such a change needs this ADR to be superseded, because recorded seeds in results files would stop replaying.
- **Consumers.** Services and `tools/simulator` depend on the generators through `testFixtures(project(":libs:money"))`, and the fixtures never ship in the `libs/money` runtime jar.
