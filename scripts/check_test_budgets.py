"""Fail-closed guard for Issue #86: bound per-test write amplification.

Stdlib only. Gate (exit non-zero fails CI) — never silently downgrades to
unredacted writes; it only rejects / alerts.

Rules (test sources only, no prod change):
- H1 (huge-string via true shaping, heuristic tripwire): a `repeat(N>=10)`
  loop must NOT contain a true-path `.appendEvent(` write referencing a huge
  fixture — either an inline/literal hint on the write line (`100_000`-class
  `.repeat(N>=100000)` constructors with underscores normalized, or the
  legacy `huge`/`big` names) or a file-level `val`/`var <name>[: Type] =
  ... .repeat(N>=100000)` variable used by the write — within its 12-line
  window. The fast-path exemption is per-call (stripped call segments), so a
  same-line true-path write is still examined. Huge homogeneous strings
  through the true `redact` shaping are O(n^2) pre-Redactor-fix and
  amplified per row; PR tests must use the fast path
  (`appendPreRedactedForBudgetTest`) or slim the loop. The
  nightly-only full stress (`ChatSnapshotByteCapFullStressTest.kt`,
  NIGHTLY_STRESS-gated) is allowlisted. Known gaps (documented, not silent):
  fixtures passed through helper parameters, built indirectly, or sized via
  constants (no `.repeat(N)` literal shape) are not tracked — H1 is a tripwire,
  not a coverage proof; shadowed same-name variables in different methods are
  treated as one name (flag-for-review direction).
- H2 (unbounded row count, loop-local): a single `repeat(N>=2000)` loop must
  NOT contain a store write (`.appendEvent(` or the pre-redacted fast path)
  inside its actual brace-delimited body (column-aware single-line close;
  `repeat`/write matches run on masked lines so strings/comments never
  count). The old file-level
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
# Underscored counts (`repeat(2_000)`) normalize to 2000.
REPEAT_RE = re.compile(r"(?<![.\w])repeat\s*\(\s*(\d[\d_]*)")


def _repeat_count(raw: str) -> int:
    try:
        return int(raw.replace("_", ""))
    except ValueError:
        return 0
APPEND_EVENT_RE = re.compile(r"\.appendEvent\s*\(")
FAST_PATH_RE = re.compile(r"appendPreRedactedForBudgetTest")
HUGE_HINT_RE = re.compile(r"100_000|100_001|\bhuge\b|\bbig\b")

# Nightly-only full stress: true shaping intentionally, NIGHTLY_STRESS-gated,
# independent 35-minute timeout, non-gating. Must stay out of the PR path.
ALLOWLIST_HUGE_TRUE_PATH = {
    "ChatSnapshotByteCapFullStressTest.kt",
}

# `val payload = "x".repeat(100_000)` (optionally typed:
# `val payload: String = ...`) assigned anywhere in the file, with the repeat
# count normalized (underscores stripped) against HUGE_FIXTURE_MIN.
HUGE_FIXTURE_MIN = 100_000
FIXTURE_VAR_RE = re.compile(
    r"(?:val|var)\s+(\w+)\s*(?::\s*[^=\n]+?)?=[^\n]*\.repeat\s*\(\s*([\d_]+)"
)
# Inline `"x".repeat(150_000)` fixture constructor (any size; thresholded).
STRING_REPEAT_N_RE = re.compile(r"\.repeat\s*\(\s*([\d_]+)")

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


def _masked_kotlin_lines(lines: list[str]) -> list[str]:
    """Masked code lines: chars inside Kotlin strings/comments replaced with
    spaces, preserving column alignment (same state machine as
    `_mask_kotlin_braces`: `"..."`, `'c'`, `\"\"\"raw\"\"\"`, `//`, `/* */`,
    escapes, cross-line block/raw; `$"..."` template braces stay string
    content). `REPEAT`/`appendEvent`/fast-path matches run on these lines so
    commented-out or string-demo code never counts."""
    masked: list[str] = []
    in_block = False
    in_raw = False
    for line in lines:
        out = list(line)
        i, n = 0, len(line)
        in_str = False
        in_char = False
        while i < n:
            c = line[i]
            nxt = line[i + 1] if i + 1 < n else ""
            if in_block:
                if c == "*" and nxt == "/":
                    out[i] = " "
                    out[i + 1] = " "
                    in_block = False
                    i += 2
                    continue
                out[i] = " "
                i += 1
                continue
            if in_raw:
                if line[i : i + 3] == '"""':
                    for k in range(i, min(i + 3, n)):
                        out[k] = " "
                    in_raw = False
                    i += 3
                    continue
                out[i] = " "
                i += 1
                continue
            if in_str:
                if c == "\\":
                    out[i] = " "
                    if i + 1 < n:
                        out[i + 1] = " "
                    i += 2
                    continue
                out[i] = " "
                if c == '"':
                    in_str = False
                i += 1
                continue
            if in_char:
                if c == "\\":
                    out[i] = " "
                    if i + 1 < n:
                        out[i + 1] = " "
                    i += 2
                    continue
                out[i] = " "
                if c == "'":
                    in_char = False
                i += 1
                continue
            if c == "/" and nxt == "/":
                for j in range(i, n):
                    out[j] = " "
                break
            if c == "/" and nxt == "*":
                out[i] = " "
                out[i + 1] = " "
                in_block = True
                i += 2
                continue
            if line[i : i + 3] == '"""':
                for k in range(i, min(i + 3, n)):
                    out[k] = " "
                in_raw = True
                i += 3
                continue
            if c == '"':
                out[i] = " "
                in_str = True
                i += 1
                continue
            if c == "'":
                out[i] = " "
                in_char = True
                i += 1
                continue
            i += 1
        masked.append("".join(out))
    return masked


def _repeat_body_lines(
    deltas: list[int],
    has_brace: list[bool],
    masked: list[str],
    idx: int,
    match_start: int,
    match_end: int,
) -> tuple[int, int]:
    """Inclusive (start, end) line range of a `repeat(N)` loop body.

    Column-aware: `startDepth` is the brace depth at the `repeat` match
    column (`cum[idx]` plus the net `{` before the match on its masked
    line), then the first `{` after the match opens the body and the scan
    pairs `{`/`}` (stack depth, so a nested close never truncates the
    outer loop) until depth returns to `startDepth`. A same-line pair is a
    single-line body. A header whose `{` sits on a following line (up to
    +5) is supported; anything more exotic falls back to the header line
    alone (documented fail-open; non-trailing `repeat(N, { ... })` uses the
    same first-`{` pairing and never crashes)."""
    n = len(deltas)
    if idx < 0 or idx >= n:
        return (idx, idx)
    cum = [0] * (n + 1)
    for i, d in enumerate(deltas):
        cum[i + 1] = cum[i] + d
    cur = masked[idx] if idx < len(masked) else ""
    lo = max(0, min(match_start, len(cur)))
    hi = max(0, min(match_end, len(cur)))
    prefix = cur[:lo]
    start_depth = cum[idx] + prefix.count("{") - prefix.count("}")
    open_col = cur.find("{", hi)
    if open_col != -1:
        depth = start_depth
        for li in range(idx, n):
            text = masked[li] if li < len(masked) else ""
            sc = open_col if li == idx else 0
            if sc > len(text):
                continue
            for ch in text[sc:]:
                if ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == start_depth:
                        return (idx, li)
                    if depth < start_depth:
                        return (idx, idx)
        return (idx, idx)
    open_idx = idx + 1
    while open_idx < n and open_idx <= idx + 5:
        text = masked[open_idx] if open_idx < len(masked) else ""
        if "{" in text:
            break
        open_idx += 1
    if open_idx >= n or open_idx > idx + 5:
        return (idx, idx)
    text0 = masked[open_idx] if open_idx < len(masked) else ""
    if "{" not in text0:
        return (idx, idx)
    first = text0.find("{")
    depth = start_depth
    for li in range(open_idx, n):
        text = masked[li] if li < len(masked) else ""
        sc = first if li == open_idx else 0
        if sc > len(text):
            continue
        for ch in text[sc:]:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == start_depth:
                    return (idx, li)
                if depth < start_depth:
                    return (idx, idx)
    return (idx, open_idx)


def _fixture_vars(content: str) -> set[str]:
    """Variable names bound to a >=100k String.repeat fixture anywhere in the
    file (typed or untyped declarations; `100000`/`100_000`/`150_000` all
    normalize to the same threshold)."""
    found: set[str] = set()
    for m in FIXTURE_VAR_RE.finditer(content):
        try:
            size = int(m.group(2).replace("_", ""))
        except ValueError:
            continue
        if size >= HUGE_FIXTURE_MIN:
            found.add(m.group(1))
    return found


def _strip_fast_path_spans(text: str) -> str:
    """Remove `appendPreRedactedForBudgetTest(...)` spans from joined masked
    text (balanced parens, window-local). Masked text has no live strings or
    comments (blanked to spaces), so naive `(`/`)` counting is exact and
    string-awareness is unnecessary. Fail-closed: only a `(` reached with
    nothing but spaces/tabs/newlines in between counts as a fast-path call;
    a bare name followed by other code is kept as ordinary text (never skip
    to a distant unrelated paren)."""
    out: list[str] = []
    i = 0
    n = len(text)
    while True:
        m = FAST_PATH_RE.search(text, i)
        if not m:
            out.append(text[i:])
            break
        out.append(text[i : m.start()])
        j = text.find("(", m.end())
        if j < 0:
            out.append(text[m.start() :])
            break
        if any(c not in " \t\r\n" for c in text[m.end() : j]):
            # Not a call (e.g. stray name before unrelated code): keep the
            # name as ordinary text and keep scanning after it.
            out.append(text[m.start() : m.end()])
            i = m.end()
            continue
        depth = 0
        k = j
        while k < n:
            c = text[k]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        i = k + 1 if k < n else n
    return "".join(out)


def _append_event_spans(text: str) -> list[str]:
    """Full `.appendEvent(...)` spans in joined masked text (balanced
    parens, window-local). Each span may cross newlines; callers join the
    span into one string before judging huge evidence."""
    spans: list[str] = []
    pos = 0
    n = len(text)
    while True:
        m = APPEND_EVENT_RE.search(text, pos)
        if not m:
            break
        j = text.find("(", m.start())
        if j < 0:
            spans.append(text[m.start() :])
            break
        depth = 0
        k = j
        while k < n:
            c = text[k]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        if k < n:
            spans.append(text[m.start() : k + 1])
            pos = k + 1
        else:
            spans.append(text[m.start() :])
            break
    return spans


def _line_has_big_inline_fixture(line: str) -> bool:
    for m in STRING_REPEAT_N_RE.finditer(line):
        try:
            if int(m.group(1).replace("_", "")) >= HUGE_FIXTURE_MIN:
                return True
        except ValueError:
            continue
    return False


def _huge_loop_violation(
    masked: list[str], var_res: list[re.Pattern[str]]
) -> bool:
    for idx, line in enumerate(masked):
        m = REPEAT_RE.search(line)
        if not m or _repeat_count(m.group(1)) < HUGE_REPEAT_MIN:
            continue
        window = masked[idx : idx + REPEAT_WINDOW_LINES]
        # Multiline span scan (window-local): strip fast-path spans first to
        # keep the per-call exemption, then pair parens from each
        # `.appendEvent(` start and judge the whole span as one string so a
        # huge hint/varref on a following line is not missed. Masked lines
        # have strings/comments blanked, so naive paren counting is exact.
        window_text = "\n".join(window)
        stripped = _strip_fast_path_spans(window_text)
        for span in _append_event_spans(stripped):
            if (
                HUGE_HINT_RE.search(span)
                or _line_has_big_inline_fixture(span)
                or any(v.search(span) for v in var_res)
            ):
                return True
        # Fixture defined inside the loop then used by a later write:
        # `val huge = "x".repeat(100_000)` in-window plus a true-path write.
        # (Bare `== 100_000` length assertions do NOT count — only fixture
        # creation via String.repeat, any size >= threshold.)
        if any(_line_has_big_inline_fixture(w) for w in window):
            if APPEND_EVENT_RE.search(stripped):
                return True
    return False


def _h2_loop_violation(
    deltas: list[int], has_brace: list[bool], masked: list[str]
) -> str | None:
    """Single-loop H2: repeat(N>=2000) with a store write inside its actual
    brace-delimited body (not a fixed line window, so adjacent methods and
    long bodies are both handled). All matches run on masked lines."""
    for idx, line in enumerate(masked):
        m = REPEAT_RE.search(line)
        if not m or _repeat_count(m.group(1)) < ROW_REPEAT_MAX:
            continue
        _, end = _repeat_body_lines(
            deltas, has_brace, masked, idx, m.start(), m.end()
        )
        if any(
            APPEND_EVENT_RE.search(w) or FAST_PATH_RE.search(w)
            for w in masked[idx : end + 1]
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
    masked = _masked_kotlin_lines(lines)
    deltas, has_brace = _mask_kotlin_braces(lines)
    # Compile once per file (not per loop): PocketGuard perf suggestion.
    var_res = [
        re.compile(r"\b" + re.escape(name) + r"\b")
        for name in sorted(_fixture_vars(content))
    ]
    violations: list[str] = []
    if base not in ALLOWLIST_HUGE_TRUE_PATH and _huge_loop_violation(
        masked, var_res
    ):
        violations.append(
            f"{path}: H1 huge-string loop via true appendEvent "
            f"(repeat>={HUGE_REPEAT_MIN} with .appendEvent on a huge fixture; "
            f"use appendPreRedactedForBudgetTest or move to nightly)"
        )
    h2_loop = _h2_loop_violation(deltas, has_brace, masked)
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
