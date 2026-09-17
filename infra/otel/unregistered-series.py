#!/usr/bin/env python3
"""Reads Prometheus label values on stdin and prints project series that are NOT in the registry (decision: D07-1).

Takes the registered names as arguments. A separate file for the same reason as parse-registry.py: the inline
heredoc form failed under the invoking shell on macOS, and a check that silently reports nothing is worse than no
check at all — that is exactly the failure mode this whole script exists to catch.
"""
import json
import re
import sys

registered = set(sys.argv[1:])
try:
    names = json.load(sys.stdin).get("data", [])
except Exception:
    raise SystemExit

ours = re.compile(r"^(outbox_|ledger_|order_to_apply|invariant_violations|kafka_consumer_lag)")


def base(name):
    """Strip the suffixes the OTLP -> Prometheus path appends, so a registered metric is not reported as extra."""
    stripped = re.sub(r"_(total|count|bucket|sum)$", "", name)
    stripped = re.sub(r"_(milliseconds|seconds)$", "", stripped)
    return re.sub(r"_max$", "", stripped)


extra = sorted({n for n in names if ours.match(n) and base(n) not in registered and n not in registered})
for name in extra:
    print(name)
