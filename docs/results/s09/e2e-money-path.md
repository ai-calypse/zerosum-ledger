# S09 — the first end-to-end test: the money path against the running stack

Date: 2026-09-17. Branch: `step/09-e2e-hardening` (test first landed on `step/09-architecture`, merged `85a1882`).

## What was run

`infra/tests/src/test/java/dev/zerosum/infra/MoneyPathE2ETest.java`, tagged `@Tag("e2e")`, against the seven-container
Compose stack (postgres, kafka, otel-lgtm, toxiproxy, order-service, ledger-service, instrument-service,
fake-providers) started with `docker compose up -d --build --wait`.

```
./gradlew :infra:tests:e2eTest --rerun-tasks
tests=1 failures=0 skipped=0 at 2026-09-17T05:58:25.486Z
  an accepted money order reaches the ledger, balances to zero, and links back to its source (0.718s)
```

`--rerun-tasks` because an up-to-date pass proves only that nothing changed.

## What it asserts

1. A money order is accepted by order-service over HTTP with an idempotency key (201).
2. It arrives in the ledger through the outbox and Kafka — the test polls balances until applied, so the whole
   asynchronous path is exercised rather than mocked.
3. The applied balances match: rider receivable `+2500`, driver payable `-2000`.
4. **Zero-sum, read back from the ledger.** The changelog delta the ledger recorded for each of the three entities is
   fetched and summed: `+2500`, `-2000`, `-500` → `0`.
5. The changelog row for this order links back to the money order id **and** the idempotency key it arrived under
   (M6(a)).
6. `GET /v1/invariants` reports `consistent: true` with `unresolved_quarantined_count: 0`.

## A defect in the first version of this test

The zero-sum assertion originally read:

```java
assertEquals(0, riderSigned + driverSigned + (-PLATFORM_FEE_MINOR), "…sum to zero across currencies");
```

That **could not fail**. `riderSigned` and `driverSigned` had been asserted equal to the test's own constants two
lines earlier, and `-PLATFORM_FEE_MINOR` was a hard-coded field never read back from the ledger — so the sum was
three known constants. Worse, it meant **the platform entry was never verified against the ledger at all**, since
`platform:main` is shared across runs and has no fixed balance to assert. The message also said "across currencies"
for a single-currency order.

It now reads each of the three deltas back from the changelog by order id (paging forward, because a shared entity
accumulates rows) and sums those. The assertion is derived from what the ledger stored, so it can fail.

This is the same trap recorded in S07, where an alert firing test "passed" because the rule was already firing: **a
test that passes because its condition was already true proves nothing.** It was caught in review, not by the test.

## Credentials

Read from `.env` rather than the environment. CI generates `.env`, starts Compose, then runs Gradle **without
exporting anything**, so a test using `System.getenv` would have passed on a developer's shell and failed in CI.

## What this does not cover

One test is not a suite.

- **No crash or restart coverage.** M4(a) still needs a container SIGKILL, which this does not do.
- **No provider path.** instrument-service has no API, so nothing end-to-end reaches FakeCard or FakeBank.
- **No duplicate delivery.** The order is posted once; M5(a) still rests on `DuplicateDeliveryIT`.
- **Not the M6(c) audit walk.** The test makes two read calls and already holds the order id from its own POST; it
  never re-fetches the order, so it does not automate the three-call walk. That remains hand-executed (S04 Case E).
- **The e2e job runs on a schedule or manual dispatch only**, never on a pull request, so a PR can still turn the
  money path red without CI noticing.
- `failOnNoDiscoveredTests = false` still means an empty tag filter would go green. The tag is no longer empty, but
  the guard is absent.
