#!/usr/bin/env python3
"""Checksum-only strict gate for gradle/verification-metadata.xml (stdlib only).

Production validator: validates the strict checksum-only gate without running
Gradle (file exists and is well-formed XML, checksum-only configuration, no
bypasses, every component carries a 64-hex sha256, no per-configuration
Gradle bypass). The regression tests in
scripts/tests/test_verification_metadata.py only import this module; the
validator logic lives here.

Usage: python3 -P scripts/verify_verification_metadata.py
Exit status: 0 = gate passes, 2 = gate rejected (ValueError).
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
METADATA = ROOT / "gradle/verification-metadata.xml"
NS = {"v": "https://schema.gradle.org/dependency-verification"}
HEX64 = frozenset("0123456789abcdefABCDEF")
VNS = "https://schema.gradle.org/dependency-verification"


def _text(element) -> str:
    return (element.text or "").strip() if element is not None else ""


def validate_tree(root: ET.Element) -> None:
    if root.tag != "{https://schema.gradle.org/dependency-verification}verification-metadata":
        raise ValueError(f"unexpected root tag: {root.tag}")
    config = root.find("v:configuration", NS)
    if config is None:
        raise ValueError("missing <configuration>")
    verify_metadata = _text(config.find("v:verify-metadata", NS))
    if verify_metadata != "true":
        raise ValueError(f"<verify-metadata> must be 'true', got {verify_metadata!r}")
    verify_signatures = _text(config.find("v:verify-signatures", NS))
    if verify_signatures != "false":
        raise ValueError(
            f"<verify-signatures> must be 'false' for the checksum-only slice, got {verify_signatures!r}"
        )
    if config.find("v:trusted-artifacts", NS) is not None:
        raise ValueError("<trusted-artifacts> allowlist is forbidden (broad trust bypass)")
    if config.find("v:ignored-keys", NS) is not None:
        raise ValueError("<ignored-keys> is forbidden in the checksum-only slice")
    key_servers = config.find("v:key-servers", NS)
    if key_servers is not None and key_servers.get("enabled") == "false":
        raise ValueError("<key-servers enabled='false'/> weakens future signature bootstrapping")
    components = root.find("v:components", NS)
    if components is None:
        raise ValueError("missing <components>")
    entries = components.findall("v:component", NS)
    if not entries:
        raise ValueError("no <component> entries (empty verification is a bypass)")
    for comp in entries:
        group, name, version = comp.get("group"), comp.get("name"), comp.get("version")
        if not group or not name or not version:
            raise ValueError(f"component missing group/name/version: {ET.tostring(comp)[:120]!r}")
        artifacts = comp.findall("v:artifact", NS)
        if not artifacts:
            raise ValueError(f"component {group}:{name}:{version} has no artifacts")
        for artifact in artifacts:
            aname = artifact.get("name")
            if not aname:
                raise ValueError(f"artifact missing name in {group}:{name}:{version}")
            sha = artifact.find("v:sha256", NS)
            if sha is None:
                raise ValueError(f"artifact {aname} missing <sha256> (weak-only or unsigned)")
            value = (sha.get("value") or "").strip()
            if len(value) != 64 or any(c not in HEX64 for c in value):
                raise ValueError(f"artifact {aname} has invalid sha256: {value!r}")
            for weak in ("md5", "sha1"):
                if artifact.find(f"v:{weak}", NS) is not None:
                    raise ValueError(f"artifact {aname} uses weak hash <{weak}> (use sha256+)")


def _properties_verification_mode(lines: list[str]) -> str | None:
    """Return first org.gradle.dependency.verification value per simplified Java properties semantics."""
    # Backslash-escaped separators do not split the key (odd backslash count
    # means escaped); \uXXXX escapes in the key are unescaped before comparing,
    # mirroring Properties.load, so unicode-obfuscated keys cannot slip past.
    key_wanted = "org.gradle.dependency.verification"
    for raw in lines:
        line = raw.lstrip(" \t\f")
        if not line or line[0] in ("#", "!"):
            continue
        stripped = line.rstrip("\r\n")
        n = len(stripped)
        key_end = n
        sep_kind = ""  # '=', ':', or 'ws'
        i = 0
        while i < n:
            c = stripped[i]
            if c in ("=", ":"):
                # Count preceding backslashes: odd count means escaped.
                bs = 0
                j = i - 1
                while j >= 0 and stripped[j] == "\\":
                    bs += 1
                    j -= 1
                if bs % 2 == 0:
                    key_end = i
                    sep_kind = c
                    break
            elif c in (" ", "\t", "\f"):
                key_end = i
                sep_kind = "ws"
                break
            i += 1
        key = stripped[:key_end]
        key = re.sub(
            r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), key
        )
        if key != key_wanted:
            continue
        rest = stripped[key_end:]
        if sep_kind in ("=", ":"):
            value = rest[1:].strip()
        else:
            # key terminated by whitespace (or no separator): skip blanks,
            # then one optional '=' / ':' and blanks, per properties spec.
            k = 0
            while k < len(rest) and rest[k] in (" ", "\t", "\f"):
                k += 1
            if k < len(rest) and rest[k] in ("=", ":"):
                k += 1
                while k < len(rest) and rest[k] in (" ", "\t", "\f"):
                    k += 1
            value = rest[k:].strip()
        if not value:
            return ""
        # Known value domain is only strict/off/lenient; first whitespace
        # token blocks '=off # comment' style inline-comment mixing with zero false kills.
        return value.lower().split(None, 1)[0]
    return None


def validate_gradle_files(root: Path = ROOT) -> None:
    # Scan Gradle scripts under root; skip generated output dirs (build/)
    # and hidden dirs so future build outputs can never false-positive.
    def _scannable(path: Path) -> bool:
        try:
            rel = path.relative_to(root)
        except ValueError:
            return True
        return not any(part == "build" or part.startswith(".") for part in rel.parts[:-1])

    targets = sorted(
        p
        for pattern in ("*.gradle.kts", "*.gradle")
        for p in root.rglob(pattern)
        if _scannable(p)
    )
    props = root / "gradle.properties"
    if props.is_file():
        targets.append(props)
    for path in targets:
        if not path.is_file():
            continue
        try:
            label = str(path.relative_to(root))
        except ValueError:
            label = str(path)
        text = path.read_text(encoding="utf-8")
        # Gradle DSL has no properties-style whitespace separator semantics, so substring matching is sufficient here.
        if "disableDependencyVerification" in text:
            raise ValueError(f"{label} disables dependency verification (bypass forbidden)")
        if path.name == "gradle.properties":
            mode = _properties_verification_mode(text.splitlines())
            if mode in ("off", "lenient"):
                raise ValueError(
                    f"{label} weakens verification mode (strict required, got {mode!r})"
                )


def load_metadata() -> ET.Element:
    if not METADATA.is_file():
        raise ValueError(f"missing {METADATA} (run --write-verification-metadata)")
    try:
        return ET.parse(str(METADATA)).getroot()
    except ET.ParseError as exc:
        raise ValueError(f"invalid XML in {METADATA}: {exc}") from exc


def main() -> int:
    try:
        root = load_metadata()
        validate_tree(root)
        validate_gradle_files()
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    count = len(root.find("v:components", NS).findall("v:component", NS))
    print(f"verification-metadata: strict checksum gate OK ({count} components checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
