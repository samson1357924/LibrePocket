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


if __name__ == "__main__":
    unittest.main()
