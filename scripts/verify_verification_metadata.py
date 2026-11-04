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


def _unescape_prop_key(s: str) -> str:
    """Unescape a properties key exactly like java.util.Properties.loadConvert.

    Left-to-right single pass: ``\\uXXXX`` (exactly 4 hex digits) decodes to
    the char; ``\\t \\n \\r \\f \\\\`` decode to control chars/backslash; any
    other ``\\x`` drops the backslash and keeps ``x`` (covers ``\\.`` ``\\=``
    ``\\:`` ``\\ `` ``\\!`` ``\\#``). A malformed ``\\u`` (fewer than 4 hex
    digits) is kept literal: loadConvert throws IllegalArgumentException
    ("Malformed \\uxxxx encoding") there, so Gradle fails closed with a build
    error instead of silently bypassing -- keeping it literal cannot hide a
    real ``off`` from this validator without also breaking the build.
    """
    out: list[str] = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        if i + 1 >= n:
            # Lone trailing backslash: unreachable mid-file (it would have
            # been consumed as a continuation marker); keep it literal.
            out.append("\\")
            i += 1
            continue
        nxt = s[i + 1]
        if nxt == "u":
            hexpart = s[i + 2 : i + 6]
            if len(hexpart) == 4 and all(ch in HEX64 for ch in hexpart):
                out.append(chr(int(hexpart, 16)))
                i += 6
            else:
                out.append("\\")
                i += 1
        elif nxt == "t":
            out.append("\t")
            i += 2
        elif nxt == "n":
            out.append("\n")
            i += 2
        elif nxt == "r":
            out.append("\r")
            i += 2
        elif nxt == "f":
            out.append("\f")
            i += 2
        else:
            out.append(nxt)
            i += 2
    return "".join(out)


def _properties_verification_mode(lines: list[str]) -> str | None:
    """Return last org.gradle.dependency.verification value per Java properties semantics."""
    # Logical-line merging runs BEFORE unescaping, mirroring Properties.load:
    # a physical line ending in an odd number of backslashes continues (the
    # final backslash plus the line break are dropped and leading whitespace
    # of the next line is stripped, per spec); an even count is literal
    # backslashes and does not continue. Comment/blank detection runs on the
    # merged logical lines. Duplicate keys are last-wins (Properties.load
    # overwrites), and key escapes are unescaped before comparing, so
    # obfuscated/continued/duplicated keys cannot slip past.
    key_wanted = "org.gradle.dependency.verification"
    logical: list[str] = []
    buf: str | None = None
    for raw in lines:
        buf = raw if buf is None else buf + raw.lstrip(" \t\f")
        trailing = len(buf) - len(buf.rstrip("\\"))
        if trailing % 2 == 1:
            buf = buf[:-1]  # drop continuation marker, merge next line
            continue
        logical.append(buf)
        buf = None
    if buf is not None:
        logical.append(buf)
    mode: str | None = None
    seen = False
    for raw in logical:
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
            if c in ("=", ":", " ", "\t", "\f"):
                # Count preceding backslashes: odd count means escaped.
                bs = 0
                j = i - 1
                while j >= 0 and stripped[j] == "\\":
                    bs += 1
                    j -= 1
                if bs % 2 == 0:
                    key_end = i
                    sep_kind = c if c in ("=", ":") else "ws"
                    break
            i += 1
        key = _unescape_prop_key(stripped[:key_end])
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
        # Java applies the same loadConvert unescaping to values, so value-side
        # obfuscation (o\u0066f, \u006f\u0066\u0066) must be decoded too --
        # verified against Properties.load: both load as "off"/"OFF".
        value = _unescape_prop_key(value)
        if not value:
            mode, seen = "", True
            continue
        # Known value domain is only strict/off/lenient; first whitespace
        # token blocks '=off # comment' style inline-comment mixing with zero false kills.
        mode, seen = value.lower().split(None, 1)[0], True
    return mode if seen else None


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
