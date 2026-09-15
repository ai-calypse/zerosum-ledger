# Secrets and log redaction

> decision: D00-8 — [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs)
> Master: [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets), [#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting)

No real payment credentials exist anywhere in this project. The secrets are local, synthetic and regenerable.

## What the secrets are

| Secret | Variables | Introduced by |
|---|---|---|
| PostgreSQL superuser password (init scripts only) | `ZS_POSTGRES_SUPERUSER_PASSWORD` | S00 (D00-4) |
| Per-service owner and application role passwords | `ZS_<DB>_OWNER_DB_PASSWORD`, `ZS_<DB>_APP_DB_PASSWORD` | S00 (D00-4) |
| Verifier and stats role passwords | `ZS_VERIFIER_DB_PASSWORD`, `ZS_STATS_DB_PASSWORD` | S00 (D00-4) |
| API bearer tokens per role | appended to `.env.example` by S03 | S03 (D03-4) |
| Webhook HMAC secrets | appended to `.env.example` by S05 | S05 (S05-T11) |

[`.env.example`](../.env.example) is the complete index of variables. Each step that adds a variable appends it there with a purpose comment and a trace comment.

## Conventions

- Project variables use the `ZS_` prefix in upper snake case. Variables required by third-party images or the OTel SDK keep their standard names (`POSTGRES_PASSWORD`, `OTEL_SERVICE_NAME`, …).
- Secret-bearing names end in `_PASSWORD`, `_TOKEN`, `_TOKENS`, `_SECRET` or `_SECRETS`, so they are recognizable in reviews and scans.
- List values use the comma-separated format from master §5.11 (for example `ZS_WRITER_TOKENS=system:token,system:token`).
- **Required secrets have no defaults and fail fast.** Compose interpolates them as `${VAR:?message}`, so `docker compose config` fails with the message when one is missing, and PostgreSQL never starts with an empty password. Boot configuration references them as `${VAR}` without a fallback, so a service fails at startup.

## Local handling

1. Generate `.env` with `tools/dev/generate-env.sh`. It copies `.env.example`, replaces every `__GENERATE__` placeholder with `openssl rand -hex 32`, writes LF line endings with mode `0600`, and exits non-zero if any placeholder marker remains. It refuses to overwrite an existing `.env` unless run with `--force`.
2. `.env` is git-ignored; `.env.example` holds placeholders only.
3. Role passwords are read by the PostgreSQL init scripts only on an empty data volume. After regenerating `.env`, run `docker compose down -v` so the roles are recreated with the new values.
4. **If `.env` is committed by mistake:** `git rm --cached .env`, regenerate every value with `--force`, reset volumes, and record the incident in the S00 register (H.5).

## CI handling

- The CI e2e job generates a throwaway `.env` with the same script, masks the generated values, and discards them with the runner.
- No workflow needs repository secrets, so pull requests from forks work.
- Never print `docker compose config` in CI logs: it shows interpolated secrets.

## Demo VM and rotation

The optional hosted demo (S5, decided in S09) and secret rotation follow master [#secrets](zerosum_ledger_mvp_plan.md#secrets): GitHub Actions environment secrets written to a `0600` `.env` on the VM, `ZS_WEBHOOK_SECRETS=current,previous` for webhook rotation, and restart-to-rotate for tokens.

## Log-redaction guideline

**Never log:**

- bearer tokens or any `Authorization` header value;
- HMAC signatures or webhook secrets;
- database passwords, or JDBC URLs that carry credentials;
- environment or configuration dumps (actuator `env`/`configprops` are never exposed, D00-6).

**Also:**

- Request logging excludes headers by default; a header is logged only by explicit allow-list.
- Authentication and signature errors never echo the presented credential or signature; log the principal name or a fixed reason code instead.

**Required redaction tests** live next to the code that handles the secret. Each one runs a known sentinel secret through the code path with logging at its most verbose level, captures the log output, and asserts the sentinel does not appear:

| Secret | Test owner |
|---|---|
| API bearer tokens | [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03) |
| Webhook HMAC signatures and secrets | [docs/step_05_instruments_fake_providers.md#s05-t11](step_05_instruments_fake_providers.md#s05-t11) |

A `grep -F` of generated values against `docker compose logs` (S00-T09) is a smoke check only; it does not replace these tests. A secret scan runs before every release tag (S09-T07).
