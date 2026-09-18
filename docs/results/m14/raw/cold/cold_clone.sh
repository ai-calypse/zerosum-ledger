#!/bin/zsh
# M14(a) COLD: fresh clone -> W1 with nothing project-related cached. Evicts this project's Docker images and the
# BuildKit cache, and gives Gradle an EMPTY user home (distribution, dependencies and the Java 25 toolchain all
# download). The owner's ~/.gradle is untouched. Prerequisites that stay installed: git, a host JDK, Docker Desktop.
set -u
S=${TMPDIR:-/tmp}/zsl-m14a-cold; mkdir -p $S
D=$S/zsl-cold; G=$S/gradle-cold; O=$S/m14a-cold; mkdir -p $O; rm -rf $D $G
now() { python3 -c 'import time;print(time.time())'; }
echo "=== evict $(date -u +%T)"
docker rmi -f zerosum-ledger-ledger-service zerosum-ledger-order-service zerosum-ledger-instrument-service \
  zerosum-ledger-fake-providers eclipse-temurin:25.0.4_7-jre postgres:18.6 apache/kafka:4.3.1 grafana/otel-lgtm:0.33.0 \
  nginx:1.29-alpine $(docker images -q apache/kafka) $(docker images -q grafana/otel-lgtm) > /dev/null 2>&1
docker builder prune -af > /dev/null 2>&1
docker images --format '{{.Repository}}:{{.Tag}}' | grep -E 'zerosum|postgres|kafka|otel-lgtm|nginx|temurin' && echo "EVICTION INCOMPLETE" || echo "evicted: none of the project images remain"
echo "build cache: $(docker system df --format '{{.Type}} {{.Size}}' | grep 'Build Cache')"
export GRADLE_USER_HOME=$G
t0=$(now); echo "T0 $(date -u +%T) clone"
git clone -q https://github.com/ai-calypse/zerosum-ledger.git $D || { echo "clone failed"; exit 2; }
cd $D; echo "SHA $(git rev-parse HEAD)"
t1=$(now); echo "T1 $(date -u +%T) make env"
make env > $O/env.log 2>&1 || { echo "make env failed"; exit 2; }
t2=$(now); echo "T2 $(date -u +%T) make up"
make up > $O/up.log 2>&1 || { echo "make up failed"; tail -30 $O/up.log; exit 2; }
t3=$(now); echo "T3 $(date -u +%T) W1"
./gradlew -q :tools:simulator:run --args="--scenario w1-trip-completed --runs 1 --seed 4242 --env-file $D/.env --out $O --label w1-cold-clone" > $O/w1.log 2>&1
w1=$?
t4=$(now); echo "T4 $(date -u +%T) done (simulator exit $w1)"
python3 - $t0 $t1 $t2 $t3 $t4 <<'EOF'
import sys
t=[float(x) for x in sys.argv[1:]]
for n,a,b in zip(['clone','make env','make up (downloads, build, start, healthy)','W1 scenario'],t,t[1:]): print(f'{n:44s} {b-a:8.1f} s')
print(f'{"TOTAL clone -> W1 completed":44s} {t[-1]-t[0]:8.1f} s  ({(t[-1]-t[0])/60:.2f} min)')
EOF
echo "gradle home downloaded: $(du -sh $G | cut -f1)  (jdks: $(du -sh $G/jdks 2>/dev/null | cut -f1), wrapper: $(du -sh $G/wrapper 2>/dev/null | cut -f1), caches: $(du -sh $G/caches 2>/dev/null | cut -f1))"
echo "images now: $(docker images --format '{{.Repository}}:{{.Tag}} {{.Size}}' | grep -E 'zerosum|postgres|kafka|otel-lgtm|nginx|temurin' | tr '\n' ';')"
grep -cE 'Downloading|Pulled|Pull complete' $O/up.log | sed 's/^/pull\/download log lines: /'
tail -3 $O/w1.log
