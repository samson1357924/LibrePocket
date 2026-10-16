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
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
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


def parse_gradle_version(gradle_path: str) -> tuple[int, str]:
    if not isinstance(gradle_path, (str, os.PathLike)):
        raise TagVersionError("Gradle file path must be a string")
    path = Path(gradle_path)
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise TagVersionError(f"cannot stat Gradle file: {exc}") from exc
    if size <= 0 or size > MAX_GRADLE_BYTES:
        if size <= 0:
            raise TagVersionError("Gradle file is empty")
        raise TagVersionError("Gradle file size exceeds the parser limit")
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise TagVersionError(f"cannot read Gradle file as UTF-8: {exc}") from exc
    if len(text.encode("utf-8")) > MAX_GRADLE_BYTES:
        raise TagVersionError("Gradle file size exceeds the parser limit")
    # NOTE: `versionNameSuffix` (debug "-debug") must not match; the pattern
    # requires `versionName` followed directly by `=`, so the longer
    # `versionNameSuffix = ...` line cannot match.
    # Kotlin comments are stripped first so commented-out literals can never
    # satisfy the gate; trailing `//` comments are tolerated.
    stripped = _strip_kotlin_comments(text)
    codes = re.findall(r"(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$", stripped)
    names = re.findall(r'(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$', stripped)
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
