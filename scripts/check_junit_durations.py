"""Fail-closed JUnit duration gate for Issue #86 (Phase C guardrail).

Stdlib only. Gate (exit non-zero fails CI): fails when any single test class
exceeds 120 seconds wall time. Only rejects / alerts — never silently
downgrades redaction or writes.

Reads `app/build/test-results/**/TEST-*.xml` (same glob as
`scripts/summarize_junit.py`, which stays reporting-only). Per-suite time is
the greater of the `<testsuite time="...">` attribute and the sum of its
direct `<testcase time="...">` children (a zeroed/NaN suite total cannot hide
slow cases).

Two-level aggregation:
1. Within each flavor/task directory (`app/build/test-results/<taskName>/...`),
   durations for the same class name are accumulated (summed) across suites
   and files (including nested shards) to prevent sharded or multi-suite tests
   from evading the budget (preventing the 140s evasion vulnerability).
2. Across flavors/tasks, the maximum (peak) duration represents the class so
   multi-flavor local runs do not sum together.

Malformed values (unparseable, unknown encoding, NaN/inf, negative), unreadable
files, unrecognized roots, reports with zero suites, and suites with no
usable timestamp at all are explicit ERRORS that fail the gate — a
present-but-unreadable (or present-but-timeless) report never silently
passes. Only the total absence of XML skips green (the Gradle step itself
governs missing results).

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
from pathlib import Path

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


def _task_key(path: str, test_results_dir: str) -> str:
    """Derive task key as the top-level directory directly under test-results/."""
    try:
        rel = os.path.relpath(path, test_results_dir)
        parts = Path(rel).parts
        if len(parts) > 1 and parts[0] != "..":
            return parts[0]
    except (ValueError, Exception):
        pass
    return os.path.dirname(path)


def analyze(root: str) -> tuple[dict[str, float], list[str]]:
    """Per-class durations plus explicit error entries (never silent).

    Durations are accumulated per class within each flavor/task directory
    (top-level directory directly under test-results/), and then aggregated
    across flavors/tasks by taking the peak (maximum).
    """
    pattern = os.path.join(root, JUNIT_GLOB)
    test_results_dir = os.path.join(root, "app", "build", "test-results")
    flavor_totals: dict[str, dict[str, float]] = {}
    errors: list[str] = []
    paths = sorted(glob.glob(pattern, recursive=True))
    for path in paths:
        try:
            tree = ET.parse(path)
        except (ET.ParseError, OSError, LookupError) as e:
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
        if not suites:
            errors.append(f"{path}: report contains no <testsuite>")
            continue
        task_key = _task_key(path, test_results_dir)
        if task_key not in flavor_totals:
            flavor_totals[task_key] = {}
        for suite in suites:
            name = suite.get("name") or os.path.basename(path)
            suite_time = _valid_time(suite.get("time"))
            if suite.get("time") is not None and suite_time is None:
                errors.append(
                    f"{path}: suite {name!r} has malformed "
                    f"time={suite.get('time')!r}"
                )
            case_total = 0.0
            valid_seen = suite_time is not None
            for case in suite.findall("testcase"):
                case_time = _valid_time(case.get("time"))
                if case.get("time") is not None and case_time is None:
                    errors.append(
                        f"{path}: case {case.get('classname')}"
                        f".{case.get('name')} has malformed "
                        f"time={case.get('time')!r}"
                    )
                if case_time is not None:
                    valid_seen = True
                    case_total += case_time
            if not valid_seen:
                # Present-but-timeless reports must not read as "0s, under
                # budget": without a single usable timestamp there is nothing
                # to judge, so this is an error, not a pass.
                errors.append(f"{path}: suite {name!r} has no usable time")
                continue
            suite_duration = max(
                suite_time if suite_time is not None else 0.0, case_total
            )
            flavor_totals[task_key][name] = (
                flavor_totals[task_key].get(name, 0.0) + suite_duration
            )

    totals: dict[str, float] = {}
    for class_durations in flavor_totals.values():
        for name, duration in class_durations.items():
            totals[name] = max(totals.get(name, 0.0), duration)
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
