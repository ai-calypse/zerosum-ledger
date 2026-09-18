# S08-M4a — kill -9 of order-service between commit and publish

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | S08-M4a (master §3.1 M4 (a); fault F1 shape, single deterministic kill) |
| Type | fault injection |
| Owning step and task | S08 (closes the container-level test S03-T05 deferred) |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `5d9d751969488027509267d7a514026511c75a43`; working tree clean (0 changes). Branched from `main` at `08f9c7f`. |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. Runtime-reported: order-service image `sha256:6049b4bf815a8bdadefcb9f4a050723e877d7e63dedb4cd08ac4d7d31b899fd7`, kafka `apache/kafka:4.3.1@sha256:77e3df90…6532837`, test JVM OpenJDK 25.0.4.1 (Eclipse Adoptium) |
| Seeds | none (non-generative evidence: fixed order bodies, random run ids) |
| Hardware | Apple M4 (Mac16,12), 10 cores, 24.0 GiB RAM, macOS 26.5.2, aarch64 |

**Image vs SHA.** The order-service image was built 2026-09-17T04:20Z, before `08f9c7f`. `git log` shows no change
to `services/order-service` or to any library it links (`libs/outbox`, `libs/money`, `libs/contracts`, `libs/auth`)
after that build; the two later `libs/` commits touch `libs/evidence` and `libs/testsupport`, which order-service does
not ship. The running code therefore matches the SHA for everything this test exercises.

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | Docker 29.8.0 (Desktop), Compose v5.5.1 |
| Docker VM CPUs / memory | 10 CPUs, 7.75 GiB |
| Emulated images (non-native architecture) | none (all aarch64) |
| Other load on the host during the run | not controlled: other agent sessions may have been building concurrently. The stack was the full demo stack (8 containers). |

## 5. Scenario, workload and seeds

Test: [`infra/tests/src/test/java/dev/zerosum/infra/recovery/OrderPublishAfterCrashE2ETest.java`](../../../infra/tests/src/test/java/dev/zerosum/infra/recovery/OrderPublishAfterCrashE2ETest.java),
against the running stack (`docker-compose.yml` + `docker-compose.demo.yml`). 5 repetitions × 20 orders each. Per repetition:

1. `docker pause` Kafka. This holds the commit-to-publish window open: the relay polls every 50 ms, so without it a
   kill would land in the window only by luck.
2. POST 20 COMMERCE orders (one fresh rider, one fresh driver, one order group); all return 201.
3. **Precondition, read from the orders database:** all 20 have an outbox row with `published_at IS NULL`.
4. `docker kill -s KILL` order-service; exit code asserted `137`.
5. `docker unpause` Kafka, wait for the broker to answer, then 3 s more. Count how many of the 20 the ledger has
   already applied **without the relay ever having marked them** (see "applied before restart").
6. `docker start` order-service. Poll the outbox until all 20 rows are `published_at` non-null.
7. Wait for the ledger to apply all 20; read `applied_orders`, the rider and driver balances, `/v1/invariants`, and
   count the copies of this group's orders on `payments.money-orders.v1`.

The riders have no registered instrument, so instrument-service ignores the orders (`no_usable_instrument`) and the
path under test is only order-service → outbox → Kafka → ledger.

**Clocks.** `published_at` is PostgreSQL's clock; the container's `StartedAt` and the `docker logs --timestamps`
stamp on Spring's "Started OrderServiceApplication" line are docker's. All three are the Docker VM's clock.

## 6. Exact commands

```sh
# stack already up: docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --wait
ln -s <main checkout>/.env .env          # the worktree has no .env of its own; it is gitignored
./gradlew :infra:tests:e2eTest --tests '*OrderPublishAfterCrashE2ETest' --rerun \
  -Dzs.crash.repetitions=5 -Dzs.crash.orders=20
```

## 7. Raw data

- [raw/m4a-crash-recovery.json](raw/m4a-crash-recovery.json) — per-repetition values with every timestamp
- [raw/m4a-crash-recovery.txt](raw/m4a-crash-recovery.txt) — the table the test printed, with its provenance block
- [raw/m4a-TEST-OrderPublishAfterCrashE2ETest.xml](raw/m4a-TEST-OrderPublishAfterCrashE2ETest.xml) — JUnit XML:
  `tests="1" skipped="0" failures="0" errors="0"`, 97.96 s

## 8. Results

Regenerate with `python3 docs/results/s08/raw/aggregate.py`.

| Rep | Unpublished at kill | Applied before restart | Spring startup (s) | Publish − container start (ms) | Publish − app Started (ms) | `applied_orders` rows | Copies on topic | Rider receivable | Invariants |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 20 / 20 | 1 | 2.289 | 3,822 | 134 | 20 | 21 | 20,000 | consistent |
| 2 | 20 / 20 | 4 | 2.283 | 3,751 | 126 | 20 | 24 | 20,000 | consistent |
| 3 | 20 / 20 | 4 | 2.249 | 3,765 | 126 | 20 | 24 | 20,000 | consistent |
| 4 | 20 / 20 | 4 | 2.258 | 3,750 | 148 | 20 | 24 | 20,000 | consistent |
| 5 | 20 / 20 | 4 | 2.257 | 3,778 | 132 | 20 | 24 | 20,000 | consistent |
| **Median** | 20 | 4 | 2.258 | **3,765** | **132** | 20 | 24 | 20,000 | |
| **Range (min–max)** | 20–20 | 1–4 | 2.249–2.289 | 3,750–3,822 | 126–148 | 20–20 | 21–24 | 20,000 | |

"Publish" is the latest `published_at` of the 20 rows, i.e. the time the LAST order was published.

**Nothing lost.** 100 of 100 orders committed-and-unpublished at the kill were published after restart and applied.

**Nothing applied twice, against duplicates that really arrived (M4 (c), container level).** In every repetition some
orders reached the ledger *while order-service was dead*: produce requests the killed producer had already written into
the paused broker's socket were appended once the broker resumed. Their outbox rows were still unmarked, so the
restarted relay sent them again — the topic holds 20 + k copies for k orders applied before restart, in every
repetition (21 = 20 + 1, 24 = 20 + 4). The ledger still holds exactly 20 `applied_orders` rows per run, and the rider
receivable is exactly 20 × 1,000, not more.

## 9. Gate or threshold compared against

[docs/zerosum_ledger_mvp_plan.md#must-have](../../zerosum_ledger_mvp_plan.md#must-have), M4 (a):
"the order is published within 5 s of restart".

- Measured from Spring's Started line: **pass** in 5 / 5 (max 148 ms).
- Measured from the container start (includes JVM boot and Spring startup): **pass** in 5 / 5 (max 3,822 ms).

Both readings are within the bound in this run, so the verdict does not depend on which one "restart" means.

## 10. Deviations and limitations

- **The window is held open by pausing the broker**, not hit by timing. This is a deliberate choice: the precondition
  is proved from the database in every repetition rather than hoped for. What it does not show is a kill landing in a
  naturally short window under load (F1 as the fault matrix schedules it: kills every 20–40 s during load). Not run.
- **Warm restarts only.** Spring started in 2.25–2.29 s here. The same image logged 7.99 s ("process running for
  12.59 s") when the whole stack cold-started together on 2026-09-17; a restart under that contention would move the
  container-start reading and was not measured. The Started-line reading is unaffected by JVM boot time.
- **5 repetitions, 100 orders.** A zero-loss result over 100 orders bounds, it does not prove; no rate is claimed.
- **Single broker, RF=1**; broker-side loss is out of scope (H.5 of the S08 register).
