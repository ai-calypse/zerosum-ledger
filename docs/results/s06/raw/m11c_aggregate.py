"""Regenerates every table in docs/results/s06/m11c-recon-under-chaos.md from the raw run JSON next to this file.

Usage: python3 docs/results/s06/raw/m11c_aggregate.py
"""
import glob
import json
import os
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))
runs = []
for path in sorted(glob.glob(os.path.join(HERE, 'm11c-run*.json'))):
    if path.endswith('-verifier.json'):
        continue
    with open(path) as f:
        doc = json.load(f)
    doc['_file'] = os.path.basename(path)
    runs.append(doc)


def row(*cells):
    print('| ' + ' | '.join(str(c) for c in cells) + ' |')


def verifier_metric(summary, check, name):
    for line in summary['verifier_lines']:
        if line.startswith('ZS-VERIFY %s ' % check):
            for part in line[line.rfind('{') + 1:line.rfind('}')].split(', '):
                key, _, value = part.partition('=')
                if key == name:
                    return int(value)
    return None


print('### Provenance per run\n')
row('Run', 'File', 'Git SHA', 'Working tree', 'Seeds (cycle 1, cycle 2)', 'fake-providers image')
row(*['---'] * 6)
for i, r in enumerate(runs, 1):
    p = r['provenance']
    row(i, r['_file'], '`%s`' % p['git_sha'][:12], p['working_tree'], ', '.join(map(str, p['seeds'])),
        '`%s`' % p['runtime_versions'].get('fake-providers image', '?')[:19])

print('\n### Per cycle\n')
row('Run', 'Cycle', 'Report date', 'Trips', 'Post retries', 'Replays after retry', 'Kills (SUBMITTING at F3 kill)',
    'Went UNKNOWN', 'Attempt statuses', 'Quiesce s', 'Report lines clean/served', 'Recon HTTP tries',
    'Lines matched', 'Breaks', 'Injected (missing/off-by-one/dup)', 'Explained', 'Unexplained', 'Undetected',
    'Duplicate charges')
row(*['---'] * 19)
waits = []
for i, r in enumerate(runs, 1):
    for c in r['summary']['cycles']:
        kills = c['kills']
        f3 = [k for k in kills if 'instrument' in k['container']]
        inj = c['injected_by_type']
        waits.append(c['quiesce']['waited_s'])
        row(i, c['cycle'], c['report_date'], c['trips'], c['post_retries'], c['post_replays_after_retry'],
            '%d (%s)' % (len(kills), f3[0]['cycle_attempts_submitting_at_kill'] if f3 else '-'),
            c['attempts_that_went_unknown'], ', '.join('%s %d' % kv for kv in c['attempt_statuses'].items()),
            '%.1f%s' % (c['quiesce']['waited_s'], '' if c['quiesce']['reached'] else ' NOT QUIESCED'),
            '%d/%d' % (c['report_lines_clean'], c['report_lines_served']), c['recon_http_attempts'],
            c['recon_lines_matched'], c['recon_breaks_found'],
            '%d/%d/%d' % (inj.get('report_missing_line', 0), inj.get('report_off_by_one', 0),
                          inj.get('report_duplicate_line', 0)),
            c['explained_breaks'], c['unexplained_breaks'], c['undetected_injections'], c['duplicate_charges'])

print('\n### Per run (verifier, cumulative over the stack at the end of the run)\n')
row('Run', 'Final quiesce s', 'Verifier exit', 'I6 missing/extra/quarantined', 'I6b drift (minor)',
    'I7 duplicate charges', 'I12 breaks', 'I12 explained', 'I12 unexplained', 'I12 undetected', 'Wall s (first load to verifier)')
row(*['---'] * 11)
for i, r in enumerate(runs, 1):
    s = r['summary']
    m = lambda check, name: verifier_metric(s, check, name)
    wall = None
    try:
        from datetime import datetime
        start = datetime.fromisoformat(s['cycles'][0]['load_started_at'].replace('Z', '+00:00'))
        end = datetime.fromisoformat(s['final_quiesce']['at'].replace('Z', '+00:00'))
        wall = round((end - start).total_seconds())
    except (KeyError, ValueError):
        pass
    row(i, s['final_quiesce']['waited_s'], s['verifier_exit_code'],
        '%s/%s/%s' % (m('I6', 'missing_orders'), m('I6', 'extra_applied'), m('I6', 'unresolved_quarantined')),
        m('I6b', 'drift_abs_minor'), m('I7', 'duplicate_charges'), m('I12', 'breaks'), m('I12', 'explained_breaks'),
        m('I12', 'unexplained_breaks'), m('I12', 'undetected_injections'), wall)

cycles = [c for r in runs for c in r['summary']['cycles']]
if cycles:
    print('\n### Totals over %d runs, %d cycles\n' % (len(runs), len(cycles)))
    total = lambda key: sum(c[key] for c in cycles)
    row('Trips', 'Kills', 'Breaks', 'Injected', 'Explained', 'Unexplained', 'Undetected', 'Duplicate charges',
        'Quiesce s median (min–max)')
    row(*['---'] * 9)
    row(total('trips'), sum(len(c['kills']) for c in cycles), total('recon_breaks_found'),
        total('injected_discrepancies'), total('explained_breaks'), total('unexplained_breaks'),
        total('undetected_injections'), total('duplicate_charges'),
        '%.1f (%.1f–%.1f)' % (statistics.median(waits), min(waits), max(waits)))
