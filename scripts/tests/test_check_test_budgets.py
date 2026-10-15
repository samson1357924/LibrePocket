"""Stdlib regression harness for scripts/check_test_budgets.py (Issue #86 C).

Pins: H1 huge-loop via true path fails (unless allowlisted nightly stress or
fast-path present), H2 repeat>=2000 fails, current PR-slim shapes pass, and the
should-pass control (small loops) stays green.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_check_test_budgets.py
"""

from __future__ import annotations

import os
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

    def test_h1_typed_alias_fails(self) -> None:
        # PR #87 Round2 P2-2: typed declaration.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "TypedTest.kt",
                'val payload: String = "x".repeat(100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_size_variants_threshold(self) -> None:
        # No-underscore, larger, and just-below-threshold sizes.
        for name, size, expect_hit in (
            ("NoUnderscoreTest.kt", "100000", True),
            ("BiggerTest.kt", "150_000", True),
            ("BelowTest.kt", "99_999", False),
        ):
            with self.subTest(size=size):
                with tempfile.TemporaryDirectory() as d:
                    p = _write(
                        Path(d),
                        name,
                        f'val payload = "x".repeat({size})\n'
                        "repeat(100) {\n"
                        "    store.appendEvent(event(sid, payload))\n"
                        "}\n",
                    )
                    v = check_test_budgets.check_file(p)
                    if expect_hit:
                        self.assertTrue(any("H1" in x for x in v), v)
                    else:
                        self.assertEqual([], v)

    def test_h1_same_line_fast_plus_real_fails(self) -> None:
        # Per-call exemption: the fast-path call is stripped, the true-path
        # write on the same line is still examined (string-aware paren skip:
        # the ")" inside "a)b" must not end the stripped segment early).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(100) { store.appendPreRedactedForBudgetTest(event(sid, \"a)b\")); "
                "store.appendEvent(event(sid, payload)) }\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_same_line_fast_only_passes(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "FastOnlyTest.kt",
                'val huge = "x".repeat(100_000)\n'
                "repeat(11) { store.appendPreRedactedForBudgetTest(event(sid, huge)) }\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_multiline_appendEvent_varref_fails(self) -> None:
        # Stage 2: cross-line leak — `.appendEvent(` opens on one line and
        # the huge varref sits on a following line of the same span.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineTest.kt",
                'val payload: String = "x".repeat(100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(\n"
                "        event(sid, payload)\n"
                "    )\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_multiline_appendEvent_fastpath_passes(self) -> None:
        # Stage 2 guard: a multiline fast-path call wrapping huge keeps its
        # per-call exemption (fix A must not break E).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineFastTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(100) {\n"
                "    store.appendPreRedactedForBudgetTest(\n"
                "        event(sid, payload)\n"
                "    )\n"
                "}\n",
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

    def test_h1_compute_loop_plus_unrelated_single_huge_write_passes(
        self,
    ) -> None:
        # Stage 3 (a1): a pure-compute repeat loop and an unrelated single
        # huge write in a sibling method share a 12-line span but no data
        # flow — the old fixed-window check misfired; the body-local check
        # must pass.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "ComputePlusSingleTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "@Test fun bigCompute() {\n"
                '    repeat(100) { println("ok $it") }\n'
                "}\n"
                "@Test fun singleWrite() = runBlocking {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_compute_loop_plus_unrelated_single_inline_huge_passes(
        self,
    ) -> None:
        # Stage 3 (a2): inline variant — the unrelated single write carries
        # its own inline huge constructor, still outside the compute loop's
        # body, so no H1.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "ComputePlusInlineTest.kt",
                "@Test fun bigCompute() {\n"
                '    repeat(100) { println("ok $it") }\n'
                "}\n"
                "@Test fun singleWrite() = runBlocking {\n"
                '    store.appendEvent(event(sid, text = "y".repeat(100_001)))\n'
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_shadowed_payload_small_loop_passes(self) -> None:
        # Stage 3 (b): same-name shadowing — the loop's function redefines
        # `payload` as small, so the top-level huge fixture of the same name
        # is not on the write's visible path.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "ShadowedTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "@Test fun smallLoop() = runBlocking {\n"
                '    val payload = "ok"\n'
                "    repeat(100) {\n"
                '        store.appendEvent(event(sid, payload))\n'
                "    }\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_run_block_small_does_not_shadow_outer_huge(self) -> None:
        # Stage P2-2 (FN): a `run { val payload = "q" }` temporary ends
        # before the loop, so the outer huge fixture stays on the write's
        # visible path and must fail.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "RunShadowTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "x".repeat(100_000)\n'
                '    run { val payload = "q" }\n'
                "    repeat(100) {\n"
                "        store.appendEvent(event(sid, payload))\n"
                "    }\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_run_block_huge_does_not_leak(self) -> None:
        # Stage P2-2 (FP): a huge fixture built inside `run { ... }` ends
        # with its block, so the loop writing the outer small value passes.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "RunLeakTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "q"\n'
                '    run { val payload = "x".repeat(100_000) }\n'
                "    repeat(100) {\n"
                "        store.appendEvent(event(sid, payload))\n"
                "    }\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_if_block_huge_does_not_leak(self) -> None:
        # Stage P2-2 (FP): same ended-block shape via `if (cond) { ... }`.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "IfLeakTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "q"\n'
                '    if (cond) { val payload = "x".repeat(100_000) }\n'
                "    repeat(100) {\n"
                "        store.appendEvent(event(sid, payload))\n"
                "    }\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_if_block_small_does_not_shadow_outer_huge(self) -> None:
        # Stage P2-2 (FN): `if` variant — the ended small temporary must not
        # shadow the outer huge fixture.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "IfShadowTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "x".repeat(100_000)\n'
                '    if (cond) { val payload = "q" }\n'
                "    repeat(100) {\n"
                "        store.appendEvent(event(sid, payload))\n"
                "    }\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_also_block_huge_does_not_leak(self) -> None:
        # Stage P2-2 (FP): `also`/`let`-style scope-function temporaries end
        # with their block too.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "AlsoLeakTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "q"\n'
                '    1.also { val payload = "x".repeat(100_000) }\n'
                "    repeat(100) {\n"
                "        store.appendEvent(event(sid, payload))\n"
                "    }\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_loop_body_def_before_write_still_fails(self) -> None:
        # Stage P2-2 retention: a huge def inside the loop body itself,
        # before the write and in a still-open block, stays visible.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "LoopBodyDefTest.kt",
                "@Test fun f() = runBlocking {\n"
                "    repeat(100) {\n"
                '        val payload = "x".repeat(100_000)\n'
                "        store.appendEvent(event(sid, payload))\n"
                "    }\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_same_line_run_huge_does_not_leak(self) -> None:
        # Stage P2-2 same-line close (FP): the huge `run` temporary ends
        # on the same line before the write, so the outer small stays.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineRunLeakTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "q"\n'
                "    repeat(100) { run { val payload = "
                '"x".repeat(100_000) }; '
                "store.appendEvent(event(sid, payload)) }\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_same_line_run_small_does_not_shadow_outer_huge(self) -> None:
        # Stage P2-2 same-line close (FN): the small `run` temporary ends
        # on the same line before the write, so the outer huge stays.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineRunShadowTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "x".repeat(100_000)\n'
                '    repeat(100) { run { val payload = "q" }; '
                "store.appendEvent(event(sid, payload)) }\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_same_line_def_after_use_passes(self) -> None:
        # Stage P2-2 def-after-use: a huge def after the write on the
        # same line is not visible to it (column-ordered, no backflow).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineDefAfterUseTest.kt",
                "@Test fun f() = runBlocking {\n"
                "    repeat(100) { store.appendEvent(event(sid, payload)); "
                'val payload = "x".repeat(100_000) }\n'
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_multiline_span_same_line_close_huge_does_not_leak(
        self,
    ) -> None:
        # Stage P2-2 multiline span (FP): the huge `run` block closes on
        # the span's first line while the `payload` reference sits on the
        # next line — per-reference columns keep it out of scope.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineCloseLeakTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "q"\n'
                "    repeat(100) {\n"
                '        run { val payload = "x".repeat(100_000) }; '
                "store.appendEvent(\n"
                "            event(sid, payload)\n"
                "        )\n"
                "    }\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_multiline_span_same_line_close_small_does_not_shadow(
        self,
    ) -> None:
        # Stage P2-2 multiline span (FN): the small `run` block closes on
        # the span's first line while the huge reference sits on the next
        # line — the outer huge stays visible.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineCloseShadowTest.kt",
                "@Test fun f() = runBlocking {\n"
                '    val payload = "x".repeat(100_000)\n'
                "    repeat(100) {\n"
                '        run { val payload = "q" }; store.appendEvent(\n'
                "            event(sid, payload)\n"
                "        )\n"
                "    }\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_late_write_in_long_body_fails(self) -> None:
        # Stage 3 (c): a genuine huge write on the 16th body line — past the
        # old 12-line window — must still fail via the brace-aware body.
        setup = "".join(f"        // setup line {i}\n" for i in range(15))
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "LateHugeWriteTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "@Test fun lateWrite() = runBlocking {\n"
                "    repeat(100) {\n" + setup
                + "        store.appendEvent(event(sid, payload))\n"
                + "    }\n"
                + "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h2_repeat_2000_fails(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "Huge2kTest.kt",
                'repeat(2000) { store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v))

    def test_h2_underscored_loop_count_normalized(self) -> None:
        # `repeat(2_000)` must read as 2000, not 2.
        with tempfile.TemporaryDirectory() as d:
            bad = _write(
                Path(d),
                "Under2kTest.kt",
                'repeat(2_000) { store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(bad)
            self.assertTrue(any("H2" in x for x in v), v)
            ok = _write(
                Path(d),
                "UnderSmallTest.kt",
                'repeat(1_0) { store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(ok))

    def test_h1_underscored_loop_count_triggers_huge_gate(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "UnderHugeTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(1_1) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

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

    def test_h2_commented_repeat_with_write_passes(self) -> None:
        # Stage 1 masked lines: a commented-out loop must not count.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "CommentedTest.kt",
                "// repeat(2000){ store.appendEvent(event(sid, \"q\")) }\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_string_demo_repeat_with_write_passes(self) -> None:
        # Stage 1 masked lines: demo code inside a string must not count.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "StringDemoTest.kt",
                'val s = "repeat(2000){ store.appendEvent(e) }"\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_prefixed_single_line_loop_with_outer_write_passes(self) -> None:
        # Stage 1 column-aware body: `fun test() { repeat(2000) { ... }`
        # closes on its own line, so the following write outside the loop
        # (but inside the function) must not count.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "PrefixedTest.kt",
                "fun test() { repeat(2000) { println(it) }\n"
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

    def test_h2_single_line_nested_block_comment_passes(self) -> None:
        # P2-3: Kotlin block comments nest — the inner `*/` closes only
        # the inner level, so the `repeat` stays inside the outer comment.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NestedSingleTest.kt",
                "/* outer /* inner */ "
                'repeat(2_000){store.appendEvent(event(sid,"q"))} */\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_multiline_nested_block_comment_passes(self) -> None:
        # P2-3: the inner `*/` must not close the outer level; the loop on
        # the following line is still commented out.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NestedMultiTest.kt",
                "/* outer\n"
                "   /* inner */\n"
                '   repeat(2_000){store.appendEvent(event(sid,"q"))}\n'
                "*/\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_triple_nested_with_braces_and_quotes_passes(self) -> None:
        # P2-3: 3 levels plus `{`/`}`/quotes inside the comment stay masked
        # and never affect brace pairing.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NestedTripleTest.kt",
                '/* a /* b /* c { " } " */\n'
                '   repeat(2_000){store.appendEvent(event(sid,"q"))}\n'
                "   */\n"
                "   */\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_unclosed_outer_block_comment_passes(self) -> None:
        # P2-3: unclosed outer level keeps EOF semantics — lines after the
        # inner close are still in the comment.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NestedUnclosedTest.kt",
                "/* outer /* inner */\n"
                'repeat(2_000){store.appendEvent(event(sid,"q"))}\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_closed_block_then_real_loop_fails(self) -> None:
        # P2-3 non-nested control: a closed `/* a */` must not over-mask —
        # the real loop after it still triggers H2.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "ClosedBlockControlTest.kt",
                "/* a */ "
                'repeat(2_000){store.appendEvent(event(sid,"q"))}\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_line_comment_block_opener_ignored(self) -> None:
        # P2-3: `/*` inside `//` never opens a block, so the real loop on
        # the next line still triggers H2.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "LineCommentBlockTest.kt",
                "// /* not a block\n"
                'repeat(2_000){store.appendEvent(event(sid,"q"))}\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_string_block_opener_ignored(self) -> None:
        # P2-3: `/*` inside a string never counts toward block depth, so
        # the real loop after the string still triggers H2 (no over-mask).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "StringBlockTest.kt",
                'val s = "/* not a block"\n'
                'repeat(2_000){store.appendEvent(event(sid,"q"))}\n',
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


    def test_h2_same_line_trailing_write_passes(self) -> None:
        # A write after the loop's own `}` on the same line is outside the
        # brace-delimited body and must not count.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "TrailingWriteTest.kt",
                'repeat(2_000){println(it)}; store.appendEvent(event(sid,"q"))\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_same_line_second_loop_fails(self) -> None:
        # Every repeat on a line is examined: the small first loop passes
        # but the second big loop with a body write must fail.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SecondLoopTest.kt",
                'repeat(1){}; repeat(2_000){store.appendEvent(event(sid,"q"))}\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h1_same_line_trailing_write_passes(self) -> None:
        # Huge-fixture variant of the trailing-write shape: the compute
        # loop body is `{println(it)}`, the huge write after its `}` is
        # outside and must not trip H1.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "HugeTrailingTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(100){println(it)}; store.appendEvent(event(sid,payload))\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_same_line_second_loop_fails(self) -> None:
        # Huge-fixture variant: the first small loop is below the H1
        # threshold, the second loop carries the huge write in its own
        # `{...}` and must fail.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "HugeSecondLoopTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(1){}; repeat(100){store.appendEvent(event(sid,payload))}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)


    def test_h1_small_text_large_timestamp_passes(self) -> None:
        # P2-3: numeric metadata (createdAt/runId/kind/sessionId) is never
        # huge evidence — a bare 100_000-class number alone must pass.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "TimestampMetaTest.kt",
                "repeat(100){store.appendEvent(TranscriptEvent(sessionId=sid, "
                'runId="r$it", kind="user", text="q", createdAt=100_000L))}\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_small_var_named_huge_passes(self) -> None:
        # P2-3: a small variable merely named `huge` is a gate, not proof —
        # with no scope-visible huge definition it must pass.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SmallHugeTest.kt",
                'val huge = "q"\n'
                "repeat(100) { store.appendEvent(event(sid, huge)) }\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_small_var_named_big_passes(self) -> None:
        # P2-3: same gate semantics for the `big` name.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SmallBigTest.kt",
                'val big = "q"\n'
                "repeat(100) { store.appendEvent(event(sid, big)) }\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_inline_huge_in_loop_still_fails(self) -> None:
        # P2-3 retention: an inline >=100k repeat constructor in the write
        # span still fails after the bare-number branch is removed.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "InlineHugeLoopTest.kt",
                "repeat(100) { store.appendEvent(event(sid, "
                'text="y".repeat(100_001))) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h2_multiline_header_fails(self) -> None:
        # P2-1 multiline: `repeat(` / count / `)` split across lines must
        # still trip H2 (joined masked-text iteration, end-based pairing).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineH2Test.kt",
                "repeat(\n"
                "  2000\n"
                '){ store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h1_multiline_header_fails(self) -> None:
        # P2-1 multiline H1: small-shape count split across lines with a
        # huge write in the braced body.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineH1Test.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(\n"
                "  100\n"
                ") {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h2_multiline_underscored_count_fails(self) -> None:
        # `repeat(\\n 2_000\\n)` normalizes to 2000 across lines.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineUnderTest.kt",
                "repeat(\n"
                "  2_000\n"
                '){ store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_newline_before_paren_fails(self) -> None:
        # `repeat\\n(2000)` — whitespace between the name and `(`.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NewlineParenTest.kt",
                "repeat\n"
                '(2000){ store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_block_comment_between_paren_and_count_fails(self) -> None:
        # `repeat(/*c*/\\n 2000)` — masked comment is whitespace for `\\s*`.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "BlockCommentCountTest.kt",
                "repeat(/*c*/\n"
                "  2000)"
                '{ store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_line_comment_between_paren_and_count_fails(self) -> None:
        # `repeat( //...\\n 2000)` — line comment blanks to end of line.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "LineCommentCountTest.kt",
                "repeat( // hi\n"
                "  2000)"
                '{ store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_multiline_small_count_passes(self) -> None:
        # Threshold unchanged: multiline `1000` stays under H2.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineSmallH2Test.kt",
                "repeat(\n"
                "  1000\n"
                '){ store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_multiline_small_count_passes(self) -> None:
        # Threshold unchanged: multiline `5` stays under H1.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MultilineSmallH1Test.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(\n"
                "  5\n"
                ") {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_dot_newline_repeat_passes(self) -> None:
        # `foo.\\nrepeat(2000)` is member access, not a loop (post-filter).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotNewlineTest.kt",
                "foo.\n"
                'repeat(2000){ store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))


    def test_h2_dot_space_fixture_no_loop_fp(self) -> None:
        # Stage 4A: `val payload = "x" .repeat (100_000)` (space before the
        # dot, legal Kotlin) is a fixture constructor, not a loop — the
        # whitespace-skipping backscan discards it, so a later brace block
        # with a write must not trip H2 (nor H1: the write is outside any
        # loop).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotSpaceTest.kt",
                'val payload = "x" .repeat (100_000)\n'
                "fun f() {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_dot_space_fixture_still_fails(self) -> None:
        # Stage 4A retention: the same dot-space fixture still resolves for
        # H1 — a genuine huge write loop fails H1 (and only H1).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotSpaceHugeTest.kt",
                'val payload = "x" .repeat (100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h2_dot_space_after_dot_no_loop_fp(self) -> None:
        # Stage 4A: space between `.` and `repeat` (`"x". repeat(...)`) is
        # also member access — never a loop, so no H2 from the fixture line.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotSpaceAfterTest.kt",
                'val payload = "x". repeat (100_000)\n'
                "fun f() {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h2_myrepeat_name_passes(self) -> None:
        # Stage 4A retention: only `.` discards — a `myrepeat` identifier
        # stays excluded by the immediate `\\w` lookbehind, not by the
        # backscan (no H2 either way: it is not a `repeat` loop at all).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "MyRepeatTest.kt",
                'myrepeat(2000){ store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_two_statements_second_line_loop_fails(self) -> None:
        # Stage 4A retention: `\\w` across a newline must NOT discard —
        # `val x = 1\\nrepeat(2000){...}` is two statements and the second
        # line is a genuine loop.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "TwoStmtsTest.kt",
                "val x = 1\n"
                'repeat(2000){ store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_fixture_vars_same_line_multi_defs(self) -> None:
        # Stage 4B: the greedy fixture expression bound the last count on
        # the line to the first name (`a` -> 50, `b` lost). With `;`
        # excluded, each definition keeps its own count.
        self.assertEqual(
            {"a"},
            check_test_budgets._fixture_vars(
                'val a = "x".repeat(100_000); val b = "y".repeat(50)\n',
            ),
        )

    def test_scoped_var_defs_same_line_per_def(self) -> None:
        # Stage 4B: line-level huge marking bled across same-line defs.
        line = 'val a = "x".repeat(100_000); val b = "ok"'
        defs = check_test_budgets._scoped_var_defs(
            check_test_budgets._masked_kotlin_lines([line])
        )
        self.assertEqual([(0, line.find("val a"), True)], defs["a"])
        self.assertEqual([(0, line.find("val b"), False)], defs["b"])

    def test_scoped_var_defs_same_line_same_name_redefinition(self) -> None:
        # Stage 4B: same-line, same-name redefinition is per-def — the small
        # half stays small in both orders.
        line = 'val payload = "x".repeat(100_000); val payload = "q"'
        defs = check_test_budgets._scoped_var_defs(
            check_test_budgets._masked_kotlin_lines([line])
        )
        second = line.find("val", 1)
        self.assertEqual([(0, 0, True), (0, second, False)], defs["payload"])
        rev = 'val payload = "q"; val payload = "x".repeat(100_000)'
        rev_defs = check_test_budgets._scoped_var_defs(
            check_test_budgets._masked_kotlin_lines([rev])
        )
        rev_second = rev.find("val", 1)
        self.assertEqual(
            [(0, 0, False), (0, rev_second, True)], rev_defs["payload"]
        )

    def test_h1_same_line_huge_and_small_write_huge_fails(self) -> None:
        # Stage 4B end-to-end (FN): `a`'s huge count was lost to greedy
        # attribution, so writing `a` passed; now H1 fires.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineHugeTest.kt",
                'val a = "x".repeat(100_000); val b = "ok"\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, a))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_same_line_huge_and_small_write_small_passes(self) -> None:
        # Stage 4B end-to-end (FP): line-level marking flagged `b` huge
        # because it shared a line with `a`; now writing `b` passes.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineSmallTest.kt",
                'val a = "x".repeat(100_000); val b = "ok"\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, b))\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_same_line_shadow_small_after_huge_passes(self) -> None:
        # Stage 4B end-to-end shadowing: the nearer same-line small
        # redefinition clears the huge fixture for the later write.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineShadowTest.kt",
                'val payload = "x".repeat(100_000); val payload = "q"\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_same_line_shadow_huge_after_small_fails(self) -> None:
        # Stage 4B end-to-end shadowing (reverse order): the nearer same-line
        # huge redefinition is on the write's visible path.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "SameLineReshadowTest.kt",
                'val payload = "q"; val payload = "x".repeat(100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)

    def test_h1_dot_after_space_fixture_fails(self) -> None:
        # R6P2-3: space between `.` and `repeat` (`"x". repeat(...)`, legal
        # Kotlin) is still a huge fixture — the write loop must fail H1
        # (and only H1: the fixture constructor is never a loop).
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterSpaceTest.kt",
                'val payload = "x". repeat(100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h1_dot_after_space_inline_fails(self) -> None:
        # R6P2-3 inline: `appendEvent("x". repeat(100_001))` in the loop
        # carries its own huge constructor across the dot-post gap.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterSpaceInlineTest.kt",
                "repeat(100) { store.appendEvent(event(sid, "
                'text="y". repeat(100_001))) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h1_dot_after_tab_fixture_fails(self) -> None:
        # R6P2-3 representative variant: tab between `.` and `repeat`.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterTabTest.kt",
                'val payload = "x".\trepeat(100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h1_dot_after_block_comment_fixture_fails(self) -> None:
        # R6P2-3 representative variant: masked block comment between `.`
        # and `repeat` blanks to spaces on masked lines, so `\s*` bridges it.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterCommentTest.kt",
                'val payload = "x". /*c*/ repeat(100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h1_dot_after_space_chained_fixture_fails(self) -> None:
        # R6P2-3 representative variant: chained gaps at all three slots
        # (`"x" . repeat (100_000)`) still resolves the huge fixture.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterChainedTest.kt",
                'val payload = "x" . repeat (100_000)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h1_dot_after_block_comment_inline_fails(self) -> None:
        # R6P2-3 inline masked-comment variant: the huge constructor inside
        # the write span bridges the blanked comment.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterCommentInlineTest.kt",
                "repeat(100) { store.appendEvent(event(sid, "
                'text="y". /*c*/ repeat(100_001))) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h2_dot_after_space_fixture_no_loop_fp(self) -> None:
        # R6P2-3 H2 pin: the dot-post-space fixture constructor is member
        # access, never a loop — the loop-side whitespace backscan discards
        # it, so a lone fixture plus an out-of-loop write stays clean.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterSpaceLoneTest.kt",
                'val payload = "x". repeat(100_000)\n'
                "fun f() {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertFalse(any("H2" in x for x in v), v)
            self.assertEqual([], v)

    def test_h1_dot_after_space_below_threshold_passes(self) -> None:
        # R6P2-3 precision pin: dot-post-space shape at `99_999` stays
        # under HUGE_FIXTURE_MIN and must pass.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "DotAfterSpaceBelowTest.kt",
                'val payload = "x". repeat(99_999)\n'
                "repeat(100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_named_param_times_fails(self) -> None:
        # R6P2-1: `repeat(times = N)` is the same stdlib loop (sole
        # parameter is `times`, case-sensitive) — it must not bypass H2.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NamedParamTest.kt",
                'repeat(times = 2_000) { store.appendEvent(event(sid, "q")) }\n',
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H2" in x for x in v), v)

    def test_h2_named_param_spacing_variants_fail(self) -> None:
        # R6P2-1: no-whitespace / padded / masked-comment / cross-line
        # `times =` shapes all read as the same 2000-row loop (masked
        # comments blank to spaces; `\s*` spans newlines on joined text).
        variants = (
            'repeat(times=2000){ store.appendEvent(event(sid, "q")) }\n',
            'repeat( times = 2_000 ) { store.appendEvent(event(sid, "q")) }\n',
            "repeat(/*c*/ times /*c*/ = /*c*/ 2000)"
            '{ store.appendEvent(event(sid, "q")) }\n',
            "repeat(times =\n"
            '  2000){ store.appendEvent(event(sid, "q")) }\n',
        )
        for i, content in enumerate(variants):
            with self.subTest(i=i):
                with tempfile.TemporaryDirectory() as d:
                    p = _write(Path(d), f"NamedVariant{i}Test.kt", content)
                    v = check_test_budgets.check_file(p)
                    self.assertTrue(any("H2" in x for x in v), v)

    def test_h1_named_param_times_fails(self) -> None:
        # R6P2-1 H1: huge fixture plus `repeat(times = 100)` write loop.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NamedHugeTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(times = 100) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            v = check_test_budgets.check_file(p)
            self.assertTrue(any("H1" in x for x in v), v)
            self.assertFalse(any("H2" in x for x in v), v)

    def test_h2_named_param_below_threshold_passes(self) -> None:
        # R6P2-1 precision: `repeat(times = 1999)` stays under H2.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "Named1999Test.kt",
                'repeat(times = 1999) { store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h1_named_param_below_threshold_passes(self) -> None:
        # R6P2-1 precision: `repeat(times = 5)` stays under H1 even with a
        # huge fixture on the write path.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NamedSmallHugeTest.kt",
                'val payload = "x".repeat(100_000)\n'
                "repeat(times = 5) {\n"
                "    store.appendEvent(event(sid, payload))\n"
                "}\n",
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_named_param_variable_count_passes(self) -> None:
        # R6P2-1 negative: `repeat(times = n)` carries no literal count —
        # the gate only judges literal digits, so it passes.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NamedVarTest.kt",
                'repeat(times = n) { store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_named_param_myrepeat_passes(self) -> None:
        # R6P2-1 retention: the immediate-`\w` lookbehind still excludes a
        # `myrepeat` identifier carrying a named argument.
        with tempfile.TemporaryDirectory() as d:
            p = _write(
                Path(d),
                "NamedMyRepeatTest.kt",
                'myrepeat(times = 2000){ store.appendEvent(event(sid, "q")) }\n',
            )
            self.assertEqual([], check_test_budgets.check_file(p))

    def test_h2_named_param_member_access_passes(self) -> None:
        # R6P2-1 loop-side exclusion retention: member access is never a
        # loop, even with a named count — immediate `.repeat` via the
        # lookbehind, spaced `. repeat` via the whitespace backscan.
        # Synthetic shapes pinning the exclusion, not compilable Kotlin.
        variants = (
            '"x".repeat(times = 2000){ store.appendEvent(event(sid, "q")) }\n',
            '"x" .repeat(times = 2000){ store.appendEvent(event(sid, "q")) }\n',
            '"x". repeat(times = 2000){ store.appendEvent(event(sid, "q")) }\n',
        )
        for i, content in enumerate(variants):
            with self.subTest(i=i):
                with tempfile.TemporaryDirectory() as d:
                    p = _write(Path(d), f"NamedMember{i}Test.kt", content)
                    self.assertEqual([], check_test_budgets.check_file(p))

    def test_main_scans_all_test_source_sets(self) -> None:
        # Stage 4C pin: the repo-wide scan covers all four test source sets,
        # not just `app/src/test`.
        root = str(Path(check_test_budgets.__file__).resolve().parent.parent)
        self.assertEqual(
            ("test", "testFoss", "testGithub", "androidTest"),
            check_test_budgets.TEST_SOURCE_SETS,
        )
        files = check_test_budgets._test_source_files(root)
        posix = [p.replace(os.sep, "/") for p in files]
        for src in check_test_budgets.TEST_SOURCE_SETS:
            self.assertTrue(
                any(f"app/src/{src}/" in p for p in posix),
                f"source set {src} contributes no files",
            )


if __name__ == "__main__":
    unittest.main()
