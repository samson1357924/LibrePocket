#!/usr/bin/env python3
"""Check release tag <-> Gradle version consistency (fail-closed, no secrets).

Rule: a release tag ``vX.Y.Z`` must exactly equal the literal ``versionName``
in ``android.defaultConfig`` (pure ``X.Y.Z`` semver, no suffix), and
``versionCode`` must be a positive Android integer (1..2147483647).

This conservative static check does not evaluate Gradle or resolve variant,
plugin, or other overrides. Verify the final APK's identity independently.

Usage::

    python3 scripts/check_release_tag_version.py \\
      --tag v0.1.0 --app-build-gradle app/build.gradle.kts

The tag is supplied by the trusted caller (CI passes ``github.ref_name``
or the output of ``git describe --exact-match``); this script never
infers identity from an APK and never touches keystores. The public
release workflow (``.github/workflows/release.yml``) stays frozen
regardless of this check's result. The Gradle path is caller-supplied
trusted input (local checkout file), not an adversarial path.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

MAX_GRADLE_BYTES = 512 * 1024
_SEMVER_CORE = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
# NOTE: use [0-9] (ASCII-only) rather than \d: Python re matches Unicode
# decimal digits with \d, which must not pass as a release version.
TAG_RE = re.compile(r"v(" + _SEMVER_CORE + r")")
VERSION_RE = re.compile(_SEMVER_CORE)
MAX_VERSION_CODE = 2_147_483_647
MAX_VERSION_NAME_LEN = 256


class TagVersionError(Exception):
    """A fail-closed tag/version mismatch."""


def _backtick_end(text: str, start: int) -> int:
    """Return the end of a Kotlin escaped identifier, rejecting malformed input."""
    end = text.find("`", start + 1)
    if end == -1 or end == start + 1 or "\n" in text[start:end] or "\r" in text[start:end]:
        raise TagVersionError("unterminated or empty backticked identifier")
    return end + 1


def _strip_kotlin_comments(text: str) -> str:
    """Replace Kotlin comments with spaces (newlines preserved).

    Strips ``//`` line comments and nestable ``/* ... */`` block comments
    so commented-out literals can never satisfy the exactly-one gate.
    Double-quoted and triple-quoted strings as well as character literals
    are preserved, so ``//`` or ``/*`` inside a version string is never
    treated as a comment. Unterminated literals and comments are rejected.
    Braced string templates other than simple identifiers are unsupported
    anywhere: their expressions can contain arbitrary Kotlin and strings.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        if text[i] == "`":
            i = _backtick_end(text, i)
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end == -1:
                raise TagVersionError("unterminated raw string")
            if "${" in re.sub(r"\$\{[A-Za-z_][A-Za-z0-9_]*\}", "", text[i + 3:end]):
                raise TagVersionError("braced string templates are unsupported")
            i = end + 3
        elif text[i] == "'":
            # Kotlin character literal ('a', '\'', '\\', '\uXXXX'): skip it
            # so a '"' inside never opens a phantom string.
            i += 1
            closed = False
            while i < n and text[i] != "\n":
                if text[i] == "\\":
                    nxt = text[i + 1 : i + 2]
                    if nxt == "" or nxt == "\n":
                        break  # unterminated escape at EOL
                    if nxt == "u":
                        if "\n" in text[i : i + 6] or len(text) < i + 6:
                            break  # unterminated \u escape
                        i += 6  # \uXXXX
                    else:
                        i += 2
                elif text[i] == "'":
                    i += 1
                    closed = True
                    break
                else:
                    i += 1
            if not closed:
                raise TagVersionError("unterminated character literal")
        elif text[i] == '"':
            i += 1
            closed = False
            while i < n:
                if text[i] == "\\":
                    nxt = text[i + 1 : i + 2]
                    if nxt == "" or nxt == "\n":
                        break  # unterminated escape at EOL
                    i += 2
                elif text[i] == '"':
                    i += 1
                    closed = True
                    break
                elif text.startswith("${", i):
                    simple = re.match(r"\$\{[A-Za-z_][A-Za-z0-9_]*\}", text[i:])
                    if not simple:
                        raise TagVersionError("expression string templates are unsupported")
                    i += len(simple.group())
                elif text[i] == "\n":
                    break  # unterminated string at EOL
                else:
                    i += 1
            if not closed:
                raise TagVersionError("unterminated string")
        elif text.startswith("/*", i):
            depth = 0
            while i < n:
                if text.startswith("/*", i):
                    depth += 1
                    out[i] = " "
                    out[i + 1] = " "
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    out[i] = " "
                    out[i + 1] = " "
                    i += 2
                    if depth == 0:
                        break
                else:
                    if text[i] != "\n":
                        out[i] = " "
                    i += 1
            if depth:
                raise TagVersionError("unterminated block comment")
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        else:
            i += 1
    return "".join(out)


def _find_matching_brace(text: str, open_index: int) -> int:
    """Return index of the brace matching the '{' at open_index, or -1."""
    if open_index < 0 or open_index >= len(text) or text[open_index] != "{":
        return -1
    depth = 0
    i = open_index
    n = len(text)
    while i < n:
        if text[i] == "`":
            i = _backtick_end(text, i)
            continue
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end == -1 else end + 3
            continue
        elif text[i] == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    nxt = text[i + 1 : i + 2]
                    if nxt == "" or nxt == "\n":
                        i = n
                        break
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
                elif text[i] == "\n":
                    break
                else:
                    i += 1
            continue
        elif text[i] == "'":
            i += 1
            while i < n and text[i] != "\n":
                if text[i] == "\\":
                    nxt = text[i + 1 : i + 2]
                    if nxt == "" or nxt == "\n":
                        break
                    if nxt == "u":
                        if "\n" in text[i : i + 6] or len(text) < i + 6:
                            break
                        i += 6
                    else:
                        i += 2
                elif text[i] == "'":
                    i += 1
                    break
                else:
                    i += 1
            continue
        elif text[i] == "{":
            depth += 1
            i += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
            i += 1
        else:
            i += 1
    return -1


def _string_intervals(text: str) -> list[tuple[int, int]]:
    """Return (start, end) half-open intervals covering string literals."""
    intervals: list[tuple[int, int]] = []
    i, n = 0, len(text)
    while i < n:
        if text[i] == "`":
            i = _backtick_end(text, i)
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end == -1:
                intervals.append((i, n))
                break
            intervals.append((i, end + 3))
            i = end + 3
        elif text[i] == '"':
            start = i
            i += 1
            while i < n:
                if text[i] == "\\":
                    nxt = text[i + 1 : i + 2]
                    if nxt == "" or nxt == "\n":
                        i = n
                        break
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
                elif text[i] == "\n":
                    break
                else:
                    i += 1
            intervals.append((start, i))
        elif text[i] == "'":
            start = i
            i += 1
            while i < n and text[i] != "\n":
                if text[i] == "\\":
                    nxt = text[i + 1 : i + 2]
                    if nxt == "" or nxt == "\n":
                        break
                    if nxt == "u":
                        if "\n" in text[i : i + 6] or len(text) < i + 6:
                            break
                        i += 6
                    else:
                        i += 2
                elif text[i] == "'":
                    i += 1
                    break
                else:
                    i += 1
            intervals.append((start, i))
        else:
            i += 1
    return intervals


def _inside_string(pos: int, intervals: list[tuple[int, int]]) -> bool:
    for start, end in intervals:
        if start <= pos < end:
            return True
    return False


def _code_and_depths(text: str) -> tuple[str, list[int]]:
    """Mask literals and identifier contents; record structural brace depth."""
    code = list(text)
    for start, end in _string_intervals(text):
        for i in range(start, end):
            if code[i] != "\n":
                code[i] = " "
    # Preserve identity property names for the shadow/mutation checks. Other
    # escaped identifiers are opaque but remain identifiers of equal length;
    # in particular their braces and quotes are never Kotlin structure.
    for match in re.finditer(r"`[^`\n]*`", "".join(code)):
        if match.group() not in ("`versionCode`", "`versionName`"):
            for i in range(match.start() + 1, match.end() - 1):
                code[i] = "x"
    code_text = "".join(code)
    depths = []
    depth = 0
    for char in code_text:
        depths.append(depth)
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth < 0:
                raise TagVersionError("unbalanced Gradle braces")
    if depth:
        raise TagVersionError("unbalanced Gradle braces")
    return code_text, depths


def _grouping_depths(code: str) -> list[int]:
    """Record parenthesis/bracket depth outside literals and identifiers."""
    code = re.sub(r"`[^`\n]*`", lambda m: " " * len(m.group()), code)
    stack: list[str] = []
    depths = []
    for char in code:
        depths.append(len(stack))
        if char in "([":
            stack.append(char)
        elif char in ")]":
            if not stack or stack.pop() != {')': '(', ']': '['}[char]:
                raise TagVersionError("unbalanced Gradle parentheses or brackets")
    if stack:
        raise TagVersionError("unbalanced Gradle parentheses or brackets")
    return depths


def _has_pending_control(prefix: str, original: str) -> bool:
    """Detect a possibly unbraced control statement before a declaration.

    Only simple, single-line val/var if expressions are recognized as complete;
    more complicated preceding control flow is intentionally rejected.
    Braces inside parentheses cannot terminate an enclosing condition.
    """
    # Keep a placeholder for each literal so a completed literal RHS is not
    # mistaken for whitespace. Never expose literal contents as Kotlin code.
    elvis_code = list(prefix)
    for start, _ in _string_intervals(original):
        elvis_code[start] = "0"
    elvis_prefix = re.sub(r"`[^`\n]*`", "identifier", "".join(elvis_code))
    # An annotation can precede the RHS expression; its argument parentheses
    # do not mean the RHS has executed. Conservatively reject annotations
    # only while the RHS is still pending, not after a completed expression.
    if re.search(r"\?:\s*(?:(?:[^\W\d]|_)\w*\s*@\s*|\(\s*)*@", elvis_prefix):
        return True
    # Labels and grouping parentheses may precede the RHS expression, including
    # the following DSL call. A closing parenthesis means the RHS has begun.
    if re.search(r"\?:\s*(?:(?:[^\W\d]|_)\w*\s*@\s*|\(\s*)*$", elvis_prefix):
        return True
    prefix = re.sub(r"`[^`\n]*`", lambda m: " " * len(m.group()), prefix)
    control = r"\b(?:if|else|for|while|when|do|try|catch|finally)\b"

    def remove_simple_if(match: re.Match[str]) -> str:
        # A second control keyword can begin an unfinished branch on the next
        # line (e.g. ``else if (false)``), so do not mask the expression.
        # A trailing label also leaves the branch awaiting an expression.
        expression = match.group()
        return "" if (re.findall(control, expression) == ["if", "else"]
                      and not re.search(r"@\s*$", expression)) else expression

    prefix = re.sub(
        r"(?m)^[ \t]*(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*\s*=\s*if\s*\([^(){}\n]*\)\s*[^{};\n]+\s+else\s+[^{};\n]+$",
        remove_simple_if, prefix,
    )
    chars = list(prefix)
    parens = 0
    for i, char in enumerate(prefix):
        if char == "(":
            parens += 1
        elif char == ")":
            parens = max(0, parens - 1)
        elif parens and char in "{};":
            chars[i] = " "
    return bool(re.search(control + r"[^{};]*$", "".join(chars)))


def _has_pending_expression(prefix: str, original: str) -> bool:
    """Reject declarations inside unfinished grouping or after an operator.

    Literals and backtick identifiers are opaque, but count as completed
    operands when checking the end of the preceding statement.
    """
    code = list(prefix)
    for start, _ in _string_intervals(original):
        code[start] = "0"
    code = re.sub(r"`[^`\n]*`", "identifier", "".join(code))
    stack: list[str] = []
    for char in code:
        if char in "([":
            stack.append(char)
        elif char in ")]":
            if not stack or stack.pop() != {')': '(', ']': '['}[char]:
                return True
    if stack:
        return True
    # Postfix increment/decrement and non-null assertions finish an operand;
    # unlike a binary operator they do not consume the following DSL call.
    if re.search(r"[\w\])](?:\+\+|--|!!)\s*$", code):
        return False
    # An infix function name consumes the next expression, even across a
    # newline. Kotlin permits arbitrary identifiers (including backticks),
    # not just the standard library's `to`. Exclude declaration keywords so
    # a standalone `val name` is not mistaken for an infix expression.
    infix = re.search(
        r"(?<![\w`])(?:[\w\])]+(?:(?:\+\+|--|!!))*[ \t]+)+(?:[^\W\d]|_)\w*\s*$",
        code,
    )
    if infix:
        tokens = infix.group().split()
        if (tokens[0] not in {"val", "var", "fun", "class", "object", "typealias", "import", "package"}
                and len(tokens) % 2 == 0):
            return True
    # A newline does not complete an expression after an operator or label.
    return bool(re.search(r"(?:[=+*/%&|^!<>?,.:-]|@)\s*$", code))


def _reject_version_declarations(code: str) -> None:
    """Reject local declarations that could shadow identity DSL properties."""
    identifier = r"(?:`version(?:Code|Name)`|version(?:Code|Name)(?![\w`]))"
    if re.search(r"(?<![\w`])(?:val|var)\s+" + identifier, code) or re.search(
        r"(?<![\w`])(?:val|var)\s*\([^)]*(?<![\w`])" + identifier + r"(?=\s*[,):])",
        code,
    ):
        raise TagVersionError("version local declaration may shadow defaultConfig property")


def _reject_preceding_returns(code: str) -> None:
    """Conservatively reject returns that could skip an identity declaration."""
    keyword_code = re.sub(r"`[^`\n]*`", lambda m: " " * len(m.group()), code)
    if re.search(r"\breturn\b", keyword_code):
        raise TagVersionError("return before identity declaration is unsupported")


def _extract_default_config(stripped: str) -> str | None:
    """Return the body of a direct top-level android.defaultConfig block."""
    code, depths = _code_and_depths(stripped)
    android_candidates = [
        cand for cand in re.finditer(r"(?<![A-Za-z0-9_])android\s*\{", code)
    ]
    if not android_candidates:
        return None
    if len(android_candidates) > 1:
        raise TagVersionError("Gradle file must contain exactly one android block")
    m = android_candidates[0]
    if depths[m.start()] != 0 or code[code.rfind("\n", 0, m.start()) + 1:m.start()].strip():
        raise TagVersionError("android block must be a direct top-level declaration")
    if (_has_pending_control(code[:m.start()], stripped[:m.start()])
            or _has_pending_expression(code[:m.start()], stripped[:m.start()])):
        raise TagVersionError("conditional android block is unsupported")
    _reject_preceding_returns(code[:m.start()])
    _reject_version_declarations(code[:m.start()])
    open_idx = m.end() - 1
    close_idx = _find_matching_brace(stripped, open_idx)
    if close_idx == -1:
        return None
    android_body = stripped[open_idx + 1 : close_idx]
    body_code, body_depths = _code_and_depths(android_body)
    default_candidates = [
        cand for cand in re.finditer(r"(?<![A-Za-z0-9_])defaultConfig\s*\{", body_code)
    ]
    if not default_candidates:
        return None
    if len(default_candidates) > 1:
        raise TagVersionError("Gradle file must contain exactly one defaultConfig block")
    dm = default_candidates[0]
    if body_depths[dm.start()] != 0 or body_code[body_code.rfind("\n", 0, dm.start()) + 1:dm.start()].strip():
        raise TagVersionError("defaultConfig must be a direct android declaration")
    if (_has_pending_control(body_code[:dm.start()], android_body[:dm.start()])
            or _has_pending_expression(body_code[:dm.start()], android_body[:dm.start()])):
        raise TagVersionError("conditional defaultConfig is unsupported")
    _reject_preceding_returns(body_code[:dm.start()])
    _reject_version_declarations(body_code[:dm.start()])
    open2 = dm.end() - 1
    close2 = _find_matching_brace(android_body, open2)
    if close2 == -1:
        return None
    return android_body[open2 + 1 : close2]


def parse_gradle_version(gradle_path: str) -> tuple[int, str]:
    if not isinstance(gradle_path, (str, os.PathLike)):
        raise TagVersionError("Gradle file path must be a string")
    path = Path(gradle_path)
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise TagVersionError(f"cannot stat Gradle file: {exc}") from exc
    if size <= 0:
        raise TagVersionError("Gradle file is empty")
    if size > MAX_GRADLE_BYTES:
        raise TagVersionError("Gradle file size exceeds the parser limit")
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise TagVersionError(f"cannot read Gradle file as UTF-8: {exc}") from exc
    # Re-check the decoded length: the stat size is advisory (the file may
    # grow or be replaced between stat and read), so the in-memory snapshot
    # is the binding measurement.
    if len(text.encode("utf-8")) > MAX_GRADLE_BYTES:
        raise TagVersionError("Gradle file size exceeds the parser limit")
    # NOTE: `versionNameSuffix` (debug "-debug") must not match; the pattern
    # requires `versionName` followed directly by `=`, so the longer
    # `versionNameSuffix = ...` line cannot match.
    # Kotlin comments are stripped first so commented-out literals can never
    # satisfy the gate; trailing `//` comments are tolerated.
    stripped = _strip_kotlin_comments(text)
    search_text = _extract_default_config(stripped)
    if search_text is None:
        raise TagVersionError("Gradle file must contain a parseable android.defaultConfig block")
    # Even a simple identifier template can invoke a property getter. Do not
    # mask any executable interpolation inside the identity-bearing block.
    for start, end in _string_intervals(search_text):
        literal = search_text[start:end]
        if literal.startswith("'"):
            continue
        raw = literal.startswith('"""')
        i = 3 if raw else 1
        while i < len(literal):
            if not raw and literal[i] == "\\":
                i += 2
            elif literal[i] == "$" and literal[i + 1:i + 2] and (
                literal[i + 1] in "_{" or literal[i + 1].isalpha()
            ):
                raise TagVersionError("string interpolation in defaultConfig is unsupported")
            else:
                i += 1
    code_text, depths = _code_and_depths(search_text)
    grouping_depths = _grouping_depths(code_text)
    _reject_version_declarations(code_text)
    # Unbraced control flow can also make a line-start declaration conditional.
    keyword_code = re.sub(r"`[^`\n]*`", lambda m: " " * len(m.group()), code_text)
    if re.search(r"\b(?:if|else|when|for|while|do|try|catch|finally|return)\b", keyword_code):
        raise TagVersionError("control flow in defaultConfig is unsupported")
    # Prefix operators may precede an arbitrary qualified receiver, not just
    # the property itself. Reject them separately from postfix/assignments.
    if re.search(
        r"(?:\+\+|--)\s*(?:(?:[\w]+|`[^`\n]+`)(?:\s*@\s*[\w]+)?\s*\??\.\s*)*`?version(?:Code|Name)`?(?![\w`])",
        code_text,
    ):
        raise TagVersionError("version mutation in defaultConfig is unsupported")
    # Reject files that declare more than one versionCode/versionName assignment
    # (e.g. a literal inside a non-executed branch plus an effective dynamic
    # assignment): the exactly-one gate applies to *all* assignments, not only
    # the literal ones.
    generic_code_matches = [
        m for m in re.finditer(r"(?<![A-Za-z0-9_])`?versionCode`?\s*(?:[+*/%\-]?=(?!=)|\+\+|--)", code_text)
    ]
    generic_name_matches = [
        m for m in re.finditer(r'(?<![A-Za-z0-9_])`?versionName`?\s*(?:[+*/%\-]?=(?!=)|\+\+|--)', code_text)
    ]
    if len(generic_code_matches) != 1:
        raise TagVersionError(
            f"Gradle file must contain exactly one versionCode assignment (found {len(generic_code_matches)})"
        )
    if len(generic_name_matches) != 1:
        raise TagVersionError(
            "Gradle file must contain exactly one versionName assignment "
            f"(double-quoted, found {len(generic_name_matches)})"
        )
    code_matches = [
        m for m in re.finditer(r"(?m)^[ \t]*versionCode[ \t]*=[ \t]*([0-9]+)[ \t]*$", search_text)
        if m.start() == search_text.rfind("\n", 0, generic_code_matches[0].start()) + 1 and depths[generic_code_matches[0].start()] == 0 and grouping_depths[generic_code_matches[0].start()] == 0
    ]
    name_matches = [
        m for m in re.finditer(r'(?m)^[ \t]*versionName[ \t]*=[ \t]*"([^"]+)"[ \t]*$', search_text)
        if m.start() == search_text.rfind("\n", 0, generic_name_matches[0].start()) + 1 and depths[generic_name_matches[0].start()] == 0 and grouping_depths[generic_name_matches[0].start()] == 0
    ]
    codes = [m.group(1) for m in code_matches]
    names = [m.group(1) for m in name_matches]
    if len(codes) != 1:
        raise TagVersionError(
            f"Gradle file must contain exactly one versionCode assignment (found {len(codes)})"
        )
    if len(names) != 1:
        raise TagVersionError(
            "Gradle file must contain exactly one versionName assignment "
            f"(double-quoted, found {len(names)})"
        )
    raw_code = codes[0]
    if len(raw_code) > 1 and raw_code.startswith("0"):
        raise TagVersionError("Gradle versionCode must not contain leading zeros")
    if len(raw_code) > 10:
        raise TagVersionError("Gradle versionCode has too many digits")
    try:
        code = int(raw_code)
    except ValueError as exc:
        raise TagVersionError("Gradle versionCode is not a decimal integer") from exc
    return code, names[0]


def check_tag_version(tag: str, version_code: int, version_name: str) -> dict[str, object]:
    if not isinstance(tag, str) or not isinstance(version_name, str):
        raise TagVersionError("tag and versionName must be strings")
    if type(version_code) is not int:
        raise TagVersionError("versionCode must be an integer")
    if len(tag) > 256 or len(version_name) > MAX_VERSION_NAME_LEN:
        raise TagVersionError("tag/versionName exceeds the length limit")
    match = TAG_RE.fullmatch(tag)
    if match is None:
        raise TagVersionError(f"tag {tag!r} must use exact vX.Y.Z form (no suffix)")
    if not VERSION_RE.fullmatch(version_name):
        raise TagVersionError(
            f"versionName {version_name!r} must be pure X.Y.Z semver (no suffix)"
        )
    if match.group(1) != version_name:
        raise TagVersionError(
            f"tag {tag!r} does not equal versionName {version_name!r}"
        )
    if not 1 <= version_code <= MAX_VERSION_CODE:
        raise TagVersionError(
            f"versionCode {version_code} is outside the Android range 1..{MAX_VERSION_CODE}"
        )
    return {"status": "ok", "tag": tag, "versionName": version_name, "versionCode": version_code}


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="release tag, exact vX.Y.Z form")
    parser.add_argument(
        "--app-build-gradle", required=True, help="path to app/build.gradle.kts"
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        code, name = parse_gradle_version(args.app_build_gradle)
        result = check_tag_version(args.tag, code, name)
    except TagVersionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # never a traceback-or-pass fallback
        print(f"FAIL: tag/version check error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
