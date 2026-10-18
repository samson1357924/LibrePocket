#!/usr/bin/env python3
"""Check release tag <-> Gradle version consistency (fail-closed, no secrets).

Rule: a release tag ``vX.Y.Z`` must exactly equal the ``versionName`` in
``app/build.gradle.kts`` (pure ``X.Y.Z`` semver, no suffix), and
``versionCode`` must be a positive Android integer (1..2147483647).

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


def _strip_kotlin_comments(text: str) -> str:
    """Replace Kotlin comments with spaces (newlines preserved).

    Strips ``//`` line comments and nestable ``/* ... */`` block comments
    so commented-out literals can never satisfy the exactly-one gate.
    Double-quoted and triple-quoted strings as well as character literals
    are preserved, so ``//`` or ``/*`` inside a version string is never
    treated as a comment. Unterminated block comments blank through EOF;
    unterminated strings/literals are preserved as-is (still fail-closed
    via the exactly-one gate).
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end == -1 else end + 3
        elif text[i] == "'":
            # Kotlin character literal ('a', '\'', '\\', '\uXXXX'): skip it
            # so a '"' inside never opens a phantom string.
            i += 1
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
                    break
                else:
                    i += 1
        elif text[i] == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    nxt = text[i + 1 : i + 2]
                    if nxt == "" or nxt == "\n":
                        break  # unterminated escape at EOL
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
                elif text[i] == "\n":
                    break  # unterminated string at EOL
                else:
                    i += 1
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
        if text.startswith('"""', i):
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
        if text.startswith('"""', i):
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


def _extract_default_config(stripped: str) -> str | None:
    """Return the body of the first android { ... defaultConfig { ... } } block.

    Returns None when no such block exists, so callers fall back to the
    (legacy) whole-file scan for simple/test fixtures.

    String literals are skipped so that an ``android {`` / ``defaultConfig {``
    sequence appearing inside a (raw or ordinary) string cannot be mistaken
    for the real block.
    """
    intervals = _string_intervals(stripped)
    android_candidates = [
        cand for cand in re.finditer(r"(?<![A-Za-z0-9_])android\s*\{", stripped)
        if not _inside_string(cand.start(), intervals)
    ]
    if not android_candidates:
        return None
    if len(android_candidates) > 1:
        raise TagVersionError("Gradle file must contain exactly one android block")
    m = android_candidates[0]
    open_idx = m.end() - 1
    close_idx = _find_matching_brace(stripped, open_idx)
    if close_idx == -1:
        return None
    android_body = stripped[open_idx + 1 : close_idx]
    body_intervals = _string_intervals(android_body)
    default_candidates = [
        cand for cand in re.finditer(r"(?<![A-Za-z0-9_])defaultConfig\s*\{", android_body)
        if not _inside_string(cand.start(), body_intervals)
    ]
    if not default_candidates:
        return None
    if len(default_candidates) > 1:
        raise TagVersionError("Gradle file must contain exactly one defaultConfig block")
    dm = default_candidates[0]
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
    # Prefer the authoritative android.defaultConfig block when present so
    # that literals in raw strings, comments (already stripped) or unrelated
    # scopes cannot satisfy the gate. Fall back to the whole file for simple
    # fixtures that do not declare an android block.
    # An ``android {`` block that is present but cannot be resolved to a
    # ``defaultConfig {`` block (unterminated/malformed or missing
    # defaultConfig) must NOT silently fall back to a whole-file scan: that
    # would let unrelated top-level literals satisfy the gate (fail-open).
    # Only files with no android block at all use the legacy whole-file scan
    # (simple/test fixtures).
    _android_block_re = re.compile(r"(?<![A-Za-z0-9_])android\s*\{")
    _detection_intervals = _string_intervals(stripped)
    _has_android_block = any(
        not _inside_string(candidate.start(), _detection_intervals)
        for candidate in _android_block_re.finditer(stripped)
    )
    search_text = _extract_default_config(stripped)
    if search_text is None:
        if _has_android_block:
            raise TagVersionError(
                "Gradle file declares an android block but no parseable "
                "android.defaultConfig block"
            )
        search_text = stripped
    intervals = _string_intervals(search_text)
    # Reject files that declare more than one versionCode/versionName assignment
    # (e.g. a literal inside a non-executed branch plus an effective dynamic
    # assignment): the exactly-one gate applies to *all* assignments, not only
    # the literal ones.
    generic_code_matches = [
        m for m in re.finditer(r"(?m)^\s*versionCode\s*=", search_text)
        if not _inside_string(m.start(), intervals)
    ]
    generic_name_matches = [
        m for m in re.finditer(r'(?m)^\s*versionName\s*=', search_text)
        if not _inside_string(m.start(), intervals)
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
        m for m in re.finditer(r"(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$", search_text)
        if not _inside_string(m.start(), intervals)
    ]
    name_matches = [
        m for m in re.finditer(r'(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$', search_text)
        if not _inside_string(m.start(), intervals)
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
