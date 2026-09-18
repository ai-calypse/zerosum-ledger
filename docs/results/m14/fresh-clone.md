# M14(a) — fresh clone to a completed W1 scenario, timed

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | M14(a) |
| Type | timing |
| Date (UTC) | 2026-09-18, 10:06:58–10:07:39 |

## 2. Status

- **Measured, with warm caches.** A cold machine was not measured (see §7).

## 3. Provenance

| Item | Value |
|---|---|
| Git commit SHA | `d0eb638`, cloned from `https://github.com/ai-calypse/zerosum-ledger` into an empty directory; the W1 result records a clean tree |
| Versions | [ADR-0002](../../adr/0002-stack-and-pinned-versions.md) at that SHA |
| Seeds | W1 seed 4242, 1 run |
| Hardware | Apple M4 (Mac16,12), 10 cores, 24 GiB, macOS 26.5.2; Docker Desktop VM 10 CPUs / 7.75 GiB |

## 4. What ran

[raw/fresh_clone.sh](raw/fresh_clone.sh), with nothing else running on the Docker VM (the main checkout's stack was
torn down with its volumes first):

```sh
git clone https://github.com/ai-calypse/zerosum-ledger.git
make env      # generated .env with local secrets
make up       # ./gradlew assemble, docker compose build, start, wait until every container is healthy
./gradlew :tools:simulator:run --args="--scenario w1-trip-completed --runs 1 --seed 4242"
```

## 5. Results ([raw/fresh-clone-timing.txt](raw/fresh-clone-timing.txt))

| Stage | Seconds |
|---|---|
| `git clone` | 1.1 |
| `make env` | 0.4 |
| `make up` (Gradle build, image build, start, all 8 containers healthy) | 37.3 |
| W1 scenario (3 orders created, applied, driver balances match, invariants consistent) | 2.2 |
| **Clone → W1 completed** | **41.1 s** |

W1 result: PASS ([raw/w1-fresh-clone.md](raw/w1-fresh-clone.md)).

## 6. Gate

M14(a): a fresh clone completes W1 in ≤ 10 minutes. **Met on this machine with warm caches**, by a wide margin.

## 7. Limitations

**Warm caches, stated plainly.** Gradle executed all 56 build tasks from source (`56 executed`, none from the build
cache), but the Gradle daemon, its dependency cache and the Java 25 toolchain were already present. Docker reused
cached base-image layers (`CACHED` ×10) and pulled nothing. On a machine that has never built this project, the first
run also downloads the Gradle distribution, every dependency, a JDK and the PostgreSQL, Kafka, Grafana and nginx images
(several GB); that time depends on the network and **was not measured**. Clearing this machine's caches to measure it
was judged not worth the cost to the owner's environment. That is why the acceptance table records M14(a) as PARTIAL.
