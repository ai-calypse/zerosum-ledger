# Lost responses with no webhook to fall back on: the only way these attempts resolve is the resolver asking FakeCard.
# Same rules as populate.py: public APIs only, tokens read from .env at run time and never printed, profile restored.
import json, random, sys, time, urllib.request, urllib.error
from datetime import datetime, timezone

BASE = "http://127.0.0.1:8080"
env = {}
with open(sys.argv[1]) as f:
    for line in f:
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
ADMIN = env["ZS_ADMIN_TOKEN"]
WRITER = env["ZS_WRITER_TOKENS"].split(",")[0].split(":", 1)[1]
TAG = sys.argv[2]
rng = random.Random(919)


def call(method, path, token, body=None, key=None):
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("Idempotency-Key", key)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


s, _ = call("PUT", "/providers/admin/faults/fakecard", ADMIN,
            {"timeout_after_commit_rate": 0.6, "webhook_drop_rate": 1.0, "seed": 919})
assert s == 200, s
try:
    for n in range(1, 9):
        rider = "rider:%s_r%02d" % (TAG, rng.randint(1, 20))
        driver = "driver:%s_d%02d" % (TAG, rng.randint(1, 8))
        fare = rng.randrange(900, 4800, 50)
        fee = round(fare * 0.2 / 10) * 10
        group = "trip_%s_u%02d" % (TAG, n)
        body = {"order_group_id": group, "type": "COMMERCE", "reason": "trip.completed", "adjusts_order_id": None,
                "entries": [{"entity_id": rider, "account": "receivable", "currency": "USD", "amount_minor": fare},
                            {"entity_id": driver, "account": "payable", "currency": "USD", "amount_minor": -(fare - fee)},
                            {"entity_id": "platform:main", "account": "revenue", "currency": "USD", "amount_minor": -fee}],
                "metadata": {"trip_id": group},
                "effective_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"}
        s, _ = call("POST", "/orders/v1/money-orders", WRITER, body, key="%s-u%02d" % (TAG, n))
        assert s in (200, 201), s
        time.sleep(1.5)
    time.sleep(6)
finally:
    s, profile = call("PUT", "/providers/admin/faults/fakecard", ADMIN, {"seed": 0})
    print("restored", s, profile.get("timeout_after_commit_rate"), profile.get("webhook_drop_rate"), profile.get("seed"),
          flush=True)
