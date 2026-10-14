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

    def test_h1_aliased_payload_outside_window_fails(self) -> None:
        # PR #87 P2-1: the fixture variable is defined far above the loop
        # (outside the 12-line window) and referenced by an alias-free name.
        filler = "".join(f'// filler line {i}\n' for i in range(20))
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "AliasTest.kt",
                'val payload = "x".repeat(100_000)\n'
                + filler
                + "repeat(100) {\n"
                + "    store.appendEvent(event(sid, payload))\n"
                + "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_unrelated_huge_var_with_small_loop_passes(self) -> None:
        # A huge fixture exists in the file but the loop writes small rows.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "UnrelatedTest.kt",
                'val payload = "x".repeat(100_000)\n'
                'repeat(50) { store.appendEvent(event(sid, "q-$it")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_fixture_var_extraction(self) -> None:
        self.assertEqual(
            {"payload", "big"},
            check_test_budgets._fixture_vars(
                'val payload = "x".repeat(100_000)\n'
                "var big = \"z\".repeat(100_001)\n"
                'val small = "q"\n',
            ),
        )
        self.assertEqual(
            set(), check_test_budgets._fixture_vars('val n = 100_000\n')
        )

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

    def test_h2_adjacent_methods_unrelated_single_append_passes(self) -> None:
        # PR #87 Round2 P2-1: adjacent independent methods — the closed
        # single-line compute loop must not capture the next method's write.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "AdjacentTest.kt",
                "@Test fun compute() {\n"
                '    repeat(2000) { println(it) }\n'
                "}\n"
                "@Test fun write() = runBlocking {\n"
                '    store.appendEvent(event(sid, "foo"))\n'
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_big_compute_loop_plus_unrelated_single_append_passes(self) -> None:
        # PR #87 P2-2: a big pure-compute loop and an unrelated single write
        # in another method must NOT count as a 2000-row write loop.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MixedTest.kt",
                (
                    "@Test fun bigCompute() {\n"
                    '    repeat(2000) { println("ok $it") }\n'
                    "}\n" + "\n" * 20
                    + "@Test fun singleWrite() = runBlocking {\n"
                    '    store.appendEvent(event(sid, "hello"))\n'
                    "}\n"
                ),
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_late_write_in_long_body_fails(self) -> None:
        # PR #87 Round2 P2-1: a genuine write past the old 12-line window,
        # inside one open block, must still fail.
        setup = "".join(f"    // setup line {i}\n" for i in range(15))
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "LateWriteTest.kt",
                "repeat(2000) {\n" + setup
                + '    store.appendEvent(event(sid, "q"))\n'
                + "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_braces_in_strings_and_comments_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "BracesTest.kt",
                "@Test fun compute() {\n"
                '    repeat(2000) { println("{ not a block }") }\n'
                '    // } stray closer in comment\n'
                "    /* { stray opener in block */\n"
                "}\n"
                "@Test fun write() = runBlocking {\n"
                '    store.appendEvent(event(sid, "foo"))\n'
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_newline_brace_style_write_fails(self) -> None:
        # Non-idiomatic header whose `{` sits on the next line.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NewlineBraceTest.kt",
                "repeat(2000)\n"
                "{\n"
                '    store.appendEvent(event(sid, "q"))\n'
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_boundary_1999_passes_2000_fails(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            ok = _write(
                Path(d),
                "Boundary1999.kt",
                'repeat(1999) { store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(ok))
            bad = _write(
                Path(d),
                "Boundary2000Fast.kt",
                "repeat(2000) {\n"
                "    store.appendPreRedactedForBudgetTest(event(sid, huge))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(bad)
            self.assertTrue(any("H2" in x for x in v), v)


if __name__ == "__main__":
    unittest.main()
