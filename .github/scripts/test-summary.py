"""Report actual JUnit counts and reject a test layer that executed no tests."""
import glob
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

layer = sys.argv[1]
files = glob.glob(f"**/build/test-results/{layer}/TEST-*.xml", recursive=True)
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for name in files:
    suite = ET.parse(name).getroot()
    for key in counts:
        counts[key] += int(suite.get(key, 0))
summary = f"{layer}: " + ", ".join(f"{key}={value}" for key, value in counts.items())
print(summary)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as report:
        report.write(summary + "\n")
if not counts["tests"] or counts["failures"] or counts["errors"]:
    sys.exit(1)
