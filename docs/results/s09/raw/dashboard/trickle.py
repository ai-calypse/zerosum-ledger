# A steady trickle of trips through the public API, one every 3 s for N minutes, so the live charts have a current
# reading. Riders and drivers are the ones populate.py registered. Tokens read from .env at run time, never printed.
import json, random, sys, time, urllib.request
from datetime import datetime, timezone

env = {}
with open(sys.argv[1]) as f:
    for line in f:
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
WRITER = env["ZS_WRITER_TOKENS"].split(",")[0].split(":", 1)[1]
TAG, minutes = sys.argv[2], float(sys.argv[3])
rng = random.Random(int(time.time()))
end = time.time() + minutes * 60
n = 0
while time.time() < end:
    n += 1
    rider = "rider:%s_r%02d" % (TAG, rng.randint(1, 20))
    driver = "driver:%s_d%02d" % (TAG, rng.randint(1, 8))
    fare = rng.randrange(900, 4800, 50)
    fee = round(fare * 0.2 / 10) * 10
    group = "trip_%s_t%d_%03d" % (TAG, int(end), n)
    body = {"order_group_id": group, "type": "COMMERCE", "reason": "trip.completed", "adjusts_order_id": None,
            "entries": [{"entity_id": rider, "account": "receivable", "currency": "USD", "amount_minor": fare},
                        {"entity_id": driver, "account": "payable", "currency": "USD", "amount_minor": -(fare - fee)},
                        {"entity_id": "platform:main", "account": "revenue", "currency": "USD", "amount_minor": -fee}],
            "metadata": {"trip_id": group},
            "effective_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"}
    req = urllib.request.Request("http://127.0.0.1:8080/orders/v1/money-orders", data=json.dumps(body).encode(),
                                 method="POST")
    req.add_header("Authorization", "Bearer " + WRITER)
    req.add_header("Content-Type", "application/json")
    req.add_header("Idempotency-Key", group)
    urllib.request.urlopen(req, timeout=20).read()
    time.sleep(3)
print("trickle done:", n, "trips", flush=True)
