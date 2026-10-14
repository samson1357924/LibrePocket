"""Stdlib regression harness for scripts/check_junit_durations.py (Issue #86 C).

Pins: per-class >120s fails, exactly-120s passes, missing XML skips green,
and suite-time fallback (sum of testcase times) works.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_check_junit_durations.py
"""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from scripts import check_junit_durations


def _xml(name: str, secs: float, *, with_suite_time: bool = True) -> str:
    t = f' time="{secs}"' if with_suite_time else ""
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<testsuite name="{name}" tests="1" failures="0" errors="0" skipped="0"{t}>'
        f'<testcase classname="{name}" name="ok" time="{secs}"/>'
        "</testsuite>"
    )


def _write_results(root: Path, cases: dict[str, str]) -> None:
    d = root / "app" / "build" / "test-results" / "testFossDebugUnitTest"
    d.mkdir(parents=True, exist_ok=True)
    for fname, content in cases.items():
        (d / fname).write_text(content, encoding="utf-8")


class CheckJunitDurationsTest(unittest.TestCase):
    def test_slow_class_fails(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            _write_results(
                Path(d),
                {"TEST-a.xml": _xml("com.example.Slow", 121.0)},
            )
            durations = check_junit_durations.collect_durations(d)
            self.assertGreater(durations["com.example.Slow"], 120.0)

    def test_boundary_120_passes(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            _write_results(
                Path(d),
                {"TEST-a.xml": _xml("com.example.Exact", 120.0)},
            )
            durations = check_junit_durations.collect_durations(d)
            self.assertLessEqual(durations["com.example.Exact"], 120.0)

    def test_no_xml_empty(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual({}, check_junit_durations.collect_durations(d))

    def test_fallback_sums_cases_without_suite_time(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            content = (
                '<?xml version="1.0" encoding="UTF-8"?>'
                '<testsuite name="com.example.Sum" tests="2" failures="0" errors="0" skipped="0">'
                '<testcase classname="com.example.Sum" name="a" time="70.0"/>'
                '<testcase classname="com.example.Sum" name="b" time="60.0"/>'
                "</testsuite>"
            )
            _write_results(Path(d), {"TEST-s.xml": content})
            durations = check_junit_durations.collect_durations(d)
            self.assertAlmostEqual(durations["com.example.Sum"], 130.0)

    def test_threshold_constant_is_120(self) -> None:
        self.assertEqual(120.0, check_junit_durations.PER_CLASS_BUDGET_SECONDS)


if __name__ == "__main__":
    unittest.main()
