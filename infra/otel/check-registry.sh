#!/usr/bin/env bash
# decision: D07-1 — docs/step_07_observability_performance.md#decisions-and-outputs
#
# Checks that every metric in infra/otel/registry.yaml actually reaches the backend, and reports series the backend
# holds that nobody registered.
#
# Why this exists: a registry is a claim about reality. Without a check it is a wish list, and the failure mode is
# silent — a dashboard panel renders empty and nobody can tell whether the system is healthy or the metric is dead.
# S03 shipped a send-failure counter that never incremented, and S04 shipped lag that only existed during an HTTP
# request; both looked fine in code review.
#
# Usage:
#   infra/otel/check-registry.sh [--prometheus URL] [--strict]
#     --strict   also fail (not just warn) on unregistered series belonging to this project
#
# Exit codes: 0 all registered series present; 1 one or more missing; 2 the backend could not be queried.
set -uo pipefail

PROM_URL="${ZS_PROM_URL:-http://localhost:3000/api/datasources/proxy/uid/prometheus}"
STRICT=0
REGISTRY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/registry.yaml"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --prometheus) PROM_URL="$2"; shift 2 ;;
    --strict) STRICT=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -f "$REGISTRY" ]] || { echo "registry not found: $REGISTRY" >&2; exit 2; }
REGISTRY_PARSER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/parse-registry.py"
[[ -f "$REGISTRY_PARSER" ]] || { echo "parser not found: $REGISTRY_PARSER" >&2; exit 2; }
REGISTRY_EXTRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/unregistered-series.py"

# The backend applies suffixes (_total for counters, _bucket/_count/_sum for histograms), so a registered name is
# looked up as itself and as its plausible exported forms. A hit on any of them counts, and the form that matched is
# reported so the registry can record the real series name rather than a guess.
query_series() {
  local name="$1"
  # The OTLP exporter appends the unit as well as the aggregation suffix, so a Timer named "ledger_apply"
  # arrives as "ledger_apply_milliseconds_bucket". Without these candidates the check reports a live series
  # as MISSING — a false alarm from the very tool meant to catch false comfort.
  for candidate in "$name" "${name}_total" "${name}_count" "${name}_bucket" "${name}_sum" \
                   "${name}_milliseconds_bucket" "${name}_milliseconds_count" "${name}_milliseconds_sum" \
                   "${name}_seconds_milliseconds_bucket" "${name}_max_milliseconds"; do
    local encoded body count
    encoded=$(printf 'count(%s)' "$candidate" | sed 's/ /%20/g; s/(/%28/g; s/)/%29/g')
    body=$(curl -s --max-time 10 "${PROM_URL}/api/v1/query?query=${encoded}" 2>/dev/null) || continue
    count=$(printf '%s' "$body" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
print(len(d.get("data",{}).get("result",[])))
' 2>/dev/null)
    if [[ "${count:-0}" -gt 0 ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

# Registered metric names, read straight from the registry rather than duplicated here.
# Plain command substitution, not mapfile + process substitution: that form failed with "bad substitution" on macOS
# and left the array unset, so the script reported nothing while appearing to run.
REGISTERED=()
while IFS= read -r line; do
  [[ -n "$line" ]] && REGISTERED+=("$line")
done <<EOF
$(python3 "$REGISTRY_PARSER" "$REGISTRY")
EOF

if [[ ${#REGISTERED[@]} -eq 0 ]]; then
  echo "no metrics parsed from the registry — refusing to report success" >&2
  exit 2
fi

echo "checking ${#REGISTERED[@]} registered metrics against ${PROM_URL}"
if ! curl -s --max-time 10 -o /dev/null "${PROM_URL}/api/v1/query?query=up"; then
  echo "cannot reach the metrics backend at ${PROM_URL}" >&2
  echo "start the stack and send smoke traffic first; an unreachable backend is not a passing check" >&2
  exit 2
fi

missing=0
for name in "${REGISTERED[@]}"; do
  if found=$(query_series "$name"); then
    if [[ "$found" == "$name" ]]; then
      printf '  ok       %s\n' "$name"
    else
      printf '  ok       %-38s (exported as %s)\n' "$name" "$found"
    fi
  else
    printf '  MISSING  %s\n' "$name"
    missing=$((missing + 1))
  fi
done

# Series the project emits but nobody registered. A warning by default: an unregistered series is untracked, not
# broken, and failing on it would make adding a metric harder than it should be.
unregistered=$(curl -s --max-time 10 "${PROM_URL}/api/v1/label/__name__/values" 2>/dev/null | python3 "$REGISTRY_EXTRA" "${REGISTERED[@]}")

if [[ -n "$unregistered" ]]; then
  echo ""
  echo "series present in the backend but not registered:"
  while IFS= read -r n; do printf '  UNREGISTERED  %s\n' "$n"; done <<< "$unregistered"
  [[ $STRICT -eq 1 ]] && missing=$((missing + 1))
fi

echo ""
if [[ $missing -gt 0 ]]; then
  echo "FAILED: ${missing} registered metric(s) absent from the backend"
  echo "a registered series that never arrives is the failure this check exists to catch"
  exit 1
fi
echo "PASSED: every registered metric is present"
