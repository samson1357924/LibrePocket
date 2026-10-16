#!/usr/bin/env python3
"""Validate a release signer-pin registry file (fail-closed, no secrets read).

The registry binds each flavor to its trusted package ID and signer
certificate SHA-256 fingerprint. Production pins live OUTSIDE this repo
with the release maintainer and are never committed; the repo holds only
``scripts/release_signer_pins.example.json`` (placeholder cert values that
this validator rejects) as a format reference.

Expected shape::

    {
      "play":   {"package": "dev.librepocket.agent",        "cert_sha256": "<64 hex>"},
      "foss":   {"package": "dev.librepocket.agent.foss",   "cert_sha256": "<64 hex>"},
      "github": {"package": "dev.librepocket.agent.github", "cert_sha256": "<64 hex>"}
    }

Rules (all fail-closed):
  - top-level JSON object with 1..3 known flavors (play/foss/github),
    no unknown flavors, no duplicate-or-extra fields per entry;
  - ``package`` matches the dotted Android package rule used by
    ``scripts/verify_release_apk.py`` (and is at most 255 chars);
  - ``cert_sha256`` is exactly 64 hexadecimal chars (case-insensitive,
    normalized to lowercase); placeholder/non-hex values are rejected;
  - file larger than 64 KiB is rejected before parsing;
  - ``--require-flavors`` (e.g. ``'play foss github'``) enforces the exact
    expected flavor set when the caller needs all three.

This validator never reads keystores, passwords, or Bitwarden state and
never approves a signing policy; it only checks pin-file shape so a
caller can fail fast before supplying pins as ``--expected-*`` identity
to ``scripts/build_release.sh``.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

KNOWN_FLAVORS = ("play", "foss", "github")
MAX_PINS_BYTES = 64 * 1024
_PACKAGE_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
_CERT_RE = re.compile(r"[0-9A-Fa-f]{64}")


class PinError(Exception):
    """A fail-closed registry validation failure."""


def load_pins(path_value: str, *, require_flavors: tuple[str, ...] | None = None) -> dict[str, dict[str, str]]:
    path = Path(path_value)
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise PinError(f"cannot stat pins file: {exc}") from exc
    if size <= 0 or size > MAX_PINS_BYTES:
        raise PinError(f"pins file size is empty or exceeds {MAX_PINS_BYTES} bytes")
    try:
        raw = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise PinError(f"cannot read pins file as UTF-8: {exc}") from exc
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise PinError(f"pins file is not valid JSON: {exc}") from exc
    if not isinstance(data, dict) or not data:
        raise PinError("pins file must be a non-empty JSON object keyed by flavor")
    if any(not isinstance(key, str) for key in data):
        raise PinError("pins file flavor keys must be strings")
    unknown = sorted(k for k in data if k not in KNOWN_FLAVORS)
    if unknown:
        raise PinError(f"pins file contains unknown flavor(s): {', '.join(unknown)}")
    if require_flavors is not None:
        if set(require_flavors) != set(data):
            raise PinError(
                "pins file flavors "
                f"{sorted(data)} do not match required {sorted(require_flavors)}"
            )
    normalized: dict[str, dict[str, str]] = {}
    for flavor in data:
        entry = data[flavor]
        if not isinstance(entry, dict):
            raise PinError(f"pins entry for {flavor} must be an object")
        if set(entry) != {"package", "cert_sha256"}:
            raise PinError(
                f"pins entry for {flavor} must contain exactly package/cert_sha256"
            )
        package = entry["package"]
        cert = entry["cert_sha256"]
        if not isinstance(package, str) or len(package) > 255 or not _PACKAGE_RE.fullmatch(package):
            raise PinError(f"invalid package for {flavor}")
        if not isinstance(cert, str) or not _CERT_RE.fullmatch(cert):
            raise PinError(f"invalid cert_sha256 for {flavor} (must be 64 hex chars)")
        normalized[flavor] = {"package": package, "cert_sha256": cert.lower()}
    return normalized


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pins", required=True, help="path to the signer-pin JSON file")
    parser.add_argument(
        "--require-flavors",
        default=None,
        help="space-separated required flavor set, e.g. 'play foss github'",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    required: tuple[str, ...] | None = None
    if args.require_flavors is not None:
        required = tuple(args.require_flavors.split())
        if not required or len(set(required)) != len(required) or any(
            f not in KNOWN_FLAVORS for f in required
        ):
            print("FAIL: --require-flavors must list unique known flavors", file=sys.stderr)
            return 1
    try:
        pins = load_pins(args.pins, require_flavors=required)
    except PinError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # never a traceback-or-pass fallback
        print(f"FAIL: pins validator error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({"status": "ok", "flavors": pins}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
