# One origin for the system, and the webhook loop observed live

Date: 2026-09-17. Tree: `main` after the six-agent wave (S05-T03/T07/T08/T09/T11, S06, M13 harness).

## Why a proxy rather than a CORS allowance

The Ledger Explorer is served by ledger-service under `Content-Security-Policy: connect-src 'self'`. That policy is
deliberate — a page that ever rendered hostile input would have nowhere to send a reader token — and it also means
the page structurally cannot call instrument-service on port 8083.

The two obvious escapes both widen a trust boundary (TB1): loosen the CSP, or open CORS on the money services. A
reverse proxy removes the problem instead. Every service answers under one origin, so `connect-src 'self'` stays
exactly as strict while the page can reach all of them.

Opt-in, in `docker-compose.demo.yml`. The default topology still keeps fake-providers off the host entirely.

## Routes, verified

`make up-proxy`, then each route probed:

| Route | Result |
|---|---|
| `/healthz` | **200** (answered by nginx itself, so the healthcheck reports on the proxy) |
| `/explorer.html` | **200** |
| `/v1/freshness` | **401** — routed to ledger-service, which correctly demands a reader token |
| `/instruments/actuator/health` | **200** |
| `/providers/actuator/health` | **200** |
| `/orders/actuator/health` | **200** |
| `/grafana/api/health` | **200** |

### The trap that made three of them 404

The first configuration used `proxy_pass $upstream/;`, expecting the trailing slash to strip the location prefix.
**It does not.** When `proxy_pass` is given a variable, nginx passes the original URI unchanged and ignores the
replacement path; the trailing-slash rewrite only applies to the literal form. The variable form is needed here so
that `resolver` re-resolves container addresses rather than caching one at startup.

The symptom was a 404 with a **95-byte body** — nginx's own error page, not a service response, so the request never
reached the upstream at all. Every upstream answered 200 directly on its own port at the same moment. Fixed with an
explicit `rewrite ^/prefix/(.*)$ /$1 break;` on each prefixed location.

## The webhook loop, observed live for the first time

Neither S05-T03 (the signed sender) nor S05-T11 (the receiver) had ever run in a deployment — both agents said so
plainly, and this project has produced five defects that only a running stack revealed.

A payout was driven through FakeBank with the `tok_bank_return_R01` token, so the simulated banking day produced a
settlement and then a return:

```
instruments.provider_events  = 2
  fakebank payout.returned  attempt=unmatched
  fakebank payout.settled   attempt=unmatched
fakeproviders.provider_events
  payout.returned delivered=true
  payout.settled  delivered=true
```

So: fake-providers signed both events and marked them delivered only after a 2xx, and instrument-service verified the
signatures and recorded both. **Signing, delivery, HMAC verification, and record-on-receipt are verified in a real
deployment.**

## What this run does *not* show, and why

**`attempt=unmatched` on both events is expected here, and it is a limit of the probe rather than a defect.** The
receiver's documented `unmatched` disposition means "no attempt has that client reference" — correct, because the
payout was driven *directly at FakeBank* rather than created by a payout run. There is no other way today: **S05-T10
payout runs do not exist**, so nothing in the system can create a payout attempt.

The consequence is precise:

- **Verified live:** signature verification, 300 s tolerance, delivery, dedupe by provider event id, record-on-receipt.
- **Not verified live:** webhook-driven *transitions*. The `AheadOfState` path — a `payout.returned` arriving while an
  attempt is still `PENDING`, resolved by lookup and walked through the intermediate states — has integration
  evidence (`WebhookReceiverIT`) but no deployment evidence, because no payout attempt can exist yet.

That gap closes when S05-T10 lands, not before. It is recorded here rather than left for a reader to infer from a
passing test count.
