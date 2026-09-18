"""Live reconciliation run: real charges through the running stack, day closed by backdating, FakeCard's real report."""
import json, subprocess, sys, time, uuid, urllib.request, urllib.error
from datetime import datetime, timezone, timedelta

ROOT = sys.argv[1]
N = int(sys.argv[2]) if len(sys.argv) > 2 else 20
BACK = int(sys.argv[3]) if len(sys.argv) > 3 else 1
PROFILE = sys.argv[4] if len(sys.argv) > 4 else None
env = dict(l.split('=', 1) for l in open(f'{ROOT}/.env').read().splitlines() if '=' in l and not l.startswith('#'))
WRITER = env['ZS_WRITER_TOKENS'].split(',')[0].strip().split(':', 1)[1]
ADMIN, READER = env['ZS_ADMIN_TOKEN'], env['ZS_READER_TOKEN']
run = 'rl' + uuid.uuid4().hex[:8]
day = (datetime.now(timezone.utc) - timedelta(days=BACK)).date().isoformat()


def http(method, url, token, body=None, key=None):
    req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode())
    req.add_header('Authorization', f'Bearer {token}')
    req.add_header('Content-Type', 'application/json')
    if key:
        req.add_header('Idempotency-Key', key)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def sql(db, q):
    out = subprocess.run(['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', db, '-Atc', q],
                         cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    return out


def log(*a):
    print(f'[{datetime.now(timezone.utc).strftime("%H:%M:%S")}]', *a, flush=True)


log('run', run, 'charges', N, 'closing day', day)
for r in range(5):
    s, b = http('POST', 'http://127.0.0.1:8083/v1/instrument-tokens', WRITER,
                {'entity_id': f'rider:{run}r{r}', 'provider': 'fakecard', 'token': 'tok_card_ok'})
    assert s == 200, (s, b)

fares = []
for i in range(N):
    fare = 1000 + 50 * i
    fares.append(fare)
    body = {'order_group_id': f'trip_{run}_{i}', 'type': 'COMMERCE', 'reason': 'trip.completed', 'adjusts_order_id': None,
            'entries': [{'entity_id': f'rider:{run}r{i % 5}', 'account': 'receivable', 'currency': 'USD', 'amount_minor': fare},
                        {'entity_id': f'driver:{run}', 'account': 'payable', 'currency': 'USD', 'amount_minor': -(fare - 200)},
                        {'entity_id': 'platform:main', 'account': 'revenue', 'currency': 'USD', 'amount_minor': -200}],
            'metadata': {'trip_id': f'trip_{run}_{i}'}, 'effective_at': datetime.now(timezone.utc).isoformat()}
    s, b = http('POST', 'http://127.0.0.1:8081/v1/money-orders', WRITER, body, key=f'{run}-{i}')
    assert s == 201, (s, b)
log('posted', N, 'COMMERCE orders, total fare', sum(fares))

where = f"entity_id LIKE 'rider:{run}%'"
for _ in range(120):
    st = sql('instruments', f"SELECT status || '=' || count(*) FROM payment_attempts WHERE {where} GROUP BY status")
    if st == f'SUCCEEDED={N}':
        break
    time.sleep(1)
log('attempts:', st.replace('\n', ' '))
assert st == f'SUCCEEDED={N}', st

ids = sql('instruments', f"SELECT string_agg(quote_literal(attempt_id::text), ',') FROM payment_attempts WHERE {where}")
log('provider charges for these attempts:',
    sql('fakeproviders', f"SELECT status || '=' || count(*) || ' sum=' || sum(amount_minor) FROM card_charges WHERE client_reference IN ({ids}) GROUP BY status"))

# Close the day: move both sides of this run one day back, exactly as the ITs do (SettlementReportIT, RecoveryTestBase).
log('backdate charges:', sql('fakeproviders', f"UPDATE card_charges SET created_at = created_at - interval '{BACK} day' WHERE client_reference IN ({ids})"))
log('backdate attempts:', sql('instruments', f"UPDATE payment_attempts SET created_at = created_at - interval '{BACK} day' WHERE {where}"))

if PROFILE:
    log('fault profile set:', http('PUT', 'http://127.0.0.1:8090/admin/faults/fakecard', ADMIN, json.loads(PROFILE)))
try:
    s, rep = http('GET', f'http://127.0.0.1:8090/fakecard/v1/settlement-reports/{day}', ADMIN)
    log('FakeCard report', day, 'HTTP', s, 'lines', len(rep['lines']) if s == 200 else rep)
finally:
    if PROFILE:
        log('fault profile restored:', http('PUT', 'http://127.0.0.1:8090/admin/faults/fakecard', ADMIN, {'seed': 0}))

s, runres = http('POST', 'http://127.0.0.1:8083/v1/reconciliation-runs', ADMIN, {'provider': 'fakecard', 'report_date': day},
                 key=f'recon-{run}')
log('reconciliation run HTTP', s, json.dumps(runres) if isinstance(runres, dict) else runres)
if s in (200, 201):
    s2, breaks = http('GET', f"http://127.0.0.1:8083/v1/reconciliation-runs/{runres['run_id']}/breaks", ADMIN)
    log('breaks HTTP', s2, json.dumps(breaks)[:1500])
    s3, again = http('POST', 'http://127.0.0.1:8083/v1/reconciliation-runs', ADMIN, {'provider': 'fakecard', 'report_date': day},
                     key=f'recon-{run}-second-caller')
    log('second caller, different key: HTTP', s3, json.dumps(again) if isinstance(again, dict) else again)
print(json.dumps({'run': run, 'day': day, 'n': N, 'fare_total': sum(fares)}))
