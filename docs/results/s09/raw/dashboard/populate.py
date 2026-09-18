# Populates the running stack through its public APIs only, so the dashboard has declines, lost responses the resolver
# settles, refunds and payouts to show. Tokens are read from .env at run time, held in memory, never printed.
import json, random, sys, time, urllib.request, urllib.error
from datetime import datetime, timezone

BASE = "http://127.0.0.1:8080"
env = {}
with open(sys.argv[1]) as f:
    for line in f:
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
READER, ADMIN = env["ZS_READER_TOKEN"], env["ZS_ADMIN_TOKEN"]
WRITER = env["ZS_WRITER_TOKENS"].split(",")[0].split(":", 1)[1]
rng = random.Random(918)
TAG = "dash" + time.strftime("%H%M")


def call(method, path, token, body=None, key=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


riders = ["rider:%s_r%02d" % (TAG, i) for i in range(1, 24)]
drivers = ["driver:%s_d%02d" % (TAG, i) for i in range(1, 9)]
cards = {r: "tok_card_ok" for r in riders[:20]}
cards[riders[20]] = cards[riders[21]] = "tok_card_decline_insufficient_funds"
cards[riders[22]] = "tok_card_processing_error"
banks = {d: "tok_bank_ok" for d in drivers[:6]}
banks[drivers[6]] = "tok_bank_return_R01"
banks[drivers[7]] = "tok_bank_fail_account_closed"

for entity, token in list(cards.items()):
    s, _ = call("POST", "/instruments/v1/instrument-tokens", WRITER, {"entity_id": entity, "provider": "fakecard", "token": token})
    assert s == 200, (entity, s)
for entity, token in banks.items():
    s, _ = call("POST", "/instruments/v1/instrument-tokens", WRITER, {"entity_id": entity, "provider": "fakebank", "token": token})
    assert s == 200, (entity, s)
log("registered", len(cards), "cards and", len(banks), "bank accounts")

trips = []


def trip(n, pool):
    rider = rng.choice(pool)
    driver = rng.choice(drivers)
    fare = rng.randrange(900, 4800, 50)
    fee = round(fare * 0.2 / 10) * 10
    group = "trip_%s_%03d" % (TAG, n)
    body = {"order_group_id": group, "type": "COMMERCE", "reason": "trip.completed", "adjusts_order_id": None,
            "entries": [{"entity_id": rider, "account": "receivable", "currency": "USD", "amount_minor": fare},
                        {"entity_id": driver, "account": "payable", "currency": "USD", "amount_minor": -(fare - fee)},
                        {"entity_id": "platform:main", "account": "revenue", "currency": "USD", "amount_minor": -fee}],
            "metadata": {"trip_id": group}, "effective_at": now()}
    s, order = call("POST", "/orders/v1/money-orders", WRITER, body, key="%s-%03d" % (TAG, n))
    assert s in (200, 201), (s, order)
    trips.append((group, order["order_id"], rider, driver))


# Phase A: ordinary traffic, declines included.
weights = riders[:20] * 3 + riders[20:]
for n in range(1, 37):
    trip(n, weights)
    time.sleep(2)
log("phase A: 36 trips")

# Phase B: FakeCard commits the charge and the response is lost; the resolver has to find out what happened.
s, _ = call("PUT", "/providers/admin/faults/fakecard", ADMIN, {"timeout_after_commit_rate": 0.4, "seed": 918})
assert s == 200, s
try:
    for n in range(37, 52):
        trip(n, riders[:20])
        time.sleep(1.5)
    time.sleep(8)
finally:
    s, profile = call("PUT", "/providers/admin/faults/fakecard", ADMIN, {"seed": 0})
    log("phase B: 15 trips with lost responses; fakecard profile restored", s, profile.get("timeout_after_commit_rate"), profile.get("seed"))

# Phase C: three fare adjustments, which refund part of an already captured charge.
time.sleep(10)
done = 0
for group, order_id, rider, driver in trips:
    if cards.get(rider) != "tok_card_ok" or done == 3:
        continue
    body = {"order_group_id": group, "type": "COMMERCE", "reason": "trip.adjusted", "adjusts_order_id": order_id,
            "entries": [{"entity_id": rider, "account": "receivable", "currency": "USD", "amount_minor": -400},
                        {"entity_id": driver, "account": "payable", "currency": "USD", "amount_minor": 320},
                        {"entity_id": "platform:main", "account": "revenue", "currency": "USD", "amount_minor": 80}],
            "metadata": {"trip_id": group, "why": "fare_adjustment"}, "effective_at": now()}
    s, _ = call("POST", "/orders/v1/money-orders", WRITER, body, key="%s-adj-%s" % (TAG, group))
    assert s in (200, 201), s
    done += 1
log("phase C: 3 adjustments")

# Wait for every attempt to leave CREATED/SUBMITTING/UNKNOWN.
for _ in range(60):
    s, summary = call("GET", "/instruments/v1/payment-attempts/summary", READER)
    busy = sum(t["attempts"] for t in summary["totals"] if t["status"] in ("CREATED", "SUBMITTING", "UNKNOWN"))
    if busy == 0:
        break
    time.sleep(2)
log("attempts settled; busy =", busy)

# Phase D: a payout run, paying each driver what the ledger says they are owed.
for attempt in range(5):
    s, run = call("POST", "/instruments/v1/payout-runs", WRITER, {"currency": "USD"}, key="%s-payout-%d" % (TAG, attempt))
    log("payout run", s, run if s >= 400 else {k: run[k] for k in ("status", "attempts_created")})
    if s in (200, 201):
        break
    time.sleep(5)

# Phase E: a little more traffic while the bank works through its day.
for n in range(52, 64):
    trip(n, weights)
    time.sleep(2.5)
log("phase E: 12 trips; done")
