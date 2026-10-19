"""Stdlib regression harness for fail-closed APK policy inspection.

Fixtures are generated in temporary directories. The DEX fixtures follow the
AOSP format contract for versions 035, 037-040: little-endian 0x70-byte headers,
string_ids/type_ids indirection, ULEB128 UTF-16 code-unit lengths, and MUTF-8
string data. DEX 036, experimental/container 041, reverse-endian, and unknown
formats are intentionally unsupported. Fixtures exercise only the header and
tables consumed by this scanner; they are not complete ART bytecode-semantic or
APK-signature verifiers. See the official AOSP DEX format specification:
https://source.android.com/docs/core/runtime/dex-format

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_apk_policy_check.py
"""

from __future__ import annotations

import base64
import contextlib
import hashlib
import io
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import xml.etree.ElementTree as ET
import zipfile
import zlib
from fnmatch import fnmatchcase
from pathlib import Path
from unittest import mock

from scripts import apk_policy_inspect as policy_inspect
from scripts import backup_rules_check
from scripts import nsc_check

ROOT = Path(__file__).resolve().parents[2]
POLICY_CHECK = ROOT / "scripts" / "play_policy_check.sh"
BUILD_RELEASE = ROOT / "scripts" / "build_release.sh"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
PR_CHECK_WORKFLOW = ROOT / ".github" / "workflows" / "pr-check.yml"

# Optional live-artifact control target: the locally produced debug APK. CI
# unittest runners have no build outputs and no SDK, so those controls skip.
_REAL_DEBUG_APK = (
    ROOT / "app" / "build" / "outputs" / "apk" / "play" / "debug" / "app-play-debug.apk"
)


def _discover_real_aapt() -> str | None:
    """Find a REAL aapt executable for the optional live-artifact controls.

    Probes ANDROID_HOME then the conventional $HOME/Android/Sdk layout for the
    newest build-tools directory carrying an executable aapt. Never returns a
    fake/harness tool: only a real executable counts.
    """
    candidates = []
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        candidates.append(Path(android_home) / "build-tools")
    home_sdk = Path(os.environ.get("HOME", "")) / "Android" / "Sdk" / "build-tools"
    candidates.append(home_sdk)
    for build_tools in candidates:
        if not build_tools.is_dir():
            continue
        for version_dir in sorted(build_tools.iterdir(), reverse=True):
            aapt = version_dir / "aapt"
            if aapt.is_file() and os.access(aapt, os.X_OK):
                return str(aapt)
    return None

_FORBIDDEN_FOSS_TYPE = "Lcom/google/mlkit/Recognizer;"
_SUPPORTED_DEX_VERSIONS = ("035", "037", "038", "039", "040")


def _uleb128(value: int) -> bytes:
    encoded = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        encoded.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(encoded)


def _mutf8(value: str) -> tuple[int, bytes]:
    """Encode DEX MUTF-8 and return its UTF-16 code-unit count."""
    utf16 = value.encode("utf-16-be", errors="surrogatepass")
    units = struct.unpack(f">{len(utf16) // 2}H", utf16) if utf16 else ()
    encoded = bytearray()
    for unit in units:
        if 0x0001 <= unit <= 0x007F:
            encoded.append(unit)
        elif unit <= 0x07FF:
            encoded.extend((0xC0 | (unit >> 6), 0x80 | (unit & 0x3F)))
        else:
            encoded.extend(
                (
                    0xE0 | (unit >> 12),
                    0x80 | ((unit >> 6) & 0x3F),
                    0x80 | (unit & 0x3F),
                )
            )
    return len(units), bytes(encoded)


def _update_dex_checksums(dex: bytearray) -> None:
    # DEX SHA-1 is an internal format integrity field, not APK signing.
    dex[12:32] = hashlib.sha1(dex[32:], usedforsecurity=False).digest()
    struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]) & 0xFFFFFFFF)


def minimal_dex(
    *,
    type_descriptors: tuple[str, ...] = (),
    literal_strings: tuple[str, ...] = (),
    class_definitions: tuple[tuple[str, str | None], ...] = (),
    version: str = "035",
    endian_tag: int = 0x12345678,
) -> bytes:
    """Build a bounded DEX table fixture, not a complete executable DEX.

    ``type_descriptors`` are reached through type_ids -> string_ids.
    ``literal_strings`` are present only in string_ids, modeling descriptor-like
    text that is not a type reference. The DEX header checksum/signature and map
    entries are populated so bounds/layout tests do not depend on guessed offsets.
    ``class_definitions`` contains (class descriptor, superclass descriptor)
    pairs in the requested definition order. No executable code is included;
    do not infer ART validity.
    """
    if version not in _SUPPORTED_DEX_VERSIONS:
        raise ValueError(f"fixture builder supports only DEX {', '.join(_SUPPORTED_DEX_VERSIONS)}")

    all_types = set(type_descriptors)
    for class_descriptor, superclass_descriptor in class_definitions:
        all_types.add(class_descriptor)
        if superclass_descriptor is not None:
            all_types.add(superclass_descriptor)
    strings = sorted(
        set((*all_types, *literal_strings)),
        key=lambda value: value.encode("utf-16-be", errors="surrogatepass"),
    )
    string_index = {value: index for index, value in enumerate(strings)}
    ordered_types = sorted(all_types, key=string_index.__getitem__)
    type_index = {value: index for index, value in enumerate(ordered_types)}

    header_size = 0x70
    string_ids_off = header_size if strings else 0
    type_ids_off = header_size + 4 * len(strings) if ordered_types else 0
    class_defs_off = (
        header_size + 4 * len(strings) + 4 * len(ordered_types)
        if class_definitions
        else 0
    )
    map_off = (
        header_size
        + 4 * len(strings)
        + 4 * len(ordered_types)
        + 32 * len(class_definitions)
    )

    map_items: list[tuple[int, int, int]] = [(0x0000, 1, 0)]  # header_item
    if strings:
        map_items.append((0x0001, len(strings), string_ids_off))
    if ordered_types:
        map_items.append((0x0002, len(ordered_types), type_ids_off))
    if class_definitions:
        map_items.append((0x0006, len(class_definitions), class_defs_off))
    map_items.append((0x1000, 1, map_off))  # map_list
    if strings:
        map_items.append((0x2002, len(strings), -1))  # string_data_item; offset filled below

    map_size = 4 + 12 * len(map_items)
    string_data_off = map_off + map_size
    if strings:
        map_items[-1] = (0x2002, len(strings), string_data_off)

    string_data = bytearray()
    string_offsets: list[int] = []
    for value in strings:
        utf16_length, encoded = _mutf8(value)
        string_offsets.append(string_data_off + len(string_data))
        string_data.extend(_uleb128(utf16_length))
        string_data.extend(encoded)
        string_data.append(0)  # MUTF-8 terminator; U+0000 itself is C0 80.

    unpadded_size = string_data_off + len(string_data)
    file_size = (unpadded_size + 3) & ~3
    data_off = map_off
    data_size = file_size - data_off
    dex = bytearray(file_size)
    dex[0:8] = b"dex\n" + version.encode("ascii") + b"\0"
    struct.pack_into("<I", dex, 32, file_size)
    struct.pack_into("<I", dex, 36, header_size)
    struct.pack_into("<I", dex, 40, endian_tag)
    struct.pack_into("<I", dex, 52, map_off)
    if strings:
        struct.pack_into("<II", dex, 56, len(strings), string_ids_off)
        for index, offset in enumerate(string_offsets):
            struct.pack_into("<I", dex, string_ids_off + 4 * index, offset)
    if ordered_types:
        struct.pack_into("<II", dex, 64, len(ordered_types), type_ids_off)
        for index, descriptor in enumerate(ordered_types):
            struct.pack_into(
                "<I", dex, type_ids_off + 4 * index, string_index[descriptor]
            )
    struct.pack_into("<II", dex, 96, len(class_definitions), class_defs_off)
    for index, (class_descriptor, superclass_descriptor) in enumerate(class_definitions):
        class_index = type_index[class_descriptor]
        superclass_index = (
            0xFFFFFFFF
            if superclass_descriptor is None
            else type_index[superclass_descriptor]
        )
        struct.pack_into(
            "<IIIIIIII",
            dex,
            class_defs_off + 32 * index,
            class_index,
            0x1,  # public
            superclass_index,
            0,  # interfaces_off
            0xFFFFFFFF,  # source_file_idx (NO_INDEX)
            0,  # annotations_off
            0,  # class_data_off
            0,  # static_values_off
        )
    struct.pack_into("<II", dex, 104, data_size, data_off)
    struct.pack_into("<I", dex, map_off, len(map_items))
    for index, (item_type, item_size, item_off) in enumerate(map_items):
        struct.pack_into("<HHII", dex, map_off + 4 + 12 * index, item_type, 0, item_size, item_off)
    dex[string_data_off : string_data_off + len(string_data)] = string_data

    # DEX signature is SHA-1 over bytes[32:], checksum is Adler-32 over bytes[12:].
    _update_dex_checksums(dex)
    return bytes(dex)


def write_apk(path: Path, members: dict[str, bytes]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in members.items():
            archive.writestr(name, payload)
    return path


def complete_apk(path: Path, dex: bytes | None = None) -> Path:
    return write_apk(
        path,
        {
            "AndroidManifest.xml": b"synthetic compiled manifest fixture",
            "classes.dex": dex or minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        },
    )


class ApkPolicyHarness(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="librepocket-apk-policy-")
        self.addCleanup(self.temp.cleanup)
        self.tmp = Path(self.temp.name)
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.sdk = self.tmp / "sdk"
        self.build_tools = self.sdk / "build-tools" / "36.0.0"
        self.build_tools.mkdir(parents=True)
        self.fake_bin = self.tmp / "bin"
        self.fake_bin.mkdir()
        self._write_aapt()
        self.dexdump_path = self.build_tools / "dexdump"
        self._write_dexdump()

    def _write_aapt(self) -> None:
        # The default xmltree is a merged manifest that CARRIES the backup AND
        # networkSecurityConfig bindings: apk_policy_inspect asserts the merged
        # <application> keeps android:fullBackupContent /
        # android:dataExtractionRules / android:networkSecurityConfig
        # (artifact-level authority), so a fixture without them would be a
        # rejection specimen now. FAKE_AAPT_XMLTREE selects negative/stripped
        # shapes; FAKE_AAPT_NSC selects the compiled-NSC dump shape, dispatched
        # on $4 exactly like the real aapt invocation pairs.
        aapt = self.build_tools / "aapt"
        aapt.write_text(
            "#!/bin/sh\n"
            "set -eu\n"
            "case \"${FAKE_AAPT_MODE:-ok}\" in\n"
            "  error) echo 'synthetic aapt parse failure' >&2; exit 23 ;;\n"
            "  empty) exit 0 ;;\n"
            "  hang) sleep 30 ;;\n"
            "esac\n"
            "case \"${1:-} ${2:-}\" in\n"
            "  'dump permissions')\n"
            "    printf '%s\\n' 'package:dev.librepocket.agent'\n"
            "    # FAKE_AAPT_PERMISSIONS: comma-separated uses-permission token\n"
            "    # names appended in the measured aapt dump spelling, used by the\n"
            "    # common/play permission-policy negative fixtures only.\n"
            "    for token in $(echo \"${FAKE_AAPT_PERMISSIONS:-}\" | tr ',' ' '); do\n"
            "      printf '%s\\n' \"uses-permission: android.permission.${token}\"\n"
            "    done ;;\n"
            "  'dump xmltree')\n"
            "    if [ \"${4:-}\" = \"res/xml/network_security_config.xml\" ]; then\n"
            "      # Compiled-NSC fixtures mirror the real aapt v1 xmltree shape\n"
            "      # (measured on a real debug APK): two-space depth steps,\n"
            "      # booleans as (type 0x12)0x0 / 0xffffffff, strings as\n"
            "      # \"x\" (Raw: \"x\"), chardata as C: \"text\".\n"
            "      case \"${FAKE_AAPT_NSC:-ok}\" in\n"
            "        loopback-debug)\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0xffffffff' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '      C: \"localhost\"' ;;\n"
            "        reviewer)\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0xffffffff' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '      C: \"example.com\"' ;;\n"
            "        loopback-nosub)\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0xffffffff' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      C: \"localhost\"' ;;\n"
            "        loopback-subtrue)\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0xffffffff' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0xffffffff' \\\n"
            "            '      C: \"localhost\"' ;;\n"
            "        n1-nested)\n"
            "          # Review probe n1: the outer loopback entry stays legal,\n"
            "          # but the claim-less inner domain-config INHERITS the\n"
            "          # \"true\" at runtime (AOSP Builder parent chain), so the\n"
            "          # compiled artifact enables cleartext for example.com.\n"
            "          # The effective-value rule must fail this in both modes.\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0xffffffff' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '      C: \"localhost\"' \\\n"
            "            '    E: domain-config (line=5)' \\\n"
            "            '      E: domain (line=6)' \\\n"
            "            '        A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '        C: \"example.com\"' ;;\n"
            "        n1-narrowed-debug)\n"
            "          # Same shape with the inner entry's OWN \"false\": the\n"
            "          # Builder override legally narrows, so under the DEBUG\n"
            "          # rules this whole compiled artifact stays valid.\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0xffffffff' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '      C: \"localhost\"' \\\n"
            "            '    E: domain-config (line=5)' \\\n"
            "            '      A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '      E: domain (line=6)' \\\n"
            "            '        A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '        C: \"app.example.com\"' ;;\n"
            "        n1-narrowed-release)\n"
            "          # An all-false nested shape is valid under the release\n"
            "          # deny-all rules too: own=\"false\" must not be rejected.\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '      C: \"app.example.com\"' \\\n"
            "            '    E: domain-config (line=5)' \\\n"
            "            '      A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '      E: domain (line=6)' \\\n"
            "            '        A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '        C: \"sdk.partner.com\"' ;;\n"
            "        base-true)\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0xffffffff' ;;\n"
            "        pins-ok)\n"
            "          # Compiled VALUE-SHAPE positive control: src=\"system\"\n"
            "          # prints as the quoted raw string and a legal 32-byte\n"
            "          # sha-256 pin (quoted attr + C: chardata) must validate\n"
            "          # at the artifact face the same way the source face does.\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '    E: trust-anchors (line=3)' \\\n"
            "            '      E: certificates (line=4)' \\\n"
            "            '        A: src=\"system\"' \\\n"
            "            '  E: domain-config (line=5)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '    E: domain (line=6)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '      C: \"example.com\"' \\\n"
            "            '    E: pin-set (line=7)' \\\n"
            "            '      E: pin (line=8)' \\\n"
            "            '        A: digest=\"sha-256\"' \\\n"
            "            '        C: \"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=\"' ;;\n"
            "        pins-bad-digest)\n"
            "          # artifact-face P1 mirror: an unsupported digest\n"
            "          # algorithm must fail the compiled bytes with the same\n"
            "          # AOSP wording (Pin.isSupportedDigestAlgorithm).\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: domain-config (line=3)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '    E: domain (line=4)' \\\n"
            "            '      A: includeSubdomains=(type 0x12)0x0' \\\n"
            "            '      C: \"example.com\"' \\\n"
            "            '    E: pin-set (line=5)' \\\n"
            "            '      E: pin (line=6)' \\\n"
            "            '        A: digest=\"SHA-512\"' \\\n"
            "            '        C: \"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+Pw==\"' ;;\n"
            "        cert-src-file-path)\n"
            "          # artifact-face certificates src: a file-path src is the\n"
            "          # runtime \"Unknown certificates src\" throw shape.\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '    E: trust-anchors (line=3)' \\\n"
            "            '      E: certificates (line=4)' \\\n"
            "            '        A: src=\"res/raw/certs.p12\"' ;;\n"
            "        cert-src-typed)\n"
            "          # artifact-face legal resource-reference src: the COMPILED\n"
            "          # typed payload prints unquoted (@res/0x...) and must stay\n"
            "          # accepted by the same allow-set prefix (parseCertificatesEntry\n"
            "          # sourceId != -1 -> ResourceCertificateSource).\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '    E: trust-anchors (line=3)' \\\n"
            "            '      E: certificates (line=4)' \\\n"
            "            '        A: src=@res/0x7f050001' ;;\n"
            "        unknown-line)\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' \\\n"
            "            '  E: trogdor (line=3)' ;;\n"
            "        error)\n"
            "          # A real defect surface: the ERROR line is printed to STDOUT;\n"
            "          # observed aapt builds differ on the exit status, so the\n"
            "          # scanner must detect the ERROR line itself (fail-closed\n"
            "          # regardless of the exit code).\n"
            "          printf '%s\\n' \\\n"
            "            'ERROR: dump failed because resource res/xml/network_security_config.xml not found' ;;\n"
            "        empty) : ;;\n"
            "        *)  # ok (default): deny-all compiled config, valid under BOTH\n"
            "            # rule sets so the default artifacts stay positive controls\n"
            "          printf '%s\\n' \\\n"
            "            'E: network-security-config (line=1)' \\\n"
            "            '  E: base-config (line=2)' \\\n"
            "            '    A: cleartextTrafficPermitted=(type 0x12)0x0' ;;\n"
            "      esac\n"
            "    elif [ \"${4:-}\" = \"res/xml/backup_rules.xml\" ] || [ \"${4:-}\" = \"res/xml/data_extraction_rules.xml\" ]; then\n"
            "      # Compiled backup-rules fixtures mirror the real aapt v1 xmltree\n"
            "      # shape: rule attributes carry NO namespace, so they print\n"
            "      # exactly like the NSC src attribute does (A: domain=\"x\"\n"
            "      # (Raw: \"x\")) and the rebuilt tree carries plain domain/path\n"
            "      # keys just like the source-file parser. Dispatch mirrors the\n"
            "      # real invocation pairs: the member path ($4) selects the dump.\n"
            "      case \"${FAKE_AAPT_RULES:-ok}\" in\n"
            "        error)\n"
            "          # Missing-compiled-resource surface: the ERROR line goes to\n"
            "          # STDOUT and builds differ on the exit status - fail closed\n"
            "          # via the tree parser, exactly like the NSC control.\n"
            "          printf 'ERROR: dump failed because resource %s not found\\n' \"${4##*/}\" ;;\n"
            "        empty) : ;;\n"
            "        missing-one)\n"
            "          # The file/librepocket_keysets exclude drops out of EVERY\n"
            "          # section: the exact-set rule must reject the compiled\n"
            "          # bytes in both members (dump succeeds, content differs).\n"
            "          if [ \"${4:-}\" = \"res/xml/backup_rules.xml\" ]; then\n"
            "            printf '%s\\n' \\\n"
            "              'E: full-backup-content (line=1)' \\\n"
            "              '  E: exclude (line=2)' \\\n"
            "              '    A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '    A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '  E: exclude (line=3)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '  E: exclude (line=4)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '  E: exclude (line=5)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")'\n"
            "          else\n"
            "            printf '%s\\n' \\\n"
            "              'E: data-extraction-rules (line=1)' \\\n"
            "              '  E: cloud-backup (line=2)' \\\n"
            "              '    E: exclude (line=3)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=4)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=5)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=6)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '  E: device-transfer (line=7)' \\\n"
            "              '    E: exclude (line=8)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=9)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=10)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=11)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")'\n"
            "          fi ;;\n"
            "        extra-one)\n"
            "          # The mirror image of missing-one: every REQUIRED exclude\n"
            "          # stays and ONE entry beyond the pinned five adds itself\n"
            "          # to EVERY section: the exact-set rule must reject the\n"
            "          # compiled bytes in both members (dump succeeds, content\n"
            "          # differs).\n"
            "          if [ \"${4:-}\" = \"res/xml/backup_rules.xml\" ]; then\n"
            "            printf '%s\\n' \\\n"
            "              'E: full-backup-content (line=1)' \\\n"
            "              '  E: exclude (line=2)' \\\n"
            "              '    A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '    A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '  E: exclude (line=3)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '  E: exclude (line=4)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '  E: exclude (line=5)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '  E: exclude (line=6)' \\\n"
            "              '    A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '    A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")' \\\n"
            "              '  E: exclude (line=7)' \\\n"
            "              '    A: domain=\"path\" (Raw: \"path\")' \\\n"
            "              '    A: path=\"extra.xml\" (Raw: \"extra.xml\")'\n"
            "          else\n"
            "            printf '%s\\n' \\\n"
            "              'E: data-extraction-rules (line=1)' \\\n"
            "              '  E: cloud-backup (line=2)' \\\n"
            "              '    E: exclude (line=3)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=4)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=5)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=6)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '    E: exclude (line=7)' \\\n"
            "              '      A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '      A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")' \\\n"
            "              '    E: exclude (line=8)' \\\n"
            "              '      A: domain=\"path\" (Raw: \"path\")' \\\n"
            "              '      A: path=\"extra.xml\" (Raw: \"extra.xml\")' \\\n"
            "              '  E: device-transfer (line=9)' \\\n"
            "              '    E: exclude (line=10)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=11)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=12)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=13)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '    E: exclude (line=14)' \\\n"
            "              '      A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '      A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")' \\\n"
            "              '    E: exclude (line=15)' \\\n"
            "              '      A: domain=\"path\" (Raw: \"path\")' \\\n"
            "              '      A: path=\"extra.xml\" (Raw: \"extra.xml\")'\n"
            "          fi ;;\n"
            "        cross)\n"
            "          # Android 16 QPR2's third PLATFORM section inside the\n"
            "          # COMPILED bytes: the validate_tree parity must refuse it\n"
            "          # regardless of the section's content (undecided policy).\n"
            "          if [ \"${4:-}\" = \"res/xml/data_extraction_rules.xml\" ]; then\n"
            "            printf '%s\\n' \\\n"
            "              'E: data-extraction-rules (line=1)' \\\n"
            "              '  E: cloud-backup (line=2)' \\\n"
            "              '    E: exclude (line=3)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=4)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=5)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=6)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '    E: exclude (line=7)' \\\n"
            "              '      A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '      A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")' \\\n"
            "              '  E: device-transfer (line=8)' \\\n"
            "              '    E: exclude (line=9)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=10)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=11)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=12)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '    E: exclude (line=13)' \\\n"
            "              '      A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '      A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")' \\\n"
            "              '  E: cross-platform-transfer (line=14)' \\\n"
            "              '    E: exclude (line=15)' \\\n"
            "              '      A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '      A: path=\"cross_transfer_leak.xml\" (Raw: \"cross_transfer_leak.xml\")'\n"
            "          else\n"
            "            printf '%s\\n' \\\n"
            "              'E: full-backup-content (line=1)' \\\n"
            "              '  E: exclude (line=2)' \\\n"
            "              '    A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '    A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '  E: exclude (line=3)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '  E: exclude (line=4)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '  E: exclude (line=5)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '  E: exclude (line=6)' \\\n"
            "              '    A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '    A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")'\n"
            "          fi ;;\n"
            "        *)  # ok (default): exactly the REQUIRED_EXCLUDES set under\n"
            "            # both roots, so the default artifacts stay positive\n"
            "            # controls for the compiled-bytes authority\n"
            "          if [ \"${4:-}\" = \"res/xml/backup_rules.xml\" ]; then\n"
            "            printf '%s\\n' \\\n"
            "              'E: full-backup-content (line=1)' \\\n"
            "              '  E: exclude (line=2)' \\\n"
            "              '    A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '    A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '  E: exclude (line=3)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '  E: exclude (line=4)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '  E: exclude (line=5)' \\\n"
            "              '    A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '    A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '  E: exclude (line=6)' \\\n"
            "              '    A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '    A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")'\n"
            "          else\n"
            "            printf '%s\\n' \\\n"
            "              'E: data-extraction-rules (line=1)' \\\n"
            "              '  E: cloud-backup (line=2)' \\\n"
            "              '    E: exclude (line=3)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=4)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=5)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=6)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '    E: exclude (line=7)' \\\n"
            "              '      A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '      A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")' \\\n"
            "              '  E: device-transfer (line=8)' \\\n"
            "              '    E: exclude (line=9)' \\\n"
            "              '      A: domain=\"sharedpref\" (Raw: \"sharedpref\")' \\\n"
            "              '      A: path=\"librepocket_keys.xml\" (Raw: \"librepocket_keys.xml\")' \\\n"
            "              '    E: exclude (line=10)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault\" (Raw: \"librepocket_vault\")' \\\n"
            "              '    E: exclude (line=11)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-wal\" (Raw: \"librepocket_vault-wal\")' \\\n"
            "              '    E: exclude (line=12)' \\\n"
            "              '      A: domain=\"database\" (Raw: \"database\")' \\\n"
            "              '      A: path=\"librepocket_vault-shm\" (Raw: \"librepocket_vault-shm\")' \\\n"
            "              '    E: exclude (line=13)' \\\n"
            "              '      A: domain=\"file\" (Raw: \"file\")' \\\n"
            "              '      A: path=\"librepocket_keysets\" (Raw: \"librepocket_keysets\")'\n"
            "          fi ;;\n"
            "      esac\n"
            "    else\n"
            "      case \"${FAKE_AAPT_XMLTREE:-bindings}\" in\n"
            "        stripped)\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' ;;\n"
            "        no-application)\n"
            "          printf '%s\\n' 'E: manifest' ;;\n"
            "        aapt2)\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: http://schemas.android.com/apk/res/android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: http://schemas.android.com/apk/res/android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f010002' ;;\n"
            "        literal)\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=\"@xml/backup_rules\" (Raw: \"@xml/backup_rules\")' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=\"@xml/data_extraction_rules\" (Raw: \"@xml/data_extraction_rules\")' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=\"@xml/network_security_config\" (Raw: \"@xml/network_security_config\")' ;;\n"
            "        rebound)\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f091001' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f091002' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=@0x7f091003' ;;\n"
            "        debug)\n"
            "          # Default NSC binding PLUS android:debuggable=true: the\n"
            "          # compiled NSC is then checked under the DEBUG rule set.\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=@0x7f010002' \\\n"
            "            '    A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff' ;;\n"
            "        no-nsc)\n"
            "          # Merger stripped ONLY the NSC binding (backup pair intact).\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' ;;\n"
            "        nsc-rebound-literal)\n"
            "          # NSC binding re-declared onto another resource by an overlay.\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=\"@xml/network_security_config_evil\" (Raw: \"@xml/network_security_config_evil\")' ;;\n"
            "        nsc-rebound-literal-junk)\n"
            "          # The pinned literal plus trailing junk: a search()-style\n"
            "          # prefix match would accept it while the runtime resolves a\n"
            "          # different value. The decoded FULL payload must full-match.\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=\"@xml/network_security_config;\" (Raw: \"@xml/network_security_config;\")' ;;\n"
            "        backup-literal-junk)\n"
            "          # The pinned BACKUP literal plus trailing junk: a search()-style\n"
            "          # prefix match would accept \"@xml/backup_rules;junk\" while the\n"
            "          # runtime would never dereference the raw string at all. The\n"
            "          # decoded FULL payload must match the pin exactly.\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=\"@xml/backup_rules;junk\" (Raw: \"@xml/backup_rules;junk\")' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=@0x7f010002' ;;\n"
            "        data-rules-rebound-id)\n"
            "          # Per-attribute proof: ONLY android:dataExtractionRules carries\n"
            "          # an id with NO entry in the resources table (default FAKE_AAPT\n"
            "          # table carries 0x7f010000-0x7f010002). One unresolvable id must\n"
            "          # fail the whole binding gate - there is no skip-return.\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f091002' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=@0x7f010002' ;;\n"
            "        accessibility)\n"
            "          # Legal foss/github overlay shape (mirrors the real overlay\n"
            "          # manifests): a declared accessibility service carrying the\n"
            "          # BIND_ACCESSIBILITY_SERVICE protection permission and the\n"
            "          # accessibilityservice intent action/meta-data. The PLAY-ONLY\n"
            "          # policy must reject this; the COMMON permission policy (foss\n"
            "          # and github modes) must never trip on it.\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=@0x7f010002' \\\n"
            "            '    E: service (line=3)' \\\n"
            "            '      A: android:name=\"dev.librepocket.foss.FossAccessibilityService\" (Raw: \"dev.librepocket.foss.FossAccessibilityService\")' \\\n"
            "            '      A: android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\" (Raw: \"android.permission.BIND_ACCESSIBILITY_SERVICE\")' \\\n"
            "            '      E: intent-filter (line=4)' \\\n"
            "            '        E: action (line=5)' \\\n"
            "            '          A: android:name=\"android.accessibilityservice.AccessibilityService\" (Raw: \"android.accessibilityservice.AccessibilityService\")' \\\n"
            "            '      E: meta-data (line=6)' \\\n"
            "            '        A: android:name=\"android.accessibilityservice\" (Raw: \"android.accessibilityservice\")' \\\n"
            "            '        A: android:resource=\"@xml/accessibility_config\" (Raw: \"@xml/accessibility_config\")' ;;\n"
            "        vpnservice)\n"
            "          # A merged <service> ADVERTISING a VpnService subclass (the\n"
            "          # android.net.VpnService intent action) WITHOUT the\n"
            "          # BIND_VPN_SERVICE uses-permission. The COMMON policy must\n"
            "          # fail every mode on this xmltree face alone.\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=@0x7f010002' \\\n"
            "            '    E: service (line=3)' \\\n"
            "            '      A: android:name=\"dev.librepocket.github.VpnTunnel\" (Raw: \"dev.librepocket.github.VpnTunnel\")' \\\n"
            "            '      E: intent-filter (line=4)' \\\n"
            "            '        E: action (line=5)' \\\n"
            "            '          A: android:name=\"android.net.VpnService\" (Raw: \"android.net.VpnService\")' ;;\n"
            "        *)  # bindings (the default, realistic merged manifest)\n"
            "          printf '%s\\n' 'E: manifest' '  E: application' \\\n"
            "            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000' \\\n"
            "            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001' \\\n"
            "            '    A: android:networkSecurityConfig(0x01010527)=@0x7f010002' ;;\n"
            "      esac\n"
            "    fi ;;\n"
            "  'dump resources')\n"
            "    # e2e single-dump counter: the counter file (optionally exported\n"
            "    # by the harness) gains one byte per ``dump resources`` INVOCATION,\n"
            "    # so a run that executes the dump exactly once leaves exactly one\n"
            "    # byte. Nothing else in this fake touches the file.\n"
            "    if [ -n \"${FAKE_AAPT_RESOURCES_COUNT_FILE:-}\" ]; then\n"
            "      printf 'x' >> \"${FAKE_AAPT_RESOURCES_COUNT_FILE}\"\n"
            "    fi\n"
            "    case \"${FAKE_AAPT_RESOURCES:-table}\" in\n"
            "      missing) printf '%s\\n' '    (no xml/type resources)' ;;\n"
            "      aapt2-table)\n"
            "        printf '%s\\n' \\\n"
            "          '    resource 0x7f010000 xml/backup_rules () (file) res/xml/backup_rules.xml' \\\n"
            "          '    resource 0x7f010001 xml/data_extraction_rules () (file) res/xml/data_extraction_rules.xml' \\\n"
            "          '    resource 0x7f010002 xml/network_security_config () (file) res/xml/network_security_config.xml' ;;\n"
            "      wrong-name)\n"
            "        printf '%s\\n' \\\n"
            "          '      spec resource 0x7f010000 dev.librepocket.agent:xml/backup_rules_evil: flags=0x00000000' \\\n"
            "          '      spec resource 0x7f010001 dev.librepocket.agent:xml/data_extraction_rules_evil: flags=0x00000000' ;;\n"
            "      nsc-wrong-name)\n"
            "        # The NSC id resolves to a DIFFERENT resource name than the pin.\n"
            "        printf '%s\\n' \\\n"
            "          '      spec resource 0x7f010000 dev.librepocket.agent:xml/backup_rules: flags=0x00000000' \\\n"
            "          '      spec resource 0x7f010001 dev.librepocket.agent:xml/data_extraction_rules: flags=0x00000000' \\\n"
            "          '      spec resource 0x7f010002 dev.librepocket.agent:xml/network_security_config_evil: flags=0x00000000' ;;\n"
            "      nsc-missing)\n"
            "        # The NSC id has no entry in the table at all.\n"
            "        printf '%s\\n' \\\n"
            "          '      spec resource 0x7f010000 dev.librepocket.agent:xml/backup_rules: flags=0x00000000' \\\n"
            "          '      spec resource 0x7f010001 dev.librepocket.agent:xml/data_extraction_rules: flags=0x00000000' ;;\n"
            "      *)  # table (the default, id→name binding intact)\n"
            "        printf '%s\\n' \\\n"
            "          '      spec resource 0x7f010000 dev.librepocket.agent:xml/backup_rules: flags=0x00000000' \\\n"
            "          '      spec resource 0x7f010001 dev.librepocket.agent:xml/data_extraction_rules: flags=0x00000000' \\\n"
            "          '      spec resource 0x7f010002 dev.librepocket.agent:xml/network_security_config: flags=0x00000000' ;;\n"
            "    esac ;;\n"
            "  *) echo 'unexpected fake aapt invocation' >&2; exit 24 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        aapt.chmod(0o755)

    def _write_dexdump(self) -> None:
        self.dexdump_path.write_text(
            "#!/bin/sh\n"
            "set -eu\n"
            "case \"${FAKE_DEXDUMP_MODE:-ok}\" in\n"
            "  error) echo 'synthetic dexdump parse failure' >&2; exit 31 ;;\n"
            "  hang) sleep 30 ;;\n"
            "  noisy) printf '%s\\n' 'DEXDUMP_STDOUT_MUST_NOT_BE_USED_OR_CAPTURED' ;;\n"
            "  ok) printf '%s\\n' 'synthetic native parser success' ;;\n"
            "  *) echo 'unexpected fake dexdump mode' >&2; exit 32 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        self.dexdump_path.chmod(0o755)

    def _env(
        self,
        *,
        sdk: Path | None = None,
        aapt_mode: str = "ok",
        dexdump_mode: str = "ok",
        xmltree_mode: str = "bindings",
        resources_mode: str = "table",
        nsc_mode: str = "ok",
        rules_mode: str = "ok",
        permissions: str = "",
        resources_count_file: Path | None = None,
    ) -> dict[str, str]:
        env = os.environ.copy()
        env.update(
            {
                "ANDROID_HOME": str(sdk or self.sdk),
                "HOME": str(self.home),
                "PATH": f"{self.fake_bin}:/usr/bin:/bin",
                "FAKE_AAPT_MODE": aapt_mode,
                "FAKE_DEXDUMP_MODE": dexdump_mode,
                "FAKE_AAPT_XMLTREE": xmltree_mode,
                "FAKE_AAPT_RESOURCES": resources_mode,
                "FAKE_AAPT_NSC": nsc_mode,
                "FAKE_AAPT_RULES": rules_mode,
                "FAKE_AAPT_PERMISSIONS": permissions,
                "LC_ALL": "C",
            }
        )
        if resources_count_file is not None:
            env["FAKE_AAPT_RESOURCES_COUNT_FILE"] = str(resources_count_file)
        return env

    def run_policy(
        self,
        apk: Path,
        *,
        foss: bool = False,
        aapt_mode: str = "ok",
        dexdump_mode: str = "ok",
        xmltree_mode: str = "bindings",
        resources_mode: str = "table",
        nsc_mode: str = "ok",
        rules_mode: str = "ok",
        sdk: Path | None = None,
        permissions: str = "",
        resources_count_file: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = ["sh", str(POLICY_CHECK)]
        if foss:
            command.append("--foss")
        command.append(str(apk))
        return subprocess.run(
            command,
            cwd=ROOT,
            env=self._env(
                sdk=sdk,
                aapt_mode=aapt_mode,
                dexdump_mode=dexdump_mode,
                xmltree_mode=xmltree_mode,
                resources_mode=resources_mode,
                nsc_mode=nsc_mode,
                rules_mode=rules_mode,
                permissions=permissions,
                resources_count_file=resources_count_file,
            ),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def assert_rejected(self, apk: Path, **kwargs: object) -> None:
        result = self.run_policy(apk, **kwargs)
        self.assertNotEqual(
            result.returncode,
            0,
            f"expected fail-closed rejection, got exit 0:\n{result.stdout}",
        )

    def test_accepts_each_explicitly_supported_dex_version(self) -> None:
        for version in _SUPPORTED_DEX_VERSIONS:
            with self.subTest(dex_version=version):
                apk = complete_apk(
                    self.tmp / f"clean-{version}.apk",
                    minimal_dex(
                        type_descriptors=("Ljava/lang/Object;",), version=version
                    ),
                )
                result = self.run_policy(apk)
                self.assertEqual(result.returncode, 0, result.stdout)

    def test_a_non_ascii_type_reference_uses_mutf8_and_utf16_length(self) -> None:
        descriptor = "Lcom/google/mlkit/Recognizer-é;"
        apk = complete_apk(
            self.tmp / "mutf8-reference.apk",
            minimal_dex(type_descriptors=(descriptor,), version="040"),
        )
        self.assert_rejected(apk, foss=True)

    def test_rejects_unsupported_dex_036_v041_container_and_unknown_version(self) -> None:
        for version in ("036", "041", "999"):
            with self.subTest(dex_version=version):
                # The scanner's documented support set ends at v040; this fixture
                # changes only the magic marker to assert unknown versions fail
                # before any unsupported layout is interpreted.
                dex = bytearray(minimal_dex(type_descriptors=("Ljava/lang/Object;",)))
                dex[4:7] = version.encode("ascii")
                apk = complete_apk(self.tmp / f"unknown-{version}.apk", bytes(dex))
                self.assert_rejected(apk)

    def test_rejects_reverse_endian_marker(self) -> None:
        dex = minimal_dex(
            type_descriptors=("Ljava/lang/Object;",), endian_tag=0x78563412
        )
        self.assert_rejected(complete_apk(self.tmp / "reverse-endian.apk", dex))

    def test_accepts_twenty_root_multidex_shards(self) -> None:
        # Historical debug APKs have about 17-20 root classes*.dex entries.
        # This compact fixture tests shard handling only, not the observed
        # 43.8-MB largest DEX / 65-80-MB total DEX size budget; that is a later
        # parent-owned SDK/native check against real debug APKs.
        members = {"AndroidManifest.xml": b"synthetic compiled manifest fixture"}
        for index in range(1, 21):
            name = "classes.dex" if index == 1 else f"classes{index}.dex"
            members[name] = minimal_dex(
                type_descriptors=(f"Lfixture/shard{index}/Entry;",)
            )
        result = self.run_policy(write_apk(self.tmp / "twenty-dex.apk", members))
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_multidex_name_controls_and_tail_shards_are_scanned(self) -> None:
        # Explicitly exercise the 2-9 and 10+ filename transitions. The Foss
        # hit lives only in classes20.dex, proving that later shards reach the
        # type_ids parser rather than being silently skipped.
        for shard_number in (2, 9, 10, 19, 20):
            with self.subTest(accepted_shard=shard_number):
                members = {
                    "AndroidManifest.xml": b"synthetic compiled manifest fixture",
                    "classes.dex": minimal_dex(
                        type_descriptors=("Ljava/lang/Object;",)
                    ),
                    f"classes{shard_number}.dex": minimal_dex(
                        type_descriptors=("Ljava/lang/Object;",)
                    ),
                }
                result = self.run_policy(
                    write_apk(self.tmp / f"classes-{shard_number}.apk", members)
                )
                self.assertEqual(result.returncode, 0, result.stdout)

        tail_members = {
            "AndroidManifest.xml": b"synthetic compiled manifest fixture",
            "classes.dex": minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
            "classes20.dex": minimal_dex(
                type_descriptors=(_FORBIDDEN_FOSS_TYPE,)
            ),
        }
        self.assert_rejected(
            write_apk(self.tmp / "classes-20-foss-reference.apk", tail_members),
            foss=True,
        )

        for invalid_name in ("classes1.dex", "classes01.dex"):
            with self.subTest(rejected_shard=invalid_name):
                members = {
                    "AndroidManifest.xml": b"synthetic compiled manifest fixture",
                    "classes.dex": minimal_dex(
                        type_descriptors=("Ljava/lang/Object;",)
                    ),
                    invalid_name: minimal_dex(
                        type_descriptors=("Ljava/lang/Object;",)
                    ),
                }
                self.assert_rejected(
                    write_apk(self.tmp / f"invalid-{invalid_name}.apk", members)
                )

    def test_accepts_structurally_complete_clean_play_apk(self) -> None:
        apk = complete_apk(
            self.tmp / "clean.apk",
            minimal_dex(
                type_descriptors=("Ljava/lang/Object;",),
                literal_strings=("label\0rocket-\U0001f680",),
            ),
        )
        result = self.run_policy(apk)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_valid_non_apk_readme_zip(self) -> None:
        apk = write_apk(self.tmp / "readme-only.apk", {"README.txt": b"not an APK"})
        self.assert_rejected(apk)

    def test_rejects_missing_manifest(self) -> None:
        apk = write_apk(
            self.tmp / "no-manifest.apk",
            {"classes.dex": minimal_dex(type_descriptors=("Ljava/lang/Object;",))},
        )
        self.assert_rejected(apk)

    def test_rejects_missing_dex(self) -> None:
        apk = write_apk(
            self.tmp / "no-dex.apk", {"AndroidManifest.xml": b"synthetic manifest"}
        )
        self.assert_rejected(apk)

    # -- merged-manifest backup-binding assertion (artifact-level authority) --

    def test_merged_backup_bindings_stripped_rejected_both_modes(self) -> None:
        # The third-party reviewer repro: a manifest-merger marker may strip
        # android:fullBackupContent / android:dataExtractionRules from the
        # merged <application>; the source manifests still LOOK bound. Only
        # the merged bytes can prove the binding survived the merger.
        apk = complete_apk(
            self.tmp / "stripped-binding.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        for foss in (False, True):
            with self.subTest(mode="foss" if foss else "play"):
                result = self.run_policy(apk, foss=foss, xmltree_mode="stripped")
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("merged manifest lost the backup binding", result.stdout)

    def test_merged_backup_binding_attr_with_wrong_printed_id_rejected(self) -> None:
        # Printing the attribute with a DIFFERENT resource-id (i.e. an attr
        # that is not actually android:fullBackupContent) fails closed too;
        # a fake aapt cannot relabel it past the id pin.
        # get_resources is never called in these cases: the failure happens
        # inside the attr loop. A return of "" would fail any proof CLOSED
        # (no table entry survives an empty dump) had it been reached.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, "lost the backup binding"
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                "    A: android:fullBackupContent(0x99990001)=@0x7f010000",
                lambda: "",
            )

    def test_merged_backup_binding_literal_rebound_name_rejected(self) -> None:
        # String-shaped values must be the pinned resource exactly.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, "rebound backup binding"
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                '    A: android:fullBackupContent(0x010104eb)=@xml/backup_rules_evil\n'
                '    A: android:dataExtractionRules(0x0101063e)=@xml/data_extraction_rules\n',
                lambda: "",
            )

    def test_merged_backup_bindings_aapt2_spelling_and_literal_raw_pass(self) -> None:
        # Both dump spellings must be accepted: the fully-qualified namespace
        # form (newer aapt2 style) and the Raw-literal string form.
        apk = complete_apk(
            self.tmp / "spelling-matrix.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        for mode in ("aapt2", "literal"):
            with self.subTest(xmltree_form=mode):
                result = self.run_policy(apk, xmltree_mode=mode)
                self.assertEqual(result.returncode, 0, result.stdout)

    def test_merged_backup_binding_outside_application_rejected(self) -> None:
        # A binding string living on a node OTHER than <application> (or an
        # attribute printed after the application block) must not satisfy
        # the gate: the search is scoped to the merged application block.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, "lost the backup binding"
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                '    A: android:label(0x01010001)="Faux" (Raw: "Faux")\n'
                "  E: someone-else\n"
                '    A: android:fullBackupContent(0x010104eb)=@xml/backup_rules\n'
                '    A: android:dataExtractionRules(0x0101063e)=@xml/data_extraction_rules\n',
                lambda: "",
            )

    def test_merged_backup_bindings_without_application_rejected(self) -> None:
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, "lacks an <application> element"
        ):
            policy_inspect._assert_merged_backup_bindings(
                "E: manifest\n",
                lambda: "",
            )

    def test_manifest_lacks_application_rejected_by_gate(self) -> None:
        # End-to-end: a merged manifest with no <application> at all cannot
        # pass the binding gate.
        apk = complete_apk(
            self.tmp / "no-application.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, xmltree_mode="no-application")
        self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_merged_resolved_via_aapt2_resource_line_accepted(self) -> None:
        # aapt2's dump resources shape (no spec/pkg prefix) must resolve too;
        # keep this accepted only because find_tool_pair passes aapt v1 - the
        # tolerance is for output-format variance, not for tool substitution.
        apk = complete_apk(
            self.tmp / "aapt2-table.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, resources_mode="aapt2-table")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_merged_backup_bindings_resource_rebound_rejected(self) -> None:
        # The attr exists but points at a DIFFERENT id than the pinned
        # resource name (rebind to xml/backup_rules_evil): the gate refuses.
        apk = complete_apk(
            self.tmp / "rebound-binding.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(
            apk, xmltree_mode="rebound", resources_mode="wrong-name"
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("rebound backup binding", result.stdout)

    def test_merged_backup_bindings_missing_resource_table_rejected(self) -> None:
        # An attr referencing an id with no xml/<name> entry in the table
        # cannot be proven to be the pinned resource: fail closed.
        apk = complete_apk(
            self.tmp / "missing-table-binding.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, resources_mode="missing")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("rebound backup binding", result.stdout)

    def test_merged_backup_bindings_id_name_mismatch_rejected(self) -> None:
        # The table binds the referenced id to a WRONG xml resource name
        # (spec resource ...xml/backup_rules_evil): reject even though both
        # attrs exist on the merged application.
        apk = complete_apk(
            self.tmp / "name-mismatch-binding.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, resources_mode="wrong-name")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("rebound backup binding", result.stdout)

    # -- P2 fail-closed binding: raw-string payloads, per-attr id proof,
    #    mandatory resources-table proof for BOTH binding authorities --

    def test_merged_backup_binding_literal_with_trailing_junk_rejected(self) -> None:
        # The backup pair's string-branch must full-match the DECODED payload:
        # "@xml/backup_rules;junk" used to satisfy a search()-style prefix
        # match, but a raw string is never a typed reference - the runtime
        # would not dereference it and Auto Backup would run rule-less. The
        # negative fixture pins the raw-string fail-closed message.
        apk = complete_apk(
            self.tmp / "backup-literal-junk.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, xmltree_mode="backup-literal-junk")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(
            "binding is a raw string, not a resource reference", result.stdout
        )

    def test_merged_data_extraction_rules_unresolvable_id_rejected(self) -> None:
        # e2e per-attr proof: android:dataExtractionRules bound to an id with
        # NO entry in the (default) resources table must fail the whole run -
        # the skip-return that previously licensed an unproven id is gone.
        apk = complete_apk(
            self.tmp / "data-rules-rebound-id.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, xmltree_mode="data-rules-rebound-id")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("rebound backup binding", result.stdout)

    def test_backup_binding_string_branch_full_value_equality(self) -> None:
        # Direct matrix for the string branch: trailing junk fails with the
        # raw-string message; a wrong but well-formed reference NAME keeps the
        # historical rebound message; the clean literal (older aapt's print of
        # a typed reference) is accepted when the table carries both names.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            "binding is a raw string, not a resource reference",
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                '    A: android:fullBackupContent(0x010104eb)="@xml/backup_rules;junk" (Raw: "@xml/backup_rules;junk")\n'
                '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001\n',
                lambda: "",
            )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"does not reference @xml/backup_rules \(got @xml/backup_rules_evil\)",
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                '    A: android:fullBackupContent(0x010104eb)=@xml/backup_rules_evil\n'
                '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001\n',
                lambda: "",
            )
        # Positive control: clean name-form bindings proven through the table.
        policy_inspect._assert_merged_backup_bindings(
            "  E: application\n"
            '    A: android:fullBackupContent(0x010104eb)=@xml/backup_rules\n'
            '    A: android:dataExtractionRules(0x0101063e)=@xml/data_extraction_rules\n',
            lambda: (
                "      spec resource 0x7f010000 dev.librepocket.agent:xml/backup_rules:"
                " flags=0x00000000\n"
                "      spec resource 0x7f010001 dev.librepocket.agent:xml/data_extraction_rules:"
                " flags=0x00000000\n"
            ),
        )
        # Gate-level proof-mandatory pin: the SAME clean name-form literals
        # against an EMPTY table must fail through the inverse proof (old
        # behavior skipped the check when resolution was absent) - the
        # binding could never survive a runtime dereference.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"references @xml/backup_rules but the resources table carries no resource with that name",
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                '    A: android:fullBackupContent(0x010104eb)=@xml/backup_rules\n'
                '    A: android:dataExtractionRules(0x0101063e)=@xml/data_extraction_rules\n',
                lambda: "",
            )

    def test_merged_backup_binding_per_attribute_unresolvable_id_rejected(self) -> None:
        # Either backup attr bound to an id the table does not carry fails the
        # whole gate; each half of the pair is proven, individually.
        data_rules_only_table = (
            "      spec resource 0x7f010001 dev.librepocket.agent:xml/data_extraction_rules:"
            " flags=0x00000000\n"
        )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"android:fullBackupContent resource id 0x7f010000 does not resolve"
            r" to @xml/backup_rules",
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                '    A: android:fullBackupContent(0x010104eb)=@0x7f010000\n'
                '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001\n',
                lambda: data_rules_only_table,
            )
        backup_rules_only_table = (
            "      spec resource 0x7f010000 dev.librepocket.agent:xml/backup_rules:"
            " flags=0x00000000\n"
        )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"android:dataExtractionRules resource id 0x7f010001 does not resolve"
            r" to @xml/data_extraction_rules",
        ):
            policy_inspect._assert_merged_backup_bindings(
                "  E: application\n"
                '    A: android:fullBackupContent(0x010104eb)=@0x7f010000\n'
                '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001\n',
                lambda: backup_rules_only_table,
            )

    def test_resources_table_carries_name_inverse_proof_matrix(self) -> None:
        # The NAME-form proof (inverse of the id↔name resolve): both measured
        # aapt spellings accept, absence fails closed.
        aapt2_table = (
            "    resource 0x7f010000 xml/backup_rules () (file) res/xml/backup_rules.xml\n"
            "    resource 0x7f010001 xml/data_extraction_rules () (file)"
            " res/xml/data_extraction_rules.xml\n"
        )
        policy_inspect._resources_table_carries_name(
            aapt2_table, "backup_rules", "fullBackupContent"
        )
        policy_inspect._resources_table_carries_name(
            aapt2_table, "data_extraction_rules", "dataExtractionRules"
        )
        policy_inspect._resources_table_carries_name(
            "      spec resource 0x7f010000 dev.librepocket.agent:xml/backup_rules:"
            " flags=0x00000000\n",
            "backup_rules",
            "fullBackupContent",
        )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"references @xml/network_security_config but the resources table"
            r" carries no resource with that name",
        ):
            policy_inspect._resources_table_carries_name(
                # A table that carries only the backup pair's names cannot
                # prove the NSC name (same fail lane as the missing dump).
                aapt2_table,
                "network_security_config",
                "networkSecurityConfig",
                binding_kind="networkSecurityConfig",
            )

    def test_merged_reference_bindings_resolve_through_table_both_modes(self) -> None:
        # Positive control: the REAL reference binding (id-form @0x…) must
        # resolve through the fake aapt resources table in BOTH scanning
        # modes - the id↔name pairing is the merger-proof half of the pin.
        apk = complete_apk(
            self.tmp / "reference-bindings.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        play_result = self.run_policy(apk)
        self.assertEqual(play_result.returncode, 0, play_result.stdout)
        github_result = self._run_scanner_cli(apk, "github")
        self.assertEqual(
            github_result.returncode, 0, github_result.stdout + github_result.stderr
        )

    def test_merged_nsc_unresolvable_id_and_literal_name_proven_through_table(self) -> None:
        # networkSecurityConfig gets the same two proofs: an id with no table
        # entry fails closed (no skip-return), and a clean literal binding
        # (older-aapt print of a typed reference) must be carried by the
        # name column of the SAME table before the compiled-XML gate runs.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"android:networkSecurityConfig resource id 0x7f091003 does not resolve"
            r" to @xml/network_security_config",
        ):
            policy_inspect._assert_merged_network_security_config(
                "/unused/aapt",
                self.tmp / "unused.apk",
                "  E: application\n"
                "    A: android:networkSecurityConfig(0x01010527)=@0x7f091003\n",
                lambda: "",
            )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"references @xml/network_security_config but the resources table"
            r" carries no resource with that name",
        ):
            policy_inspect._assert_merged_network_security_config(
                "/unused/aapt",
                self.tmp / "unused.apk",
                "  E: application\n"
                '    A: android:networkSecurityConfig(0x01010527)="@xml/network_security_config" (Raw: "@xml/network_security_config")\n',
                lambda: "",
            )

    def test_one_shared_resources_dump_serves_both_binding_authorities(self) -> None:
        # P3: ``aapt dump resources`` runs ONCE per APK. Both binding
        # authorities prove their bindings through the SAME per-APK resolver
        # (parameter threading, no module-global cache), so a scan that runs
        # both authorities executes exactly one resources dump subprocess -
        # the refactor's replacement for the previous dump-per-authority.
        resolver = policy_inspect._resources_dump_resolver(
            str(self.build_tools / "aapt"), self.tmp / "unused.apk"
        )
        xmltree = (
            "  E: application\n"
            '    A: android:fullBackupContent(0x010104eb)=@0x7f010000\n'
            '    A: android:dataExtractionRules(0x0101063e)=@0x7f010001\n'
            '    A: android:networkSecurityConfig(0x01010527)=@0x7f010002\n'
        )
        with mock.patch.object(
            policy_inspect,
            "_run_bounded_stdout",
            wraps=policy_inspect._run_bounded_stdout,
        ) as bounded:
            policy_inspect._assert_merged_backup_bindings(xmltree, resolver)
            policy_inspect._assert_merged_network_security_config(
                str(self.build_tools / "aapt"),
                self.tmp / "unused.apk",
                xmltree,
                resolver,
            )
        # The backup and the NSC authority together trigger exactly ONE
        # ``dump resources`` execution (the NSC binding check would spawn a
        # second one without the shared resolver).
        resources_invocations = [
            call
            for call in bounded.call_args_list
            if call.args[0][1:3] == ["dump", "resources"]
        ]
        self.assertEqual(len(resources_invocations), 1)
        # The NSC COMPILED-config dump is a separate authority's execution and
        # still runs exactly once by itself (the two dumps are not conflated).
        member_invocations = [
            call
            for call in bounded.call_args_list
            if len(call.args[0]) > 4
            and call.args[0][4] == "res/xml/network_security_config.xml"
        ]
        self.assertEqual(len(member_invocations), 1)

    def test_e2e_resources_dump_runs_once_per_scan(self) -> None:
        # End-to-end P3 guard: a full play-mode scan proving both binding
        # authorities leaves exactly ONE ``dump resources`` invocation byte in
        # the fake aapt's counter file (the second authority reuses the
        # threadable result instead of spawning a second dump).
        apk = complete_apk(
            self.tmp / "single-dump.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        counter = self.tmp / "resources-invocations"
        result = self.run_policy(apk, resources_count_file=counter)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(
            counter.read_bytes(),
            b"x",
            "the binding authorities must share ONE resources dump per scan",
        )

    # -- compiled backup-rules BYTES (artifact-level exact-set authority) --

    def test_compiled_backup_rules_missing_exclude_rejected(self) -> None:
        # A resources-overlay/merger rewrite can change the COMPILED rule
        # content while every source file and the manifest binding stay
        # faithful. The artifact bytes must still carry the EXACT five
        # excludes: dropping one entry fails the run even though the dump
        # succeeded and the binding survived.
        apk = complete_apk(
            self.tmp / "rules-missing-one.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, rules_mode="missing-one")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(
            "missing exclude domain=file path=librepocket_keysets", result.stdout
        )
        self.assertIn("compiled res/xml/", result.stdout)

    def test_compiled_backup_rules_unexpected_exclude_rejected(self) -> None:
        # The mirror image of the missing-one control: a resources-overlay /
        # merger rewrite can also ADD an exclude to the COMPILED rule
        # content. The artifact bytes must still carry EXACTLY the five
        # excludes: adding one entry (domain="path", path="extra.xml") fails
        # the run even though every required entry survives and the dump
        # succeeded.
        apk = complete_apk(
            self.tmp / "rules-extra-one.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, rules_mode="extra-one")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(
            "unexpected exclude domain=path path=extra.xml", result.stdout
        )
        self.assertIn("compiled res/xml/", result.stdout)

    def test_compiled_backup_rules_valid_bytes_accepted(self) -> None:
        # Positive control: the exactly-pinned five-exclude set (any order)
        # passes both members, keeping the default artifact runs positive.
        apk = complete_apk(
            self.tmp / "rules-ok.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, rules_mode="ok")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_compiled_backup_rules_dump_failures_fail_closed(self) -> None:
        # A missing/unparseable compiled rules member must reject before any
        # "inert dump" reading can pass the gate (ERROR stdout line and empty
        # output both surface as policy failures).
        apk = complete_apk(
            self.tmp / "rules-dump-failure.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        for rules_mode in ("error", "empty"):
            with self.subTest(dump_mode=rules_mode):
                result = self.run_policy(apk, rules_mode=rules_mode)
                self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_compiled_cross_platform_transfer_rejected_at_artifact_level(self) -> None:
        # The Android 16 QPR2 third platform section inside the COMPILED
        # bytes must be refused by the artifact scan too (same engine
        # parity as the source gate): undecided policy = fail closed.
        apk = complete_apk(
            self.tmp / "rules-cross-section.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, rules_mode="cross")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("cross-platform-transfer", result.stdout)

    def test_compiled_backup_rules_evaluate_via_shared_engine(self) -> None:
        # In-process proof that the scanner validates the rebuilt compiled
        # tree with the SOURCE gate's exact engine (validate_tree with
        # exact_excludes=True), so a divergent port cannot silently disagree
        # with the file-level validator.
        engine_module = policy_inspect._load_backup_rules_check()
        apk = complete_apk(
            self.tmp / "rules-direct.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        with mock.patch.object(
            engine_module, "validate_tree", wraps=engine_module.validate_tree
        ) as engine:
            policy_inspect._assert_merged_backup_rules_bytes(
                str(self.build_tools / "aapt"), apk
            )
        self.assertEqual(engine.call_count, len(engine_module.ARTIFACT_RULE_MEMBERS))
        for engine_call, (member, expected_root) in zip(
            engine.call_args_list, engine_module.ARTIFACT_RULE_MEMBERS
        ):
            self.assertEqual(engine_call.args[1], member)
            self.assertEqual(engine_call.args[2], expected_root)
            # Exact-set mode is ON at artifact level: extras beyond the five
            # required excludes are refused, not tolerated.
            self.assertIs(engine_call.kwargs.get("exact_excludes"), True)

    # -- github artifact mode (mode-independent authorities only) --

    def _run_scanner_cli(
        self,
        apk: Path,
        mode: str,
        *,
        rules_mode: str = "ok",
        xmltree_mode: str = "bindings",
        resources_mode: str = "table",
        nsc_mode: str = "ok",
        permissions: str = "",
    ) -> subprocess.CompletedProcess[str]:
        env = self._env(
            rules_mode=rules_mode,
            xmltree_mode=xmltree_mode,
            resources_mode=resources_mode,
            nsc_mode=nsc_mode,
            permissions=permissions,
        )
        return subprocess.run(
            [
                sys.executable,
                str(ROOT / "scripts" / "apk_policy_inspect.py"),
                "--mode",
                mode,
                "--apk",
                str(apk),
                "--aapt",
                str(self.build_tools / "aapt"),
                "--dexdump",
                str(self.dexdump_path),
            ],
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_github_mode_passes_mode_independent_authorities(self) -> None:
        # The github debug build is not a play/foss artifact: the flavor DEX
        # class-table policies must NOT reject it, while the mode-independent
        # artifact authorities still run. The DEX deliberately carries a
        # forbidden Foss reference to prove DEX policies are entirely skipped
        # in this mode (play/foss modes would reject the same bytes).
        dex = minimal_dex(
            type_descriptors=("Ljava/lang/Object;",),
            class_definitions=((_FORBIDDEN_FOSS_TYPE, None),),
        )
        apk = complete_apk(self.tmp / "github-mode-dex.apk", dex)
        result = self._run_scanner_cli(apk, "github")
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn("policy: github OK", result.stdout)
        self.assertIn("mode-independent", result.stdout)

    def test_github_mode_still_enforces_artifact_authorities(self) -> None:
        # Mode-independence is not mode-blindness: a merged manifest stripped
        # of the backup bindings must fail the github scan too.
        apk = complete_apk(
            self.tmp / "github-mode-stripped.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self._run_scanner_cli(apk, "github", xmltree_mode="stripped")
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertIn("merged manifest lost the backup binding", result.stderr)

    def test_github_mode_compiled_rules_and_nsc_checked(self) -> None:
        # The compiled-bytes and NSC authorities ride along in github mode:
        # a dump-failed rules member rejects the same artifact (fail-closed
        # ERROR line), as does a compiled set missing one of the pinned
        # excludes (the exact-set authority works before any mode split).
        apk = complete_apk(
            self.tmp / "github-mode-rules.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self._run_scanner_cli(apk, "github", rules_mode="error")
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            "aapt xmltree res/xml/backup_rules.xml reported a dump ERROR",
            result.stderr,
        )
        result = self._run_scanner_cli(apk, "github", rules_mode="missing-one")
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertIn("compiled res/xml/backup_rules.xml", result.stderr)
        self.assertIn(
            "missing exclude domain=file path=librepocket_keysets",
            result.stderr,
        )
        result = self._run_scanner_cli(apk, "github", nsc_mode="reviewer")
        self.assertNotEqual(result.returncode, 0, result.stderr)

    def test_github_mode_unknown_mode_arg_rejected_by_cli(self) -> None:
        apk = complete_apk(
            self.tmp / "github-mode-typo.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self._run_scanner_cli(apk, "githubx")
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertIn("invalid choice", result.stderr)

    # -- common flavor permission policy (P2-1, enforced BEFORE the mode split) --

    def test_common_forbidden_permissions_rejected_in_every_mode(self) -> None:
        # The locked flavor decision (app/src/foss + app/src/github overlay
        # headers): NO SMS, NO MANAGE_EXTERNAL_STORAGE, NO VPN in any flavor.
        # A uses-permission line for each COMMON token must fail the gate in
        # play, foss AND github lanes (github previously early-returned with
        # zero permission scanning).
        apk = complete_apk(
            self.tmp / "common-perm.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        for token in policy_inspect.COMMON_FORBIDDEN_PERMISSIONS:
            for mode in ("play", "foss", "github"):
                with self.subTest(token=token, mode=mode):
                    if mode == "github":
                        result = self._run_scanner_cli(apk, "github", permissions=token)
                        output = result.stdout + result.stderr
                    elif mode == "foss":
                        result = self.run_policy(apk, foss=True, permissions=token)
                        # run_policy merges stderr into stdout.
                        output = result.stdout
                    else:
                        result = self.run_policy(apk, permissions=token)
                        output = result.stdout
                    self.assertNotEqual(
                        result.returncode,
                        0,
                        f"{mode} mode accepted forbidden {token}:\n{output}",
                    )
                    self.assertIn(
                        f"declares forbidden permission/reference {token}",
                        output,
                    )

    def test_common_check_scans_the_xmltree_face_for_permission_tokens(self) -> None:
        # Direct function-level matrix: the token scan covers BOTH surfaces
        # the play scan consumes (permissions dump + merged xmltree); the
        # VPN xmltree reference ("vpnservice") is caught even without the
        # BIND_VPN_SERVICE uses-permission token.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"forbidden permission/reference SEND_SMS",
        ):
            policy_inspect._check_common_permissions(
                "",
                'A: android:name="android.permission.SEND_SMS"',
            )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError,
            r"forbidden permission/reference MANAGE_EXTERNAL_STORAGE",
        ):
            policy_inspect._check_common_permissions(
                "uses-permission: android.permission.MANAGE_EXTERNAL_STORAGE\n",
                "  E: manifest\n",
            )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, r"registers a VpnService"
        ):
            policy_inspect._check_common_permissions(
                "",
                'E: intent-filter\n'
                '  A: android:name="android.net.VpnService"',
            )

    def test_github_mode_fails_on_common_permission_only(self) -> None:
        # The asymmetry-fix core proof: a github-mode APK whose ONLY policy
        # problem is the COMMON token scan still fails - the common check
        # must execute upstream of the github early-return (which used to
        # run no permission scan at all).
        apk = complete_apk(
            self.tmp / "github-only-common.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self._run_scanner_cli(apk, "github", permissions="RECEIVE_SMS")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(
            "declares forbidden permission/reference RECEIVE_SMS", result.stderr
        )
        self.assertNotIn("policy: github OK", result.stdout)

    def test_vpn_service_reference_rejected_by_common_check(self) -> None:
        # A bare VpnService-declaring <service> in the merged xmltree (the
        # android.net.VpnService intent action, no uses-permission token in
        # the permissions dump) must fail the COMMON policy - in github mode,
        # without requiring any DEX parse.
        dex = minimal_dex(type_descriptors=("Ljava/lang/Object;",))
        apk = complete_apk(self.tmp / "github-vpn-service.apk", dex)
        result = self._run_scanner_cli(
            apk, "github", xmltree_mode="vpnservice", permissions=""
        )
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        # Policy message may carry either the vpn-ref shape; the common
        # scanner asserts the dedicated VpnService wording.
        self.assertIn("registers a VpnService", result.stderr)
        # Pre-mode-split routing: foss and play must fail the same shape
        # too (the old play-only regex is gone; the common check is the
        # only vpnservice xmltree authority).
        result = self.run_policy(
            apk, foss=True, xmltree_mode="vpnservice", permissions=""
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("registers a VpnService", result.stdout)

    def test_accessibility_token_stays_play_only(self) -> None:
        # Positive controls pinning accessibility legality: a merged manifest
        # carrying android.permission.BIND_ACCESSIBILITY_SERVICE (both as a
        # uses-permission token AND as the service's protection permission)
        # plus an accessibilityservice xmltree declaration must PASS the foss
        # and github lanes and FAIL the play lane, pinning that the COMMON
        # set never grows an a11y token.
        dex = minimal_dex(
            type_descriptors=("Ljava/lang/Object;",),
            class_definitions=(
                ("Ldev/librepocket/foss/FossAccessibilityService;", None),
            ),
        )
        apk = complete_apk(self.tmp / "a11y-legal.apk", dex)
        for mode in ("github", "foss"):
            with self.subTest(legal_mode=mode):
                if mode == "github":
                    result = self._run_scanner_cli(
                        apk,
                        "github",
                        xmltree_mode="accessibility",
                        permissions="BIND_ACCESSIBILITY_SERVICE",
                    )
                    self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
                    self.assertIn("policy: github OK", result.stdout)
                else:
                    result = self.run_policy(
                        apk,
                        foss=True,
                        xmltree_mode="accessibility",
                        permissions="BIND_ACCESSIBILITY_SERVICE",
                    )
                    self.assertEqual(result.returncode, 0, result.stdout)
        with self.subTest(play_still_forbids="BIND_ACCESSIBILITY_SERVICE"):
            result = self.run_policy(
                apk, xmltree_mode="accessibility", permissions="BIND_ACCESSIBILITY_SERVICE"
            )
            self.assertNotEqual(result.returncode, 0, result.stdout)
            # In play mode the token surfaces through the permissions dump
            # (the play-only scan worded below); the a11y service-token
            # message rides the same lane when only the xmltree face
            # carries it.
            self.assertIn(
                "declares forbidden permission/reference BIND_ACCESSIBILITY_SERVICE",
                result.stdout,
            )

    def test_common_permissions_function_accepts_accessibility_surface(self) -> None:
        # Function-level control for the same asymmetric contract: the a11y
        # token (permission dump or xmltree spelling) must NEVER trip the
        # COMMON checker.
        policy_inspect._check_common_permissions(
            "uses-permission: android.permission.BIND_ACCESSIBILITY_SERVICE\n",
            'E: service\n'
            '  A: android:name="android.accessibilityservice.AccessibilityService"\n',
        )

    # -- merged-manifest networkSecurityConfig binding (artifact authority) --

    def test_merged_nsc_binding_stripped_from_application_rejected(self) -> None:
        # Merger-marker repro for the third pinned binding: stripping ONLY
        # android:networkSecurityConfig must fail even though both backup
        # bindings survive - cleartext policy would fall back to the platform
        # default while every source-level file check keeps passing.
        apk = complete_apk(
            self.tmp / "no-nsc-binding.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, xmltree_mode="no-nsc")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("lost the network security config binding", result.stdout)

    def test_merged_nsc_binding_rebound_literal_rejected(self) -> None:
        apk = complete_apk(
            self.tmp / "nsc-rebound-literal.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, xmltree_mode="nsc-rebound-literal")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("rebound networkSecurityConfig binding", result.stdout)

    def test_merged_nsc_literal_with_trailing_junk_rejected(self) -> None:
        # The literal branch must full-match the DECODED payload: pinned
        # literal + trailing junk ("@xml/network_security_config;") changes
        # what the runtime resolves, so a re.search()-style prefix hit must
        # not satisfy the binding.
        apk = complete_apk(
            self.tmp / "nsc-rebound-literal-junk.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, xmltree_mode="nsc-rebound-literal-junk")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("rebound networkSecurityConfig binding", result.stdout)

    def test_merged_nsc_binding_outside_application_rejected(self) -> None:
        # Same <application>-block scoping as the backup pair.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, "lost the network security config binding"
        ):
            policy_inspect._assert_merged_network_security_config(
                "/unused/aapt",
                self.tmp / "unused.apk",
                "  E: application\n"
                '    A: android:label(0x01010001)="Faux" (Raw: "Faux")\n'
                "  E: someone-else\n"
                "    A: android:networkSecurityConfig(0x01010527)=@0x7f010002\n",
                lambda: "",
            )

    def test_merged_nsc_binding_with_wrong_printed_id_rejected(self) -> None:
        # Only the measured attr id is the binding; a printed different id is
        # not android:networkSecurityConfig at all.
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, "lost the network security config binding"
        ):
            policy_inspect._assert_merged_network_security_config(
                "/unused/aapt",
                self.tmp / "unused.apk",
                "  E: application\n"
                "    A: android:networkSecurityConfig(0x99990002)=@0x7f010002\n",
                lambda: "",
            )

    def test_merged_nsc_resources_table_wrong_name_or_missing_rejected(self) -> None:
        # The id column must prove xml/network_security_config exactly: a
        # bound id resolving to another resource name (or to nothing) cannot
        # pass, mirroring the backup binding's id↔name authority.
        apk = complete_apk(
            self.tmp / "nsc-id-name.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        for resources_mode in ("nsc-wrong-name", "nsc-missing"):
            with self.subTest(resources_mode=resources_mode):
                result = self.run_policy(apk, resources_mode=resources_mode)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("rebound networkSecurityConfig binding", result.stdout)

    def test_compiled_nsc_debug_rules_matrix(self) -> None:
        # With android:debuggable proven, the compiled NSC is checked under the
        # DEBUG rule set: the deny-all fixture, the loopback whitelist, and an
        # own-"false" narrowed nested entry pass; every other escape is
        # rejected (outside domain, missing/true includeSubdomains, base true,
        # unknown element, and the probe-n1 nested entry inheriting the outer
        # cleartext "true" onto example.com).
        apk = complete_apk(
            self.tmp / "debug-nsc-matrix.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        for nsc_mode in ("ok", "loopback-debug", "n1-narrowed-debug"):
            with self.subTest(accepted_nsc=nsc_mode):
                result = self.run_policy(
                    apk, xmltree_mode="debug", nsc_mode=nsc_mode
                )
                self.assertEqual(result.returncode, 0, result.stdout)
        for nsc_mode in (
            "reviewer",
            "n1-nested",
            "loopback-nosub",
            "loopback-subtrue",
            "base-true",
            "unknown-line",
        ):
            with self.subTest(rejected_nsc=nsc_mode):
                result = self.run_policy(
                    apk, xmltree_mode="debug", nsc_mode=nsc_mode
                )
                self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_compiled_nsc_release_rules_for_unproven_debuggable(self) -> None:
        # Without a PROVEN debuggable attr the compiled NSC is checked with the
        # release deny-all rules: the loopback whitelist (legal only for a
        # proof-carrying debug build) is rejected fail-closed here.
        apk = complete_apk(
            self.tmp / "release-nsc.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, nsc_mode="loopback-debug")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(
            "compiled res/xml/network_security_config.xml fails the release",
            result.stdout,
        )

    def test_compiled_nsc_inner_own_false_narrows_and_releases(self) -> None:
        # own="false" on the inner nested entry overrides the parent builder
        # (AOSP Builder#setCleartextTrafficPermitted), so an all-"false"
        # nested shape remains valid under the release deny-all rules.
        apk = complete_apk(
            self.tmp / "release-n1-narrowed.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, nsc_mode="n1-narrowed-release")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_compiled_nsc_dump_failures_fail_closed(self) -> None:
        # An ERROR line printed to stdout (observed aapt builds differ on the
        # exit status for missing resources) and an empty dump both fail
        # closed instead of validating nothing.
        apk = complete_apk(
            self.tmp / "nsc-dump-failure.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        for nsc_mode in ("error", "empty"):
            with self.subTest(dump_mode=nsc_mode):
                result = self.run_policy(apk, nsc_mode=nsc_mode)
                self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_compiled_nsc_value_semantics_matrix(self) -> None:
        # The AOSP parse-time VALUE checks route through _attr_errors, so the
        # COMPILED dump face (rebuilt ElementTree from `aapt dump xmltree` via
        # _parse_aapt_xmltree) enforces the identical semantics: a legal
        # src="system" trust-anchor plus a 32-byte sha-256 pin passes, an
        # unsupported digest algorithm and a file-path certificates src fail
        # with the same AOSP wording, and a TYPED resource-reference src
        # (printed unquoted by aapt as @res/0x...) stays accepted
        # (parseCertificatesEntry's sourceId != -1 branch).
        apk = complete_apk(
            self.tmp / "nsc-values.apk",
            minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        )
        result = self.run_policy(apk, nsc_mode="pins-ok")
        self.assertEqual(result.returncode, 0, result.stdout)
        result = self.run_policy(apk, nsc_mode="cert-src-typed")
        self.assertEqual(result.returncode, 0, result.stdout)
        result = self.run_policy(apk, nsc_mode="pins-bad-digest")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Unsupported pin digest algorithm: SHA-512", result.stdout)
        result = self.run_policy(apk, nsc_mode="cert-src-file-path")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("Unknown certificates src", result.stdout)

    def test_network_security_mode_derivation_is_fail_closed(self) -> None:
        # Only the measured debuggable encoding proves debug mode: absent,
        # explicit false, unknown bit patterns, and absent-debuggable
        # manifests all validate with the stricter release deny-all rules.
        cases = {
            "    A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff": "debug",
            "    A: http://schemas.android.com/apk/res/android:"
            "debuggable(0x0101000f)=(type 0x12)0xffffffff": "debug",
            "    A: android:debuggable(0x0101000f)=(type 0x12)0x0": "release",
            "    A: android:debuggable(0x0101000f)=(type 0x12)0xfffffffe": "release",
            '    A: android:label(0x01010001)="x" (Raw: "x")': "release",
        }
        for line, expected in cases.items():
            with self.subTest(line=line):
                self.assertEqual(policy_inspect._network_security_mode(line), expected)
        self.assertEqual(policy_inspect._network_security_mode("  E: application"), "release")

    def test_real_debug_apk_artifact_nsc_authority(self) -> None:
        # Live control (skipped without a real APK + real aapt): the actual
        # production debug artifact must pass ALL artifact authorities with
        # its proven debuggable attr (debug NSC rules) — merged backup
        # bindings, compiled backup-rules bytes, and the NSC pair.
        if not _REAL_DEBUG_APK.exists():
            self.skipTest("real debug APK not built in this checkout")
        aapt = _discover_real_aapt()
        if aapt is None:
            self.skipTest("no real aapt found (ANDROID_HOME/HOME SDK probes)")
        permissions, xmltree = policy_inspect._validate_aapt_output(
            aapt, _REAL_DEBUG_APK
        )
        self.assertTrue(permissions)
        # The three artifact authorities now share ONE resources dump via the
        # lazily-memoized resolver: pass the same callable to both binding
        # checks (~the inspect_apk wiring).
        get_resources = policy_inspect._resources_dump_resolver(aapt, _REAL_DEBUG_APK)
        policy_inspect._assert_merged_backup_bindings(xmltree, get_resources)
        policy_inspect._assert_merged_backup_rules_bytes(aapt, _REAL_DEBUG_APK)
        policy_inspect._assert_merged_network_security_config(
            aapt, _REAL_DEBUG_APK, xmltree, get_resources
        )

    def test_rejects_truncated_zip(self) -> None:
        complete = complete_apk(self.tmp / "complete.apk").read_bytes()
        truncated = self.tmp / "truncated.apk"
        truncated.write_bytes(complete[:-12])
        self.assert_rejected(truncated)

    def test_rejects_non_zip_corruption(self) -> None:
        corrupt = self.tmp / "corrupt.apk"
        corrupt.write_bytes(b"not a ZIP or APK\x00\xff")
        self.assert_rejected(corrupt)

    def test_rejects_duplicate_archive_member_names(self) -> None:
        apk = self.tmp / "duplicate.apk"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("AndroidManifest.xml", b"synthetic manifest")
                archive.writestr(
                    "classes.dex", minimal_dex(type_descriptors=("Ljava/lang/Object;",))
                )
                archive.writestr("classes.dex", b"second conflicting DEX entry")
        self.assert_rejected(apk)

    def test_rejects_path_escape_member(self) -> None:
        apk = write_apk(
            self.tmp / "path-escape.apk",
            {
                "AndroidManifest.xml": b"synthetic manifest",
                "classes.dex": minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
                "../outside.txt": b"must not escape extraction root",
            },
        )
        self.assert_rejected(apk)
        self.assertFalse((self.tmp / "outside.txt").exists())

    def _write_nul_member_apk(self, path: Path, raw_name: bytes) -> Path:
        # CPython's ZipFile.writestr normalizes NUL out of written names, so
        # build a same-length placeholder entry and patch both the local and
        # central filename bytes to the raw NUL name. Same length keeps all
        # ZIP offsets valid.
        placeholder = "classes.dexQQQ"
        if len(raw_name) != len(placeholder):
            raise ValueError("NUL fixture raw name must match placeholder length")
        members = {
            "AndroidManifest.xml": b"synthetic compiled manifest fixture",
            placeholder: minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        }
        write_apk(path, members)
        raw = bytearray(path.read_bytes())
        self.assertEqual(raw.count(placeholder.encode("ascii")), 2)
        path.write_bytes(raw.replace(placeholder.encode("ascii"), raw_name))
        return path

    def test_rejects_nul_byte_archive_member_name(self) -> None:
        # Regression for CPython ZipInfo NUL truncation: info.filename is
        # "classes.dex" while info.orig_filename keeps "classes.dex\x00ab".
        # The archive validator must fail closed on the original bytes,
        # independently of fake aapt/dexdump results.
        apk = self._write_nul_member_apk(
            self.tmp / "nul-dex-name.apk", b"classes.dex\x00ab"
        )
        with zipfile.ZipFile(apk, "r") as archive:
            matches = [
                info
                for info in archive.infolist()
                if info.orig_filename == "classes.dex\x00ab"
            ]
            self.assertEqual(len(matches), 1)
            self.assertEqual(matches[0].filename, "classes.dex")
        with self.assertRaisesRegex(policy_inspect.PolicyError, r"NUL"):
            policy_inspect._validate_archive(apk)
        self.assert_rejected(apk)

    def test_rejects_nul_byte_manifest_member_name(self) -> None:
        members = {
            "AndroidManifest.xmlPLACE": b"synthetic compiled manifest fixture",
            "classes.dex": minimal_dex(type_descriptors=("Ljava/lang/Object;",)),
        }
        apk = self.tmp / "nul-manifest-name.apk"
        write_apk(apk, members)
        placeholder = b"AndroidManifest.xmlPLACE"
        raw_name = b"AndroidManifest.xml\x00LACE"
        self.assertEqual(len(placeholder), len(raw_name))
        raw = bytearray(apk.read_bytes())
        self.assertEqual(raw.count(placeholder), 2)
        apk.write_bytes(raw.replace(placeholder, raw_name))
        with self.assertRaisesRegex(policy_inspect.PolicyError, r"NUL"):
            policy_inspect._validate_archive(apk)
        self.assert_rejected(apk)

    def test_rejects_directory_entry_satisfying_root_dex_name(self) -> None:
        # A "classes.dex/" directory entry must not satisfy the root
        # classes.dex requirement.
        apk = self.tmp / "dir-dex-name.apk"
        with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic manifest")
            archive.writestr("classes.dex/", b"")
            archive.writestr(
                "classes2.dex", minimal_dex(type_descriptors=("Ljava/lang/Object;",))
            )
        with self.assertRaisesRegex(
            policy_inspect.PolicyError, r"must contain root classes\.dex"
        ):
            policy_inspect._validate_archive(apk)
        self.assert_rejected(apk)

    def test_rejects_invalid_dex_type_table(self) -> None:
        malformed_dex = bytearray(minimal_dex(type_descriptors=("Ljava/lang/Object;",)))
        # Point type_ids outside the DEX byte range. A parser error must not turn
        # this into an empty successful DEX scan. Recompute the DEX's own
        # integrity fields so rejection specifically exercises table bounds,
        # rather than only detecting a stale fixture checksum.
        struct.pack_into("<I", malformed_dex, 68, len(malformed_dex) + 32)
        _update_dex_checksums(malformed_dex)
        apk = complete_apk(self.tmp / "invalid-dex.apk", bytes(malformed_dex))
        self.assert_rejected(apk, foss=True)

    def test_accepts_dependency_ordered_class_defs_with_nonmonotonic_type_indices(self) -> None:
        # DEX type_ids are descriptor-sorted, but class_defs are dependency
        # ordered. Ljava/lang/Object; therefore precedes La/Child; while its numeric
        # type_idx is greater. This is a table-parser fixture, not executable DEX.
        dex = minimal_dex(
            class_definitions=(
                ("Ljava/lang/Object;", None),
                ("La/Child;", "Ljava/lang/Object;"),
            )
        )
        class_defs_off = struct.unpack_from("<I", dex, 100)[0]
        first = struct.unpack_from("<I", dex, class_defs_off)[0]
        second = struct.unpack_from("<I", dex, class_defs_off + 32)[0]
        self.assertGreater(first, second, "fixture must exercise nonmonotonic class_idx order")
        result = self.run_policy(complete_apk(self.tmp / "dependency-order.apk", dex))
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_rejects_duplicate_and_out_of_range_class_def_indices(self) -> None:
        duplicate = minimal_dex(
            class_definitions=(("Lfixture/Duplicate;", None), ("Lfixture/Duplicate;", None))
        )
        with self.subTest(class_def="duplicate"):
            self.assert_rejected(
                complete_apk(self.tmp / "duplicate-class-def.apk", duplicate)
            )

        out_of_range = bytearray(
            minimal_dex(class_definitions=(("Lfixture/Only;", None),))
        )
        class_defs_off = struct.unpack_from("<I", out_of_range, 100)[0]
        type_ids_size = struct.unpack_from("<I", out_of_range, 64)[0]
        struct.pack_into("<I", out_of_range, class_defs_off, type_ids_size)
        _update_dex_checksums(out_of_range)
        with self.subTest(class_def="out-of-range"):
            self.assert_rejected(
                complete_apk(self.tmp / "out-of-range-class-def.apk", bytes(out_of_range))
            )

    def test_foss_rejects_external_type_id_reference_without_class_definition(self) -> None:
        # No class_defs are present in this bounded fixture: the forbidden name
        # is reached through type_ids, not merely a class definition or filename.
        apk = complete_apk(
            self.tmp / "foss-reference-only.apk",
            minimal_dex(type_descriptors=(_FORBIDDEN_FOSS_TYPE,)),
        )
        self.assert_rejected(apk, foss=True)

    def test_foss_allows_forbidden_looking_const_string_without_type_id(self) -> None:
        # Table-level const-string control: forbidden-looking text is only a
        # string_id and is absent from type_ids. This does not model executable
        # code or assert full DEX semantic validity.
        apk = complete_apk(
            self.tmp / "foss-string-control.apk",
            minimal_dex(
                type_descriptors=("Ljava/lang/Object;",),
                literal_strings=(_FORBIDDEN_FOSS_TYPE,),
            ),
        )
        result = self.run_policy(apk, foss=True)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_dexdump_nonzero_is_not_suppressed(self) -> None:
        self.assert_rejected(
            complete_apk(self.tmp / "dexdump-error.apk"), dexdump_mode="error"
        )

    def test_native_tool_timeouts_kill_and_reap_fake_hanging_tools(self) -> None:
        # The production timeout is fixed and is not configurable from the
        # environment. Patch only this test process's module constant so the
        # fake hanging executables exercise timeout cleanup quickly.
        with mock.patch.object(policy_inspect, "TOOL_TIMEOUT_SECONDS", 0.05), mock.patch.dict(
            os.environ,
            {"FAKE_AAPT_MODE": "hang", "FAKE_DEXDUMP_MODE": "hang"},
        ):
            with self.subTest(tool="aapt"):
                with self.assertRaisesRegex(policy_inspect.PolicyError, "timed out"):
                    policy_inspect._run_bounded_stdout(
                        [str(self.build_tools / "aapt"), "dump", "permissions", "unused.apk"],
                        "fake aapt",
                    )
            with self.subTest(tool="dexdump"):
                with self.assertRaisesRegex(policy_inspect.PolicyError, "timed out"):
                    policy_inspect._validate_dexdump(
                        str(self.dexdump_path), "classes.dex", b"synthetic DEX input"
                    )

    def test_missing_dexdump_fails_closed(self) -> None:
        self.dexdump_path.unlink()
        self.assert_rejected(complete_apk(self.tmp / "missing-dexdump.apk"))

    def test_falls_back_to_older_complete_build_tools(self) -> None:
        older = self.sdk / "build-tools" / "35.0.0"
        older.mkdir(parents=True)
        aapt_text = (self.build_tools / "aapt").read_text(encoding="utf-8")
        dexdump_text = self.dexdump_path.read_text(encoding="utf-8")
        (older / "aapt").write_text(aapt_text, encoding="utf-8")
        (older / "dexdump").write_text(dexdump_text, encoding="utf-8")
        (older / "aapt").chmod(0o755)
        (older / "dexdump").chmod(0o755)
        # The newest directory is incomplete; the gate must use the older
        # complete pair instead of reporting tools missing.
        self.dexdump_path.unlink()
        result = self.run_policy(complete_apk(self.tmp / "fallback-sdk.apk"))
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_split_tool_pair_across_versions_fails_closed(self) -> None:
        older = self.sdk / "build-tools" / "35.0.0"
        older.mkdir(parents=True)
        dexdump_text = self.dexdump_path.read_text(encoding="utf-8")
        (older / "dexdump").write_text(dexdump_text, encoding="utf-8")
        (older / "dexdump").chmod(0o755)
        # 36.0.0 has aapt only and 35.0.0 has dexdump only: no single
        # directory holds both executables, so the gate must fail closed
        # rather than mix versions.
        self.dexdump_path.unlink()
        self.assert_rejected(complete_apk(self.tmp / "split-sdk.apk"))

    def test_sdk_path_with_space_resolves_tool_pair(self) -> None:
        spaced_sdk = self.tmp / "sdk with space"
        tools_dir = spaced_sdk / "build-tools" / "36.0.0"
        tools_dir.mkdir(parents=True)
        (tools_dir / "aapt").write_text(
            (self.build_tools / "aapt").read_text(encoding="utf-8"), encoding="utf-8"
        )
        (tools_dir / "dexdump").write_text(
            self.dexdump_path.read_text(encoding="utf-8"), encoding="utf-8"
        )
        (tools_dir / "aapt").chmod(0o755)
        (tools_dir / "dexdump").chmod(0o755)
        result = self.run_policy(
            complete_apk(self.tmp / "spaced-sdk.apk"), sdk=spaced_sdk
        )
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_dexdump_stdout_is_not_forwarded_or_used_as_policy_data(self) -> None:
        # Native stdout must be DEVNULL or otherwise bounded, never captured as
        # a full dump. This control checks output/data flow, not peak memory;
        # the scanner's DEX type_ids parser is the policy source of truth.
        result = self.run_policy(
            complete_apk(self.tmp / "dexdump-noisy.apk"),
            foss=True,
            dexdump_mode="noisy",
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("DEXDUMP_STDOUT_MUST_NOT_BE_USED_OR_CAPTURED", result.stdout)

    def test_aapt_nonzero_is_a_parser_failure(self) -> None:
        self.assert_rejected(
            complete_apk(self.tmp / "aapt-error.apk"), aapt_mode="error"
        )

    def test_aapt_empty_success_output_is_not_silently_accepted(self) -> None:
        self.assert_rejected(
            complete_apk(self.tmp / "aapt-empty.apk"), aapt_mode="empty"
        )

    def test_missing_aapt_fails_closed(self) -> None:
        empty_sdk = self.tmp / "sdk-without-aapt"
        (empty_sdk / "build-tools" / "36.0.0").mkdir(parents=True)
        self.assert_rejected(
            complete_apk(self.tmp / "missing-aapt.apk"), sdk=empty_sdk
        )

    def test_aab_is_rejected_before_bitwarden_or_gradle_is_invoked(self) -> None:
        # The current builder defaults to AAB. A fake Bitwarden executable logs
        # any invocation and reports a locked synthetic vault, preventing the
        # baseline script from reaching the real ./gradlew wrapper.
        tool_log = self.tmp / "credential-or-build-invocations.txt"
        bw = self.fake_bin / "bw"
        bw.write_text(
            "#!/bin/sh\n"
            "printf 'bw %s\\n' \"$*\" >> \"$TOOL_LOG\"\n"
            "if [ \"${1:-}\" = status ]; then\n"
            "  printf '%s\\n' '{\"status\":\"locked\"}'\n"
            "  exit 0\n"
            "fi\n"
            "echo 'unexpected fake bw command' >&2\n"
            "exit 25\n",
            encoding="utf-8",
        )
        bw.chmod(0o755)
        env = self._env()
        env.update({"BW_SESSION": "synthetic-test-session", "TOOL_LOG": str(tool_log)})
        for name in (
            "PLAY_KEYSTORE_PASSWORD",
            "FOSS_KEYSTORE_PASSWORD",
            "DIRECT_KEYSTORE_PASSWORD",
            "PLAY_KEYSTORE_FILE",
            "FOSS_KEYSTORE_FILE",
            "DIRECT_KEYSTORE_FILE",
        ):
            env.pop(name, None)
        result = subprocess.run(
            ["sh", str(BUILD_RELEASE)],
            cwd=ROOT,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertRegex(result.stdout.lower(), r"aab.*unsupported|unsupported.*aab")
        log_contents = (
            tool_log.read_text(encoding="utf-8")
            if tool_log.exists()
            else "(no fake tool was invoked)"
        )
        self.assertFalse(
            tool_log.exists(),
            f"AAB rejection occurred after credential lookup: {log_contents}",
        )

    def test_release_workflow_is_frozen_read_only_and_has_no_public_artifacts(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(workflow, r"(?m)^permissions:\s*\n\s+contents:\s*read\s*$")
        forbidden_public_paths = (
            "softprops/action-gh-release",
            "actions/upload-artifact",
            "gh release",
            "gh api",
            "github.token",
            "GITHUB_TOKEN",
        )
        for fragment in forbidden_public_paths:
            with self.subTest(fragment=fragment):
                self.assertNotIn(fragment.lower(), workflow.lower())
        self.assertNotRegex(workflow, r"(?m)^\s*contents:\s*write\s*$")
        self.assertIn("issue #12", workflow.lower())
        self.assertIn("disabled", workflow.lower())
        self.assertRegex(workflow, r"(?m)^\s*exit 1\s*$")

    def test_frozen_workflow_has_no_signing_or_unsigned_fallback(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        forbidden_fragments = (
            "r0adkll/sign-android-release",
            "RELEASE_KEYSTORE_BASE64",
            "*signed.apk",
            'GITHUB_APK=$(find',
        )
        for fragment in forbidden_fragments:
            with self.subTest(fragment=fragment):
                self.assertNotIn(fragment, workflow)


class DexDescriptorResourceHarness(unittest.TestCase):
    """Small synthetic inputs; never reproduce the full memory-exhaustion case."""

    def assert_rejected_before_decode(self, dex: bytes, message: str) -> None:
        with mock.patch.object(
            policy_inspect, "_decode_mutf8_string", wraps=policy_inspect._decode_mutf8_string
        ) as decoder:
            with self.assertRaisesRegex(policy_inspect.PolicyError, message):
                policy_inspect.parse_dex_type_tables(dex)
            decoder.assert_not_called()

    def test_duplicate_string_offsets_reject_before_amplified_decoding(self) -> None:
        # Reproduce the 128-type, one-long-string proof without a large payload.
        # descriptor_idx remains increasing; only string_data offsets alias.
        descriptors = ("LA" + "x" * 4096 + ";",) + tuple(
            f"LB{index:03d};" for index in range(127)
        )
        dex = bytearray(minimal_dex(type_descriptors=descriptors))
        string_ids_off = struct.unpack_from("<I", dex, 60)[0]
        first_offset = struct.unpack_from("<I", dex, string_ids_off)[0]
        for index in range(128):
            struct.pack_into("<I", dex, string_ids_off + 4 * index, first_offset)
        _update_dex_checksums(dex)
        self.assert_rejected_before_decode(bytes(dex), "duplicate string_data offset")

    def test_duplicate_type_values_at_distinct_offsets_reject(self) -> None:
        dex = bytearray(minimal_dex(type_descriptors=("Lfixture/A;", "Lfixture/B;")))
        string_ids_off = struct.unpack_from("<I", dex, 60)[0]
        first, second = struct.unpack_from("<II", dex, string_ids_off)
        # Same-length, physically disjoint string_data_items now name one type.
        length, start = policy_inspect._read_uleb128(dex, first, "fixture length")
        item_bytes = bytes(dex[first : start + length + 1])
        dex[second : second + len(item_bytes)] = item_bytes
        _update_dex_checksums(dex)
        with self.assertRaisesRegex(policy_inspect.PolicyError, "duplicate type descriptor"):
            policy_inspect.parse_dex_type_tables(bytes(dex))

    def test_overlapping_distinct_string_offsets_reject_before_decode(self) -> None:
        # ASCII B (66) is simultaneously a valid outer-name character and an
        # inner ULEB128 length. Both descriptors would be valid in isolation,
        # but the two distinct offsets point into overlapping encoded data.
        outer = "LA" + "x" * 128 + "B" + "L" + "x" * 64 + ";"
        dex = bytearray(minimal_dex(type_descriptors=(outer, "LB;")))
        string_ids_off = struct.unpack_from("<I", dex, 60)[0]
        first = struct.unpack_from("<I", dex, string_ids_off)[0]
        _, start = policy_inspect._read_uleb128(dex, first, "fixture length")
        struct.pack_into("<I", dex, string_ids_off + 4, start + 2 + 128)
        _update_dex_checksums(dex)
        self.assert_rejected_before_decode(bytes(dex), "overlaps|unterminated")

    def test_string_ids_cap_precedes_offset_table_allocation(self) -> None:
        dex = minimal_dex(type_descriptors=("Lfixture/A;", "Lfixture/B;"))
        with mock.patch.object(policy_inspect, "MAX_STRING_IDS", 1, create=True):
            with mock.patch.object(policy_inspect, "_u32", wraps=policy_inspect._u32) as fields:
                self.assert_rejected_before_decode(dex, "string_ids.*scanner limit")
                self.assertFalse(
                    any("string_ids[" in call.args[2] for call in fields.call_args_list),
                    "string_ids must be capped before reading/allocating its offset table",
                )

    def test_distinct_long_descriptors_have_predecode_cumulative_unit_cap(self) -> None:
        dex = minimal_dex(type_descriptors=("LA" + "x" * 2048 + ";", "LB" + "x" * 2048 + ";"))
        with mock.patch.object(policy_inspect, "MAX_DEX_DESCRIPTOR_UTF16_UNITS", 3000, create=True):
            self.assert_rejected_before_decode(dex, "cumulative descriptor UTF-16")

    def test_distinct_long_mutf8_descriptors_have_predecode_encoded_byte_cap(self) -> None:
        dex = minimal_dex(type_descriptors=("LA" + "é" * 1000 + ";", "LB" + "é" * 1000 + ";"))
        with mock.patch.object(policy_inspect, "MAX_DEX_DESCRIPTOR_ENCODED_BYTES", 3000, create=True):
            self.assert_rejected_before_decode(dex, "cumulative descriptor encoded bytes")

    def test_exact_cumulative_budget_boundary_preserves_valid_types(self) -> None:
        descriptors = ("LAé;", "LBé;")
        units = sum(_mutf8(value)[0] for value in descriptors)
        encoded_bytes = sum(
            len(_uleb128(_mutf8(value)[0])) + len(_mutf8(value)[1]) + 1
            for value in descriptors
        )
        with mock.patch.object(policy_inspect, "MAX_DEX_DESCRIPTOR_UTF16_UNITS", units, create=True), mock.patch.object(
            policy_inspect, "MAX_DEX_DESCRIPTOR_ENCODED_BYTES", encoded_bytes, create=True
        ):
            parsed = policy_inspect.parse_dex_type_tables(minimal_dex(type_descriptors=descriptors))
        self.assertEqual(parsed.type_descriptors, descriptors)

    def test_per_descriptor_limit_rejects_before_decode_and_accepts_boundary(self) -> None:
        descriptor = "LAxx;"
        with mock.patch.object(policy_inspect, "MAX_TYPE_DESCRIPTOR_UTF16_UNITS", len(descriptor)):
            parsed = policy_inspect.parse_dex_type_tables(minimal_dex(type_descriptors=(descriptor,)))
            self.assertEqual(parsed.type_descriptors, (descriptor,))
            self.assert_rejected_before_decode(
                minimal_dex(type_descriptors=("LAxxx;",)), "bounded string limit"
            )

    def test_apk_budget_bounds_retained_descriptors_across_shards_before_decode(self) -> None:
        first = minimal_dex(type_descriptors=("LA;",))
        second = minimal_dex(type_descriptors=("LB;",))
        for limit, message in (
            ("MAX_APK_DESCRIPTOR_UTF16_UNITS", "APK cumulative descriptor UTF-16"),
            ("MAX_APK_DESCRIPTOR_ENCODED_BYTES", "APK cumulative descriptor encoded bytes"),
        ):
            with self.subTest(cap=limit), mock.patch.object(policy_inspect, limit, 5):
                budget = policy_inspect.DescriptorBudget()
                policy_inspect.parse_dex_type_tables(first, descriptor_budget=budget)
                with mock.patch.object(
                    policy_inspect, "_decode_mutf8_string", wraps=policy_inspect._decode_mutf8_string
                ) as decoder:
                    with self.assertRaisesRegex(policy_inspect.PolicyError, message):
                        policy_inspect.parse_dex_type_tables(second, descriptor_budget=budget)
                    decoder.assert_not_called()

        # The same cross-shard budget accepts exactly six units / ten encoded
        # bytes (length prefix and terminator included for each descriptor).
        with mock.patch.object(policy_inspect, "MAX_APK_DESCRIPTOR_UTF16_UNITS", 6), mock.patch.object(
            policy_inspect, "MAX_APK_DESCRIPTOR_ENCODED_BYTES", 10
        ):
            budget = policy_inspect.DescriptorBudget()
            policy_inspect.parse_dex_type_tables(first, descriptor_budget=budget)
            policy_inspect.parse_dex_type_tables(second, descriptor_budget=budget)
            self.assertEqual((budget.remaining_units, budget.remaining_bytes), (0, 0))


class NscDumpParser(unittest.TestCase):
    """``_parse_aapt_xmltree`` against the MEASURED real aapt dump shapes."""

    _REAL_SHAPE_DUMP = (
        "E: network-security-config (line=15)\n"
        "  E: base-config (line=16)\n"
        "    A: cleartextTrafficPermitted=(type 0x12)0x0\n"
        "    E: trust-anchors (line=17)\n"
        "      E: certificates (line=18)\n"
        "        A: src=\"system\" (Raw: \"system\")\n"
        "  E: domain-config (line=21)\n"
        "    A: cleartextTrafficPermitted=(type 0x12)0xffffffff\n"
        "    E: domain (line=22)\n"
        "      A: includeSubdomains=(type 0x12)0x0\n"
        "      C: \"localhost\"\n"
    )

    def test_measured_dump_rebuilds_and_validates_under_both_rules(self) -> None:
        # The compiled NSC shape printed by aapt v1 for a real debug APK must
        # rebuild into a tree the shared nsc_check rule engine accepts under
        # DEBUG rules (loopback whitelist) and rejects under RELEASE rules
        # (deny-all) - one parser, both rule sets, no divergence.
        root = policy_inspect._parse_aapt_xmltree(
            self._REAL_SHAPE_DUMP, "aapt xmltree network security config"
        )
        self.assertEqual(root.tag, "network-security-config")
        base = root.find("base-config")
        self.assertIsNotNone(base)
        self.assertEqual(base.get("cleartextTrafficPermitted"), "false")
        certificates = root.find("base-config/trust-anchors/certificates")
        self.assertIsNotNone(certificates)
        self.assertEqual(certificates.get("src"), "system")
        domain = root.find("domain-config/domain")
        self.assertIsNotNone(domain)
        self.assertEqual(domain.get("includeSubdomains"), "false")
        self.assertEqual(domain.text, "localhost")
        self.assertEqual(nsc_check.validate_tree(root, "debug", "mem"), [])
        errors = nsc_check.validate_tree(root, "release", "mem")
        self.assertTrue(
            any("<domain-config> sets cleartextTrafficPermitted" in e for e in errors),
            errors,
        )

    def test_namespaced_manifest_dump_rebuilds_from_first_element(self) -> None:
        # Real manifest dumps start with N: namespace lines so the root
        # element sits one indent step deeper; the parser must still rebuild
        # the manifest as the tree root and keep the printed attr prefix.
        dump = (
            "N: android=http://schemas.android.com/apk/res/android\n"
            "  E: manifest (line=8)\n"
            "    A: android:networkSecurityConfig(0x01010527)=@0x7f0b0002\n"
        )
        root = policy_inspect._parse_aapt_xmltree(dump, "manifest dump")
        self.assertEqual(root.tag, "manifest")
        self.assertEqual(
            root.get("android:networkSecurityConfig"), "@0x7f0b0002"
        )

    def test_error_lines_fail_closed_regardless_of_tool_exit(self) -> None:
        # Observed aapt behavior: missing resources print
        # "ERROR: dump failed because resource … not found" and builds differ
        # on the exit status - the parser detects the ERROR line itself.
        dump = (
            "ERROR: dump failed because resource "
            "res/xml/network_security_config.xml not found\n"
        )
        with self.assertRaisesRegex(policy_inspect.PolicyError, "ERROR"):
            policy_inspect._parse_aapt_xmltree(dump, "nsc dump")

    def test_empty_or_dangling_dump_fails_closed(self) -> None:
        for dump, message in (
            ("", "no element tree"),
            ("\n\n", "no element tree"),
            ('    A: attr="x"\n', "outside any element"),
            ('    C: "text"\n', "outside any element"),
        ):
            with self.subTest(dump=dump):
                with self.assertRaisesRegex(policy_inspect.PolicyError, message):
                    policy_inspect._parse_aapt_xmltree(dump, "nsc dump")

    def test_unknown_encodings_fail_closed(self) -> None:
        cases = {
            "E: root\n    A: attr=(type 0x10)0x25\n": (
                "unrecognized compiled attribute encoding"
            ),
            "E: root\nX: weird\n": "unrecognized dump line",
            "E: root\n    C: bare\n": "unrecognized chardata encoding",
            "E: root\n    A: attr=??\n": "unrecognized attribute value encoding",
        }
        for dump, message in cases.items():
            with self.subTest(dump=dump):
                with self.assertRaisesRegex(policy_inspect.PolicyError, message):
                    policy_inspect._parse_aapt_xmltree(dump, "nsc dump")

    def test_multiple_roots_and_value_decodings(self) -> None:
        with self.assertRaisesRegex(policy_inspect.PolicyError, "multiple root"):
            policy_inspect._parse_aapt_xmltree(
                "E: one\n  E: child\nE: two\n", "nsc dump"
            )
        root = policy_inspect._parse_aapt_xmltree(
            'E: root\n'
            '    A: k="a\\nb" (Raw: "a\\nb")\n'
            "    A: r=@res/0x7f010002\n"
            "    A: q=?0x01010001\n",
            "nsc dump",
        )
        self.assertEqual(root.get("k"), "a\nb")
        self.assertEqual(root.get("r"), "@res/0x7f010002")
        self.assertEqual(root.get("q"), "?0x01010001")


def _pr_check_policy_patterns() -> list[str]:
    """Extract the `policy:` paths-filter patterns from pr-check.yml.

    The workflow file is parsed as text (stdlib only): lines under the
    `policy:` key of the form `- 'pattern'` are collected until the next
    top-level filter key or job.
    """
    text = PR_CHECK_WORKFLOW.read_text(encoding="utf-8")
    patterns: list[str] = []
    in_policy = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "policy:":
            in_policy = True
            continue
        if in_policy:
            if stripped.startswith("- '") and stripped.endswith("'"):
                patterns.append(stripped[3:-1])
                continue
            if stripped in ("code:", "policy:") or stripped.endswith(":") and not stripped.startswith("-"):
                break
    return patterns


class PolicyCiGateControl(unittest.TestCase):
    """Pin the CI wiring so a scanner-only diff cannot skip validation."""

    def test_policy_filter_covers_scanner_and_tests(self) -> None:
        patterns = _pr_check_policy_patterns()
        for required in (
            "scripts/play_policy_check.sh",
            "scripts/apk_policy_inspect.py",
            "scripts/backup_rules_check.py",
            "scripts/nsc_check.py",
            "scripts/summarize_junit.py",
            "scripts/check_test_budgets.py",
            "scripts/check_junit_durations.py",
            "scripts/tests/test_apk_policy_check.py",
            "scripts/tests/test_summarize_junit.py",
            "scripts/tests/test_check_test_budgets.py",
            "scripts/tests/test_check_junit_durations.py",
            ".github/workflows/pr-check.yml",
        ):
            with self.subTest(path=required):
                self.assertIn(required, patterns)

    def test_scanner_only_diff_triggers_policy_gate(self) -> None:
        patterns = _pr_check_policy_patterns()
        for diff in (
            ["scripts/apk_policy_inspect.py"],
            ["scripts/backup_rules_check.py"],
            ["scripts/tests/test_apk_policy_check.py"],
            ["scripts/apk_policy_inspect.py", "scripts/tests/test_apk_policy_check.py"],
            ["scripts/summarize_junit.py"],
            ["scripts/tests/test_summarize_junit.py"],
            ["scripts/check_test_budgets.py"],
            ["scripts/check_junit_durations.py"],
            ["scripts/tests/test_check_test_budgets.py"],
            ["scripts/tests/test_check_junit_durations.py"],
            [".github/workflows/pr-check.yml"],
        ):
            with self.subTest(diff=tuple(diff)):
                matched = any(
                    fnmatchcase(path, pattern)
                    for path in diff
                    for pattern in patterns
                )
                self.assertTrue(matched, f"scanner-only diff {diff} must set policy=true")

    def test_policy_harness_runs_repo_budget_guard(self) -> None:
        """PR #87 P2-4: script-only diffs skip unit-tests, so the repo-wide
        write-budget scan must run inside policy-harness (harness unit tests
        alone never scan the current tree).

        Pins the EXACT harness-step line (env prefix included): the bare
        `run: python3 scripts/check_test_budgets.py` form also exists in the
        `unit-tests` job, so a bare-substring assertion would stay green if
        this step were ever removed.
        """
        workflow = PR_CHECK_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            "run: PYTHONDONTWRITEBYTECODE=1 python3 scripts/check_test_budgets.py",
            workflow,
        )

    def test_docs_only_diff_does_not_trigger_policy_gate(self) -> None:
        patterns = _pr_check_policy_patterns()
        matched = any(
            fnmatchcase("docs/TESTING.md", pattern) for pattern in patterns
        )
        self.assertFalse(matched, "docs-only diff must leave policy=false")

    def test_unit_summary_step_uses_extracted_parser_with_fallback(self) -> None:
        workflow = PR_CHECK_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("python3 scripts/summarize_junit.py", workflow)
        self.assertIn("summary_parser_crashed_fallback", workflow)

    def test_github_flavor_artifact_check_runs_mode_independent_scan(self) -> None:
        # The github debug APK is neither a play nor a foss artifact, yet the
        # mode-independent artifact authorities (merged backup bindings,
        # compiled backup-rules bytes, compiled NSC) still apply to it. The
        # assembly job must run the scanner's github mode on it right after
        # the play/foss policy gate so a scanner-only or wiring-only diff
        # cannot drop this check silently again.
        workflow = PR_CHECK_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            "Run Github Flavor Artifact Binding Check (mode-independent)",
            workflow,
        )
        self.assertIn("python3 scripts/apk_policy_inspect.py --mode github", workflow)
        # The scanned artifact is the github debug APK assembly just built
        # (exact artifact-to-mode pairing).
        self.assertIn(
            "--apk app/build/outputs/apk/github/debug/app-github-debug.apk",
            workflow,
        )

    def test_pr_gate_requires_policy_harness(self) -> None:
        workflow = PR_CHECK_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("policy-harness", workflow)
        self.assertIn("needs: [changes, unit-tests, lint, build-and-policy-gate, policy-harness, docs-guard, bot-tests]", workflow)
        self.assertIn("needs.policy-harness.result", workflow)
        self.assertIn("policy-harness skipped but policy=", workflow)
        for test_path in (
            "scripts/tests/test_apk_policy_check.py",
            "scripts/tests/test_release_artifact_verifier.py",
            "scripts/tests/test_build_release_identity.py",
            "scripts/tests/test_summarize_junit.py",
        ):
            with self.subTest(test_path=test_path):
                self.assertIn(test_path, workflow)
        self.assertIn(
            "PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_apk_policy_check.py scripts/tests/test_release_artifact_verifier.py scripts/tests/test_build_release_identity.py scripts/tests/test_summarize_junit.py",
            workflow,
        )


class BackupRulesValidator(unittest.TestCase):
    """Structured backup/data-extraction rule validation (P2 gate regression).

    A raw-text ``grep domain=... path=...`` matches anywhere in the file, so a
    device-transfer branch with zero excludes still passed. These fixtures pin
    the parser-based validator against exactly those regressions.
    """

    _ALL_EXCLUDES = (
        '        <exclude domain="sharedpref" path="librepocket_keys.xml" />\n'
        '        <exclude domain="database" path="librepocket_vault" />\n'
        '        <exclude domain="database" path="librepocket_vault-wal" />\n'
        '        <exclude domain="database" path="librepocket_vault-shm" />\n'
        '        <exclude domain="file" path="librepocket_keysets" />\n'
    )

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)

    def _write(self, name: str, body: str) -> str:
        path = self.tmp / name
        path.write_text(body, encoding="utf-8")
        return str(path)

    @staticmethod
    def _full_backup(excludes: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<full-backup-content>\n"
            f"{excludes}"
            "</full-backup-content>\n"
        )

    @staticmethod
    def _data_extraction(cloud: str, device: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<data-extraction-rules>\n"
            f"    <cloud-backup>\n{cloud}    </cloud-backup>\n"
            f"    <device-transfer>\n{device}    </device-transfer>\n"
            "</data-extraction-rules>\n"
        )

    def _check(self, path: str) -> list[str]:
        """Hinted validation keyed on fixture filename (fixtures use the two
        well-known names). ``validate`` takes an expected root REQUIRED - a
        hint-free call is refused, so tests always pass the binding."""
        name = Path(path).name
        root = (
            backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION
            if "data_extraction" in name
            else backup_rules_check.EXPECTED_ROOT_BACKUP_RULES
        )
        return backup_rules_check.validate(path, root)

    @staticmethod
    def _manifest(application_attrs: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f"    <application {application_attrs} />\n"
            "</manifest>\n"
        )

    _MANIFEST_BINDING = (
        'android:fullBackupContent="@xml/backup_rules" '
        'android:dataExtractionRules="@xml/data_extraction_rules"'
    )

    @staticmethod
    def _tools_manifest(attrs: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:tools="http://schemas.android.com/tools">\n'
            f"    <application {attrs} />\n"
            "</manifest>\n"
        )

    # -- manifest-merger markers: absence is NOT legal inheritance when a
    # marker strips main's binding (third-party reviewer repro) --

    def test_overlay_tools_remove_backup_attrs_fails_closed(self) -> None:
        p = self._write(
            "AndroidManifest.xml",
            self._tools_manifest(
                'tools:remove="android:fullBackupContent,android:dataExtractionRules"'
            ),
        )
        errors = backup_rules_check.validate_manifest(p)
        self.assertEqual(len(errors), 2, errors)
        self.assertTrue(
            any("tools:remove strips android:fullBackupContent" in e for e in errors),
            errors,
        )
        self.assertTrue(
            any(
                "tools:remove strips android:dataExtractionRules" in e for e in errors
            ),
            errors,
        )

    def test_overlay_tools_node_replace_without_attrs_fails_closed(self) -> None:
        p = self._write(
            "AndroidManifest.xml",
            self._tools_manifest('android:allowBackup="true" tools:node="replace"'),
        )
        errors = backup_rules_check.validate_manifest(p)
        self.assertEqual(len(errors), 2, errors)
        self.assertTrue(
            any("tools:node=\"replace\"" in e for e in errors), errors
        )

    def test_overlay_tools_node_remove_application_fails_closed(self) -> None:
        p = self._write(
            "AndroidManifest.xml", self._tools_manifest('tools:node="remove"')
        )
        errors = backup_rules_check.validate_manifest(p)
        # ONE combined message: the whole element is stripped - only its
        # re-declaration could prove otherwise, and tools:node="remove"
        # never admits that.
        self.assertEqual(len(errors), 1, errors)
        self.assertIn('tools:node="remove"', errors[0])
        self.assertIn("android:fullBackupContent", errors[0])
        self.assertIn("android:dataExtractionRules", errors[0])

    def test_overlay_marker_with_own_pinned_values_passes(self) -> None:
        # Re-declaring BOTH pinned values while carrying a marker is provably
        # fine: this document's own attributes survive the merge.
        for marker in (
            'tools:node="replace" ',
            'tools:remove="android:fullBackupContent" ',
        ):
            with self.subTest(marker=marker):
                p = self._write(
                    "AndroidManifest.xml",
                    self._tools_manifest(marker + self._MANIFEST_BINDING),
                )
                self.assertEqual(backup_rules_check.validate_manifest(p), [])

    def test_root_manifest_level_marker_naming_backup_attrs_rejected(self) -> None:
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:tools="http://schemas.android.com/tools"\n'
            '    tools:remove="android:fullBackupContent">\n'
            "</manifest>\n"
        )
        p = self._write("AndroidManifest.xml", body)
        errors = backup_rules_check.validate_manifest(p)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("tools:remove marker on <manifest>", errors[0])

    def test_overlay_benign_tools_markers_still_pass(self) -> None:
        # tools:ignore / tools:insert never strip the inherited attributes.
        p = self._write(
            "AndroidManifest.xml",
            self._tools_manifest(
                'tools:ignore="android:fullBackupContent" '
                'tools:insert="android:appComponentFactory@placeholder" '
                'android:allowBackup="true"'
            ),
        )
        self.assertEqual(backup_rules_check.validate_manifest(p), [])

    def test_manifest_structured_binding_passes(self) -> None:
        # Plain double-quoted attrs AND single-quoted (equally valid XML) both
        # parse to the same values - the old byte-grep failed the latter.
        for body in (
            self._manifest(self._MANIFEST_BINDING),
            self._manifest(self._MANIFEST_BINDING.replace('"', "'")),
        ):
            p = self._write("AndroidManifest.xml", body)
            self.assertEqual(
                backup_rules_check.validate_manifest(p, require_both=True), []
            )

    def test_manifest_comment_survivor_no_longer_fools_the_check(self) -> None:
        # The raw-grep escape: attributes deleted, literals surviving only
        # inside an XML comment (runtime runs unprotected Auto Backup while a
        # grep gate reports PASS). ET drops comments by construction - the
        # structured check fails closed instead.
        commented = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <!-- <application android:fullBackupContent="@xml/backup_rules" '
            'android:dataExtractionRules="@xml/data_extraction_rules" /> -->\n'
            "    <application />\n"
            "</manifest>\n"
        )
        p = self._write("AndroidManifest.xml", commented)
        errors = backup_rules_check.validate_manifest(p, require_both=True)
        self.assertEqual(len(errors), 2, errors)
        self.assertTrue(
            any("fullBackupContent must reference @xml/backup_rules" in e for e in errors),
            errors,
        )
        self.assertTrue(
            any("dataExtractionRules must reference @xml/data_extraction_rules" in e for e in errors),
            errors,
        )
        # Overlay semantics: same comment-escape tree without --require-both
        # is LEGAL absence (merger inherits main's values).
        self.assertEqual(backup_rules_check.validate_manifest(p), [])

    def test_manifest_misbound_or_seen_value_fails_closed(self) -> None:
        p = self._write(
            "AndroidManifest.xml",
            self._manifest('android:fullBackupContent="@xml/other_rules"'),
        )
        errors = backup_rules_check.validate_manifest(p)
        self.assertTrue(
            any("fullBackupContent must reference @xml/backup_rules" in e and "got" in e for e in errors),
            errors,
        )
        # require_both additionally reports the absent dataExtractionRules.
        errors = backup_rules_check.validate_manifest(p, require_both=True)
        self.assertTrue(
            any("dataExtractionRules must reference @xml/data_extraction_rules" in e and "absent" in e for e in errors),
            errors,
        )

    def test_manifest_wrong_root_or_missing_application_fails_closed(self) -> None:
        p = self._write(
            "AndroidManifest.xml",
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n',
        )
        errors = backup_rules_check.validate_manifest(p, require_both=True)
        self.assertTrue(any("needs a <manifest> root" in e for e in errors), errors)

        # MAIN: no <application> at all → fatal (attrs must live there).
        p = self._write(
            "AndroidManifest.xml",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            "</manifest>\n",
        )
        errors = backup_rules_check.validate_manifest(p, require_both=True)
        self.assertTrue(any("needs an <application>" in e for e in errors), errors)

        # OVERLAY: a permissions-only manifest without <application> inherits
        # the whole merged tree and is legal (play flavor is exactly this).
        self.assertEqual(backup_rules_check.validate_manifest(p), [])

        # Missing file surfaces as unreadable (exit 1 downstream).
        errors = backup_rules_check.validate_manifest(
            str(self.tmp / "no-such-manifest.xml"), require_both=True
        )
        self.assertTrue(any("unreadable" in e for e in errors))

    def test_cli_check_manifest_wiring_exit_codes(self) -> None:
        good = self._write("AndroidManifest.xml", self._manifest(self._MANIFEST_BINDING))
        missing = self._write(
            "AndroidManifestMissing.xml", self._manifest('android:allowBackup="true"')
        )
        self.assertEqual(
            backup_rules_check.main(
                ["x", "--check-manifest", "--require-both", good]
            ),
            0,
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                ["x", "--check-manifest", "--require-both", missing]
            )
        self.assertEqual(1, code)
        self.assertIn("fullBackupContent must reference", buf_err.getvalue())
        # Mode conflicts stay fail-closed exit 2.
        for argv in (
            ["x", "--check-manifest", "--expect-root", "full-backup-content", good],
            ["x", "--require-both", good],
        ):
            buf_err = io.StringIO()
            with contextlib.redirect_stderr(buf_err):
                code = backup_rules_check.main(argv)
            self.assertEqual(2, code, argv)

    def test_cli_duplicate_expect_root_fails_closed(self) -> None:
        # Last-wins on a repeated binding hint is a silent contract slip:
        # reject duplicates outright (exit 2).
        good = self._write(
            "backup_rules.xml", self._full_backup(self._ALL_EXCLUDES)
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "x",
                    "--expect-root",
                    "full-backup-content",
                    "--expect-root",
                    "data-extraction-rules",
                    good,
                ]
            )
        self.assertEqual(2, code)
        self.assertIn("duplicate --expect-root", buf_err.getvalue())

    def test_unknown_wrapper_descends_into_nested_unknown(self) -> None:
        # AOSP validates every START_TAG: an unknown wrapper does not exempt
        # its content, so a nested unknown inside an unknown wrapper is
        # reported too (message completeness; outer report already fail-closed).
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<full-backup-content>\n"
            f"{self._ALL_EXCLUDES}"
            "    <outer-wrapper>\n"
            "        <inner-future />\n"
            "    </outer-wrapper>\n"
            "</full-backup-content>\n"
        )
        path = self._write("backup_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(any("<outer-wrapper>" in e for e in errors), errors)
        self.assertTrue(any("<inner-future>" in e for e in errors), errors)

    def test_repo_backup_files_are_valid(self) -> None:
        # Gates pin the file↔root binding: each real file must validate against
        # its expected Android root.
        expected = {
            "backup_rules.xml": backup_rules_check.EXPECTED_ROOT_BACKUP_RULES,
            "data_extraction_rules.xml": backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION,
        }
        errors: list[str] = []
        for name, eroot in expected.items():
            errors.extend(
                backup_rules_check.validate(
                    str(ROOT / "app" / "src" / "main" / "res" / "xml" / name), eroot
                )
            )
        self.assertEqual(errors, [])

    def test_missing_device_transfer_exclude_is_caught(self) -> None:
        # Minimal counterexample from the review: cloud-backup complete,
        # device-transfer drops vault-wal. A raw-text grep would still PASS.
        device = self._ALL_EXCLUDES.replace(
            '        <exclude domain="database" path="librepocket_vault-wal" />\n', ""
        )
        path = self._write(
            "data_extraction_rules.xml", self._data_extraction(self._ALL_EXCLUDES, device)
        )
        errors = self._check(path)
        self.assertTrue(
            any("<device-transfer>" in e and "librepocket_vault-wal" in e for e in errors),
            errors,
        )

    def test_empty_device_transfer_is_caught(self) -> None:
        path = self._write(
            "data_extraction_rules.xml", self._data_extraction(self._ALL_EXCLUDES, "")
        )
        errors = self._check(path)
        self.assertTrue(any("<device-transfer>" in e for e in errors), errors)
        self.assertEqual(len(errors), len(backup_rules_check.REQUIRED_EXCLUDES), errors)
        self.assertTrue(all("<device-transfer>" in e for e in errors), errors)

    def test_commented_out_excludes_do_not_count(self) -> None:
        commented = (
            "    <!--\n"
            '    <exclude domain="sharedpref" path="librepocket_keys.xml" />\n'
            '    <exclude domain="database" path="librepocket_vault" />\n'
            '    <exclude domain="database" path="librepocket_vault-wal" />\n'
            '    <exclude domain="database" path="librepocket_vault-shm" />\n'
            '    <exclude domain="file" path="librepocket_keysets" />\n'
            "    -->\n"
        )
        path = self._write(
            "data_extraction_rules.xml", self._data_extraction(commented, commented)
        )
        errors = self._check(path)
        self.assertEqual(
            len(errors), 2 * len(backup_rules_check.REQUIRED_EXCLUDES), errors
        )  # 5 per branch; comments ignored
        self.assertTrue(
            all("<cloud-backup>" in e or "<device-transfer>" in e for e in errors),
            errors,
        )

    def test_missing_cloud_backup_branch_is_caught(self) -> None:
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<data-extraction-rules>\n"
            "    <device-transfer>\n"
            f"{self._ALL_EXCLUDES}    </device-transfer>\n"
            "</data-extraction-rules>\n"
        )
        path = self._write("data_extraction_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(any("missing <cloud-backup>" in e for e in errors), errors)

    def test_full_backup_content_missing_exclude_is_caught(self) -> None:
        excludes = self._ALL_EXCLUDES.replace(
            '        <exclude domain="file" path="librepocket_keysets" />\n', ""
        )
        path = self._write("backup_rules.xml", self._full_backup(excludes))
        errors = self._check(path)
        self.assertTrue(any("librepocket_keysets" in e for e in errors), errors)

    def test_unexpected_root_element_is_caught(self) -> None:
        # Unknown/unrelated roots are caught by the binding hint first
        # (expected-root mismatch), which is the only surface a wrong schema
        # can appear through now that validate() requires the hint.
        path = self._write("bad.xml", '<?xml version="1.0"?><something-else />')
        errors = self._check(path)
        self.assertTrue(
            any("expected root <full-backup-content>" in e for e in errors), errors
        )

    def test_exclude_missing_domain_or_path_attribute_is_caught(self) -> None:
        # (None, path) / (domain, None) must not satisfy a required (domain, path).
        excludes = (
            '        <exclude path="librepocket_vault" />\n'
            '        <exclude domain="database" />\n'
        )
        path = self._write("backup_rules.xml", self._full_backup(excludes))
        errors = self._check(path)
        self.assertEqual(len(errors), len(backup_rules_check.REQUIRED_EXCLUDES), errors)

    def test_nested_exclude_does_not_count(self) -> None:
        # Android allows <exclude> only as a direct child of the branch; a
        # nested <exclude> (e.g. under a future <rule>) must not satisfy it.
        # The <rule> wrapper is itself an unknown element that Android's
        # runtime parser rejects, so the gate flags it too (both directions).
        nested = (
            '        <rule>\n'
            f'{self._ALL_EXCLUDES.replace("        ", "            ")}'
            '        </rule>\n'
        )
        path = self._write(
            "data_extraction_rules.xml",
            self._data_extraction(self._ALL_EXCLUDES, nested),
        )
        errors = self._check(path)
        self.assertEqual(
            len(errors),
            len(backup_rules_check.REQUIRED_EXCLUDES) + 1,
            errors,
        )  # 5 missing per branch + 1 unknown-element (<rule>)
        self.assertTrue(all("<device-transfer>" in e for e in errors), errors)
        self.assertTrue(
            any("unknown element <rule>" in e for e in errors), errors
        )

    def test_namespace_qualified_root_fails_closed(self) -> None:
        # A default-namespace root is not the Android backup schema; the
        # validator must fail closed rather than silently PASS.
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<full-backup-content xmlns="http://schemas.android.com/apk/res-auto">\n'
            f"{self._ALL_EXCLUDES}</full-backup-content>\n"
        )
        path = self._write("backup_rules.xml", body)
        errors = self._check(path)
        # Namespaced root tag (ElementTree-qualified {@uri}tag) never equals the
        # bare expected root — fail-closed on the binding mismatch message.
        self.assertTrue(
            any("expected root <full-backup-content>" in e for e in errors), errors
        )

    def test_duplicate_sections_are_fully_validated(self) -> None:
        # Android's runtime parser visits every matching section occurrence
        # (a duplicate <cloud-backup> carrying an unknown tag throws at
        # runtime); the gate must validate ALL occurrences, not just the first.
        duplicate = (
            "    <cloud-backup>\n"
            f"{self._ALL_EXCLUDES}    </cloud-backup>\n"
            "    <cloud-backup>\n"
            '        <notatag domain="database" path="x" />\n'
            "    </cloud-backup>\n"
            f"    <device-transfer>\n{self._ALL_EXCLUDES}    </device-transfer>\n"
        )
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f"<data-extraction-rules>\n{duplicate}</data-extraction-rules>\n"
        )
        path = self._write("data_extraction_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(
            any("<cloud-backup> unknown element <notatag>" in e for e in errors),
            errors,
        )

    def test_exclude_moved_into_duplicate_section_fails_gate(self) -> None:
        # Splitting the required excludes across duplicate cloud-backup
        # sections must not satisfy the gate: every occurrence must carry the
        # full five-exclude set.
        split = (
            "    <cloud-backup>\n"
            '        <exclude domain="sharedpref" path="librepocket_keys.xml" />\n'
            "    </cloud-backup>\n"
            "    <cloud-backup>\n"
            '        <exclude domain="database" path="librepocket_vault" />\n'
            '        <exclude domain="database" path="librepocket_vault-wal" />\n'
            '        <exclude domain="database" path="librepocket_vault-shm" />\n'
            '        <exclude domain="file" path="librepocket_keysets" />\n'
            "    </cloud-backup>\n"
            f"    <device-transfer>\n{self._ALL_EXCLUDES}    </device-transfer>\n"
        )
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f"<data-extraction-rules>\n{split}</data-extraction-rules>\n"
        )
        path = self._write("data_extraction_rules.xml", body)
        errors = self._check(path)
        # First occurrence misses 4, second misses 1 (sharedpref covered there,
        # first misses it) — every occurrence must be complete.
        self.assertTrue(
            any("missing exclude domain=database path=librepocket_vault" in e for e in errors),
            errors,
        )

    def test_stray_root_level_child_is_ignored_like_runtime(self) -> None:
        # AOSP's data-extraction-rules parser silently skips root-level
        # children that are not one of the section tags; the gate mirrors
        # that (no false alarm) while the two required branches carry policy.
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<data-extraction-rules>\n"
            '    <exclude domain="database" path="ignored-at-runtime.xml" />\n'
            f"    <cloud-backup>\n{self._ALL_EXCLUDES}    </cloud-backup>\n"
            f"    <device-transfer>\n{self._ALL_EXCLUDES}    </device-transfer>\n"
            "</data-extraction-rules>\n"
        )
        path = self._write("data_extraction_rules.xml", body)
        self.assertEqual(self._check(path), [])

    def test_cross_platform_transfer_section_is_refused(self) -> None:
        # Android 16 QPR2 (API 36.1) promotes <cross-platform-transfer> to a
        # THIRD platform section (iOS cross-platform transfer). It is NOT an
        # unknown element the runtime skips silently, and no transfer policy
        # has settled the gate for it: the gate refuses its appearance
        # outright instead of neither validating nor rejecting it.
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<data-extraction-rules>\n"
            f"    <cloud-backup>\n{self._ALL_EXCLUDES}    </cloud-backup>\n"
            f"    <device-transfer>\n{self._ALL_EXCLUDES}    </device-transfer>\n"
            "    <cross-platform-transfer>\n"
            '        <exclude domain="file" path="cross_leak.xml" />\n'
            "    </cross-platform-transfer>\n"
            "</data-extraction-rules>\n"
        )
        path = self._write("data_extraction_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(
            any("<cross-platform-transfer> is a platform-supported root section" in e for e in errors),
            errors,
        )
        self.assertTrue(
            any("UNDECIDED" in e for e in errors), errors
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "backup_rules_check.py",
                    "--expect-root",
                    backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION,
                    path,
                ]
            )
        self.assertEqual(1, code)
        self.assertIn("cross-platform-transfer", buf_err.getvalue())

    def test_cross_platform_transfer_inside_legal_section_fails(self) -> None:
        # The element nested INSIDE a legal section is an unknown rule child
        # (FullBackup validates every START_TAG) - the depth-agnostic walk
        # already fails it; pin that sibling shape explicitly.
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<data-extraction-rules>\n"
            "    <cloud-backup>\n"
            f"{self._ALL_EXCLUDES}"
            "        <cross-platform-transfer transferId=\"x\" />\n"
            "    </cloud-backup>\n"
            f"    <device-transfer>\n{self._ALL_EXCLUDES}    </device-transfer>\n"
            "</data-extraction-rules>\n"
        )
        path = self._write("data_extraction_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(
            any("<cloud-backup> unknown element <cross-platform-transfer>" in e for e in errors),
            errors,
        )

    def test_two_platform_sections_alone_still_pass(self) -> None:
        # The incumbent two-mode matrix stays a positive control after the
        # third-section refusal lands (cloud-backup + device-transfer only).
        path = self._write(
            "data_extraction_rules.xml",
            self._data_extraction(self._ALL_EXCLUDES, self._ALL_EXCLUDES),
        )
        self.assertEqual(self._check(path), [])

    def test_rule_path_containing_parent_dir_is_refused(self) -> None:
        # Android's rules schema resolves paths INSIDE the rule domain (no
        # parent-directory references); the gate cannot verify such a path's
        # runtime behavior, so ANY ".." in a rule path refuses the file,
        # including a standalone "foo..bar" spelling (substring semantics,
        # deterministic and stricter than runtime).
        shapes = {
            "dotdot-lead": '        <exclude domain="file" path="../evil.xml" />\n',
            "dotdot-mid": '        <include domain="file" path="a/../b" />\n',
            "dotdot-tail-only": '        <exclude domain="file" path="notes..bak" />\n',
        }
        for name, extra in shapes.items():
            with self.subTest(shape=name):
                path = self._write(
                    "backup_rules.xml", self._full_backup(self._ALL_EXCLUDES + extra)
                )
                errors = self._check(path)
                self.assertTrue(
                    any('contains ".."' in e for e in errors), errors
                )
                # Both include and exclude shapes carry the same message shape.
                if extra.startswith('        <include'):
                    self.assertTrue(
                        any("<include> path" in e for e in errors), errors
                    )

    def test_rule_path_traversal_rejected_in_branches_too(self) -> None:
        # The refusal walks data-extraction branches with the same branch
        # label discipline as the other rule checks.
        body = self._data_extraction(
            '        <exclude domain="file" path="sub/../../escape" />\n',
            self._ALL_EXCLUDES,
        )
        path = self._write("data_extraction_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(
            any("<cloud-backup>" in e and 'contains ".."' in e for e in errors),
            errors,
        )

    def test_exact_excludes_mode_flags_set_differences(self) -> None:
        # Artifact-level authority: `exact_excludes=True` (the compiled-bytes
        # scan) demands the exact REQUIRED_EXCLUDES set - a missing entry
        # fails as usual AND an unexpected extra is a set difference too,
        # order-free. Source-level default stays presence-only (over-shielding
        # tolerated at source: the exact-set pin is deliberately compiled-byte
        # only).
        tree = ET.fromstring(
            self._full_backup(
                self._ALL_EXCLUDES
                + '        <exclude domain="file" path="unrelated.xml" />\n'
            )
        )
        self.assertEqual(
            backup_rules_check.validate_tree(tree, "x", "full-backup-content"), []
        )
        errors = backup_rules_check.validate_tree(
            tree, "x", "full-backup-content", exact_excludes=True
        )
        self.assertTrue(
            any("unexpected exclude domain=file path=unrelated.xml" in e for e in errors),
            errors,
        )
        exact_subset = ET.fromstring(
            self._full_backup(
                self._ALL_EXCLUDES.replace(
                    '        <exclude domain="database" path="librepocket_vault-wal" />\n',
                    "",
                )
            )
        )
        missing_present = backup_rules_check.validate_tree(
            exact_subset, "x", "full-backup-content", exact_excludes=True
        )
        self.assertTrue(
            any("missing exclude domain=database path=librepocket_vault-wal" in e
                for e in missing_present),
            missing_present,
        )
        # Reordered but complete set: still exact (order-independent).
        exclude_lines = self._ALL_EXCLUDES.splitlines(keepends=True)
        reordered = ET.fromstring(
            self._full_backup("".join(reversed(exclude_lines)))
        )
        self.assertEqual(
            backup_rules_check.validate_tree(
                reordered, "x", "full-backup-content", exact_excludes=True
            ),
            [],
        )

    def test_validate_tree_matches_validate_on_representative_shapes(self) -> None:
        # The artifact scanner runs validate_tree directly; the file loader
        # must agree with it for at least one good and one bad tree so the
        # two surfaces can never diverge.
        good = self._full_backup(self._ALL_EXCLUDES)
        bad = self._data_extraction(self._ALL_EXCLUDES, "")
        for body, tag, hint in (
            (good, "backup_rules.xml", backup_rules_check.EXPECTED_ROOT_BACKUP_RULES),
            (bad, "data_extraction_rules.xml", backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION),
        ):
            path = self._write(tag, body)
            self.assertEqual(
                backup_rules_check.validate(path, hint),
                backup_rules_check.validate_tree(
                    ET.parse(path).getroot(), path, hint
                ),
            )

    # ---- 檔名↔root 綁定（swapped / wrong root 不可 false PASS）----

    def test_swapped_roots_are_rejected(self) -> None:
        # The reviewer's reproduced counterexample: the two files' contents
        # swapped (correct excludes inside the *other* schema each). With an
        # expected_root hint both must FAIL instead of silently PASSing.
        backup_path = self._write(
            "backup_rules.xml",
            self._data_extraction(self._ALL_EXCLUDES, self._ALL_EXCLUDES),
        )
        data_path = self._write(
            "data_extraction_rules.xml", self._full_backup(self._ALL_EXCLUDES)
        )
        errors = backup_rules_check.validate(
            backup_path, backup_rules_check.EXPECTED_ROOT_BACKUP_RULES
        )
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("expected root <full-backup-content>", errors[0], errors)
        errors = backup_rules_check.validate(
            data_path, backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION
        )
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("expected root <data-extraction-rules>", errors[0], errors)

    def test_single_wrong_root_is_rejected(self) -> None:
        # A single misattributed file (data-extraction tree under the
        # backup_rules.xml name) must also fail with the expected-root hint.
        path = self._write(
            "backup_rules.xml",
            self._data_extraction(self._ALL_EXCLUDES, self._ALL_EXCLUDES),
        )
        errors = backup_rules_check.validate(
            path, backup_rules_check.EXPECTED_ROOT_BACKUP_RULES
        )
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("expected root <full-backup-content>", errors[0], errors)

    def test_cli_without_expected_root_fails_closed_exit_2(self) -> None:
        # The swapped-root false-PASS repro surfaces through every hint-free
        # call surface. Hence validate(path) now REQUIRES the expected root and
        # the CLI fails closed (exit 2) without --expect-root — the reviewer's
        # swapped contents can no longer pass outside the shell gate either.
        backup_path = self._write(
            "backup_rules.xml",
            self._data_extraction(self._ALL_EXCLUDES, self._ALL_EXCLUDES),
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(["backup_rules_check.py", backup_path])
        self.assertEqual(2, code)
        self.assertIn("usage", buf_err.getvalue())
        # validate itself: a keyword/inline None is no longer a valid contract.
        with self.assertRaises(TypeError):
            backup_rules_check.validate(backup_path)  # type: ignore[misc]  # pylint: disable=no-value-for-parameter

    # ---- Android runtime 嚴格性映射（FullBackup.validateInnerTagContents）----

    def test_unknown_child_tag_in_full_backup_content_is_caught(self) -> None:
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<full-backup-content>\n"
            f"{self._ALL_EXCLUDES}"
            '    <future-tag domain="database" path="somewhere" />\n'
            "</full-backup-content>\n"
        )
        path = self._write("backup_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(any("unknown element <future-tag>" in e for e in errors), errors)

    def test_nested_unknown_tag_inside_legal_rule_is_caught(self) -> None:
        # AOSP parseRules walks every START_TAG at ANY depth inside a section
        # and validateInnerTagContents throws on the nested unknown tag too -
        # so a legal include wrapping an unknown child must FAIL (it would
        # silently disable backup at runtime), not PASS.
        nested = (
            f"{self._ALL_EXCLUDES}"
            '    <include domain="file" path="sessions.txt">\n'
            "        <future-tag />\n"
            "    </include>\n"
        )
        path = self._write("backup_rules.xml", self._full_backup(nested))
        errors = self._check(path)
        self.assertTrue(
            any("unknown element <future-tag>" in e for e in errors), errors
        )
        # Same shape inside a data-extraction branch.
        nested_branch = (
            f"{self._ALL_EXCLUDES}"
            '    <exclude domain="file" path="sessions.txt">\n'
            "        <odd-wrapper />\n"
            "    </exclude>\n"
        )
        path = self._write(
            "data_extraction_rules.xml",
            self._data_extraction(nested_branch, self._ALL_EXCLUDES),
        )
        errors = self._check(path)
        self.assertTrue(
            any("unknown element <odd-wrapper>" in e for e in errors), errors
        )

    def test_tools_attributes_do_not_count_toward_runtime_limit(self) -> None:
        # aapt strips tools:* before the runtime sees the resource: an exclude
        # with 2 real attrs + tools:ignore is runtime-valid (2 <= 2) and must
        # PASS, while 3 real attrs still FAIL (mirror the runtime count).
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<full-backup-content xmlns:tools="http://schemas.android.com/tools">\n'
            f"{self._ALL_EXCLUDES}"
            '    <exclude domain="file" path="sessions.txt" tools:ignore="FullBackupContent" />\n'
            '    <exclude domain="database" path="notes.db" tools:ignore="x" />\n'
            "</full-backup-content>\n"
        )
        path = self._write("backup_rules.xml", body)
        self.assertEqual(self._check(path), [])

        over = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<full-backup-content xmlns:tools="http://schemas.android.com/tools">\n'
            f"{self._ALL_EXCLUDES}"
            '    <exclude domain="file" path="sessions.txt" extra="flag" tools:ignore="x" />\n'
            "</full-backup-content>\n"
        )
        path = self._write("backup_rules.xml", over)
        errors = self._check(path)
        self.assertTrue(any("has 3 attributes" in e for e in errors), errors)

    def test_pathological_nesting_surfaces_as_clean_depth_fail(self) -> None:
        # _MAX_RULE_NEST_DEPTH bounds the walk: a 70-deep include/exclude
        # suffix must come out as a clean "exceeds the maximum rule nesting
        # depth" FAIL (exit 1 with actionable stderr), never an uncaught
        # RecursionError traceback.
        includes = "".join(
            f'<include domain="file" path="p{i}">' for i in range(70)
        )
        closes = "</include>" * 70
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<full-backup-content>\n"
            f"{self._ALL_EXCLUDES}"
            f"    {includes}<future-tag />{closes}\n"
            "</full-backup-content>\n"
        )
        path = self._write("backup_rules.xml", body)
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "backup_rules_check.py",
                    "--expect-root",
                    backup_rules_check.EXPECTED_ROOT_BACKUP_RULES,
                    path,
                ]
            )
        self.assertEqual(1, code)
        self.assertIn("exceeds the maximum rule nesting depth", buf_err.getvalue())

    def test_doctype_attlist_default_injection_refused_at_source(self) -> None:
        # expat resolves internal-subset ATTLIST DEFAULTS into the parsed tree
        # (measured): `<!ATTLIST exclude domain CDATA "sharedpref">` fabricates
        # the domain value of an <exclude> that carries only path=, composing
        # one REQUIRED key out of injected data. Without the DOCTYPE guard this
        # fixture would PASS the exact-set gate with data the source never
        # carried; the guard must refuse it fail-closed BEFORE the fabricated
        # values can count (and NOT because of a coincidental required-attr
        # failure - the control below shows the attribute-less exclude alone
        # produces the "missing exclude" structural error, while the DOCTYPE
        # shape fails with the DOCTYPE message instead).
        fours = (
            '        <exclude domain="database" path="librepocket_vault" />\n'
            '        <exclude domain="database" path="librepocket_vault-wal" />\n'
            '        <exclude domain="database" path="librepocket_vault-shm" />\n'
            '        <exclude domain="file" path="librepocket_keysets" />\n'
        )
        doctype_body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!DOCTYPE full-backup-content [\n'
            '<!ATTLIST exclude domain CDATA "sharedpref">\n'
            ']>\n'
            "<full-backup-content>\n"
            '  <exclude path="librepocket_keys.xml" />\n'
            f"{fours}"
            "</full-backup-content>\n"
        )
        # Document the expat behavior the guard exists for.
        fabricated = ET.fromstring(doctype_body)
        self.assertEqual(fabricated[0].get("domain"), "sharedpref")
        # Control: the SAME shape without the internal subset fails on the
        # missing exclude (NOT with the DOCTYPE message).
        control_path = self._write(
            "control.xml",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<full-backup-content>\n"
            '  <exclude path="librepocket_keys.xml" />\n'
            f"{fours}"
            "</full-backup-content>\n",
        )
        errors = backup_rules_check.validate(
            control_path, backup_rules_check.EXPECTED_ROOT_BACKUP_RULES
        )
        self.assertTrue(
            any("missing exclude domain=sharedpref" in e for e in errors), errors
        )
        path = self._write("doctyped.xml", doctype_body)
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "backup_rules_check.py",
                    "--expect-root",
                    backup_rules_check.EXPECTED_ROOT_BACKUP_RULES,
                    path,
                ]
            )
        self.assertEqual(1, code)
        stderr = buf_err.getvalue()
        self.assertIn("DOCTYPE declarations are rejected", stderr)
        self.assertIn("fabricate rule data", stderr)
        self.assertNotIn("missing exclude", stderr)

    def test_fifo_rules_file_is_refused_immediately(self) -> None:
        # A checker handed a FIFO must refuse without ever blocking: the
        # loader opens the path O_RDONLY|O_NONBLOCK and proves S_ISREG on the
        # OPEN FILE's own descriptor via os.fstat before any read, so the
        # refusal fires on the SAME inode that would be read. main() exit
        # code and message only, no timing claim.
        # Post-fix note: the retired stat-then-open load had a swap window in
        # which a FIFO appearing between os.stat and open() wedged the
        # checker's open() until a writer appeared - open/fstat/read now pin
        # ONE fd, so that mkfifo-in-window race is structurally impossible.
        fifo = self.tmp / "fifo.xml"
        os.mkfifo(fifo)
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "backup_rules_check.py",
                    "--expect-root",
                    backup_rules_check.EXPECTED_ROOT_BACKUP_RULES,
                    str(fifo),
                ]
            )
        self.assertEqual(1, code)
        self.assertIn("not a regular file", buf_err.getvalue())

    def test_directory_rules_file_refused_as_non_regular(self) -> None:
        # Directory analog of the FIFO pin (deterministic, no race): opening
        # a directory read-only never blocks, and the loader's fstat proof on
        # the OPEN file descriptor lands in the SAME structured non-regular
        # fail lane with exit 1 - a directory can never reach ET.parse as a
        # stream source.
        directory = self.tmp / "rules-dir"
        directory.mkdir()
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "backup_rules_check.py",
                    "--expect-root",
                    backup_rules_check.EXPECTED_ROOT_BACKUP_RULES,
                    str(directory),
                ]
            )
        self.assertEqual(1, code)
        self.assertIn("not a regular file", buf_err.getvalue())

    def test_missing_rules_file_reports_unreadable(self) -> None:
        # The open-first loader keeps the historical "unreadable" lane for a
        # missing path (os.open's OSError), with the same harness exit code.
        missing = str(self.tmp / "no-such.xml")
        errors = backup_rules_check.validate(
            missing, backup_rules_check.EXPECTED_ROOT_BACKUP_RULES
        )
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("unreadable: ", errors[0])

    def test_noncanonical_hint_matching_root_reaches_defensive_branch(self) -> None:
        # validate() requires the hint; a caller may still pass a NON-canonical
        # hint (not one of the two documented roots) that happens to equal the
        # file's root tag — the binding check passes and the defensive
        # "unexpected root element" branch rejects it (fail-closed).
        path = self._write("weird.xml", '<something-else></something-else>')
        errors = backup_rules_check.validate(path, "something-else")
        self.assertTrue(
            any("unexpected root element <something-else>" in e for e in errors),
            errors,
        )

    def test_unknown_child_tag_in_branch_is_caught(self) -> None:
        body = self._data_extraction(
            "    <wrapper-domain>\n"
            f"{self._ALL_EXCLUDES}"
            "    </wrapper-domain>\n",
            "    <wrapper>\n    </wrapper>\n",
        )
        path = self._write("data_extraction_rules.xml", body)
        errors = self._check(path)
        self.assertTrue(
            any("<cloud-backup> unknown element <wrapper-domain>" in e for e in errors),
            errors,
        )
        self.assertTrue(
            any("<device-transfer> unknown element <wrapper>" in e for e in errors),
            errors,
        )

    def test_exclude_with_extra_attribute_is_caught(self) -> None:
        # <exclude> with 3 attributes would make Android's runtime parser
        # throw (backup silently disabled): the gate must reject, not PASS.
        excludes = self._ALL_EXCLUDES.replace(
            '        <exclude domain="file" path="librepocket_keysets" />\n',
            '        <exclude domain="file" path="librepocket_keysets" foo="bar" />\n',
        )
        path = self._write("backup_rules.xml", self._full_backup(excludes))
        errors = self._check(path)
        self.assertTrue(
            any("<exclude> has 3 attributes" in e for e in errors), errors
        )

    def test_include_with_four_attributes_is_caught(self) -> None:
        includes = self._ALL_EXCLUDES.replace("exclude", "include").replace(
            '        <include domain="sharedpref" path="librepocket_keys.xml" />\n',
            '        <include domain="sharedpref" path="librepocket_keys.xml" a="1" b="2" />\n',
        )
        path = self._write("backup_rules.xml", self._full_backup(includes))
        errors = self._check(path)
        self.assertTrue(
            any("<include> has 4 attributes" in e for e in errors), errors
        )

    def test_extra_legal_include_and_exclude_rules_are_allowed(self) -> None:
        # Unrelated-but-valid rules must NOT break the gate (positive control):
        # <include domain+path+requireFlags> = 3 attrs, extra <exclude> = 2 attrs.
        extra = (
            f"{self._ALL_EXCLUDES}"
            '        <include domain="database" path="notes.db" requireFlags="deviceProtection" />\n'
            '        <exclude domain="sharedpref" path="unrelated.xml" />\n'
        )
        path = self._write("backup_rules.xml", self._full_backup(extra))
        self.assertEqual(self._check(path), [])

    def test_malformed_xml_is_caught(self) -> None:
        # The bot's suggestion: a malformed rules file must produce exactly one
        # "invalid XML" error, not PASS.
        path = self._write(
            "backup_rules.xml",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<full-backup-content>\n"
            '  <exclude domain="database" path="unclosed\n',
        )
        errors = self._check(path)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("invalid XML", errors[0], errors)

    # ---- CLI 層（fail-closed）----

    def test_cli_no_args_is_usage_error_exit_2(self) -> None:
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf_out), contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(["backup_rules_check.py"])
        self.assertEqual(2, code, buf_err.getvalue())
        self.assertIn("usage", buf_err.getvalue())

    def test_cli_unknown_option_is_usage_error_exit_2(self) -> None:
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                ["backup_rules_check.py", "--root", "full-backup-content"]
            )
        self.assertEqual(2, code, buf_err.getvalue())
        self.assertIn("unknown option --root", buf_err.getvalue())

    def test_cli_expect_root_value_required_exit_2(self) -> None:
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                ["backup_rules_check.py", "--expect-root"]
            )
        self.assertEqual(2, code, buf_err.getvalue())

    def test_cli_missing_file_fails_closed_exit_1(self) -> None:
        # With the required hint the missing file surfaces as "unreadable"
        # exit 1; hint-free invocation is exit 2 (pinned above).
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "backup_rules_check.py",
                    "--expect-root",
                    backup_rules_check.EXPECTED_ROOT_BACKUP_RULES,
                    str(self.tmp / "no-such.xml"),
                ]
            )
        self.assertEqual(1, code)
        self.assertIn("unreadable", buf_err.getvalue())

    def test_cli_subprocess_smoke_pass_fail_usage(self) -> None:
        # In-process main() tests cover the argv parsing; run the REAL script
        # once via subprocess to pin shebang/__main__/exit-code plumbing
        # (0 pass / 1 fail / 2 usage), which in-process calls never execute.
        good = self._write(
            "backup_rules.xml", self._full_backup(self._ALL_EXCLUDES)
        )
        cmd = [sys.executable, str(ROOT / "scripts" / "backup_rules_check.py")]
        rc_ok = subprocess.run(
            cmd + ["--expect-root", backup_rules_check.EXPECTED_ROOT_BACKUP_RULES, good],
            capture_output=True, text=True,
        )
        self.assertEqual(0, rc_ok.returncode, rc_ok.stderr)
        rc_fail = subprocess.run(
            cmd + ["--expect-root", backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION, good],
            capture_output=True, text=True,
        )
        self.assertEqual(1, rc_fail.returncode)
        self.assertIn("expected root <data-extraction-rules>", rc_fail.stderr)
        rc_usage = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(2, rc_usage.returncode)
        self.assertIn("usage", rc_usage.stderr)

    def test_cli_wrong_expected_root_exits_1(self) -> None:
        path = self._write(
            "backup_rules.xml",
            self._data_extraction(self._ALL_EXCLUDES, self._ALL_EXCLUDES),
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = backup_rules_check.main(
                [
                    "backup_rules_check.py",
                    "--expect-root",
                    "full-backup-content",
                    path,
                ]
            )
        self.assertEqual(1, code)
        self.assertIn("expected root <full-backup-content>", buf_err.getvalue())


class PermissionRemovalValidator(unittest.TestCase):
    """Play permission blacklist via the structured manifest scanner.

    The old gate filtered each manifest body line-by-line
    (``grep -vE "tools:node=..."`` then grepped tokens), so a LEGAL multi-line
    element whose ``android:name`` and ``tools:node="remove"`` sit on
    different lines left the name line unfiltered and false-FAILED the gate,
    while a whole-body grep also matched text inside XML comments.

    The structured scan uses the same merger-marker machinery as the
    manifest binding checks: only a real (comment-proof) element whose
    android:name carries a blacklisted token, AND that carries no
    ``tools:node="remove"`` ON ITSELF, is a violation.
    """

    _TOOLS_NS = "http://schemas.android.com/tools"
    _ANDROID_NS = "http://schemas.android.com/apk/res/android"

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)

    def _write(self, name: str, body: str) -> str:
        path = self.tmp / name
        path.write_text(body, encoding="utf-8")
        return str(path)

    def _manifest(self, body: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<manifest xmlns:android="{self._ANDROID_NS}"'
            f' xmlns:tools="{self._TOOLS_NS}">\n'
            f"{body}"
            "</manifest>\n"
        )

    _BLACKLIST = ("SEND_SMS", "RECEIVE_SMS", "READ_SMS", "BIND_VPN_SERVICE")

    def test_multiline_marked_removal_is_accepted(self) -> None:
        # The original false-FAIL repro: android:name and tools:node sit on
        # separate lines of ONE legal element; a line filter sees the name
        # line unmarked. The element-aware scan accepts it.
        body = self._manifest(
            "    <uses-permission\n"
            '        android:name="android.permission.SEND_SMS"\n'
            '        tools:node="remove" />\n'
        )
        path = self._write("main.xml", body)
        self.assertEqual(
            backup_rules_check.validate_permission_removals(path, self._BLACKLIST),
            [],
        )

    def test_single_line_marked_removal_is_accepted(self) -> None:
        body = self._manifest(
            '    <uses-permission android:name="android.permission.BIND_VPN_SERVICE"'
            ' tools:node="remove" />\n'
        )
        path = self._write("main.xml", body)
        self.assertEqual(
            backup_rules_check.validate_permission_removals(path, self._BLACKLIST),
            [],
        )

    def test_sdk23_variant_tag_is_scanned_too(self) -> None:
        # uses-permission-sdk-23 declares the same permission on API 23+;
        # scanning only the plain tag would shrink the grep catch-universe.
        body = self._manifest(
            '    <uses-permission-sdk-23\n'
            '        android:name="android.permission.RECEIVE_SMS"\n'
            '        tools:node="remove" />\n'
        )
        path = self._write("main.xml", body)
        self.assertEqual(
            backup_rules_check.validate_permission_removals(path, self._BLACKLIST),
            [],
        )
        unmarked = body.replace(' tools:node="remove" />\n', " />\n")
        path = self._write("sdk23-unmarked.xml", unmarked)
        errors = backup_rules_check.validate_permission_removals(path, self._BLACKLIST)
        self.assertTrue(
            any("uses-permission-sdk-23" in e for e in errors), errors
        )

    def test_unmarked_blacklisted_element_is_rejected(self) -> None:
        body = self._manifest(
            '    <uses-permission android:name="android.permission.SEND_SMS" />\n'
        )
        path = self._write("main.xml", body)
        errors = backup_rules_check.validate_permission_removals(path, self._BLACKLIST)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn(
            "blacklisted permission SEND_SMS", errors[0], errors
        )
        self.assertIn('without tools:node="remove"', errors[0], errors)

    def test_wrong_marker_still_rejected(self) -> None:
        # The merger deletes a whole element ONLY with tools:node="remove";
        # tools:remove targets attributes, tools:node="replace"/"merge" keep
        # (or re-declare in a more specific way) the declaration.
        for marker, label in (
            ('        tools:remove="android:name" />\n', "tools:remove-attr"),
            ('        tools:node="merge" />\n', "merge-marker"),
        ):
            with self.subTest(label=label):
                body = self._manifest(
                    "    <uses-permission\n"
                    '        android:name="android.permission.READ_SMS"\n'
                    + marker
                )
                path = self._write(f"{label}.xml", body)
                errors = backup_rules_check.validate_permission_removals(
                    path, self._BLACKLIST
                )
                self.assertTrue(
                    any("blacklisted permission READ_SMS" in e for e in errors),
                    errors,
                )

    def test_comment_wrapped_element_is_invisible(self) -> None:
        # A comment (including a blacklisted literal inside it) cannot
        # declare the permission: ElementTree drops it, and so must the scan
        # (the swapped legacy grep false-tokened here).
        body = self._manifest(
            "    <!-- <uses-permission android:name=\"android.permission.SEND_SMS\" /> -->\n"
        )
        path = self._write("main.xml", body)
        self.assertEqual(
            backup_rules_check.validate_permission_removals(path, self._BLACKLIST),
            [],
        )

    def test_unblacklisted_or_unlisted_names_never_flag(self) -> None:
        body = self._manifest(
            '    <uses-permission android:name="android.permission.INTERNET" />\n'
            '    <uses-permission android:name="android.permission.SEND_SMS_EXTENDED"'
            ' tools:node="remove" />\n'
        )
        path = self._write("main.xml", body)
        self.assertEqual(
            backup_rules_check.validate_permission_removals(path, self._BLACKLIST),
            [],
        )

    def test_unnamed_permission_element_is_uncharged_error(self) -> None:
        # A permission element whose name cannot be resolved cannot prove it
        # is safe (or blacklisted) - refuse, rather than green-light a shape
        # the merger behavior of is unresolved.
        body = self._manifest(
            '    <uses-permission tools:node="remove" />\n'
        )
        path = self._write("main.xml", body)
        errors = backup_rules_check.validate_permission_removals(path, self._BLACKLIST)
        self.assertTrue(
            any("carries no android:name" in e for e in errors), errors
        )

    def test_non_manifest_root_and_invalid_xml_fail_closed(self) -> None:
        path = self._write("rules.xml", "<resources>\n</resources>\n")
        errors = backup_rules_check.validate_permission_removals(path, self._BLACKLIST)
        self.assertTrue(any("needs a <manifest> root" in e for e in errors), errors)
        path = self._write("broken.xml", "<manifest xmlns:android='x'>\n")
        errors = backup_rules_check.validate_permission_removals(path, self._BLACKLIST)
        self.assertTrue(any("invalid XML" in e for e in errors), errors)

    # -- CLI 層（--check-permission-removals exit-code matrix）--

    def test_cli_exit_codes_and_mutual_exclusion(self) -> None:
        marked = self._manifest(
            '    <uses-permission android:name="android.permission.SEND_SMS"'
            ' tools:node="remove" />\n'
        )
        unmarked = self._manifest(
            '    <uses-permission android:name="android.permission.SEND_SMS" />\n'
        )
        good = self._write("marked.xml", marked)
        bad = self._write("unmarked.xml", unmarked)
        manifest_for_other_mode = self._write("manifest.xml", marked)
        # PASS / real-FAIL / hint-free exit-code matrix.
        self.assertEqual(
            backup_rules_check.main(
                ["x", "--check-permission-removals", "SEND_SMS", good]
            ),
            0,
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(
                backup_rules_check.main(
                    ["x", "--check-permission-removals", "SEND_SMS", bad]
                ),
                1,
            )
        self.assertIn('without tools:node="remove"', buf_err.getvalue())
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(backup_rules_check.main(["x", manifest_for_other_mode]), 2)
        self.assertIn("usage", buf_err.getvalue())
        # Exactly one command mode at a time.
        for argv in (
            "--check-manifest",
            "--expect-root full-backup-content",
        ):
            buf_err = io.StringIO()
            with contextlib.redirect_stderr(buf_err):
                self.assertEqual(
                    backup_rules_check.main(
                        ["x", *argv.split(), "--check-permission-removals", "SEND_SMS",
                         manifest_for_other_mode]
                    ),
                    2,
                )
            self.assertIn(
                "mutually exclusive", buf_err.getvalue(), argv
            )
        # Missing value / duplicate / empty blacklist / require-both alone.
        for argv, expected in (
            (["x", "--check-permission-removals"], "requires a"),
            (
                ["x", "--check-permission-removals", "SEND_SMS", bad,
                 "--check-permission-removals", "RECEIVE_SMS"],
                "duplicate --check-permission-removals",
            ),
            (["x", "--check-permission-removals", "", bad], "blacklist is empty"),
            (["x", "--check-permission-removals", ",,", bad], "blacklist is empty"),
            (
                ["x", "--check-permission-removals", "SEND_SMS",
                 "--require-both", bad],
                "--require-both requires --check-manifest",
            ),
        ):
            buf_err = io.StringIO()
            with contextlib.redirect_stderr(buf_err):
                self.assertEqual(backup_rules_check.main(argv), 2, argv)
            self.assertIn(expected, buf_err.getvalue(), argv)
        # The real gate's CSV carries multi-token slices; token = substring
        # of android:name (the gate passes bare classifier tokens).
        multi = self._manifest(
            '    <uses-permission android:name="android.permission.READ_SMS"'
            ' tools:node="remove" />\n'
        )
        path = self._write("multi.xml", multi)
        self.assertEqual(
            backup_rules_check.main(
                ["x", "--check-permission-removals",
                 "SEND_SMS,RECEIVE_SMS,READ_SMS,BIND_VPN_SERVICE", path]
            ),
            0,
        )
        # A whitespace CSV must still MATCH: storing the unstripped
        # " RECEIVE_SMS" used to be a token that is a substring of no
        # android:name, so an unmarked RECEIVE_SMS element silently
        # PASSED (fail-open). Strip-before-store makes this same shape
        # fail closed with the real marker message.
        path = self._write(
            "whitespace-csv.xml",
            self._manifest(
                '    <uses-permission android:name="android.permission.RECEIVE_SMS" />\n'
            ),
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(
                backup_rules_check.main(
                    ["x", "--check-permission-removals", "SEND_SMS, RECEIVE_SMS", path]
                ),
                1,
            )
        self.assertIn(
            "blacklisted permission RECEIVE_SMS", buf_err.getvalue()
        )


class BackupAnchorSync(unittest.TestCase):
    """Pin REQUIRED_EXCLUDES to BackupPolicy.KEY_FILES_EXCLUDED (drift guard).

    Nothing else ties the Python tuple list to the Kotlin truth source: adding
    a 6th key file to Kotlin/XML would otherwise leave this gate under-checking
    with zero signal.
    """

    def test_required_excludes_match_backup_policy_kotlin(self) -> None:
        kb = ROOT / "app" / "src" / "main" / "java" / "dev" / "librepocket" / "backup" / "BackupPolicy.kt"
        self.assertTrue(kb.exists(), kb)
        text = kb.read_text(encoding="utf-8")
        block = re.search(
            r"KEY_FILES_EXCLUDED[^=]*=\s*listOf\(([^)]*)\)", text, re.DOTALL
        )
        self.assertIsNotNone(block, text)
        body = block.group(1)
        # Every quoted literal inside the block must be a domain/path pair;
        # otherwise a non-literal entry (e.g. concatenated constants) would
        # silently go unmatched and drift would be undetectable.
        all_literals = re.findall(r'"([^"]+)"', body)
        kotlin_entries = re.findall(r'"(sharedpref|database|file|device_root)/([^"]+)"', body)
        self.assertEqual(
            sorted((d, p) for d, p in kotlin_entries),
            sorted(backup_rules_check.REQUIRED_EXCLUDES),
        )
        for literal in all_literals:
            self.assertRegex(
                literal,
                r"^(sharedpref|database|file|device_root)/.+$",
                f"non-literal or malformed entry in BackupPolicy: {literal}",
            )


class BackupRulesGateWiring(unittest.TestCase):
    """Pin the shell gate so the structured validator is actually used."""

    def test_gate_invokes_structured_validator(self) -> None:
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn("backup_rules_check.py", text)

    def test_gate_pins_expected_roots(self) -> None:
        # The gate must pass the file↔root binding explicitly: one call per
        # file with --expect-root (a swapped file must not false-PASS), with
        # each root PAIRED to its specific file (swapped arguments between the
        # two invocations would otherwise survive the pin).
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn(
            f"--expect-root {backup_rules_check.EXPECTED_ROOT_BACKUP_RULES}", text
        )
        self.assertIn(
            f"--expect-root {backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION}", text
        )
        # Argument pairing: each fixed-path invocation carries the right root.
        self.assertRegex(
            text,
            r"--expect-root\s+" + re.escape(backup_rules_check.EXPECTED_ROOT_BACKUP_RULES)
            + r"[\s\\]+\"\$ROOT/app/src/main/res/xml/backup_rules\.xml\"",
        )
        self.assertRegex(
            text,
            r"--expect-root\s+" + re.escape(backup_rules_check.EXPECTED_ROOT_DATA_EXTRACTION)
            + r"[\s\\]+\"\$ROOT/app/src/main/res/xml/data_extraction_rules\.xml\"",
        )

    def test_gate_deep_scans_semantic_rule_shapes(self) -> None:
        # The overlay Sweep must match semantic shapes (any *backup*rules*.xml /
        # *data_extraction*rules*.xml), not exact filenames — an overlay alias
        # (backup_rules_foss.xml with the WRONG root) must still reach the
        # validator. Also pins that a find failure fails closed: find must run
        # OUTSIDE the sort pipeline (POSIX assignment status = last pipeline
        # element) and its non-pipelined status is guarded with an explicit fail.
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn("-name '*backup*rules*.xml'", text)
        self.assertIn("-name '*data_extraction*rules*.xml'", text)
        self.assertIn("case \"$f\" in\n        *backup*rules*.xml)", text)
        # find runs OUTSIDE the sort pipeline: the LIST assignment contains the
        # find substitution but no "| sort", and the explicit fail guard rides
        # directly on that assignment's status (POSIX assignment status = last
        # pipeline element, so find-in-pipeline would mask its own failure).
        find_line = next(
            line for line in text.splitlines() if "BACKUP_RULES_LIST=" in line
        )
        self.assertIn("$(find ", find_line)
        self.assertNotIn("| sort", find_line)
        # The explicit fail guard rides DIRECTLY on the non-pipelined find
        # assignment's status (line-anchored: whatever the argument-list
        # escapes look like, `||` and `fail` must follow on that assignment).
        lines = text.splitlines()
        guard_idx = next(
            i
            for i, l in enumerate(lines)
            if "fail 'deep-scan of backup rules resources failed'" in l
        )
        self.assertIn("||", lines[guard_idx - 1])
        self.assertIn(
            'BACKUP_RULES_FILES="$(printf \'%s\\n\' "$BACKUP_RULES_LIST" | sort)"',
            text,
        )

    def test_gate_nsc_scan_is_guarded_find(self) -> None:
        # The all-flavor network_security_config scan must fail closed the
        # same way the backup deep scan does: a non-pipelined guarded find
        # (assignment status is find's own), then a non-empty guard. Without
        # this pin the e2e tests cannot tell WHICH guarded scan surfaced a
        # find failure (both print named "scan failed" messages).
        text = POLICY_CHECK.read_text(encoding="utf-8")
        lines = text.splitlines()
        nsc_assign = next(
            (i for i, line in enumerate(lines) if "NSC_LIST=" in line),
            None,
        )
        self.assertIsNotNone(nsc_assign, text)
        self.assertIn("$(find ", lines[nsc_assign])
        self.assertNotIn("| sort", lines[nsc_assign])
        guard = next(
            (
                i
                for i in range(nsc_assign + 1, min(nsc_assign + 3, len(lines)))
                if "fail 'network security config scan failed'" in lines[i]
            ),
            None,
        )
        self.assertIsNotNone(guard, text)
        self.assertIn("||", lines[guard - 1])

    def test_gate_binds_manifest_attrs_via_structured_check(self) -> None:
        # Runtime binding must be enforced by the structured XML checker
        # (comment-escape proof: bare-text grep PASSes a manifest whose
        # attributes are gone with the survivors inside a comment). The gate
        # requires BOTH attrs on the main manifest and only equality-when-set
        # on flavor overlays; no raw grep for these attrs may survive.
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn('--check-manifest --require-both "$MAIN_MANIFEST"', text)
        self.assertIn('for m in "$ROOT"/app/src/*/AndroidManifest.xml', text)
        self.assertIn("--check-manifest \"$m\"", text)
        self.assertRegex(
            text,
            r"python3 .+backup_rules_check\.py.+--check-manifest",
        )
        # The old raw-grep enforcer is retired for these attrs.
        self.assertNotIn("grep -q 'android:fullBackupContent", text)
        self.assertNotIn("grep -q 'android:dataExtractionRules", text)

    def test_gate_does_not_use_raw_grep_for_excludes(self) -> None:
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertNotRegex(text, r"grep\b[^|\n]*\b-q\b[^|\n]*\bdomain=")

    def test_gate_enforces_play_blacklist_via_structured_scan(self) -> None:
        # Play permission blacklist: the structured element-aware scan
        # (validate_permission_removals) replaced the retired per-line
        # grep -vE filter (a legal multi-line element with tools:node on
        # its own line false-FAILED it). The marker machinery rides the
        # same script entry that already validates manifest bindings.
        # P2-1 round 2: the scan is now fed from a per-mode dispatch that
        # maps EVERY flavor to its blacklist + overlay pair; the play
        # branch keeps the full six-token CSV (common + play-only a11y).
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn(
            '--check-permission-removals "$PERM_TOKENS"', text
        )
        # The play branch of the dispatch must keep the full-play token set
        # and the play overlay dir (a11y-token enforcement stays here).
        self.assertRegex(
            text,
            r'    play\)\n'
            r'        PERM_TOKENS="\$BLACKLIST_PERMS_CSV"\n'
            r'        FLAVOR_DIR="play"\n'
            r'        ;;\n',
        )
        # One structured scanner loop over main + the mode's OWN overlay;
        # a missing manifest FAILS the gate named (see the dedicated
        # fail-closed pin), it is never silently skipped.
        self.assertRegex(
            text,
            r'for m in "\$ROOT/app/src/main/AndroidManifest\.xml"'
            r' "\$ROOT/app/src/\$FLAVOR_DIR/AndroidManifest\.xml"; do\n'
            r'    \[ -f "\$m" \] \|\| fail "missing manifest: \$m"\n'
            r'    python3 "\$SCRIPT_DIR/backup_rules_check\.py" \\',
        )
        # The retired enforcer must be gone from EXECUTED script text (the
        # documentation comment quoting `grep -vE "tools:node=..."` stays as
        # the design note, but no line-filter pipeline may survive).
        for line in text.splitlines():
            if line.lstrip().startswith("#"):
                continue
            self.assertNotIn("grep -vE", line, line)

    def test_gate_enforces_common_blacklist_in_every_mode(self) -> None:
        # P2-1 locked decision enforced at the shell layer: the COMMON
        # blacklist (NO SMS / MANAGE_EXTERNAL_STORAGE / VPN) runs in EVERY
        # branch - the foss lane previously only grepped proprietary
        # needles and the github lane did not exist at all. The dispatch
        # maps play -> full six-token CSV, foss/github -> common five
        # tokens; the a11y token must NEVER join the common set (the foss
        # and github overlays legally declare accessibility services).
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn(
            'BLACKLIST_PERMS="SEND_SMS RECEIVE_SMS READ_SMS'
            ' MANAGE_EXTERNAL_STORAGE BIND_ACCESSIBILITY_SERVICE BIND_VPN_SERVICE"\n',
            text,
        )
        self.assertIn(
            'COMMON_BLACKLIST_PERMS="SEND_SMS RECEIVE_SMS READ_SMS'
            ' MANAGE_EXTERNAL_STORAGE BIND_VPN_SERVICE"\n',
            text,
        )
        common_match = re.search(r'COMMON_BLACKLIST_PERMS="([^"]*)"', text)
        self.assertIsNotNone(common_match, text)
        common_tokens = common_match.group(1).split()
        self.assertNotIn("BIND_ACCESSIBILITY_SERVICE", common_tokens)
        # Cross-face drift guard: the shell common set must equal the
        # python scanner's COMMON_FORBIDDEN_PERMISSIONS tuple exactly -
        # either face drifting alone would reopen the flavor asymmetry
        # this gate just closed.
        self.assertEqual(
            set(common_tokens),
            set(policy_inspect.COMMON_FORBIDDEN_PERMISSIONS),
        )
        # Exact per-mode mapping (missing github branch here would mean
        # the P2-1 shell gap reopened).
        self.assertIn(
            'case "$MODE" in\n'
            '    play)\n'
            '        PERM_TOKENS="$BLACKLIST_PERMS_CSV"\n'
            '        FLAVOR_DIR="play"\n'
            '        ;;\n'
            '    foss)\n'
            '        PERM_TOKENS="$COMMON_BLACKLIST_PERMS_CSV"\n'
            '        FLAVOR_DIR="foss"\n'
            '        ;;\n'
            '    github)\n'
            '        PERM_TOKENS="$COMMON_BLACKLIST_PERMS_CSV"\n'
            '        FLAVOR_DIR="github"\n'
            '        ;;\n'
            '    *)\n'
            '        fail "unknown mode: $MODE"\n'
            '        ;;\n'
            'esac\n',
            text,
        )
        # The workflow's github assembly step must call the SAME common
        # five-token source scan (it is the only github-facing
        # element-level check on a plain scanner/scripts diff, so the
        # yml text itself gets a literal pin - otherwise the claim above
        # is manually verifiable only).
        workflow = PR_CHECK_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            'backup_rules_check.py --check-permission-removals '
            '"SEND_SMS,RECEIVE_SMS,READ_SMS,MANAGE_EXTERNAL_STORAGE,'
            'BIND_VPN_SERVICE" app/src/main/AndroidManifest.xml '
            'app/src/github/AndroidManifest.xml',
            workflow,
        )

    def test_gate_missing_manifest_fails_closed_not_skipped(self) -> None:
        # The retired `[ -f "$m" ] || continue` turned a vanishing flavor
        # overlay into a silently ABSENT structured permission scan
        # (fail-open). Every existence guard in executed text must fail
        # the gate with a named path; only the while-read blank-line
        # guards (`[ -n ... ] || continue`) legitimately keep skipping.
        text = POLICY_CHECK.read_text(encoding="utf-8")
        for line in text.splitlines():
            if line.lstrip().startswith("#"):
                continue
            self.assertNotRegex(
                line, r'\[ -f [^]]*\] \|\| continue', line
            )
        # Named-path failures are the expected shape everywhere a
        # structured manifest/resource check is fed.
        self.assertGreaterEqual(
            text.count('|| fail "missing manifest:'), 5, text
        )
        self.assertIn('[ -f "$m" ] || fail "missing manifest: $m"', text)

    def test_gate_cleartext_scan_is_quote_and_rc_honest(self) -> None:
        # The raw cleartext scan must observe POSIX grep marking instead
        # of piping through `grep -q .` behind `2>/dev/null` (which
        # swallowed rc 2 = unreadable subtree, a fail-open). Both legal
        # spelling variants (single quotes, whitespace around "=") are in
        # the pattern, and the rc case disposes 0/1/* explicitly.
        text = POLICY_CHECK.read_text(encoding="utf-8")
        grep_line = next(
            line for line in text.splitlines() if "usesCleartextTraffic" in line
        )
        self.assertIn("|| cleartext_rc=$?", grep_line, grep_line)
        # The aapt-facing pattern carries the escaped double quote inside the
        # shell double-quoted string: [\"']true.
        self.assertIn(
            "usesCleartextTraffic[[:space:]]*=[[:space:]]*[\\\"']true",
            grep_line,
            grep_line,
        )
        # The retired stderr discard must stay retired on the scan line.
        self.assertNotIn("2>/dev/null", grep_line, grep_line)
        self.assertIn(
            'case "$cleartext_rc" in\n'
            "    0) fail 'cleartext explicitly enabled somewhere under app/src' ;;\n"
            "    1) : ;;\n"
            "    *) fail 'cleartext scan failed (grep filesystem error under app/src)' ;;\n"
            "esac",
            text,
        )

    def test_gate_deep_scans_alias_rule_names(self) -> None:
        # P2-1 deep-scan widening: a rules resource may be added under a
        # dash spelling (data-extraction-rules.xml) and an NSC alias
        # without the "config" segment (network-security.xml); the
        # exact-name finds ignored both, so an overlay could ship an
        # unvalidated resource under those names. The widened name sets
        # ride the same guarded finds, and the case dispatch binds the
        # new dash shape to the true data-extraction-rules root.
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn("-name '*data-extraction*rules*.xml'", text)
        self.assertIn(
            "*data-extraction*rules*.xml) broot=data-extraction-rules ;;", text
        )
        nsc_line = next(line for line in text.splitlines() if "NSC_LIST=" in line)
        self.assertIn("-name '*network*security*.xml'", nsc_line, nsc_line)
        # The retired narrower NSC name must be gone entirely (no
        # half-widened find quietly left behind).
        self.assertNotIn("-name '*network*security*config*.xml'", text)


class NscCheckValidator(unittest.TestCase):
    """Structured network-security-config rule engine (nsc_check.py) matrix.

    The old gate grep'd for ``cleartextTrafficPermitted="false"`` anywhere in
    a file; the reviewer's counterexample (base-config false + domain-config
    true for example.com) PASSED while the config enabled cleartext for a
    domain. These fixtures pin the structured XML replacement.
    """

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)

    def _write(self, name: str, body: str) -> str:
        path = self.tmp / name
        path.write_text(body, encoding="utf-8")
        return str(path)

    @staticmethod
    def _config(inner: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<network-security-config>\n"
            f"{inner}"
            "</network-security-config>\n"
        )

    _BASE_FALSE = '  <base-config cleartextTrafficPermitted="false" />\n'

    @staticmethod
    def _loopback_domain(domain: str, subdomains: str = ' includeSubdomains="false"') -> str:
        return (
            '  <domain-config cleartextTrafficPermitted="true">\n'
            f"    <domain{subdomains}>{domain}</domain>\n"
            "  </domain-config>\n"
        )

    def test_reviewer_counterexample_fails_both_modes(self) -> None:
        # The whole false-PASS lineage: ANY "false" anywhere satisfied the old
        # grep even when a domain enabled cleartext. Structurally the config
        # fails BOTH rule sets now.
        path = self._write(
            "network_security_config.xml",
            self._config(self._BASE_FALSE + self._loopback_domain("example.com")),
        )
        release = nsc_check.validate(path, "release")
        self.assertTrue(
            any("release pin denies cleartext" in e for e in release), release
        )
        debug = nsc_check.validate(path, "debug")
        self.assertTrue(
            any(
                "outside the debug loopback allowlist" in e and "'example.com'" in e
                for e in debug
            ),
            debug,
        )

    def test_release_clean_shape_passes_and_debug_agrees(self) -> None:
        base_trust_anchors = (
            '  <base-config cleartextTrafficPermitted="false">\n'
            "    <trust-anchors>\n"
            '      <certificates src="system" />\n'
            "    </trust-anchors>\n"
            "  </base-config>\n"
        )
        path = self._write("nsc.xml", self._config(base_trust_anchors))
        for mode in nsc_check.MODES:
            with self.subTest(mode=mode):
                self.assertEqual(nsc_check.validate(path, mode), [])
        # certificateTransparency is schema-legal inside base-config AND
        # domain-config (AOSP XmlConfigSource.parseConfigEntry rejects it
        # only with "certificateTransparency not allowed in debug-overrides");
        # the fixture below pins the domain-config placement. An NSC ROOT
        # level <certificateTransparency> stays exactly the fail-closed
        # unknown-element shape, which the rule engine refuses (behavior of
        # _BASE_CHILDREN/_DOMAIN_CHILDREN vs _DEBUG_CHILDREN unchanged).
        path = self._write(
            "nsc.xml",
            self._config(
                self._BASE_FALSE
                + '  <domain-config cleartextTrafficPermitted="false">\n'
                '      <domain includeSubdomains="false">example.com</domain>\n'
                '      <certificateTransparency enabled="true" />\n'
                "    </domain-config>\n"
            ),
        )
        for mode in nsc_check.MODES:
            with self.subTest(mode=mode, shape="certificateTransparency"):
                self.assertEqual(nsc_check.validate(path, mode), [])

    def test_release_rejects_cleartext_true_on_every_element(self) -> None:
        # No element may claim cleartext=true in release mode, whatever its
        # position (this includes debug-overrides, applied only to debuggable
        # builds at runtime - the gate refuses to bet on that promise).
        shapes = {
            "base": '  <base-config cleartextTrafficPermitted="true" />\n',
            "debug-overrides": (
                '  <base-config cleartextTrafficPermitted="false" />\n'
                '  <debug-overrides cleartextTrafficPermitted="true" />\n'
            ),
        }
        for name, inner in shapes.items():
            with self.subTest(shape=name):
                path = self._write("nsc.xml", self._config(inner))
                errors = nsc_check.validate(path, "release")
                self.assertTrue(
                    any("cleartextTrafficPermitted" in e for e in errors), errors
                )

    def test_debug_loopback_allowlist_matrix(self) -> None:
        # Debug mode admits cleartext=true ONLY for the exact loopback
        # domains LlmProvider.validateBaseUrl accepts for http://, with the
        # runtime-normalized spelling (trim + lowercase) and explicit
        # includeSubdomains="false" per domain.
        for domain in ("localhost", "127.0.0.1", "10.0.2.2", "LocalHost", " 127.0.0.1 "):
            with self.subTest(accepted=domain):
                path = self._write("nsc.xml", self._config(self._BASE_FALSE + self._loopback_domain(domain)))
                self.assertEqual(nsc_check.validate(path, "debug"), [])
        for domain in ("example.com", "10.0.2.2.evil.com", "abclocalhost"):
            with self.subTest(rejected=domain):
                path = self._write("nsc.xml", self._config(self._BASE_FALSE + self._loopback_domain(domain)))
                errors = nsc_check.validate(path, "debug")
                self.assertTrue(
                    any("outside the debug loopback allowlist" in e for e in errors),
                    errors,
                )

    def _run_cli(self, *args: str) -> subprocess.CompletedProcess[str]:
        # Run scripts/nsc_check.py through its CLI so the exit-code contract
        # of the probe surface (not only the validate() return list) is pegged.
        cli = ROOT / "scripts" / "nsc_check.py"
        return subprocess.run(
            [sys.executable, str(cli), *args],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_reviewer_probe_n1_nested_alias_fails_cli(self) -> None:
        # Review probe n1 (review-proven against Android runtime): the outer
        # domain-config's "true" is legal for localhost, but the INNER
        # <domain-config> with NO cleartextTrafficPermitted attribute
        # INHERITS that "true" (AOSP XmlConfigSource#parseConfigEntry parents
        # the nested builder to the outer one,
        # Builder#getEffectiveCleartextTrafficPermitted walks the chain), so
        # runtime enables cleartext for example.com. The gate must exit 1 -
        # pre-fix it skipped attribute-less entries and exited 0.
        inner = (
            self._BASE_FALSE
            + '  <domain-config cleartextTrafficPermitted="true">\n'
            '    <domain includeSubdomains="false">localhost</domain>\n'
            "    <domain-config>\n"
            '      <domain includeSubdomains="false">example.com</domain>\n'
            "    </domain-config>\n"
            "  </domain-config>\n"
        )
        path = self._write("n1.xml", self._config(inner))
        debug = self._run_cli("--mode", "debug", path)
        self.assertEqual(debug.returncode, 1, debug.stderr)
        self.assertIn("outside the debug loopback allowlist", debug.stderr)
        self.assertIn("'example.com'", debug.stderr)
        release = self._run_cli("--mode", "release", path)
        self.assertEqual(release.returncode, 1, release.stderr)
        self.assertIn("release pin denies cleartext", release.stderr)

    def test_nested_config_effective_cleartext_rules_matrix(self) -> None:
        # The EFFECTIVE value (own explicit attribute ?? inherited parent
        # value) drives every rule exactly like an explicit "true":
        top = self._BASE_FALSE + (
            '  <domain-config cleartextTrafficPermitted="true">\n'
            '    <domain includeSubdomains="false">localhost</domain>\n'
        )
        cases = {
            # inherited "true" + allowlisted inner domain + explicit
            # includeSubdomains="false": legal in debug (runtime really does
            # enable cleartext for this localhost entry), denied in release.
            "inherited-true-loopback-ok": ("", "10.0.2.2", []),
            # inherited "true" + own "false" override: the Builder override
            # legally narrows - no clause can apply to the inner entry.
            "own-false-narrows": (
                ' cleartextTrafficPermitted="false"',
                "app.example.com",
                [],
            ),
            # inherited "true" + outside-domain inner entry: the review probe
            # n1 bypass, failed in BOTH modes.
            "inherited-true-outside-domain": ("", "example.com", None),
            # own "true" + outside-domain inner entry: the own value obeys the
            # same allowlist rules (guards against a "always inherit" fix).
            "own-true-outside-domain": (
                ' cleartextTrafficPermitted="true"',
                "example.com",
                None,
            ),
        }
        for name, (attr, domain, debug_expect) in cases.items():
            with self.subTest(case=name):
                inner = (
                    top
                    + f"    <domain-config{attr}>\n"
                    + f'      <domain includeSubdomains="false">{domain}</domain>\n'
                    + "    </domain-config>\n"
                    + "  </domain-config>\n"
                )
                path = self._write("nsc.xml", self._config(inner))
                release = nsc_check.validate(path, "release")
                self.assertTrue(
                    any(
                        "cleartextTrafficPermitted" in e and "true" in e
                        for e in release
                    ),
                    release,
                )
                debug = nsc_check.validate(path, "debug")
                if debug_expect is None:
                    self.assertTrue(
                        any(
                            "outside the debug loopback allowlist" in e
                            and f"'{domain}'" in e
                            for e in debug
                        ),
                        debug,
                    )
                else:
                    self.assertEqual(debug, debug_expect, debug)

    def test_deep_nested_chain_carries_effective_through_two_levels(self) -> None:
        # getEffectiveCleartextTrafficPermitted recurses the WHOLE parent
        # chain: a claim-less innermost entry two levels deep inherits the
        # outermost "true" exactly as at runtime. Domains stay globally
        # unique because the runtime dedupes across all configs.
        inner = (
            self._BASE_FALSE
            + '  <domain-config cleartextTrafficPermitted="true">\n'
            '    <domain includeSubdomains="false">localhost</domain>\n'
            "    <domain-config>\n"
            '      <domain includeSubdomains="false">10.0.2.2</domain>\n'
            "      <domain-config>\n"
            '        <domain includeSubdomains="false">example.com</domain>\n'
            "      </domain-config>\n"
            "    </domain-config>\n"
            "  </domain-config>\n"
        )
        path = self._write("deep.xml", self._config(inner))
        debug = nsc_check.validate(path, "debug")
        self.assertTrue(
            any(
                "outside the debug loopback allowlist" in e and "'example.com'" in e
                for e in debug
            ),
            debug,
        )
        self.assertTrue(
            any("release pin denies cleartext" in e
                for e in nsc_check.validate(path, "release"))
        )

    def test_nested_own_false_narrows_in_both_modes_cli(self) -> None:
        # Regression pairs for the fix's twin duties: (a) inherited values are
        # checked (probe n1 exits 1 above), and (b) an explicit own="false"
        # on an inner entry - and an unattributed TOP-LEVEL entry inheriting
        # the base "false" pin - must keep exiting 0 in BOTH modes.
        narrowed = (
            self._BASE_FALSE
            + "  <domain-config>\n"
            '    <domain includeSubdomains="false">api.example.com</domain>\n'
            '    <domain-config cleartextTrafficPermitted="false">\n'
            '      <domain includeSubdomains="false">sdk.partner.com</domain>\n'
            "    </domain-config>\n"
            "  </domain-config>\n"
        )
        path = self._write("narrowed.xml", self._config(narrowed))
        for mode in nsc_check.MODES:
            with self.subTest(mode=mode):
                self.assertEqual(nsc_check.validate(path, mode), [], mode)
                result = self._run_cli("--mode", mode, path)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_debug_requires_explicit_include_subdomains_false(self) -> None:
        # AOSP already defaults includeSubdomains to FALSE
        # (parseDomain: getAttributeBooleanValue(..., false)), so an ABSENT
        # attribute cannot widen anything at runtime; demanding the explicit
        # "false" is stricter-than-runtime review bookkeeping (the whitelisted
        # entry must state its scope in text). An explicit "true" DOES widen
        # the whitelisted entry to a whole subdomain tree: FAIL in both cases,
        # for different reasons.
        for attr in ("", ' includeSubdomains="true"'):
            with self.subTest(include_subdomains=attr or "absent"):
                path = self._write(
                    "nsc.xml",
                    self._config(self._BASE_FALSE + self._loopback_domain("localhost", attr)),
                )
                errors = nsc_check.validate(path, "debug")
                self.assertTrue(
                    any("includeSubdomains=\"false\"" in e for e in errors), errors
                )

    def test_domain_normalization_is_java_trim_not_python_strip(self) -> None:
        # Runtime parseDomain uses Java String#trim: ONLY characters <= U+0020
        # are dropped. NBSP (U+00A0) and IDEOGRAPHIC SPACE (U+3000) survive
        # trimming there, so the stored domain keeps the whitespace and can
        # never match a host: under runtime semantics such an entry is inert,
        # and under the runtime-identical normalization it deviates from the
        # loopback allowlist - the gate fails it in debug mode (fail-closed:
        # the dev alias may not hide non-matching decorated spellings; write
        # plain localhost instead). Plain ASCII spaces (<= U+0020) keep
        # trimming away, so the pre-existing " 127.0.0.1 " control still
        # normalizes to an allowlisted name.
        decorated = (
            '  <domain-config cleartextTrafficPermitted="true">\n'
            '    <domain includeSubdomains="false">\u00a0localhost\u00a0</domain>\n'
            "  </domain-config>\n"
            '  <domain-config cleartextTrafficPermitted="true">\n'
            '    <domain includeSubdomains="false">\u3000localhost</domain>\n'
            "  </domain-config>\n"
        )
        path = self._write("trim.xml", self._config(self._BASE_FALSE + decorated))
        debug = nsc_check.validate(path, "debug")
        # The messages carry the names through repr(), which escapes the
        # non-printable decorations exactly like the runtime string differs.
        self.assertTrue(
            any(
                "outside the debug loopback allowlist" in e
                and "\\xa0localhost\\xa0" in e
                for e in debug
            ),
            debug,
        )
        self.assertTrue(
            any(
                "outside the debug loopback allowlist" in e
                and "\\u3000localhost" in e
                for e in debug
            ),
            debug,
        )
        # Sanity: str.strip() would have made BOTH pass before this fix.
        allowed = " 127.0.0.1 "
        path = self._write(
            "trim-ascii.xml", self._config(self._BASE_FALSE + self._loopback_domain(allowed))
        )
        self.assertEqual(nsc_check.validate(path, "debug"), [])

    def test_missing_base_config_and_comment_survivor_fail(self) -> None:
        # The deny-all pin must exist STRUCTURALLY: an empty config and a
        # config whose only "false" lives inside a comment both fail.
        for body in (
            self._config(""),
            self._config(
                '  <!-- <base-config cleartextTrafficPermitted="false" /> -->\n'
            ),
        ):
            with self.subTest(body=body[:64]):
                path = self._write("nsc.xml", body)
                for mode in nsc_check.MODES:
                    errors = nsc_check.validate(path, mode)
                    self.assertTrue(
                        any("missing <base-config>" in e for e in errors), errors
                    )

    def test_unknown_schema_drift_fails_closed(self) -> None:
        # Android's parser SILENTLY skips unknown elements/attributes (AOSP
        # XmlConfigSource); the gate fail-closes on the same shapes instead -
        # a typo'd attribute must not sneak semantics past the review.
        shapes = {
            "root-child": (
                self._BASE_FALSE + '  <trogdor cleartextTrafficPermitted="true" />\n',
            ),
            "unknown-child": (
                self._BASE_FALSE
                + '  <domain-config cleartextTrafficPermitted="false">\n'
                "      <weird />\n"
                "    </domain-config>\n",
            ),
            "typo-attr": (
                self._BASE_FALSE
                + '  <domain-config cleartextTrafficPermitted="false">\n'
                '      <domain includesubdomains="false">localhost</domain>\n'
                "    </domain-config>\n",
            ),
            "extra-attr": (
                '  <base-config cleartextTrafficPermitted="false" extra="1" />\n',
            ),
        }
        for name, inner in shapes.items():
            with self.subTest(shape=name):
                path = self._write("nsc.xml", self._config(inner))
                errors = nsc_check.validate(path, "release")
                self.assertTrue(
                    any("unknown attribute" in e or "unknown element" in e for e in errors),
                    errors,
                )

    def test_runtime_throw_shapes_fail(self) -> None:
        # Mirror every runtime parse exception of AOSP XmlConfigSource:
        # duplicates that throw, and domain-config without any <domain>.
        shapes = {
            "duplicate-base": (
                self._BASE_FALSE + self._BASE_FALSE
            ),
            "domain-config-without-domain": (
                self._BASE_FALSE
                + '  <domain-config cleartextTrafficPermitted="false" />\n'
            ),
            "duplicate-domain": (
                self._BASE_FALSE
                + self._loopback_domain("localhost")
                + self._loopback_domain("localhost")
            ),
            "duplicate-trust-anchors": (
                self._BASE_FALSE
                + '  <domain-config cleartextTrafficPermitted="false">\n'
                "    <domain>example.com</domain>\n"
                '    <trust-anchors><certificates src="system" /></trust-anchors>\n'
                '    <trust-anchors><certificates src="user" /></trust-anchors>\n'
                "  </domain-config>\n"
            ),
        }
        for name, inner in shapes.items():
            with self.subTest(shape=name):
                path = self._write("nsc.xml", self._config(inner))
                errors = nsc_check.validate(path, "release")
                self.assertTrue(errors, name)

    def test_required_certificates_src_and_pin_digest_fail(self) -> None:
        # runtime throws without src / digest - the gate mirrors both.
        path = self._write(
            "nsc.xml",
            self._config(
                '  <base-config cleartextTrafficPermitted="false">\n'
                "    <trust-anchors>\n"
                "      <certificates />\n"
                "    </trust-anchors>\n"
                "  </base-config>\n"
                '  <domain-config cleartextTrafficPermitted="false">\n'
                '    <domain>example.com</domain>\n'
                '    <pin-set>\n'
                '      <pin />\n'
                "    </pin-set>\n"
                "  </domain-config>\n"
            ),
        )
        errors = nsc_check.validate(path, "release")
        self.assertTrue(
            any("missing required attribute src" in e for e in errors), errors
        )
        self.assertTrue(
            any("missing required attribute digest" in e for e in errors), errors
        )

    # ---- AOSP parse-time VALUE semantics (_VALUE_CHECKS, single landing
    # point in _attr_errors so source and artifact faces cannot drift) ----

    _PIN_32B = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    _PIN_64B = (
        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEy"
        "MzQ1Njc4OTo7PD0+Pw=="
    )

    @classmethod
    def _pinned_config(
        cls,
        digest_attr: str | None = ' digest="sha-256"',
        pin_body: str | None = None,
        expiration_attr: str | None = ' expiration="2023-02-28"',
    ) -> str:
        body = cls._PIN_32B if pin_body is None else pin_body
        expiration = expiration_attr if expiration_attr is not None else ""
        digest = digest_attr if digest_attr is not None else ""
        return (
            '  <base-config cleartextTrafficPermitted="false" />\n'
            '  <domain-config cleartextTrafficPermitted="false">\n'
            "    <domain>example.com</domain>\n"
            f"    <pin-set{expiration}>\n"
            f"      <pin{digest}>{body}</pin>\n"
            "    </pin-set>\n"
            "  </domain-config>\n"
        )

    def test_pin_digest_algorithm_matrix(self) -> None:
        # AOSP Pin.isSupportedDigestAlgorithm: "SHA-256".equalsIgnoreCase(value)
        # (Pin.java "Currently only SHA-256 is supported"). Case-insensitive
        # sha-256 spellings are runtime-legal; SHA-512/MD5 throw at parse time
        # and even the MISSING attr (parsePin sees null) throws the same
        # exception - so the value check must fail it too, on top of the
        # required-attribute mirror.
        for spelling in ("sha-256", "SHA-256", "ShA-256"):
            with self.subTest(accepted=spelling):
                path = self._write("nsc.xml", self._config(
                    self._pinned_config(digest_attr=f' digest="{spelling}"')
                ))
                self.assertEqual(nsc_check.validate(path, "release"), [])
        for digest_attr, expected_value in (
            (' digest="SHA-512"', "SHA-512"),
            (' digest="MD5"', "MD5"),
            ("", "null"),  # missing attr -> parsePin sees null
        ):
            with self.subTest(rejected=digest_attr or "absent"):
                path = self._write("nsc.xml", self._config(
                    self._pinned_config(digest_attr=digest_attr)
                ))
                errors = nsc_check.validate(path, "release")
                self.assertTrue(
                    any(
                        "Unsupported pin digest algorithm" in e
                        and f": {expected_value} " in e
                        for e in errors
                    ),
                    errors,
                )
        # Missing digest stays a required-attribute failure as well; the pair
        # of runtime-throw mirrors coexists on the same element.
        path = self._write("nsc.xml", self._config(
            self._pinned_config(digest_attr="")
        ))
        errors = nsc_check.validate(path, "release")
        self.assertTrue(
            any("missing required attribute digest" in e for e in errors),
            errors,
        )

    def test_pin_digest_value_matrix(self) -> None:
        # AOSP parsePin: Base64.decode(getText().trim(), 0) then the byte
        # length must equal Pin.getDigestLength("SHA-256") = 32. Bad base64
        # -> "Invalid pin digest"; a decodable payload of any other length
        # -> the "digest length N does not match expected length..." wording.
        path = self._write("nsc.xml", self._config(
            self._pinned_config(pin_body=self._PIN_32B)
        ))
        self.assertEqual(nsc_check.validate(path, "release"), [])
        # Runtime-parity positive control: Java String#trim drops ASCII
        # whitespace around the payload exactly like _java_trim.
        path = self._write("trimmed.xml", self._config(
            self._pinned_config(pin_body=f"  {self._PIN_32B}  ")
        ))
        self.assertEqual(nsc_check.validate(path, "release"), [])
        for body, expected in (
            ("not_bas@64!!", "Invalid pin digest"),
            (
                base64.b64encode(b"16 bytes exactly").decode("ascii"),
                "digest length 16 does not match expected length for SHA-256 of 32",
            ),
            (self._PIN_64B, "digest length 64 does not match expected length for SHA-256 of 32"),
        ):
            with self.subTest(pin_body=body[:12]):
                path = self._write("nsc.xml", self._config(
                    self._pinned_config(pin_body=body)
                ))
                errors = nsc_check.validate(path, "release")
                self.assertTrue(any(expected in e for e in errors), errors)

    def test_pin_set_expiration_matrix(self) -> None:
        # AOSP parsePinSet: expiration is OPTIONAL (absent -> Long.MAX_VALUE)
        # and a present value must be a real yyyy-MM-dd date
        # (SimpleDateFormat("yyyy-MM-dd") + setLenient(false)).
        for expiration_attr in (' expiration="2023-02-28"', "", ' expiration="2016-02-29"'):
            with self.subTest(accepted=expiration_attr or "absent"):
                path = self._write("nsc.xml", self._config(
                    self._pinned_config(expiration_attr=expiration_attr)
                ))
                self.assertEqual(nsc_check.validate(path, "release"), [])
        # Empty <pin-set/> is runtime-legal on current main (no thrown
        # exception): the gate accepts it in the same direction - the documented
        # not-mirrored shape.
        for body in (
            self._BASE_FALSE + '  <domain-config cleartextTrafficPermitted="false">\n'
            '    <domain>example.com</domain>\n'
            "    <pin-set />\n"
            "  </domain-config>\n",
            self._BASE_FALSE + '  <domain-config cleartextTrafficPermitted="false">\n'
            '    <domain>example.com</domain>\n'
            '    <pin-set expiration="2023-02-28" />\n'
            "  </domain-config>\n",
        ):
            path = self._write("empty-pin-set.xml", self._config(body))
            self.assertEqual(nsc_check.validate(path, "release"), [])
        for expiration_attr in (' expiration="2023-02-30"', ' expiration="2023-13-01"', ' expiration="20230228"'):
            with self.subTest(rejected=expiration_attr.strip()):
                path = self._write("nsc.xml", self._config(
                    self._pinned_config(expiration_attr=expiration_attr)
                ))
                errors = nsc_check.validate(path, "release")
                self.assertTrue(
                    any("Invalid expiration date in pin-set" in e for e in errors),
                    errors,
                )

    def test_certificates_src_allowset_matrix(self) -> None:
        # AOSP parseCertificatesEntry allow set (hard evidence, frameworks/base
        # main): "system" / "user" / "wfa" (WfaCertificateSource - the runtime
        # diagnostic string omits wfa but its code accepts it) plus resource
        # references. A file-path src throws
        # "Unknown certificates src. Should be one of system|user|@resourceVal".
        for source in ("system", "user", "wfa", "@raw/dev_certs"):
            with self.subTest(accepted=source):
                path = self._write("nsc.xml", self._config(
                    '  <base-config cleartextTrafficPermitted="false">\n'
                    "    <trust-anchors>\n"
                    f'      <certificates src="{source}" />\n'
                    "    </trust-anchors>\n"
                    "  </base-config>\n"
                ))
                self.assertEqual(nsc_check.validate(path, "release"), [])
        for source in ("res/raw/certs.p12", "/data/misc/certs", "system-key-store", "?attr/dev_certs"):
            # "?attr/dev_certs" compiles to TypedValue.TYPE_ATTRIBUTE, and
            # XmlBlock.Parser#getAttributeResourceValue converts ONLY
            # TypedValue.TYPE_REFERENCE ("don't attempt to convert any other
            # types") - so the runtime never enters the
            # ResourceCertificateSource branch for a ?-typed src and throws on
            # the raw string instead. Refused here with the same message.
            with self.subTest(rejected=source):
                path = self._write("nsc.xml", self._config(
                    '  <base-config cleartextTrafficPermitted="false">\n'
                    "    <trust-anchors>\n"
                    f'      <certificates src="{source}" />\n'
                    "    </trust-anchors>\n"
                    "  </base-config>\n"
                ))
                errors = nsc_check.validate(path, "release")
                self.assertTrue(
                    any(
                        "Unknown certificates src. Should be one of "
                        "system|user|@resourceVal" in e
                        and f"{source!r}" in e
                        for e in errors
                    ),
                    errors,
                )

    def test_runtime_accuracy_rationales_corrected(self) -> None:
        # Rationale-accuracy pins: (a) the Android runtime READS
        # hstsEnforced on config entries (XmlConfigSource.parseConfigEntry ->
        # Builder#setHstsEnforced), so the gate's refusal must be worded as
        # the stricter-than-runtime fail-safe it is - never as a
        # "silently ignores" mirror - and must name the future-whitelist duty;
        # (b) <certificateTransparency> inside debug-overrides is an EXPLICIT
        # runtime throw ("certificateTransparency not allowed in
        # debug-overrides"), mirrored with its own message instead of the
        # generic "runtime skips unknown tags" wording.
        path = self._write("hsts.xml", self._config(
            '  <base-config cleartextTrafficPermitted="false" hstsEnforced="true" />\n'
        ))
        errors = nsc_check.validate(path, "release")
        self.assertTrue(
            any(
                "runtime DOES honor hstsEnforced" in e
                and "stricter-than-runtime fail-safe" in e
                and "whitelist" in e
                for e in errors
            ),
            errors,
        )
        self.assertFalse(
            any("silently ignores unknown attributes" in e for e in errors),
            errors,
        )
        path = self._write("ct-debug.xml", self._config(
            self._BASE_FALSE
            + '  <debug-overrides cleartextTrafficPermitted="false">\n'
            '    <certificateTransparency enabled="true" />\n'
            "  </debug-overrides>\n"
        ))
        for mode in nsc_check.MODES:
            with self.subTest(mode=mode):
                errors = nsc_check.validate(path, mode)
                self.assertTrue(
                    any(
                        'certificateTransparency not allowed in debug-overrides' in e
                        and "not a runtime" in e
                        for e in errors
                    ),
                    errors,
                )
                self.assertFalse(
                    any("skips unknown tags" in e for e in errors), errors
                )

    def test_nsc_doctype_attlist_fabrication_refused_at_source(self) -> None:
        # expat resolves internal-subset ATTLIST DEFAULTS into the parsed tree
        # (measured: `<!ATTLIST base-config cleartextTrafficPermitted CDATA
        # "false">` materializes the deny-all pin on a bare <base-config/>).
        # Without the DOCTYPE guard this fixture PASSES the gate with rule
        # data the source never carried; the guard must refuse it fail-closed
        # BEFORE that fabrication can count.
        # Control proves the fabrication is load-bearing: the SAME shape
        # without the DOCTYPE block still fails on the missing explicit pin.
        control = self._write("control.xml", self._config("  <base-config />\n"))
        errors = nsc_check.validate(control, "release")
        self.assertTrue(
            any('must set cleartextTrafficPermitted="false" explicitly' in e for e in errors),
            errors,
        )
        body = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!DOCTYPE network-security-config [\n'
            '<!ATTLIST base-config cleartextTrafficPermitted CDATA "false">\n'
            ']>\n'
            "<network-security-config>\n"
            "  <base-config />\n"
            "</network-security-config>\n"
        )
        path = self._write("doctyped.xml", body)
        # Document the expat behavior the guard exists for.
        fabricated = ET.fromstring(body)
        self.assertEqual(fabricated[0].get("cleartextTrafficPermitted"), "false")
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = nsc_check.main(["nsc_check.py", "--mode", "release", path])
        self.assertEqual(1, code)
        self.assertIn("DOCTYPE declarations are rejected", buf_err.getvalue())

    def test_deep_nested_domain_config_surfaces_clean_fail(self) -> None:
        # _MAX_NS_DOMAIN_NEST_DEPTH bounds the nested-domain-config walk: a
        # 70-level suffix must surface as a clean structured FAIL (exit 1 with
        # an actionable message), never an uncaught RecursionError traceback.
        nested = "  <domain-config>\n" * 70
        body = (
            self._BASE_FALSE
            + nested
            + '    <domain includeSubdomains="false">example.com</domain>\n'
            + "  </domain-config>\n" * 70
        )
        path = self._write("deep.xml", self._config(body))
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = nsc_check.main(["nsc_check.py", "--mode", "release", path])
        self.assertEqual(1, code)
        stderr = buf_err.getvalue()
        self.assertIn("exceeds the maximum domain-config nesting depth 64", stderr)
        # Legal nesting depths stay accepted: a three-level all-false chain
        # inside the bound keeps validating clean in BOTH modes.
        chain = (
            '  <domain-config cleartextTrafficPermitted="false">\n'
            '    <domain includeSubdomains="false">outer.example.com</domain>\n'
            "    <domain-config cleartextTrafficPermitted=\"false\">\n"
            '      <domain includeSubdomains="false">mid.example.com</domain>\n'
            "      <domain-config cleartextTrafficPermitted=\"false\">\n"
            '        <domain includeSubdomains="false">inner.example.com</domain>\n'
            "      </domain-config>\n"
            "    </domain-config>\n"
            "  </domain-config>\n"
        )
        legal = self._write("legal-depth.xml", self._config(self._BASE_FALSE + chain))
        for mode in nsc_check.MODES:
            with self.subTest(mode=mode):
                self.assertEqual(nsc_check.validate(legal, mode), [])

    def test_fifo_config_is_refused_immediately(self) -> None:
        # A checker handed a FIFO must refuse without ever blocking: the
        # loader opens the path O_RDONLY|O_NONBLOCK and proves S_ISREG on the
        # OPEN FILE's own descriptor via os.fstat before any read - the same
        # fail-closed shape as the backup-rules side, asserted via main()
        # exit code and message only (no timing claim).
        # Post-fix note: the retired stat-then-open load had a swap window in
        # which a FIFO appearing between os.stat and open() wedged the
        # checker's open() until a writer appeared - open/fstat/read now pin
        # ONE fd, so that mkfifo-in-window race is structurally impossible.
        fifo = self.tmp / "fifo.xml"
        os.mkfifo(fifo)
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = nsc_check.main(["nsc_check.py", "--mode", "release", str(fifo)])
        self.assertEqual(1, code)
        self.assertIn("not a regular file", buf_err.getvalue())

    def test_directory_nsc_config_refused_as_non_regular(self) -> None:
        # Directory analog of the FIFO pin (deterministic, no race): the
        # O_NONBLOCK open never blocks, and the fstat regular-file proof on
        # the OPEN descriptor lands in the same structured non-regular lane
        # with exit 1 (never an ET.parse stall, never an exception).
        directory = self.tmp / "nsc-dir"
        directory.mkdir()
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            code = nsc_check.main(["nsc_check.py", "--mode", "release", str(directory)])
        self.assertEqual(1, code)
        self.assertIn("not a regular file", buf_err.getvalue())

    def test_bool_attribute_values_must_be_lowercased_keywords(self) -> None:
        path = self._write(
            "nsc.xml",
            self._config(
                '  <base-config cleartextTrafficPermitted="True" />\n'
            ),
        )
        errors = nsc_check.validate(path, "release")
        self.assertTrue(
            any('must be exactly "true" or "false"' in e for e in errors), errors
        )
        self.assertTrue(
            any('must set cleartextTrafficPermitted="false" explicitly' in e for e in errors),
            errors,
        )

    def test_wrong_root_and_missing_file_fail(self) -> None:
        path = self._write("nsc.xml", "<resources />")
        for mode in nsc_check.MODES:
            errors = nsc_check.validate(path, mode)
            self.assertTrue(
                any("expected root <network-security-config>" in e for e in errors),
                errors,
            )
        errors = nsc_check.validate(str(self.tmp / "no-such.xml"), "release")
        self.assertTrue(any("unreadable" in e for e in errors))

    def test_tools_attributes_are_excluded(self) -> None:
        # tools:* never reaches the runtime (aapt strips them) and is excluded
        # from the attribute whitelist in both modes.
        path = self._write(
            "nsc.xml",
            '<network-security-config xmlns:tools="http://schemas.android.com/tools">\n'
            '  <base-config cleartextTrafficPermitted="false" tools:ignore="Nsc" />\n'
            "</network-security-config>\n",
        )
        for mode in nsc_check.MODES:
            self.assertEqual(nsc_check.validate(path, mode), [])

    # ---- manifest 屬性綁定（委派 marker 語義）----

    @staticmethod
    def _manifest(application_attrs: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f"    <application {application_attrs} />\n"
            "</manifest>\n"
        )

    @staticmethod
    def _tools_manifest(attrs: str) -> str:
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:tools="http://schemas.android.com/tools">\n'
            f"    <application {attrs} />\n"
            "</manifest>\n"
        )

    _NSC_BINDING = 'android:networkSecurityConfig="@xml/network_security_config"'

    def test_nsc_manifest_main_binding_required_and_pinned(self) -> None:
        # MAIN manifest: the binding must exist AND pin the exact resource.
        missing = self._write(
            "AndroidManifest.xml",
            self._manifest('android:allowBackup="true"'),
        )
        errors = nsc_check.validate_manifest(missing, require_both=True)
        self.assertEqual(
            errors,
            [
                f"{missing}: android:networkSecurityConfig must reference "
                "@xml/network_security_config (attribute absent)"
            ],
        )
        rebound = self._write(
            "AndroidManifest.xml",
            self._manifest('android:networkSecurityConfig="@xml/other_nsc"'),
        )
        errors = nsc_check.validate_manifest(rebound, require_both=True)
        self.assertTrue(
            any("got '@xml/other_nsc'" in e for e in errors), errors
        )

    def test_nsc_manifest_quote_forms_and_comment_survivors(self) -> None:
        # Single quotes are equally valid XML and equally pinned; a comment
        # survivor must not resurrect the binding (ET drops comments).
        p = self._write(
            "AndroidManifest.xml",
            self._manifest(self._NSC_BINDING.replace('"', "'")),
        )
        self.assertEqual(nsc_check.validate_manifest(p, require_both=True), [])
        commented = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            "    <!-- <application "
            'android:networkSecurityConfig="@xml/network_security_config" /> -->\n'
            "    <application />\n"
            "</manifest>\n"
        )
        p = self._write("CommentedManifest.xml", commented)
        errors = nsc_check.validate_manifest(p, require_both=True)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("(attribute absent)", errors[0])

    def test_nsc_manifest_checker_only_watches_own_binding_set(self) -> None:
        # Attribute-set parameterization proof: the NSC checker ignores the
        # BACKUP attrs and their merger markers entirely (those belong to
        # backup_rules_check.validate_manifest) - a manifest carrying only the
        # NSC pin must pass the NSC checker without require-both complaints.
        p = self._write("AndroidManifest.xml", self._manifest(self._NSC_BINDING))
        self.assertEqual(nsc_check.validate_manifest(p, require_both=True), [])
        # ...and an overlay stripping ONLY the backup attrs does not trip the
        # NSC checker (no marker names networkSecurityConfig here).
        p = self._write(
            "OverlayManifest.xml",
            self._tools_manifest(
                'tools:remove="android:fullBackupContent,android:dataExtractionRules"'
            ),
        )
        self.assertEqual(nsc_check.validate_manifest(p), [])

    def test_nsc_manifest_overlay_markers_fail_closed(self) -> None:
        # Marker shapes that would silently drop the merged binding.
        p = self._write(
            "AndroidManifest.xml",
            self._tools_manifest(
                'tools:remove="android:networkSecurityConfig"'
            ),
        )
        errors = nsc_check.validate_manifest(p, require_both=True)
        self.assertTrue(
            any("tools:remove strips android:networkSecurityConfig" in e for e in errors),
            errors,
        )
        p = self._write(
            "AndroidManifest.xml",
            self._tools_manifest('tools:node="replace"'),
        )
        errors = nsc_check.validate_manifest(p, require_both=True)
        self.assertTrue(
            any(
                "tools:node=\"replace\" on <application>" in e
                and "android:networkSecurityConfig" in e
                for e in errors
            ),
            errors,
        )
        p = self._write(
            "AndroidManifest.xml",
            self._tools_manifest('tools:node="remove"'),
        )
        errors = nsc_check.validate_manifest(p, require_both=True)
        self.assertTrue(
            any("tools:node=\"remove\"" in e for e in errors), errors
        )
        p = self._write(
            "AndroidManifest.xml",
            self._tools_manifest(
                self._NSC_BINDING + ' tools:node="replace"'
            ),
        )
        # Re-declaring the pinned value inside the same document is provably
        # fine even under the marker.
        self.assertEqual(nsc_check.validate_manifest(p, require_both=True), [])

    def test_nsc_manifest_wrong_root_needs_manifest_element(self) -> None:
        p = self._write(
            "AndroidManifest.xml",
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n',
        )
        errors = nsc_check.validate_manifest(p, require_both=True)
        self.assertTrue(any("needs a <manifest> root" in e for e in errors), errors)
        p = self._write(
            "AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            "</manifest>\n",
        )
        errors = nsc_check.validate_manifest(p, require_both=True)
        self.assertTrue(
            any("needs an <application>" in e for e in errors), errors
        )
        # Overlay without <application>: whole-tree inheritance stays legal.
        self.assertEqual(nsc_check.validate_manifest(p), [])

    # ---- CLI 層（fail-closed exit codes）----

    def test_cli_exit_code_matrix(self) -> None:
        good = self._write("nsc.xml", self._config(self._BASE_FALSE))
        bad = self._write(
            "bad.xml",
            self._config(
                self._BASE_FALSE + self._loopback_domain("example.com")
            ),
        )
        self.assertEqual(nsc_check.main(["x", "--mode", "release", good]), 0)
        self.assertEqual(nsc_check.main(["x", "--mode", "debug", good]), 0)
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(nsc_check.main(["x", "--mode", "release", bad]), 1)
        self.assertIn("release pin denies cleartext", buf_err.getvalue())
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(nsc_check.main(["x", "--mode", "debug", bad]), 1)
        self.assertIn("outside the debug loopback allowlist", buf_err.getvalue())
        # Per-argv REAL messages: each usage-refusal shape must carry its own
        # reason (the previous shared tail asserted
        # `assertIn("usage", buf_err + "usage")`, which is true of EVERY
        # string and therefore pinned nothing).
        for argv, expected in (
            (["x", bad], "usage: nsc_check.py --mode (release|debug)"),
            (["x", "--mode", "staging", bad], "unknown --mode 'staging'"),
            (
                ["x", "--mode", "release", "--mode", "debug", bad],
                "duplicate --mode",
            ),
            (["x", "--mode"], "--mode requires a value (release|debug)"),
            (["x", "--root", "x", bad], "unknown option --root"),
            (
                ["x", "--mode", "release", "--check-manifest", good],
                "--check-manifest and --mode are mutually exclusive",
            ),
            (["x", "--require-both", good], "usage: nsc_check.py --mode (release|debug)"),
            (
                # With a mode present the hint-free refusal no longer fires,
                # so this argv is the one that reaches the flag check.
                ["x", "--mode", "release", "--require-both", good],
                "--require-both requires --check-manifest",
            ),
            (["x"], "usage: nsc_check.py (--mode (release|debug)"),
        ):
            buf_err = io.StringIO()
            with contextlib.redirect_stderr(buf_err):
                self.assertEqual(nsc_check.main(argv), 2, argv)
            self.assertIn(expected, buf_err.getvalue(), argv)

    def test_cli_manifest_mode_exit_codes(self) -> None:
        good = self._write("AndroidManifest.xml", self._manifest(self._NSC_BINDING))
        bad = self._write(
            "OtherManifest.xml",
            self._manifest('android:networkSecurityConfig="@xml/other_nsc"'),
        )
        self.assertEqual(nsc_check.main(["x", "--check-manifest", good]), 0)
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(
                nsc_check.main(["x", "--check-manifest", "--require-both", good]), 0
            )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(nsc_check.main(["x", "--check-manifest", bad]), 1)
        self.assertIn("got '@xml/other_nsc'", buf_err.getvalue())
        absent = self._write(
            "AbsentManifest.xml",
            self._manifest('android:allowBackup="true"'),
        )
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            self.assertEqual(
                nsc_check.main(
                    ["x", "--check-manifest", "--require-both", absent]
                ),
                1,
            )
        self.assertIn("(attribute absent)", buf_err.getvalue())

    def test_cli_subprocess_smoke_pass_fail_usage(self) -> None:
        # Pin shebang/__main__/exit-code plumbing of the REAL script.
        good = self._write("nsc.xml", self._config(self._BASE_FALSE))
        bad = self._write(
            "bad.xml",
            self._config(self._BASE_FALSE + self._loopback_domain("example.com")),
        )
        cmd = [sys.executable, str(ROOT / "scripts" / "nsc_check.py")]
        rc_ok = subprocess.run(
            cmd + ["--mode", "release", good], capture_output=True, text=True
        )
        self.assertEqual(0, rc_ok.returncode, rc_ok.stderr)
        rc_fail = subprocess.run(
            cmd + ["--mode", "release", bad], capture_output=True, text=True
        )
        self.assertEqual(1, rc_fail.returncode)
        self.assertIn("release pin denies cleartext", rc_fail.stderr)
        rc_usage = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(2, rc_usage.returncode)
        self.assertIn("usage", rc_usage.stderr)

    def test_repo_nsc_files_are_valid(self) -> None:
        # The real repository NSCs are the gate's positive controls: main is
        # deny-all (release rules), debug is the loopback whitelist (debug
        # rules). Any drift in either direction is caught here.
        self.assertEqual(
            nsc_check.validate(
                str(ROOT / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"),
                "release",
            ),
            [],
        )
        self.assertEqual(
            nsc_check.validate(
                str(ROOT / "app" / "src" / "debug" / "res" / "xml" / "network_security_config.xml"),
                "debug",
            ),
            [],
        )


class NscGateWiring(unittest.TestCase):
    """Pin the shell gate's NSC deep scan + manifest pins (source level)."""

    def test_gate_runs_structured_nsc_check_with_mode_routing(self) -> None:
        # Per-file structured validation with the source-set→mode routing
        # (debug source set → debug rules; everything else → release).
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn('python3 "$SCRIPT_DIR/nsc_check.py" --mode "$nsc_mode"', text)
        self.assertIn('case "$nsconfig" in', text)
        self.assertIn('"$ROOT/app/src/debug/"*) nsc_mode=debug ;;', text)
        self.assertIn("*) nsc_mode=release ;;", text)

    def test_gate_requires_main_nsc_resource(self) -> None:
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn(
            '[ -f "$ROOT/app/src/main/res/xml/network_security_config.xml" ] ||',
            text,
        )

    def test_gate_binds_nsc_manifest_attr_via_structured_check(self) -> None:
        # Same structured manifest binding as the backup pair: main requires
        # the attr pinned to @xml/network_security_config (require-both =
        # absence fails); overlays fail only on present-but-different. The
        # pins must ride the nsc_check.py CLI, NOT the backup checker.
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn(
            'python3 "$SCRIPT_DIR/nsc_check.py" --check-manifest --require-both "$MAIN_MANIFEST"',
            text,
        )
        self.assertRegex(text, r"python3 .+nsc_check\.py.+--check-manifest")
        self.assertIn("--check-manifest \"$m\"", text)


class BackupRulesGateEndToEnd(unittest.TestCase):
    """Run the REAL gate script against a cloned source tree (sandbox).

    The gate computes ROOT from its own location, so copying ``scripts/`` plus
    a minimal ``app/src`` into a temp dir exercises the source-stage checks
    end-to-end without an APK, build-tools, or the real repo. ANDROID_HOME
    points at a NONEXISTENT path and HOME at an empty dir so that the
    ``$HOME/Android/Sdk`` probe (which runs regardless of ANDROID_HOME) also
    yields nothing — the expected later failure is the build-tools/artifact
    stage, unless a source check broke (any earlier/different failure means a
    source check is being asserted on).
    """

    @staticmethod
    def _minimal_tree(tree: str) -> "Path":
        root = Path(tree)
        scripts = root / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy2(ROOT / "scripts" / "play_policy_check.sh", scripts)
        shutil.copy2(ROOT / "scripts" / "backup_rules_check.py", scripts)
        # The source-stage NSC deep scan and manifest pin invoke nsc_check.py
        # directly; the sandbox tree must be self-consistent.
        shutil.copy2(ROOT / "scripts" / "nsc_check.py", scripts)
        # apk_policy_inspect.py is imported only in the artifact stage; copy it
        # so the tree is self-consistent anyway.
        shutil.copy2(ROOT / "scripts" / "apk_policy_inspect.py", scripts)
        main_res = root / "app" / "src" / "main" / "res" / "xml"
        main_res.mkdir(parents=True)
        manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <application android:fullBackupContent="@xml/backup_rules"'
            ' android:dataExtractionRules="@xml/data_extraction_rules"'
            ' android:networkSecurityConfig="@xml/network_security_config" />\n'
            "</manifest>\n",
            encoding="utf-8",
        )
        # P2-1 round 2: every flavor lane scans main + ITS OWN overlay
        # manifest fail-closed (missing overlay = FAIL, never skip), so the
        # sandbox ships a legal minimal overlay per flavor. Each carries an
        # <application> with NO pinned attrs: enforcement is
        # equality-when-set, absence legally inherits main's bindings. (The
        # real overlays additionally declare the accessibility service via an
        # android:permission attribute - orthogonal to this gate's checks.)
        for flavor in ("play", "foss", "github"):
            (root / "app" / "src" / flavor).mkdir(parents=True, exist_ok=True)
            (
                root / "app" / "src" / flavor / "AndroidManifest.xml"
            ).write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <application android:allowBackup="true" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
        (main_res / "network_security_config.xml").write_text(
            '<network-security-config>\n'
            '    <base-config cleartextTrafficPermitted="false" />\n'
            "</network-security-config>\n",
            encoding="utf-8",
        )
        # A valid initializer: both resources legally complete. Tests that
        # need a broken tree overwrite these files afterwards.
        (main_res / "backup_rules.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<full-backup-content>\n"
            "    <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
            "    <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
            "    <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
            "    <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
            "    <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
            "</full-backup-content>\n",
            encoding="utf-8",
        )
        (main_res / "data_extraction_rules.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<data-extraction-rules>\n"
            "    <cloud-backup>\n"
            "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
            "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
            "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
            "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
            "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
            "    </cloud-backup>\n"
            "    <device-transfer>\n"
            "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
            "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
            "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
            "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
            "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
            "    </device-transfer>\n"
            "</data-extraction-rules>\n",
            encoding="utf-8",
        )
        return root

    def _run_gate(
        self, tree_root: "Path", *mode_flags: str
    ) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["ANDROID_HOME"] = str(tree_root / "no-such-sdk")
        env.pop("ANDROID_SDK_ROOT", None)
        # HOME is neutered too: find_tool_pair's $HOME/Android/Sdk probe runs
        # regardless of ANDROID_HOME, so an empty HOME keeps it yielding
        # nothing (no developer SDK sneaks in ahead of the source stage).
        env["HOME"] = str(tree_root / "home")  # no $HOME/Android/Sdk here
        (tree_root / "home").mkdir(exist_ok=True)
        # Minimal empty "APK" (precondition only checks extension + existence).
        dummy = tree_root / "dummy.apk"
        dummy.write_bytes(b"PK\x03\x04truncated-not-a-real-apk")
        return subprocess.run(
            [
                "sh",
                str(tree_root / "scripts" / "play_policy_check.sh"),
                *mode_flags,
                str(dummy),
            ],
            capture_output=True,
            text=True,
            env=env,
            cwd=str(tree_root),
        )

    def _assert_source_stage_clean_then_tool_stage_failure(
        self, proc: subprocess.CompletedProcess
    ) -> None:
        # After a clean source stage the run must still fail in the
        # tool/artifact stage: CI has no build-tools ("required Android
        # build-tools"), a dev environment may expose them and then trip
        # the dummy-APK ZIP parse instead. Either marker proves only the
        # late stage fired; every source-stage FAIL must have stayed
        # absent (callers assert those individually first).
        self.assertNotEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(
            "required Android build-tools" in proc.stderr
            or "ZIP structure" in proc.stderr,
            proc.stderr,
        )

    def test_valid_tree_source_stage_passes(self) -> None:
        # A fully valid tree must sail through every source-stage check — the
        # only later failures may come from the artifact/tool environment
        # (build-tools missing, dummy APK not a real zip), never from source
        # validation (no backup/manifest/cleartext/deep-scan FAIL at all).
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            proc = self._run_gate(root)
            all_fail_lines = "\n".join(
                line for line in proc.stderr.splitlines() if line.startswith("FAIL:")
            )
            self.assertNotIn("expected root", all_fail_lines, proc.stderr)
            self.assertNotIn("must reference @xml/", all_fail_lines, proc.stderr)
            self.assertNotIn("cleartext", all_fail_lines, proc.stderr)
            self.assertNotIn("deep-scan", all_fail_lines, proc.stderr)

    def test_swapped_main_file_fails_at_source_stage(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (
                root / "app" / "src" / "main" / "res" / "xml" / "backup_rules.xml"
            ).write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<data-extraction-rules>\n"
                "    <cloud-backup>\n"
                "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
                "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
                "    </cloud-backup>\n"
                "    <device-transfer>\n"
                "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
                "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
                "    </device-transfer>\n"
                "</data-extraction-rules>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("expected root <full-backup-content>", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)

    def test_aliased_overlay_wrong_root_fails_before_tool_stage(self) -> None:
        # The original reviewer escape: a DIFFERENTLY-NAMED overlay file never
        # reached the validator when the scan matched exact filenames only.
        # The semantic-shape deep scan must catch it (expected-root mismatch),
        # still before the artifact/tool stage.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "main" / "res" / "xml" / "backup_rules.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<full-backup-content>\n"
                "    <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
                "    <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
                "    <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
                "    <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
                "    <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
                "</full-backup-content>\n",
                encoding="utf-8",
            )
            foss_res = root / "app" / "src" / "foss" / "res" / "xml"
            foss_res.mkdir(parents=True)
            (foss_res / "backup_rules_foss.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<data-extraction-rules>\n"
                "    <cloud-backup>\n"
                "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
                "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
                "    </cloud-backup>\n"
                "    <device-transfer>\n"
                "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
                "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
                "    </device-transfer>\n"
                "</data-extraction-rules>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("expected root <full-backup-content>", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)

    def test_find_failure_fails_closed_not_silent(self) -> None:
        # POSIX pipeline semantics: the OLD wiring put find inside the sort
        # pipeline, so the assignment status came from sort and a find failure
        # never tripped the fail guard — an unreadable subtree could hide an
        # overlay from the scan silently. Non-pipelined find must propagate
        # its failure; the gate must stop at the deep-scan guard.
        if os.geteuid() == 0:
            self.skipTest("root bypasses directory permissions")
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            locked = root / "app" / "src" / "foss" / "res" / "xml"
            locked.mkdir(parents=True)
            (locked / "backup_rules_evil.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<data-extraction-rules>\n"
                "    <cloud-backup>\n"
                "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
                "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
                "    </cloud-backup>\n"
                "    <device-transfer>\n"
                "        <exclude domain=\"sharedpref\" path=\"librepocket_keys.xml\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-wal\" />\n"
                "        <exclude domain=\"database\" path=\"librepocket_vault-shm\" />\n"
                "        <exclude domain=\"file\" path=\"librepocket_keysets\" />\n"
                "    </device-transfer>\n"
                "</data-extraction-rules>\n",
                encoding="utf-8",
            )
            locked.chmod(0o000)
            try:
                listed: list[str] = []
                try:
                    listed = os.listdir(locked)
                except PermissionError:
                    pass
                if listed:
                    # A mode-ignoring TMPDIR (e.g. drvfs/WSL mounts) makes the
                    # lock a no-op: find succeeds and the premise is untestable
                    # here. Fail-safe skip (can only weaken a skip, never a
                    # real regression signal); CI's default /tmp is unaffected.
                    self.skipTest(
                        "TMPDIR ignores directory mode bits; find-failure "
                        "premise untestable (see finding note)"
                    )
                proc = self._run_gate(root)
            finally:
                locked.chmod(0o755)  # let TemporaryDirectory clean up
            self.assertEqual(proc.returncode, 1, proc.stderr)
            # The unreadable subtree trips the FIRST fail-closed scan guard
            # reachable: since the rc-honest cleartext rewrite, that guard is
            # the grep rc case (rc >= 2 = filesystem error, named fail); the
            # NSC/deep-scan guarded finds would fire the same way if the run
            # got that far. What must hold: a scan failure produces a
            # named-scan FAIL (never silent progress), and no later-stage
            # message can follow.
            self.assertIn("scan failed", proc.stderr)
            self.assertTrue(
                any(
                    marker in proc.stderr
                    for marker in (
                        "cleartext scan failed (grep filesystem error under app/src)",
                        "deep-scan of backup rules resources failed",
                        "network security config scan failed",
                    )
                ),
                proc.stderr,
            )
            self.assertNotIn("expected root", proc.stderr, proc.stderr)

    def test_missing_manifest_binding_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
            manifest.write_text(
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                "    <application android:allowBackup=\"true\" />\n"
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn(
                "android:fullBackupContent must reference @xml/backup_rules",
                proc.stderr,
            )

    def test_comment_escape_manifest_fails_closed(self) -> None:
        # The raw-grep escape this gate closed: attributes deleted, the two
        # literals surviving only inside an XML comment. An ET-based check
        # sees no attributes (comments are dropped by construction) and must
        # fail the gate where the old byte-grep PASSed.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <!-- <application android:fullBackupContent="@xml/backup_rules" '
                'android:dataExtractionRules="@xml/data_extraction_rules" /> -->\n'
                '    <application android:allowBackup="true" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("(attribute absent)", proc.stderr)
            self.assertNotIn("build-tools", proc.stderr)

    def test_flavor_overlay_tools_remove_marker_fails_closed(self) -> None:
        # The reviewer's P2 repro at gate level: a flavor overlay whose
        # <application> carries tools:remove for the two backup attrs passes
        # the naive "absent = inheritance" reading, but the merger removes
        # main's bindings from the merged manifest. The source gate must
        # reject the marker shape before any artifact exists.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "foss").mkdir(parents=True, exist_ok=True)
            (root / "app" / "src" / "foss" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    xmlns:tools="http://schemas.android.com/tools">\n'
                "    <application\n"
                '        tools:remove="android:fullBackupContent,android:dataExtractionRules" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("tools:remove strips android:fullBackupContent", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)

    def test_flavor_overlay_tools_node_replace_fails_closed(self) -> None:
        # The sibling shape: tools:node="replace" replaces the whole
        # <application>; without re-declaring the bindings the merged app
        # would run Auto Backup unprotected.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "foss").mkdir(parents=True, exist_ok=True)
            (root / "app" / "src" / "foss" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    xmlns:tools="http://schemas.android.com/tools">\n'
                '    <application android:allowBackup="true" tools:node="replace" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("tools:node=\"replace\"", proc.stderr)

    def test_flavor_overlay_marker_with_own_pinned_values_passes(self) -> None:
        # tools:node="replace" must re-declare EVERY pinned binding (backup
        # pair AND networkSecurityConfig) inside the same document: each
        # checker sees its own attr survive the marker.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "foss").mkdir(parents=True, exist_ok=True)
            (root / "app" / "src" / "foss" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    xmlns:tools="http://schemas.android.com/tools">\n'
                "    <application\n"
                '        android:fullBackupContent="@xml/backup_rules"\n'
                '        android:dataExtractionRules="@xml/data_extraction_rules"\n'
                '        android:networkSecurityConfig="@xml/network_security_config"\n'
                '        tools:node="replace" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertNotIn("or marker would lose", proc.stderr)
            self.assertNotIn("tools:node", proc.stderr)
            self.assertNotIn("must reference @xml/", proc.stderr)
            self.assertNotIn("tools:remove strips", proc.stderr)

    def test_flavor_overlay_tools_marker_kept_values_passes(self) -> None:
        # tools:remove naming one attr while the same document re-declares
        # that attr with the pinned value survives legally (own values beat
        # the strip marker); the gate must not false-FAIL this shape.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "foss").mkdir(parents=True, exist_ok=True)
            (root / "app" / "src" / "foss" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    xmlns:tools="http://schemas.android.com/tools">\n'
                "    <application\n"
                '        android:fullBackupContent="@xml/backup_rules"\n'
                "        tools:remove=\"android:fullBackupContent\" />\n"
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertNotIn("tools:remove strips", proc.stderr)
            self.assertNotIn("must reference @xml/", proc.stderr)

    def test_flavor_overlay_rebound_attr_fails_closed(self) -> None:
        # Merger lets a flavor overlay rebind android:fullBackupContent; the
        # gate must reject a rebinding to any other resource while tolerating
        # legal absence (inherit main's values).
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "foss").mkdir(parents=True, exist_ok=True)
            (root / "app" / "src" / "foss" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <application android:allowBackup="true"\n'
                '        android:fullBackupContent="@xml/other_rules" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("fullBackupContent must reference @xml/backup_rules", proc.stderr)
            self.assertIn("got '@xml/other_rules'", proc.stderr)

    def test_flavor_overlay_inheriting_attrs_passes(self) -> None:
        # A flavor manifest that simply omits the attrs is legal (merger keeps
        # main's binding); the gate must not false-FAIL on it.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "foss").mkdir(parents=True, exist_ok=True)
            (root / "app" / "src" / "foss" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <application android:allowBackup="true" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            all_fail_lines = "\n".join(
                line for line in proc.stderr.splitlines() if line.startswith("FAIL:")
            )
            self.assertNotIn("must reference", all_fail_lines, proc.stderr)

    # ---- NSC 來源級（深掃 + manifest pin）e2e ----

    def test_source_nsc_missing_fails_closed(self) -> None:
        # The all-flavor NSC existence guard fires before the manifest pins:
        # a missing main resource can never satisfy the (later) attr pin.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (
                root / "app" / "src" / "main" / "res" / "xml"
                / "network_security_config.xml"
            ).unlink()
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            # The main-resource existence guard generalizes over the scan list
            # (no NSC file left anywhere under app/src in this fixture).
            self.assertIn(
                "missing network_security_config.xml under app/src",
                proc.stderr,
            )
            self.assertNotIn("required Android build-tools", proc.stderr)

    def test_main_manifest_nsc_binding_missing_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <application android:fullBackupContent="@xml/backup_rules"'
                ' android:dataExtractionRules="@xml/data_extraction_rules" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn(
                "android:networkSecurityConfig must reference "
                "@xml/network_security_config",
                proc.stderr,
            )
            self.assertNotIn("required Android build-tools", proc.stderr)

    def test_main_manifest_nsc_rebound_fails_closed(self) -> None:
        # Dropping the attr falls back to the platform cleartext default;
        # rebounding to an unvalidated resource escapes every file check.
        # Both shapes must fail the source manifest pin.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <application android:fullBackupContent="@xml/backup_rules"'
                ' android:dataExtractionRules="@xml/data_extraction_rules"'
                ' android:networkSecurityConfig="@xml/other_nsc" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("got '@xml/other_nsc'", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)

    def test_debug_nsc_cleartext_matrix(self) -> None:
        # The debug source set is validated with the DEBUG rules: its
        # loopback whitelist sails through, while the reviewer's
        # counterexample domain fails at the source stage (no tools needed).
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            debug_res = root / "app" / "src" / "debug" / "res" / "xml"
            debug_res.mkdir(parents=True)
            debug_nsc = debug_res / "network_security_config.xml"
            debug_nsc.write_text(
                "<network-security-config>\n"
                '  <base-config cleartextTrafficPermitted="false" />\n'
                '  <domain-config cleartextTrafficPermitted="true">\n'
                '    <domain includeSubdomains="false">localhost</domain>\n'
                "  </domain-config>\n"
                "</network-security-config>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            all_fail_lines = "\n".join(
                line for line in proc.stderr.splitlines() if line.startswith("FAIL:")
            )
            self.assertNotIn("cleartext", all_fail_lines, proc.stderr)
            debug_nsc.write_text(
                "<network-security-config>\n"
                '  <base-config cleartextTrafficPermitted="false" />\n'
                '  <domain-config cleartextTrafficPermitted="true">\n'
                '    <domain includeSubdomains="false">example.com</domain>\n'
                "  </domain-config>\n"
                "</network-security-config>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("outside the debug loopback allowlist", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)

    # -- Play permission blacklist (structured, element-aware) --

    def _play_manifest(self, permission_body: str) -> str:
        # The sandbox main manifest extended with a tools-namespace binding
        # (the merger-marker machinery needs it) and a permission element.
        return (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"'
            ' xmlns:tools="http://schemas.android.com/tools">\n'
            '    <application android:fullBackupContent="@xml/backup_rules"'
            ' android:dataExtractionRules="@xml/data_extraction_rules"'
            ' android:networkSecurityConfig="@xml/network_security_config" />\n'
            f"{permission_body}"
            "</manifest>\n"
        )

    def test_multiline_marked_permission_removal_no_longer_false_fails(self) -> None:
        # The original false-FAIL repro through the REAL gate: one legal
        # multi-line element whose android:name and tools:node="remove" sit
        # on different lines left the name line unfiltered in the old
        # grep -vE wiring and blacklisted-token-grep then hit it. Source
        # stage must now pass (later failure may only be the artifact stage).
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "main" / "AndroidManifest.xml").write_text(
                self._play_manifest(
                    "    <uses-permission\n"
                    '        android:name="android.permission.SEND_SMS"\n'
                    '        tools:node="remove" />\n'
                    '    <uses-permission'
                    ' android:name="android.permission.INTERNET" />\n'
                ),
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            fail_lines = "\n".join(
                line for line in proc.stderr.splitlines() if line.startswith("FAIL:")
            )
            self.assertNotIn("SEND_SMS", fail_lines, proc.stderr)
            self.assertNotIn("blacklisted permission", fail_lines, proc.stderr)

    def test_unmarked_blacklisted_permission_fails_at_source_stage(self) -> None:
        # The actual danger: a blacklisted permission DECLARED without the
        # removal marker (the old grep would miss it in a multi-line or
        # comment shape too). The structured scan fails the gate at the
        # source stage, before any artifact/message drift.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "main" / "AndroidManifest.xml").write_text(
                self._play_manifest(
                    '    <uses-permission'
                    ' android:name="android.permission.SEND_SMS" />\n'
                ),
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn('without tools:node="remove"', proc.stderr)
            self.assertIn("SEND_SMS", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)

    # -- P2-1 通行黑名單 lanes（common policy + fail-closed wiring round 2）--

    def test_missing_flavor_manifest_fails_named_not_skipped(self) -> None:
        # The retired `[ -f "$m" ] || continue`: a vanishing overlay made the
        # whole structured permission stage silently ABSENT (and even the
        # explicit-glob binding loops cannot see a nonexistent file, so the
        # gate would green-light the flavor end-to-end). Every lane must now
        # FAIL naming the path, before any tool/artifact stage.
        for mode_flags, flavor in (
            ((), "play"),  # default lane
            (("--github",), "github"),
            (("--foss",), "main"),
        ):
            with self.subTest(mode=mode_flags, flavor=flavor):
                with tempfile.TemporaryDirectory() as tmp:
                    root = self._minimal_tree(tmp)
                    (
                        root / "app" / "src" / flavor / "AndroidManifest.xml"
                    ).unlink()
                    proc = self._run_gate(root, *mode_flags)
                    self.assertEqual(proc.returncode, 1, proc.stderr)
                    self.assertIn("missing manifest:", proc.stderr)
                    self.assertIn(
                        f"app/src/{flavor}/AndroidManifest.xml", proc.stderr
                    )
                    # Failed at the named guard, not later: no tool-stage
                    # message may follow (androidx/tools errors would betray
                    # a silently-continued scan).
                    self.assertNotIn("required Android build-tools", proc.stderr)
                    self.assertNotIn("blacklisted permission", proc.stderr)

    def test_github_lane_rejects_unmarked_common_permission(self) -> None:
        # The new github shell lane: an unmarked COMMON token (SEND_SMS) in
        # the github overlay must fail `--github` via the structured scan at
        # the source stage - the lane previously did not exist, so this
        # shape shipped unscanned. The foss lane (previously only grepping
        # proprietary needles) is pinned to the same common token set.
        for mode_flags, overlay, token in (
            (("--github",), "github", "SEND_SMS"),
            (("--foss",), "foss", "READ_SMS"),
        ):
            with self.subTest(mode=mode_flags, overlay=overlay, token=token):
                with tempfile.TemporaryDirectory() as tmp:
                    root = self._minimal_tree(tmp)
                    overlay_manifest = (
                        root / "app" / "src" / overlay / "AndroidManifest.xml"
                    )
                    overlay_manifest.write_text(
                        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
                        '    <application android:allowBackup="true" />\n'
                        f'    <uses-permission android:name="android.permission.{token}" />\n'
                        "</manifest>\n",
                        encoding="utf-8",
                    )
                    proc = self._run_gate(root, *mode_flags)
                    self.assertEqual(proc.returncode, 1, proc.stderr)
                    self.assertIn(
                        f"blacklisted permission {token}", proc.stderr
                    )
                    self.assertIn('without tools:node="remove"', proc.stderr)
                    self.assertNotIn("required Android build-tools", proc.stderr)

    def test_github_lane_accepts_marked_removal_and_clean_overlay(self) -> None:
        # Positive control for the new lane with merger machinery: a github
        # overlay that marks SEND_SMS with tools:node="remove" (deleting a
        # lower-priority declaration in the merged artifact) must pass every
        # source-stage check; the run then reaches the artifact/tool stage -
        # no permission/binding/deep-scan FAIL may surface first.
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            (root / "app" / "src" / "github" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"'
                ' xmlns:tools="http://schemas.android.com/tools">\n'
                '    <application android:allowBackup="true" />\n'
                '    <uses-permission android:name="android.permission.SEND_SMS"'
                ' tools:node="remove" />\n'
                "</manifest>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root, "--github")
            self.assertNotIn("blacklisted permission", proc.stderr, proc.stderr)
            self.assertNotIn("missing manifest", proc.stderr, proc.stderr)
            self.assertNotIn("expected root", proc.stderr, proc.stderr)
            self._assert_source_stage_clean_then_tool_stage_failure(proc)

    def test_cleartext_scan_matches_all_legal_spellings(self) -> None:
        # Quote/space-tolerant + rc-honest raw scan: single-quoted values and
        # whitespace around "=" are legal XML attribute spellings that the
        # retired double-quote-only grep PASSed. Every variant must fail the
        # gate at the scan step; only a "false" spelling must never trip it.
        for label, snippet in (
            ("double-quoted", '    <application android:usesCleartextTraffic="true" />\n'),
            (
                "single-quoted-spaced",
                "    <application android:usesCleartextTraffic = 'true' />\n",
            ),
        ):
            with self.subTest(label=label, snippet=snippet):
                with tempfile.TemporaryDirectory() as tmp:
                    root = self._minimal_tree(tmp)
                    notes = root / "app" / "src" / "github" / "res" / "values"
                    notes.mkdir(parents=True)
                    (notes / "legacy_notes.xml").write_text(
                        '<?xml version="1.0" encoding="utf-8"?>\n'
                        "<resources>\n"
                        f"{snippet}"
                        "</resources>\n",
                        encoding="utf-8",
                    )
                    proc = self._run_gate(root)
                    self.assertEqual(proc.returncode, 1, proc.stderr)
                    self.assertIn(
                        "cleartext explicitly enabled somewhere under app/src",
                        proc.stderr,
                    )
                    self.assertNotIn("required Android build-tools", proc.stderr)
        # rc-honesty + quote-tolerance NEGATIVE control: an enforced-false
        # spelling in any quoting must sail past the scan step (its failure
        # is later, at the tool stage).
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            notes = root / "app" / "src" / "github" / "res" / "values"
            notes.mkdir(parents=True)
            (notes / "legacy_notes.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<resources>\n"
                "    <application android:usesCleartextTraffic = 'false' />\n"
                "</resources>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertNotIn("cleartext", proc.stderr, proc.stderr)
            self._assert_source_stage_clean_then_tool_stage_failure(proc)

    def test_dash_alias_rules_files_are_deep_scanned(self) -> None:
        # P2-1 deep-scan widening: overlay rules resources spelled with a
        # DASH (data-extraction-rules.xml) were invisible to the retired
        # exact-name find, so an overlay could ship an unvalidated rules
        # file under that alias. The widened find must validate it with the
        # data-extraction-rules root binding; a wrong-root alias fails at
        # the deep-scan stage, a correct one passes (legal new shape).
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            res = root / "app" / "src" / "github" / "res" / "xml"
            res.mkdir(parents=True)
            (res / "data-extraction-rules.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<full-backup-content>\n"
                '    <exclude domain="sharedpref" path="bait.xml" />\n'
                "</full-backup-content>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("expected root <data-extraction-rules>", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            res = root / "app" / "src" / "foss" / "res" / "xml"
            res.mkdir(parents=True)
            (res / "data-extraction-rules.xml").write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                "<data-extraction-rules>\n"
                "    <cloud-backup>\n"
                '        <exclude domain="sharedpref" path="librepocket_keys.xml" />\n'
                '        <exclude domain="database" path="librepocket_vault" />\n'
                '        <exclude domain="database" path="librepocket_vault-wal" />\n'
                '        <exclude domain="database" path="librepocket_vault-shm" />\n'
                '        <exclude domain="file" path="librepocket_keysets" />\n'
                "    </cloud-backup>\n"
                "    <device-transfer>\n"
                '        <exclude domain="sharedpref" path="librepocket_keys.xml" />\n'
                '        <exclude domain="database" path="librepocket_vault" />\n'
                '        <exclude domain="database" path="librepocket_vault-wal" />\n'
                '        <exclude domain="database" path="librepocket_vault-shm" />\n'
                '        <exclude domain="file" path="librepocket_keysets" />\n'
                "    </device-transfer>\n"
                "</data-extraction-rules>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertNotIn("expected root", proc.stderr, proc.stderr)
            self.assertNotIn("deep-scan", proc.stderr, proc.stderr)
            self._assert_source_stage_clean_then_tool_stage_failure(proc)

    def test_nsc_alias_without_config_segment_is_deep_scanned(self) -> None:
        # The NSC deep scan was widened from the exact
        # *network*security*config* name shape to *network*security*: an
        # overlay naming its network-security resource WITHOUT the "config"
        # segment (network-security.xml) used to escape every validation.
        # The alias must reach nsc_check.py now, and a wrong root must fail
        # the gate; the correctly-shaped dash alias passes (name widening
        # can only add files to the scan, never loosen the root/shape
        # judgement nsc_check independently applies).
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            res = root / "app" / "src" / "foss" / "res" / "xml"
            res.mkdir(parents=True)
            (res / "network-security.xml").write_text(
                "<resources>\n</resources>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertEqual(proc.returncode, 1, proc.stderr)
            self.assertIn("expected root <network-security-config>", proc.stderr)
            self.assertNotIn("required Android build-tools", proc.stderr)
        with tempfile.TemporaryDirectory() as tmp:
            root = self._minimal_tree(tmp)
            res = root / "app" / "src" / "github" / "res" / "xml"
            res.mkdir(parents=True)
            (res / "network-security-config.xml").write_text(
                "<network-security-config>\n"
                '    <base-config cleartextTrafficPermitted="false" />\n'
                "</network-security-config>\n",
                encoding="utf-8",
            )
            proc = self._run_gate(root)
            self.assertNotIn("expected root", proc.stderr, proc.stderr)
            self.assertNotIn("network security", proc.stderr, proc.stderr)
            self._assert_source_stage_clean_then_tool_stage_failure(proc)


if __name__ == "__main__":
    unittest.main(verbosity=2)
