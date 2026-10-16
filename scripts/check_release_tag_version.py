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
regardless of this check's result.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MAX_GRADLE_BYTES = 512 * 1024
TAG_RE = re.compile(r"v(\d+\.\d+\.\d+)")
VERSION_RE = re.compile(r"\d+\.\d+\.\d+")
MAX_VERSION_CODE = 2_147_483_647


class TagVersionError(Exception):
    """A fail-closed tag/version mismatch."""


def parse_gradle_version(gradle_path: str) -> tuple[int, str]:
    path = Path(gradle_path)
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise TagVersionError(f"cannot stat Gradle file: {exc}") from exc
    if size <= 0 or size > MAX_GRADLE_BYTES:
        raise TagVersionError("Gradle file size is empty or exceeds the parser limit")
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise TagVersionError(f"cannot read Gradle file as UTF-8: {exc}") from exc
    # NOTE: `versionNameSuffix` (debug "-debug") must not match; the pattern
    # requires `versionName` followed directly by `=`, so the longer
    # `versionNameSuffix = ...` line cannot match.
    codes = re.findall(r"(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$", text)
    names = re.findall(r'(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$', text)
    if len(codes) != 1:
        raise TagVersionError(
            f"Gradle file must contain exactly one versionCode assignment (found {len(codes)})"
        )
    if len(names) != 1:
        raise TagVersionError(
            f"Gradle file must contain exactly one versionName assignment (found {len(names)})"
        )
    try:
        code = int(codes[0])
    except ValueError as exc:
        raise TagVersionError("Gradle versionCode is not a decimal integer") from exc
    return code, names[0]


def check_tag_version(tag: str, version_code: int, version_name: str) -> dict[str, object]:
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
    import json

    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
