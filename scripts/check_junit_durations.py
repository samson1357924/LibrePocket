"""Fail-closed JUnit duration gate for Issue #86 (Phase C guardrail).

Stdlib only. Gate (exit non-zero fails CI): fails when any single test class
exceeds 120 seconds wall time. Only rejects / alerts — never silently
downgrades redaction or writes.

Reads `app/build/test-results/**/TEST-*.xml` (same glob as
`scripts/summarize_junit.py`, which stays reporting-only). Per-class time is
the greater of the `<testsuite time="...">` attribute and the sum of its
direct `<testcase time="...">` children (a zeroed/NaN suite total cannot hide
slow cases). Malformed values (unparseable, NaN/inf, negative), unreadable
files, and unrecognized roots are explicit ERRORS that fail the gate — a
present-but-unreadable report never silently passes. Only the total absence
of XML skips green (the Gradle step itself governs missing results).

Threshold: 120s per class (Issue #86 acceptance: single-class >120s fails).

Run with: python3 scripts/check_junit_durations.py
Test with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_check_junit_durations
"""

from __future__ import annotations

import glob
import math
import os
import sys
import xml.etree.ElementTree as ET

JUNIT_GLOB = "app/build/test-results/**/TEST-*.xml"
PER_CLASS_BUDGET_SECONDS = 120.0
TOP_N = 10


def _valid_time(raw: str | None) -> float | None:
    """Parse a JUnit time value; None when missing-or-malformed (NaN/inf/<0)."""
    if raw is None or raw == "":
        return None
    try:
        value = float(raw)
    except ValueError:
        return None
    if not math.isfinite(value) or value < 0:
        return None
    return value


def analyze(root: str) -> tuple[dict[str, float], list[str]]:
    """Per-class durations plus explicit error entries (never silent)."""
    pattern = os.path.join(root, JUNIT_GLOB)
    totals: dict[str, float] = {}
    errors: list[str] = []
    paths = sorted(glob.glob(pattern, recursive=True))
    for path in paths:
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError) as e:
            errors.append(f"{path}: unreadable report ({e})")
            continue
        xml_root = tree.getroot()
        if xml_root.tag == "testsuites":
            suites = xml_root.findall("testsuite")
        elif xml_root.tag == "testsuite":
            suites = [xml_root]
        else:
            errors.append(f"{path}: unrecognized root <{xml_root.tag}>")
            continue
        for suite in suites:
            name = suite.get("name") or os.path.basename(path)
            suite_time = _valid_time(suite.get("time"))
            if suite.get("time") is not None and suite_time is None:
                errors.append(
                    f"{path}: suite {name!r} has malformed "
                    f"time={suite.get('time')!r}"
                )
            case_total = 0.0
            cases_seen = 0
            for case in suite.findall("testcase"):
                cases_seen += 1
                case_time = _valid_time(case.get("time"))
                if case.get("time") is not None and case_time is None:
                    errors.append(
                        f"{path}: case {case.get('classname')}"
                        f".{case.get('name')} has malformed "
                        f"time={case.get('time')!r}"
                    )
                case_total += case_time if case_time is not None else 0.0
            if suite_time is None and cases_seen == 0:
                errors.append(f"{path}: suite {name!r} has no usable time")
                continue
            totals[name] = totals.get(name, 0.0) + max(
                suite_time if suite_time is not None else 0.0, case_total
            )
    return totals, errors


def collect_durations(root: str) -> dict[str, float]:
    return analyze(root)[0]


def main(root: str | None = None) -> int:
    if root is None:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    durations, errors = analyze(root)
    if errors:
        print("check_junit_durations: FAILED (unreadable/invalid reports)")
        for e in errors:
            print(f"  - {e}")
        return 1
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
