"""Fail-closed guard for Issue #86: bound per-test write amplification.

Stdlib only. Gate (exit non-zero fails CI) — never silently downgrades to
unredacted writes; it only rejects / alerts.

Rules (test sources only, no prod change):
- H1 (huge-string via true shaping): a `repeat(N>=10)` loop must NOT contain a
  true-path `.appendEvent(` write on a huge fixture (`100_000` / `100_001` /
  `huge` / `big`) within its 12-line window. Huge homogeneous strings through
  the true `redact` shaping are O(n^2) pre-Redactor-fix and amplified per row;
  PR tests must use the fast path (`appendPreRedactedForBudgetTest`, skipped
  per-line) or slim the loop. The nightly-only full stress
  (`ChatSnapshotByteCapFullStressTest.kt`, NIGHTLY_STRESS-gated) is allowlisted.
- H2 (unbounded row count): no file may `repeat(N>=2000)` with `.appendEvent(`.
  Current max is `repeat(1000)` (small-string row-cap pins, cheap); anything at
  or above 2000 needs explicit review + nightly placement.

Run with: python3 scripts/check_test_budgets.py
Test with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_check_test_budgets
"""

from __future__ import annotations

import glob
import os
import re
import sys

# Kotlin `repeat(N)` loop only — must NOT match `"x".repeat(100_000)"
# (String.repeat fixture). Negative lookbehind excludes the `.repeat` shape;
# `\w` excludes identifiers ending in repeat (none today, fail-closed).
REPEAT_RE = re.compile(r"(?<![.\w])repeat\s*\(\s*(\d+)")
APPEND_EVENT_RE = re.compile(r"\.appendEvent\s*\(")
FAST_PATH_RE = re.compile(r"appendPreRedactedForBudgetTest")
HUGE_HINT_RE = re.compile(r"100_000|100_001|\bhuge\b|\bbig\b")

# Nightly-only full stress: true shaping intentionally, NIGHTLY_STRESS-gated,
# independent 35-minute timeout, non-gating. Must stay out of the PR path.
ALLOWLIST_HUGE_TRUE_PATH = {
    "ChatSnapshotByteCapFullStressTest.kt",
}

HUGE_MARKER = "100_000"
HUGE_REPEAT_MIN = 10
ROW_REPEAT_MAX = 2000
# How many lines after `repeat(N)` to scan for a true-path huge write. Covers
# `repeat(100) {` + `store.appendEvent(... huge ...)` shapes without parsing
# Kotlin braces.
REPEAT_WINDOW_LINES = 12


def _huge_loop_violation(lines: list[str]) -> bool:
    for idx, line in enumerate(lines):
        m = REPEAT_RE.search(line)
        if not m or int(m.group(1)) < HUGE_REPEAT_MIN:
            continue
        window = lines[idx : idx + REPEAT_WINDOW_LINES]
        for follow in window:
            if FAST_PATH_RE.search(follow):
                continue
            if APPEND_EVENT_RE.search(follow) and HUGE_HINT_RE.search(follow):
                return True
        # Fixture defined inside the loop then used by a later write:
        # `val huge = "x".repeat(100_000)` in-window plus a true-path write.
        # (Bare `== 100_000` length assertions do NOT count — only fixture
        # creation via String.repeat.)
        if any(
            ".repeat" in w and ("100_000" in w or "100_001" in w) for w in window
        ):
            if any(
                APPEND_EVENT_RE.search(w) and not FAST_PATH_RE.search(w)
                for w in window
            ):
                return True
    return False


def check_file(path: str) -> list[str]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
    except OSError as e:
        return [f"{path}: unreadable ({e})"]
    base = os.path.basename(path)
    lines = content.splitlines()
    repeats = [int(m.group(1)) for m in REPEAT_RE.finditer(content)]
    has_append = APPEND_EVENT_RE.search(content) is not None
    violations: list[str] = []
    if base not in ALLOWLIST_HUGE_TRUE_PATH and _huge_loop_violation(lines):
        violations.append(
            f"{path}: H1 huge-string loop via true appendEvent "
            f"(repeat>={HUGE_REPEAT_MIN} with .appendEvent on a huge fixture; "
            f"use appendPreRedactedForBudgetTest or move to nightly)"
        )
    max_repeat = max(repeats) if repeats else 0
    if has_append and max_repeat >= ROW_REPEAT_MAX:
        violations.append(
            f"{path}: H2 unbounded write loop (repeat({max_repeat}) + "
            f".appendEvent >= {ROW_REPEAT_MAX}; needs review + nightly)"
        )
    return violations


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    pattern = os.path.join(root, "app", "src", "test", "**", "*.kt")
    files = sorted(glob.glob(pattern, recursive=True))
    if not files:
        print("check_test_budgets: no test files found (parser drift?)")
        return 1
    violations: list[str] = []
    for path in files:
        violations.extend(check_file(path))
    if violations:
        print("check_test_budgets: FAILED")
        for v in violations:
            print(f"  - {v}")
        return 1
    print(f"check_test_budgets: PASSED ({len(files)} files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
