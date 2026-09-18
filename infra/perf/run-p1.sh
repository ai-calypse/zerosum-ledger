#!/usr/bin/env bash
# P1 rate ladder against the RUNNING Compose stack (make up). k6 runs in a container on the stack's own network, so
# client <-> server transit is the Docker bridge the §6.2 budget assumes. Per rate: REPS x (warm-up, discarded; then a
# measured window). A 30 s docker-stats sample runs alongside, so every window can be checked for foreign load.
# Usage: infra/perf/run-p1.sh <out-dir> [rates] [reps] [window] [warmup]
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(cd "${1:?out dir}" && pwd); RATES=${2:-"100 200 500 1000 2000"}; REPS=${3:-3}; WINDOW=${4:-60s}; WARMUP=${5:-30s}
K6=grafana/k6:1.3.0@sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d
NET=zerosum-ledger_default
TOKEN=$(grep '^ZS_WRITER_TOKENS=' .env | cut -d= -f2- | cut -d, -f1 | cut -d: -f2-)
( while true; do
    echo "$(date -u +%FT%TZ) ps[$(docker ps --format '{{.Names}}={{.Status}}' | tr '\n' ';')] stats[$(docker stats --no-stream --format '{{.Name}}={{.CPUPerc}},{{.MemUsage}}' | tr '\n' ';')]" >> "$OUT/vm-load.log"
    sleep 30
  done ) & LOADER=$!
trap 'kill $LOADER 2>/dev/null' EXIT
k6() {  # label rate duration
  docker run --rm --network "$NET" -v "$PWD/infra/perf:/scripts:ro" -v "$OUT:/results" \
    -e RATE="$2" -e DURATION="$3" -e LABEL="$1" -e RUN_ID="$(openssl rand -hex 4)" -e WRITER_TOKEN="$TOKEN" \
    "$K6" run --quiet /scripts/p1-order-ack.js > "$OUT/$1.log" 2>&1
}
for rate in $RATES; do
  for rep in $(seq 1 "$REPS"); do
    k6 "warmup-${rate}-r${rep}" "$rate" "$WARMUP"
    start=$(date -u +%FT%TZ)
    k6 "p1-${rate}-r${rep}" "$rate" "$WINDOW"
    echo "p1-${rate}-r${rep} window ${start} .. $(date -u +%FT%TZ)" | tee -a "$OUT/windows.txt"
  done
done
