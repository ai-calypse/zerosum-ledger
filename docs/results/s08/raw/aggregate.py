#!/usr/bin/env python3
"""Regenerates the result tables in docs/results/s08/*.md from the raw run files next to this script.

Numbers in the .md files are pasted from this script's output, never typed by hand:
    python3 docs/results/s08/raw/aggregate.py
"""
import glob
import json
import os
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))


def m4a():
    runs = json.load(open(os.path.join(HERE, "m4a-crash-recovery.json")))
    print("## M4(a)\n")
    print("| Rep | Unpublished at kill | Applied before restart | Spring startup (s) | Publish - container start (ms) "
          "| Publish - app Started (ms) | applied_orders rows | Copies on topic | Rider receivable | Invariants |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for r in runs:
        print(f"| {r['rep']} | {r['unpublished_at_kill']} / {r['orders']} | {r['applied_before_restart']} "
              f"| {r['spring_startup_seconds']} | {r['publish_after_container_start_ms']:,} "
              f"| {r['publish_after_app_started_ms']} | {r['applied_rows']} | {r['kafka_copies']} "
              f"| {r['rider_receivable']:,} | {'consistent' if r['invariants_consistent'] else 'INCONSISTENT'} |")
    for name, key in (("Median", statistics.median), ("Range", None)):
        cols = ["unpublished_at_kill", "applied_before_restart", "spring_startup_seconds",
                "publish_after_container_start_ms", "publish_after_app_started_ms", "applied_rows", "kafka_copies"]
        vals = [[r[c] for r in runs] for c in cols]
        cells = [str(key(v)) if key else f"{min(v)}-{max(v)}" for v in vals]
        print(f"| **{name}** | " + " | ".join(cells) + " | | |")
    print()


def m8b(pattern, title):
    files = sorted(glob.glob(os.path.join(HERE, pattern)))
    if not files:
        return
    print(f"## {title}\n")
    print("| Run | Seed | Charges | Attempts | Exactly one successful charge | Charges per attempt | Outcome paths "
          "| timeout_after_commit rows | Lost responses | Matched 1:1 | Retry rows (ambiguous) | Unexplained "
          "| Stray charges | Riders receivable != 0 | Wall s |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    total = {}
    for f in files:
        s = json.load(open(f))["summary"]
        row = [s["label"], s["seed"], s["charges_requested"], s["attempts"],
               s["attempts_with_exactly_one_successful_charge"],
               ", ".join(f"{k}: {v}" for k, v in s["provider_charges_per_attempt"].items()),
               ", ".join(f"{k} {v}" for k, v in s["outcome_paths"].items()),
               s["timeout_after_commit_rows"], s["lost_responses"], s["lost_responses_matched_to_a_fault_row"],
               f"{s['timeout_fault_rows_attributed_to_retries_of_unknown_attempts']} "
               f"({s['timeout_fault_rows_ambiguous']})",
               s["timeout_fault_rows_unexplained"], s["stray_charges_in_window"],
               s["riders_with_nonzero_receivable"], s["wall_seconds_activation_to_restore"]]
        print("| " + " | ".join(str(c) for c in row) + " |")
        for k in ("charges_requested", "attempts", "attempts_with_exactly_one_successful_charge",
                  "timeout_after_commit_rows", "lost_responses", "lost_responses_matched_to_a_fault_row",
                  "timeout_fault_rows_attributed_to_retries_of_unknown_attempts", "timeout_fault_rows_ambiguous",
                  "timeout_fault_rows_unexplained", "stray_charges_in_window", "riders_with_nonzero_receivable",
                  "wall_seconds_activation_to_restore"):
            total[k] = total.get(k, 0) + s[k]
        for path, n in s["outcome_paths"].items():
            total["path " + path] = total.get("path " + path, 0) + n
        for cause, n in s["settled_by"].items():
            total["settled " + cause] = total.get("settled " + cause, 0) + n
        for status, n in s["attempt_statuses"].items():
            total["status " + status] = total.get("status " + status, 0) + n
    print("\nTotals:\n")
    for k, v in total.items():
        print(f"- {k}: {v:,}")
    print()


def latencies(pattern, title):
    """Seconds from an attempt's SUBMITTING transition to its settling one, per outcome path, from the CSVs."""
    import csv
    import gzip
    from datetime import datetime

    def t(s):
        return datetime.fromisoformat(s.replace("Z", "+00:00"))

    by = {}
    for f in sorted(glob.glob(os.path.join(HERE, pattern))):
        for r in csv.DictReader(gzip.open(f, "rt")):
            if r["settled_at"] == "null" or r["submitting_at"] == "null":
                continue
            by.setdefault(r["path"], []).append((t(r["settled_at"]) - t(r["submitting_at"])).total_seconds())
            if r["unknown_at"] != "null":
                by.setdefault("submit -> UNKNOWN", []).append(
                    (t(r["unknown_at"]) - t(r["submitting_at"])).total_seconds())
    if not by:
        return
    print(f"### {title}: seconds from SUBMITTING to settled\n")
    print("| Path | n | min | p50 | p95 | p99 | max |\n|---|---|---|---|---|---|---|")
    for k, v in sorted(by.items()):
        v.sort()
        q = lambda p: v[min(len(v) - 1, int(p * len(v)))]
        print(f"| {k} | {len(v):,} | {v[0]:.3f} | {q(0.5):.3f} | {q(0.95):.3f} | {q(0.99):.3f} | {v[-1]:.3f} |")
    print()


def text(name, title):
    path = os.path.join(HERE, name)
    if os.path.exists(path):
        print(f"## {title}\n")
        print(open(path).read())


if __name__ == "__main__":
    m4a()
    m8b("m8b-chunk*.json", "M8(b) - timeout_after_commit_rate=0.2 (as specified)")
    latencies("m8b-chunk*-attempts.csv.gz", "M8(b) as specified")
    m8b("m8b-resolver*.json", "M8(b) supplementary - plus webhook_drop_rate=1.0 (forces the UNKNOWN resolver)")
    latencies("m8b-resolver*-attempts.csv.gz", "M8(b) supplementary")
    text("a2-outbox-ablation.txt", "A2 emulated")
    text("a4-zero-sum-trigger-ablation.txt", "A4 partial")
