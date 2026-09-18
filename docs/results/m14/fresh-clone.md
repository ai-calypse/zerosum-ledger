# M14(a) — fresh clone to a completed W1 scenario, timed cold and warm

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | M14(a) |
| Type | timing |
| Date (UTC) | 2026-09-18: cold 22:22:24–22:27:15; warm 10:06:58–10:07:39 |

## 2. Status

- **Measured, cold and warm.**

## 3. Provenance

| Item | Value |
|---|---|
| Git commit SHA | Cold: `417b412`; warm: `eb8b163` (pre-rewrite ID, see [commit-id-map](../../commit-id-map.txt)). Each cloned from `https://github.com/ai-calypse/zerosum-ledger` into an empty directory; both W1 results record a clean tree |
| Versions | [ADR-0002](../../adr/0002-stack-and-pinned-versions.md) at that SHA |
| Seeds | W1 seed 4242, 1 run |
| Hardware | Apple M4 (Mac16,12), 10 cores, 24 GiB, macOS 26.5.2, on mains power; Docker Desktop VM 10 CPUs / 7.75 GiB |
| Network | single-stream download test just before the cold run: 25 MB in 1.40 s ≈ **143 Mbit/s** |
| Other load | nothing else on the Docker VM; the only other open applications were the editor, Docker Desktop and Finder |

## 4. What ran

[raw/cold/cold_clone.sh](raw/cold/cold_clone.sh). The cold run first **removed every project artefact a new machine would
not have**:

- all four service images, the Java base image, and the PostgreSQL, Kafka, Grafana LGTM and nginx images;
- the entire BuildKit build cache (`docker builder prune -af`: 0 B left);
- the Gradle user home, by pointing `GRADLE_USER_HOME` at an **empty directory**, so the Gradle distribution, every
  dependency and the Java 25 toolchain were downloaded. The owner's own `~/.gradle` was not touched.

What stayed installed, as the README's prerequisites: git, a host JDK to start the Gradle wrapper, Docker Desktop.

```sh
git clone https://github.com/ai-calypse/zerosum-ledger.git
make env      # generated .env with local secrets
make up       # ./gradlew assemble, docker compose build, pull, start, wait until all 8 containers are healthy
./gradlew :tools:simulator:run --args="--scenario w1-trip-completed --runs 1 --seed 4242"
```

## 5. Results

### 5.1 Cold ([raw/cold/cold-clone-timing.txt](raw/cold/cold-clone-timing.txt))

| Stage | Seconds |
|---|---|
| `git clone` | 1.2 |
| `make env` | 0.3 |
| `make up`: downloads, build, image build and pull, start, all 8 containers healthy | 286.1 |
| W1 scenario (3 orders created and applied, balances match, invariants consistent) | 2.5 |
| **Clone → W1 completed** | **290.1 s (4.83 min)** |

Downloaded during the run: **1.4 GB into the empty Gradle home** (Java 25 toolchain 438 MB, Gradle 9.7.1
distribution 164 MB, dependencies 832 MB), and **≈ 5.9 GB of container images** (Grafana LGTM alone is 3.4 GB). Of
`make up`'s 286 s, the Gradle build was 174 s (`BUILD SUCCESSFUL in 2m 54s`, all 56 tasks executed). W1 result:
PASS ([raw/cold/w1-cold-clone.md](raw/cold/w1-cold-clone.md)).

### 5.2 Warm ([raw/fresh-clone-timing.txt](raw/fresh-clone-timing.txt))

Same steps with Gradle's dependency cache, the toolchain and the images already present: **41.1 s** (clone 1.1,
`make env` 0.4, `make up` 37.3, W1 2.2).

## 6. Gate

M14(a): a fresh clone completes W1 in ≤ 10 minutes. **Met cold, in 4.83 minutes**, on a 143 Mbit/s connection.

## 7. Limitations

- **Network-bound.** Most of the cold time is downloads, about 7.3 GB in all, fetched in parallel. A slower
  connection lengthens the run; how much was not measured.
- A host JDK, git and Docker Desktop were already installed; the README lists them as prerequisites.
- One cold run. The time is dominated by the network, which varies between runs.
