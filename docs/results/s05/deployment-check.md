# Deployment check — S05-T03 and S05-T08 against a running stack

Date: 2026-09-17. Tree: `main` after merging S05-T08, S05-T03 and the M13 harness.

Everything in S05-T03 and S05-T08 had been proven under Testcontainers and never once in a deployment. This session
has already found four defects that only a running stack could reveal — a service calling itself on `localhost:8090`,
tokens absent from a Compose block, a service whose Compose block contradicted its own YAML, and a stack that would
not start at all — so the suites passing is not the same as the thing working.

## What was run

`./gradlew assemble && docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --wait`, then
probes against the published ports.

All seven containers reported healthy: postgres, kafka, otel-lgtm, order-service, ledger-service,
instrument-service, fake-providers.

| Check | Result |
|---|---|
| `./gradlew :infra:tests:e2eTest --rerun-tasks` (money path) | **1 test, 0 failures** |
| `GET /v1/payment-attempts/{id}` with no token | **401** |
| `GET /v1/payment-attempts/{id}` with a reader token | **404** (authenticated, attempt does not exist) |
| `GET /admin/truth` with no token | **401** |
| `GET /admin/truth` with a **reader** token | **403** |
| `GET /admin/truth` with the admin token | **200** |
| `PUT /admin/faults/fakecard` with no token | **401** |
| `PUT /admin/faults/fakecard` with the admin token | **200** |
| `GET /v1/invariants` | `consistent: true`, no I2/I3/I4 violations |

The **403** is the one worth naming. Earlier today fake-providers' Compose block passed only `ZS_ADMIN_TOKEN` while
its `application.yml` read all three, so a recognised-but-wrong-role caller would have been answered 401 ("no token")
instead of 403 ("wrong role"). This row is that fix verified where it actually matters, rather than in a test that
sets its own properties.

## A probe that silently did not run

The first pass reported this:

```
(eval):5: no matches found: http://127.0.0.1:8090/admin/truth?entity_id=rider:R1
  truth no token ->   (expect 401)
```

zsh glob-expanded the unquoted `?`, so that curl never executed. The status printed **blank** — and a blank sitting
next to "(expect 401)" reads like a pass at a glance rather than an error. It was re-run with the URL quoted, which
is where the 401 and 403 above come from.

Same family as `BUILD SUCCESSFUL` with zero tests selected: the absence of a failure is not the presence of a result.

## What this does not cover

- **The webhook sender is inert.** `ZS_WEBHOOK_RECEIVER_URL` is deliberately unset because the receiver is S05-T11,
  so provider events accumulate undelivered. Nothing here exercises signing or redelivery in the stack.
- **No attempt has ever been created in the deployed stack.** The collection policy that creates them is S05-T09;
  the `404` above is an authenticated lookup of an id that does not exist, not a read of a real attempt.
- **Fault knobs were set, not exercised.** `PUT /admin/faults` returned 200; no request was then driven through the
  injected latency or failure rates.
