#!/usr/bin/env python3
"""Prints one registered metric name per line from infra/otel/registry.yaml (decision: D07-1).

Kept as a file rather than inline in the shell script: the inline heredoc form failed with "bad substitution" on
macOS and left the caller's array unset, so the check reported nothing while appearing to succeed.
No YAML library is pinned for shell tooling (D00-1), and the file's shape is ours, so a small reader is enough.
"""
import re
import sys

text = open(sys.argv[1]).read()
in_metrics = False
for line in text.splitlines():
    if line.startswith("metrics:"):
        in_metrics = True
        continue
    if in_metrics and line and not line[0].isspace():
        break   # the next top-level key ends the metrics block
    match = re.match(r"\s*name_in_code:\s*(\S+)", line)
    if in_metrics and match:
        print(match.group(1))
