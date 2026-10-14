"""Fail-closed guard for Issue #86: bound per-test write amplification.

Stdlib only. Gate (exit non-zero fails CI) — never silently downgrades to
unredacted writes; it only rejects / alerts.

Rules (test sources only, no prod change):
- H1 (huge-string via true shaping, heuristic tripwire): a `repeat(N>=10)`
  loop must NOT contain a true-path `.appendEvent(` write referencing a huge
  fixture — either a literal hint on the write line (`100_000` / `100_001` /
  `huge` / `big`) or a file-level `val`/`var <name> = ... .repeat(100_00x)`
  variable used by the write — within its 12-line window. Huge homogeneous
  strings through the true `redact` shaping are O(n^2) pre-Redactor-fix and
  amplified per row; PR tests must use the fast path
  (`appendPreRedactedForBudgetTest`, skipped per-line) or slim the loop. The
  nightly-only full stress (`ChatSnapshotByteCapFullStressTest.kt`,
  NIGHTLY_STRESS-gated) is allowlisted. Known gap (documented, not silent):
  fixtures passed through helper parameters or built indirectly (no
  `val/var = ... .repeat(100_00x)` shape) are not tracked — H1 is a tripwire,
  not a coverage proof.
- H2 (unbounded row count, loop-local): a single `repeat(N>=2000)` loop must
  NOT contain a store write (`.appendEvent(` or the pre-redacted fast path)
  inside its actual brace-delimited body (single-line loops close on their own
  line; strings/comments never count as braces). The old file-level
  `max(repeat)` check misfired when a big pure-compute loop and an unrelated
  single write shared a file. Current max is `repeat(1000)` (small-string
  row-cap pins, cheap); anything at or above 2000 needs explicit review +
  nightly placement.
  Limitation: nested-loop products (e.g. 100x100) are not multiplied — each
  loop is judged alone.

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

# `val payload = "x".repeat(100_000)` assigned anywhere in the file (String.repeat
# fixture bound to a variable, even far above the loop that uses it).
FIXTURE_VAR_RE = re.compile(
    r"(?:val|var)\s+(\w+)\s*=[^\n]*\.repeat\s*\(\s*100_00[01]"
)

HUGE_REPEAT_MIN = 10
ROW_REPEAT_MAX = 2000
# Fixed lookback for H1's loop-local window (H1 is a tripwire over the common
# `repeat(N) { ...writes... }` shape; H2 below is brace-aware instead).
REPEAT_WINDOW_LINES = 12


def _mask_kotlin_braces(lines: list[str]) -> tuple[list[int], list[bool]]:
    """Per-line brace deltas and has-brace flags, ignoring Kotlin strings and
    comments (so `{`/`}` inside `"..."`, `\"\"\"...\"\"\"`, `'c'`, `//...` and
    `/*...*/` never open or close a counted block). Tracks block comments and
    raw strings across lines; `$"..."` template braces are string content."""
    deltas: list[int] = []
    has_brace: list[bool] = []
    in_block = False
    in_raw = False
    for line in lines:
        depth = 0
        braced = False
        i, n = 0, len(line)
        in_str = False
        in_char = False
        while i < n:
            c = line[i]
            nxt = line[i + 1] if i + 1 < n else ""
            if in_block:
                if c == "*" and nxt == "/":
                    in_block = False
                    i += 2
                    continue
                i += 1
                continue
            if in_raw:
                if line[i : i + 3] == '"""':
                    in_raw = False
                    i += 3
                    continue
                i += 1
                continue
            if in_str:
                if c == "\\":
                    i += 2
                    continue
                if c == '"':
                    in_str = False
                i += 1
                continue
            if in_char:
                if c == "\\":
                    i += 2
                    continue
                if c == "'":
                    in_char = False
                i += 1
                continue
            if c == "/" and nxt == "/":
                break
            if c == "/" and nxt == "*":
                in_block = True
                i += 2
                continue
            if line[i : i + 3] == '"""':
                in_raw = True
                i += 3
                continue
            if c == '"':
                in_str = True
                i += 1
                continue
            if c == "'":
                in_char = True
                i += 1
                continue
            if c == "{":
                depth += 1
                braced = True
            elif c == "}":
                depth -= 1
                braced = True
            i += 1
        deltas.append(depth)
        has_brace.append(braced)
    return deltas, has_brace


def _repeat_body_lines(
    deltas: list[int], has_brace: list[bool], idx: int
) -> tuple[int, int]:
    """Inclusive (start, end) line range of a `repeat(N)` loop body.

    Single-line `repeat(N) { ... }` closes on its own line; otherwise the body
    runs until the matching close (the closing line included). A header whose
    `{` sits on a following line (up to +5) is supported; anything more exotic
    falls back to the header line alone (documented fail-open)."""
    n = len(deltas)
    cum = [0] * (n + 1)
    for i, d in enumerate(deltas):
        cum[i + 1] = cum[i] + d
    d0 = cum[idx]
    if has_brace[idx] and cum[idx + 1] == d0:
        return (idx, idx)
    open_idx = idx
    if not has_brace[idx]:
        open_idx = idx + 1
        while (
            open_idx < n
            and open_idx <= idx + 5
            and not has_brace[open_idx]
        ):
            open_idx += 1
        if open_idx >= n or open_idx > idx + 5 or not has_brace[open_idx]:
            return (idx, idx)
        d0 = cum[open_idx]
    end = open_idx
    k = open_idx + 1
    while k < n and cum[k] > d0:
        end = k
        k += 1
    if k < n and k > open_idx and has_brace[k] and cum[k + 1] <= d0:
        end = k
    return (idx, end)


def _fixture_vars(content: str) -> set[str]:
    """Variable names bound to a 100k String.repeat fixture anywhere in the file."""
    return {m.group(1) for m in FIXTURE_VAR_RE.finditer(content)}


def _huge_loop_violation(lines: list[str], fixture_vars: set[str]) -> bool:
    var_res = [
        re.compile(r"\b" + re.escape(name) + r"\b") for name in sorted(fixture_vars)
    ]
    for idx, line in enumerate(lines):
        m = REPEAT_RE.search(line)
        if not m or int(m.group(1)) < HUGE_REPEAT_MIN:
            continue
        window = lines[idx : idx + REPEAT_WINDOW_LINES]
        for follow in window:
            if FAST_PATH_RE.search(follow):
                continue
            if APPEND_EVENT_RE.search(follow) and (
                HUGE_HINT_RE.search(follow)
                or any(v.search(follow) for v in var_res)
            ):
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


def _h2_loop_violation(
    lines: list[str], deltas: list[int], has_brace: list[bool]
) -> str | None:
    """Single-loop H2: repeat(N>=2000) with a store write inside its actual
    brace-delimited body (not a fixed line window, so adjacent methods and
    long bodies are both handled)."""
    for idx, line in enumerate(lines):
        m = REPEAT_RE.search(line)
        if not m or int(m.group(1)) < ROW_REPEAT_MAX:
            continue
        _, end = _repeat_body_lines(deltas, has_brace, idx)
        if any(
            APPEND_EVENT_RE.search(w) or FAST_PATH_RE.search(w)
            for w in lines[idx : end + 1]
        ):
            return f"repeat({m.group(1)})"
    return None


def check_file(path: str) -> list[str]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
    except OSError as e:
        return [f"{path}: unreadable ({e})"]
    base = os.path.basename(path)
    lines = content.splitlines()
    deltas, has_brace = _mask_kotlin_braces(lines)
    violations: list[str] = []
    if base not in ALLOWLIST_HUGE_TRUE_PATH and _huge_loop_violation(
        lines, _fixture_vars(content)
    ):
        violations.append(
            f"{path}: H1 huge-string loop via true appendEvent "
            f"(repeat>={HUGE_REPEAT_MIN} with .appendEvent on a huge fixture; "
            f"use appendPreRedactedForBudgetTest or move to nightly)"
        )
    h2_loop = _h2_loop_violation(lines, deltas, has_brace)
    if h2_loop is not None:
        violations.append(
            f"{path}: H2 unbounded write loop ({h2_loop} with a store write "
            f"in its body; needs review + nightly)"
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
