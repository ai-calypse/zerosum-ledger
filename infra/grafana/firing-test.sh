#!/usr/bin/env bash
# decision: D07-3 — docs/step_07_observability_performance.md#decisions-and-outputs
#
# M12(c): proves an alert rule actually FIRES, not merely that it provisioned.
#
# Provisioned rules are evidence of configuration. A rule can provision cleanly and still never fire — wrong series
# name, wrong threshold, a gauge that reports zero instead of absence. This stops Kafka so the outbox backs up past
# the 30s threshold, then watches the rule's state transition through Grafana's own API.
#
# Usage: infra/grafana/firing-test.sh [--rule zs-outbox-backlog] [--timeout 300]
set -uo pipefail

RULE_UID="zs-outbox-backlog"
TIMEOUT=300
GRAFANA="${ZS_GRAFANA_URL:-http://localhost:3000}"
AUTH="${ZS_GRAFANA_AUTH:-admin:admin}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rule) RULE_UID="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

rule_state() {
  curl -s -u "$AUTH" "${GRAFANA}/api/prometheus/grafana/api/v1/rules" 2>/dev/null | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('unknown'); raise SystemExit
for group in d.get('data', {}).get('groups', []):
    for rule in group.get('rules', []):
        if rule.get('labels', {}).get('__alert_rule_uid__') == '$RULE_UID' or rule.get('name', '').lower().startswith('outbox'):
            print(rule.get('state', 'unknown')); raise SystemExit
print('not_found')
"
}

echo "M12(c) firing test for rule: ${RULE_UID}"

# A test that starts with the rule already firing proves nothing: it passes on the first read without witnessing a
# transition. That happened on the second run of this test, and it is the same "passed for the wrong reason" shape
# this suite exists to reject. So: make sure the broker is up, wait for the rule to CLEAR, and only then induce the
# fault. If it will not clear, the run is abandoned rather than reported as a pass.
docker compose start kafka >/dev/null 2>&1
clear_deadline=$((SECONDS + 180))
while [[ $SECONDS -lt $clear_deadline ]]; do
  state=$(rule_state)
  case "$(printf '%s' "$state" | tr '[:upper:]' '[:lower:]')" in
    firing|alerting|pending) printf '  waiting for the rule to clear... state=%s\n' "$state"; sleep 15 ;;
    *) break ;;
  esac
done
baseline=$(rule_state)
case "$(printf '%s' "$baseline" | tr '[:upper:]' '[:lower:]')" in
  firing|alerting)
    echo "ABANDONED: the rule was still ${baseline} before the fault was induced."
    echo "this run could only have passed without observing a transition, which is not evidence"
    exit 2 ;;
esac
echo "baseline: state=${baseline} (not firing — a transition is now observable)"

echo "stopping kafka so the outbox cannot drain..."
docker compose stop kafka >/dev/null 2>&1 || { echo "could not stop kafka" >&2; exit 2; }

# Orders keep being accepted while the broker is down — that is the outbox working — so the backlog grows and the
# oldest-unpublished age climbs past the rule's 30s threshold.
set -a; . ./.env 2>/dev/null; set +a
WRITER=$(printf '%s' "${ZS_WRITER_TOKENS:-}" | cut -d, -f1 | cut -d: -f2-)
for i in 1 2 3; do
  group="trip_firing_$(date +%s)_$i"
  body=$(python3 -c "
import json, sys
g = sys.argv[1]
print(json.dumps({'order_group_id': g, 'type': 'COMMERCE', 'reason': 'trip.completed', 'adjusts_order_id': None,
 'entries': [{'entity_id': 'rider:R1', 'account': 'receivable', 'currency': 'USD', 'amount_minor': 2500},
             {'entity_id': 'driver:D1', 'account': 'payable', 'currency': 'USD', 'amount_minor': -2000},
             {'entity_id': 'platform:main', 'account': 'revenue', 'currency': 'USD', 'amount_minor': -500}],
 'metadata': {'trip_id': g}, 'effective_at': '2026-09-16T16:00:00.000Z'}))" "$group")
  curl -s -o /dev/null -X POST http://localhost:8081/v1/money-orders \
    -H "Authorization: Bearer ${WRITER}" -H "Idempotency-Key: firing-${group}" \
    -H 'Content-Type: application/json' -d "$body"
done
echo "posted 3 orders that cannot be published"

deadline=$((SECONDS + TIMEOUT))
observed=""
while [[ $SECONDS -lt $deadline ]]; do
  state=$(rule_state)
  printf '  t+%-4ss state=%s\n' "$SECONDS" "$state"
  # Grafana's Prometheus-compatible rules API reports lowercase "firing"/"pending", not the "Alerting" spelling used
  # in the UI and in provisioning files. The first run of this test watched the rule reach firing at t+212 and
  # reported FAILED anyway, because it was comparing against a string the API never emits.
  case "$(printf '%s' "$state" | tr '[:upper:]' '[:lower:]')" in
    firing|alerting) observed="firing"; break ;;
  esac
  sleep 15
done

echo "restoring kafka..."
docker compose start kafka >/dev/null 2>&1

if [[ "$observed" == "firing" ]]; then
  echo "PASSED: the rule transitioned ${baseline} -> firing on a real backlog"
  exit 0
fi
echo "FAILED: the rule never reached firing within ${TIMEOUT}s"
echo "a rule that provisions but cannot fire is configuration, not alerting — M12(c) is not met"
exit 1
