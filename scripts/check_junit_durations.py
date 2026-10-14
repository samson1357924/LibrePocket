"""Fail-closed JUnit duration gate for Issue #86 (Phase C guardrail).

Stdlib only. Gate (exit non-zero fails CI): fails when any single test class
exceeds 120 seconds wall time. Only rejects / alerts — never silently
downgrades redaction or writes.

Reads `app/build/test-results/**/TEST-*.xml` (same glob as
`scripts/summarize_junit.py`, which stays reporting-only). Per-class time is
the `<testsuite time="...">` attribute when present, else the sum of its direct
`<testcase time="...">` children. Prints the top 10 slowest classes.

Threshold: 120s per class (Issue #86 acceptance: single-class >120s fails).
No XML found -> exit 0 with a note (the Gradle step itself governs
missing results; this gate only judges observed durations).

Run with: python3 scripts/check_junit_durations.py
Test with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_check_junit_durations
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

JUNIT_GLOB = "app/build/test-results/**/TEST-*.xml"
PER_CLASS_BUDGET_SECONDS = 120.0
TOP_N = 10


def _suite_time(suite: ET.Element) -> float:
    raw = suite.get("time")
    if raw is not None:
        try:
            return float(raw)
        except ValueError:
            pass
    total = 0.0
    for case in suite.findall("testcase"):
        try:
            total += float(case.get("time", "0") or "0")
        except ValueError:
            continue
    return total


def collect_durations(root: str) -> dict[str, float]:
    pattern = os.path.join(root, JUNIT_GLOB)
    totals: dict[str, float] = {}
    for path in sorted(glob.glob(pattern, recursive=True)):
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError):
            continue
        xml_root = tree.getroot()
        if xml_root.tag == "testsuites":
            suites = xml_root.findall("testsuite")
        elif xml_root.tag == "testsuite":
            suites = [xml_root]
        else:
            continue
        for suite in suites:
            name = suite.get("name") or os.path.basename(path)
            totals[name] = totals.get(name, 0.0) + _suite_time(suite)
    return totals


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    durations = collect_durations(root)
    if not durations:
        print("check_junit_durations: no XML found, skipping (Gradle governs)")
        return 0
    ranked = sorted(durations.items(), key=lambda kv: kv[1], reverse=True)
    print(f"check_junit_durations: {len(ranked)} classes")
    for name, secs in ranked[:TOP_N]:
        print(f"  {secs:8.1f}s  {name}")
    slow = [(n, s) for n, s in ranked if s > PER_CLASS_BUDGET_SECONDS]
    if slow:
        print(
            f"check_junit_durations: FAILED "
            f"({len(slow)} class(es) > {PER_CLASS_BUDGET_SECONDS:.0f}s)"
        )
        for name, secs in slow:
            print(f"  - {secs:.1f}s  {name}")
        return 1
    print(
        f"check_junit_durations: PASSED "
        f"(slowest {ranked[0][1]:.1f}s <= {PER_CLASS_BUDGET_SECONDS:.0f}s)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
