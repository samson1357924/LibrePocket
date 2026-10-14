"""Stdlib regression harness for scripts/check_test_budgets.py (Issue #86 C).

Pins: H1 huge-loop via true path fails (unless allowlisted nightly stress or
fast-path present), H2 repeat>=2000 fails, current PR-slim shapes pass, and the
should-pass control (small loops) stays green.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_check_test_budgets.py
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts import check_test_budgets


def _write(tmp: Path, name: str, content: str) -> str:
    p = tmp / name
    p.write_text(content, encoding="utf-8")
    return str(p)


class CheckTestBudgetsTest(unittest.TestCase):
    def test_h1_bad_huge_true_path_fails(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "BadTest.kt",
                'val huge = "x".repeat(100_000)\n'
                "repeat(100) { store.appendEvent(event(sid, huge)) }\n",
            )
            self.assertTrue(check_test_budgets.check_file(p))

    def test_h1_fast_path_passes(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SlimTest.kt",
                'val huge = "x".repeat(100_000)\n'
                "repeat(11) { store.appendPreRedactedForBudgetTest(event(sid, huge)) }\n"
                'repeat(999) { store.appendEvent(event(sid, "q")) }\n',
            )
            # repeat(11) < HUGE_REPEAT_MIN is not the H1 shape; craft the real slim shape:
            # huge via fast path + big small-loop via true path must pass.
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_allowlisted_nightly_passes(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "ChatSnapshotByteCapFullStressTest.kt",
                'val huge = "x".repeat(100_000)\n'
                "repeat(100) { store.appendEvent(event(sid, huge)) }\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_single_huge_row_passes(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SessionStoreTest.kt",
                'store.appendEvent(event(sid, text = "y".repeat(100_001)))\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_repeat_2000_fails(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "Huge2kTest.kt",
                'repeat(2000) { store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v))

    def test_h2_repeat_1000_passes(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "RowCapTest.kt",
                'repeat(1000) { store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))


if __name__ == "__main__":
    unittest.main()
