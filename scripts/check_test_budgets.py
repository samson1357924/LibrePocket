"""Fail-closed guard for Issue #86: bound per-test write amplification.

Stdlib only. Gate (exit non-zero fails CI) — never silently downgrades to
unredacted writes; it only rejects / alerts.

Rules (test sources only, no prod change):
- H1 (huge-string via true shaping, heuristic tripwire): a `repeat(N>=10)`
  loop must NOT contain a true-path `.appendEvent(` write referencing a huge
  fixture inside its actual brace-delimited body (column-aware pairing shared
  with H2; `repeat`/write matches run on masked lines so strings/comments
  never count). A write counts only when its own span carries huge evidence:
  an inline `.repeat(N>=100000)` constructor (underscores normalized), or a
  variable whose nearest scope-visible definition builds a >=100k repeat —
  a bare `100_000`-class number (e.g. timestamps) is never evidence, and a
  `huge`/`big` name counts only when it resolves to such a scope-visible
  huge definition — top-level definitions are visible
  everywhere, same/enclosing-function and loop-body definitions are visible
  to their loop, sibling-function definitions are excluded, a definition
  inside a `run`/`if`/`also`/`let`-style `{...}` block that already ended
  before the write is not visible to it, and the closest
  preceding visible definition wins (so a shadowing small redefinition
  clears the flag). A body line that merely builds huge without the write
  span referencing it does NOT count (no cross-line disjunction). The
  fast-path exemption is per-call (spans blanked with offsets preserved), so
  a same-line true-path write is still examined. Huge homogeneous strings
  through the true `redact` shaping are O(n^2) pre-Redactor-fix and
  amplified per row; PR tests must use the fast path
  (`appendPreRedactedForBudgetTest`) or slim the loop. The
  nightly-only full stress (`ChatSnapshotByteCapFullStressTest.kt`,
  NIGHTLY_STRESS-gated) is allowlisted. Fallback (fail-closed): a header
  whose brace block cannot be paired (no `{...}` on the header line and no
  block found in the following lines) is scanned over the legacy
  REPEAT_WINDOW_LINES window instead of silently passing — the same
  span-evidence + scope rules still apply there. Known gaps (documented, not
  silent): fixtures passed through helper parameters, built indirectly,
  sized via constants, or defined after the write (no `.repeat(N)` literal
  shape / not visible) are not tracked — H1 is a tripwire, not a coverage
  proof; a reference with no scope-visible huge definition passes
  (precision: only a definite in-scope huge path fails).
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
# Gate-only name hint: a bare `100_000`-class number (e.g. createdAt
# timestamps) is never huge evidence; `huge`/`big` counts only when the name
# resolves to a scope-visible huge definition (checked at the use site).
HUGE_HINT_RE = re.compile(r"\bhuge\b|\bbig\b")

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
# Fallback-only lookahead for H1 headers whose brace block cannot be paired
# (H1 is body-local otherwise, like H2). Fail-closed: an unparseable header
# is scanned over this window instead of silently passing; span-evidence +
# scope rules still apply inside the window, so unknown scope never fails.
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


def _repeat_block_slices(
    masked: list[str], idx: int, match_end: int
) -> list[tuple[int, str]] | None:
    """Column-precise body slices for one `repeat` match.

    Returns [(orig_line, substring)] covering only the brace-delimited
    body: single-line bodies slice `{...}` (open col through the paired
    `}`), multiline bodies take the header tail from `{`, full middle
    lines, and the closer head through `}`. Runs on masked lines so
    pairing is exact. Returns None when the header cannot be paired
    (no `{` on the header line and none within +5 lines, or no close
    found) — the caller keeps the legacy fail-closed window semantics.
    """
    n = len(masked)
    if idx < 0 or idx >= n:
        return None
    cur = masked[idx] if idx < len(masked) else ""
    hi = max(0, min(match_end, len(cur)))
    open_col = cur.find("{", hi)
    if open_col != -1:
        depth = 0
        for li in range(idx, n):
            text = masked[li] if li < len(masked) else ""
            sc = open_col if li == idx else 0
            if sc > len(text):
                continue
            for col in range(sc, len(text)):
                ch = text[col]
                if ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        if li == idx:
                            return [(idx, text[open_col : col + 1])]
                        segs = [(idx, masked[idx][open_col:])]
                        for mid in range(idx + 1, li):
                            segs.append((mid, masked[mid]))
                        segs.append((li, masked[li][: col + 1]))
                        return segs
                    if depth < 0:
                        return None
        return None
    open_idx = -1
    first = -1
    for g in range(idx + 1, min(n, idx + 6)):
        c = masked[g].find("{") if g < len(masked) else -1
        if c != -1:
            open_idx, first = g, c
            break
    if open_idx < 0:
        return None
    depth = 0
    for li in range(open_idx, n):
        text = masked[li] if li < len(masked) else ""
        sc = first if li == open_idx else 0
        if sc > len(text):
            continue
        for col in range(sc, len(text)):
            ch = text[col]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    if li == open_idx:
                        return [(open_idx, text[first : col + 1])]
                    segs = [(open_idx, masked[open_idx][first:])]
                    for mid in range(open_idx + 1, li):
                        segs.append((mid, masked[mid]))
                    segs.append((li, masked[li][: col + 1]))
                    return segs
                if depth < 0:
                    return None
    return None


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
    to a distant unrelated paren). Spans are blanked (non-newline chars to
    spaces, 1:1) rather than deleted so span offsets stay aligned with the
    joined body/window text for scope-aware line mapping (Stage 3); blanking
    (vs deletion) also never fuses the surrounding text into phantom tokens."""
    out: list[str] = []
    i = 0
    n = len(text)

    def _blank(s: str) -> str:
        return "".join(c if c in "\r\n" else " " for c in s)

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
        if k < n:
            out.append(_blank(text[m.start() : k + 1]))
            i = k + 1
        else:
            out.append(_blank(text[m.start() :]))
            break
    return "".join(out)


def _append_event_spans_with_offsets(text: str) -> list[tuple[str, int]]:
    """Full `.appendEvent(...)` spans in joined masked text with their start
    offsets (balanced parens, window-local). Offsets let callers map a span
    back to its source line for scope-aware fixture resolution (Stage 3)."""
    spans: list[tuple[str, int]] = []
    pos = 0
    n = len(text)
    while True:
        m = APPEND_EVENT_RE.search(text, pos)
        if not m:
            break
        j = text.find("(", m.start())
        if j < 0:
            spans.append((text[m.start() :], m.start()))
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
            spans.append((text[m.start() : k + 1], m.start()))
            pos = k + 1
        else:
            spans.append((text[m.start() :], m.start()))
            break
    return spans


def _append_event_spans(text: str) -> list[str]:
    """Full `.appendEvent(...)` spans in joined masked text (balanced
    parens, window-local). Each span may cross newlines; callers join the
    span into one string before judging huge evidence."""
    return [span for span, _ in _append_event_spans_with_offsets(text)]


def _line_has_big_inline_fixture(line: str) -> bool:
    for m in STRING_REPEAT_N_RE.finditer(line):
        try:
            if int(m.group(1).replace("_", "")) >= HUGE_FIXTURE_MIN:
                return True
        except ValueError:
            continue
    return False


# Any `val`/`var <name> = ...` declaration (typed or not) on one masked line;
# huge-ness is decided separately by FIXTURE_VAR_RE + HUGE_FIXTURE_MIN, so a
# small redefinition of the same name is recorded too (shadowing).
VAL_DEF_RE = re.compile(r"(?:val|var)\s+(\w+)\s*(?::\s*[^=\n]+?)?=")
FUN_RE = re.compile(r"\bfun\b")


def _function_ranges(masked: list[str]) -> list[tuple[int, int]]:
    """Inclusive [start, end] line ranges of `fun ... { ... }` bodies on
    masked lines (strings/comments already blanked, so `{`/`}` pairing is
    exact). An expression body (`fun f() = g()`, no brace within +5 lines)
    covers its own line. An unclosed body extends to EOF (fail-closed: its
    contents stay function-local rather than leaking to top-level scope)."""
    ranges: list[tuple[int, int]] = []
    n = len(masked)
    for f in range(n):
        for m in FUN_RE.finditer(masked[f]):
            open_line, open_col = -1, -1
            c0 = masked[f].find("{", m.end())
            if c0 != -1:
                open_line, open_col = f, c0
            else:
                for g in range(f + 1, min(n, f + 6)):
                    c0 = masked[g].find("{")
                    if c0 != -1:
                        open_line, open_col = g, c0
                        break
            if open_line < 0:
                ranges.append((f, f))
                continue
            depth = 0
            closed = -1
            for li in range(open_line, n):
                text = masked[li]
                sc = open_col if li == open_line else 0
                for ch in text[sc:]:
                    if ch == "{":
                        depth += 1
                    elif ch == "}":
                        depth -= 1
                        if depth == 0:
                            closed = li
                            break
                if closed >= 0:
                    break
            ranges.append((f, closed if closed >= 0 else n - 1))
    return ranges


def _scoped_var_defs(masked: list[str]) -> dict[str, list[tuple[int, int, bool]]]:
    """All `val`/`var` definitions on masked lines: name -> sorted
    [(def_line, match_col, is_huge)], where huge means the same line builds a
    `>=HUGE_FIXTURE_MIN` String.repeat fixture (typed or untyped). Runs on
    masked lines so commented-out or string-demo declarations never count.
    The match column locates the def inside its lexical `{...}` block (see
    `_def_enclosing_blocks`)."""
    defs: dict[str, list[tuple[int, int, bool]]] = {}
    for idx, line in enumerate(masked):
        huge_here: set[str] = set()
        for m in FIXTURE_VAR_RE.finditer(line):
            try:
                size = int(m.group(2).replace("_", ""))
            except ValueError:
                continue
            if size >= HUGE_FIXTURE_MIN:
                huge_here.add(m.group(1))
        for m in VAL_DEF_RE.finditer(line):
            name = m.group(1)
            defs.setdefault(name, []).append((idx, m.start(), name in huge_here))
    for entries in defs.values():
        entries.sort()
    return defs


def _brace_blocks(masked: list[str]) -> list[tuple[int, int, int, int]]:
    """Every `{...}` pair on masked lines as (start_line, start_col,
    end_line, end_col). Runs on masked lines (strings/comments blanked) so
    pairing is exact. An unclosed `{` extends to EOF (fail-closed: its
    contents stay block-local rather than leaking to outer scope). A stray
    `}` with an empty stack is ignored."""
    blocks: list[tuple[int, int, int, int]] = []
    stack: list[tuple[int, int]] = []
    for idx, line in enumerate(masked):
        for col, ch in enumerate(line):
            if ch == "{":
                stack.append((idx, col))
            elif ch == "}":
                if stack:
                    sline, scol = stack.pop()
                    blocks.append((sline, scol, idx, col))
    n = len(masked)
    for sline, scol in stack:
        blocks.append((sline, scol, n - 1, 1 << 30))
    return blocks


def _def_enclosing_blocks(
    masked: list[str], blocks: list[tuple[int, int, int, int]]
) -> dict[tuple[int, int], tuple[int, int, int, int] | None]:
    """Innermost enclosing `{...}` block per `val`/`var` def, keyed by
    (def_line, match_col), or None for top-level (file scope).

    Column-aware: a block encloses a def only when its `{` opens strictly
    before the def's match start, so `val x = foo.also { ... }` stays an
    outer-scope def (the `also` block opens after it) while
    `run { val payload = "q" }` is block-local. A def whose block ends
    before the write span's line is not visible to that write
    (`run`/`if`/`also`/`let` temporaries never leak past their `}`)."""
    out: dict[tuple[int, int], tuple[int, int, int, int] | None] = {}
    for idx, line in enumerate(masked):
        for m in VAL_DEF_RE.finditer(line):
            pos = (idx, m.start())
            best: tuple[int, int, int, int] | None = None
            for sl, sc, el, ec in blocks:
                if (sl, sc) < pos and (el > idx or (el == idx and ec > m.start())):
                    if best is None or (sl, sc) > (best[0], best[1]):
                        best = (sl, sc, el, ec)
            out[pos] = best
    return out


def _def_visible(
    ranges: list[tuple[int, int]], def_line: int, loop_line: int
) -> bool:
    """A definition is visible to a loop iff every `fun` body containing the
    definition also contains the loop: top-level definitions (no containing
    body) are visible everywhere, same/enclosing-function and loop-body
    definitions are visible, sibling-function definitions are excluded."""
    for a, b in ranges:
        if a <= def_line <= b and not (a <= loop_line <= b):
            return False
    return True


def _nearest_visible_is_huge(
    defs: dict[str, list[tuple[int, int, bool]]],
    name: str,
    span_line: int,
    loop_idx: int,
    ranges: list[tuple[int, int]],
    def_blocks: dict[tuple[int, int], tuple[int, int, int, int] | None] | None = None,
) -> bool:
    """Nearest-visible-definition rule: among scope-visible definitions of
    `name` on or before the write span's line, the closest one decides — a
    nearer small redefinition (shadowing) clears an outer huge fixture, and
    no visible definition means unknown (precision: not huge, never fails).

    Besides the `fun`-level rule (`_def_visible`), a def is visible only
    when its innermost enclosing `{...}` block still contains the write
    span's line (top-level defs, block None, are visible everywhere): a
    `run`/`if`/`also`/`let` temporary whose block already ended is never on
    the write's path, while a def in the same function body or inside the
    loop body before the write stays visible. A missing block entry is
    treated as visible (fail-open for unmapped defs, never a new fail)."""
    best: tuple[int, int, bool] | None = None
    for ln, col, huge in defs.get(name, []):
        if ln <= span_line and _def_visible(ranges, ln, loop_idx):
            if def_blocks is not None:
                blk = def_blocks.get((ln, col))
                if blk is not None and blk[2] < span_line:
                    continue
            best = (ln, col, huge)
    return best is not None and best[2]


def _single_line_closed_body(header: str, match_end: int) -> bool:
    """Trust a `(idx, idx)` body as a genuine single-line loop only when the
    header line pairs `{...}` after the `repeat(...)` match; otherwise the
    header is unparseable and the caller falls back to the legacy window
    (fail-closed)."""
    open_col = header.find("{", match_end)
    return open_col != -1 and "}" in header[open_col + 1 :]


def _huge_loop_violation(
    masked: list[str], deltas: list[int], has_brace: list[bool]
) -> bool:
    """H1 tripwire, body-local and scope-aware (Stage 3).

    Each `repeat(N>=10)` loop is judged over its actual brace-delimited body
    (same column-aware `_repeat_body_lines` pairing as H2, header line
    through the closing line) — never a fixed cross-method window. A body
    write fails only when its own `.appendEvent(` span carries huge evidence
    (an inline `>=100k` repeat, or a referenced variable — including a
    `huge`/`big` name — whose nearest scope-visible definition builds a
    `>=100k` repeat; bare numbers never count). The old second disjunct (big fixture anywhere near any
    write) is deleted: building huge on one body line while writing small on
    another is not a costly path. Fast-path spans are blanked first, keeping
    the per-call exemption without shifting span offsets.
    """
    ranges = _function_ranges(masked)
    defs = _scoped_var_defs(masked)
    def_blocks = _def_enclosing_blocks(masked, _brace_blocks(masked))
    # Compile once per file (not per loop): PocketGuard perf suggestion.
    var_res = {
        name: re.compile(r"\b" + re.escape(name) + r"\b")
        for name in defs
    }
    for idx, line in enumerate(masked):
        for m in REPEAT_RE.finditer(line):
            if _repeat_count(m.group(1)) < HUGE_REPEAT_MIN:
                continue
            start, end = _repeat_body_lines(
                deltas, has_brace, masked, idx, m.start(), m.end()
            )
            if (start, end) == (idx, idx) and not _single_line_closed_body(
                line, m.end()
            ):
                # Fail-closed fallback for headers whose block cannot be
                # paired: scan the legacy window instead of silently
                # passing. Span evidence + scope rules still apply, so
                # unknown scope never fails here either.
                end = min(len(masked) - 1, idx + REPEAT_WINDOW_LINES - 1)
                body_text = "\n".join(masked[start : end + 1])
                line_map = list(range(start, end + 1))
            else:
                slices = _repeat_block_slices(masked, idx, m.end())
                if slices is None:
                    # Unpaired but not the window fallback (e.g. later-line
                    # open without a close): keep the legacy line range.
                    body_text = "\n".join(masked[start : end + 1])
                    line_map = list(range(start, end + 1))
                else:
                    body_text = "\n".join(sub for _, sub in slices)
                    line_map = [orig for orig, _ in slices]
            # Multiline span scan (body-local): blank fast-path spans first
            # to keep the per-call exemption, then pair parens from each
            # `.appendEvent(` start and judge the whole span as one string
            # so a huge hint/varref on a following line is not missed.
            # Masked lines have strings/comments blanked, so naive paren
            # counting is exact.
            stripped = _strip_fast_path_spans(body_text)
            for span, off in _append_event_spans_with_offsets(stripped):
                if _line_has_big_inline_fixture(span):
                    return True
                span_line = line_map[stripped[:off].count("\n")]
                for hm in HUGE_HINT_RE.finditer(span):
                    if _nearest_visible_is_huge(
                        defs, hm.group(0), span_line, idx, ranges, def_blocks
                    ):
                        return True
                for name, v in var_res.items():
                    if v.search(span) and _nearest_visible_is_huge(
                        defs, name, span_line, idx, ranges, def_blocks
                    ):
                        return True
        # NOTE: no cross-line disjunction — a body line that builds a huge
        # fixture while a *different* write span carries no huge evidence is
        # not a definite costly path (precision over flag-for-review).
        # (Bare `== 100_000` length assertions do NOT count — only fixture
        # creation via String.repeat, any size >= threshold.)
    return False


def _h2_loop_violation(
    deltas: list[int], has_brace: list[bool], masked: list[str]
) -> str | None:
    """Single-loop H2: repeat(N>=2000) with a store write inside its actual
    brace-delimited body (not a fixed line window, so adjacent methods and
    long bodies are both handled). All matches run on masked lines."""
    for idx, line in enumerate(masked):
        for m in REPEAT_RE.finditer(line):
            if _repeat_count(m.group(1)) < ROW_REPEAT_MAX:
                continue
            start, end = _repeat_body_lines(
                deltas, has_brace, masked, idx, m.start(), m.end()
            )
            if (start, end) == (idx, idx) and not _single_line_closed_body(
                line, m.end()
            ):
                # Unparseable header: keep legacy single-line scan.
                if any(
                    APPEND_EVENT_RE.search(w) or FAST_PATH_RE.search(w)
                    for w in masked[start : end + 1]
                ):
                    return f"repeat({m.group(1)})"
                continue
            slices = _repeat_block_slices(masked, idx, m.end())
            if slices is None:
                if any(
                    APPEND_EVENT_RE.search(w) or FAST_PATH_RE.search(w)
                    for w in masked[start : end + 1]
                ):
                    return f"repeat({m.group(1)})"
            elif any(
                APPEND_EVENT_RE.search(sub) or FAST_PATH_RE.search(sub)
                for _, sub in slices
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
    violations: list[str] = []
    if base not in ALLOWLIST_HUGE_TRUE_PATH and _huge_loop_violation(
        masked, deltas, has_brace
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
