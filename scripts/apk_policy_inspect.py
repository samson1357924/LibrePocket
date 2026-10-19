#!/usr/bin/env python3
"""Fail-closed APK structure and Play/Foss policy inspector.

This scanner parses only the DEX header, string_ids, type_ids, and class_defs
needed by its policy checks. It is not a complete DEX/ART semantic verifier.
Android's native dexdump is run as an independent parseability check with both
streams discarded; its potentially large textual dump is never captured.

DEX support is intentionally limited to little-endian versions 035, 037, 038,
039, and 040. Version 041/container, reverse-endian, and unknown formats fail
closed. Format details follow the official AOSP DEX format specification:
https://source.android.com/docs/core/runtime/dex-format

Observed current debug APK inventory (provided by the parent, not verified by
this helper): about 17-20 root DEX shards, largest shard about 43.8 MB, and
about 65-80 MB total DEX. Limits below leave headroom for that inventory; they
are resource limits, not evidence that any real APK passed this scanner.

Backup-binding authority: this scanner asserts that the MERGED manifest
(what the final APK will actually run) still carries
``android:fullBackupContent``/``android:dataExtractionRules`` bound to the
pinned xml resources. Source-level checks (``backup_rules_check.py``) cannot
see what a manifest-merger marker (``tools:node="replace"``, ``tools:remove``)
stripped, so this artifact-level check is the authoritative proof that the
bindings survived the merge. Runtime impact when missing: AOSP FullBackup
treats both refs as absent and copies EVERYTHING (including the key files the
whole policy exists to protect).

Network-security-config authority (same artifact-level reasoning): the merged
manifest must still carry ``android:networkSecurityConfig`` pinned to
``@xml/network_security_config``, AND the compiled resource itself is parsed
out of ``aapt dump xmltree`` of the APK and re-validated with the EXACT rules
of ``scripts/nsc_check.py``. The source stage greps/validates source XML files,
but the merged artifact is what ships: a resource overlay, an alias flip, or a
merger rebind would never be visible at source level. The validation mode is
derived fail-closed from the merged manifest's own ``android:debuggable``
(attr 0x0101000f) = true (type 0x12)0xffffffff → debug rules (loopback-only
cleartext); anything else (absent/false/unparseable) → the release deny-all
rules, so a build that cannot PROVE it is debuggable is checked with the
stricter contract.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import os
import re
import selectors
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import time
import types
import zipfile
import zlib
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable

SUPPORTED_DEX_VERSIONS = frozenset((b"035", b"037", b"038", b"039", b"040"))
LITTLE_ENDIAN_TAG = 0x12345678
REVERSE_ENDIAN_TAG = 0x78563412
DEX_HEADER_SIZE = 0x70
NO_INDEX = 0xFFFFFFFF

MAX_APK_BYTES = 512 * 1024 * 1024
MAX_ZIP_ENTRIES = 100_000
MAX_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
MAX_DEX_COUNT = 64
MAX_SINGLE_DEX_BYTES = 128 * 1024 * 1024
MAX_TOTAL_DEX_BYTES = 256 * 1024 * 1024
# Deliberate scanner limits, not DEX format maxima. Read-only inventory of the
# current three debug APKs: max 125,703 string_ids, longest descriptor 182 UTF-16
# units, max 1,116,929 descriptor units/shard, max 2,720,128 units/APK. These
# limits leave headroom while bounding table allocations and decoded strings.
MAX_STRING_IDS = 1_000_000
MAX_TYPE_DESCRIPTOR_UTF16_UNITS = 4096
MAX_DEX_DESCRIPTOR_UTF16_UNITS = 4 * 1024 * 1024
MAX_DEX_DESCRIPTOR_ENCODED_BYTES = 12 * 1024 * 1024
MAX_APK_DESCRIPTOR_UTF16_UNITS = 16 * 1024 * 1024
MAX_APK_DESCRIPTOR_ENCODED_BYTES = 48 * 1024 * 1024
MAX_AAPT_OUTPUT_BYTES = 8 * 1024 * 1024
# ``aapt dump resources`` prints one line per (entry, config) pair (~100-160
# bytes each); a real app-wide table can reach several MB, while the compact
# manifest-only dumps stay ~KBs. A larger per-call bound keeps the shared
# manifest-dump budget (and with it the manifest parse scope) unchanged.
MAX_RESOURCES_DUMP_BYTES = 32 * 1024 * 1024
TOOL_TIMEOUT_SECONDS = 120.0

# Merged-manifest backup bindings (the artifact-level authority). Resource-id
# constants are stable SDK definitions of the android attrs:
# android:fullBackupContent = 0x010104eb, android:dataExtractionRules =
# 0x0101063e. The resource NAMES are the pins from backup_rules_check.py
# EXPECTED_MANIFEST_ATTRS (values "@xml/backup_rules" /
# "@xml/data_extraction_rules" minus the "@xml/" prefix).
_BACKUP_BINDING_ATTRS: tuple[tuple[str, str, str], ...] = (
    ("fullBackupContent", "0x010104eb", "backup_rules"),
    ("dataExtractionRules", "0x0101063e", "data_extraction_rules"),
)

# Merged-manifest networkSecurityConfig binding (same artifact-level authority
# as the backup pair). Resource-id constants are MEASURED SDK definitions
# (platform data/res/values/public-final.xml, android-36 == android-37.0, not
# guessed): android:networkSecurityConfig = 0x01010527,
# android:debuggable = 0x0101000f. The resource NAME is the pin from
# nsc_check.py EXPECTED_MANIFEST_RESOURCE ("@xml/network_security_config" minus
# the "@xml/" prefix); aapt v1 xmltree only resolves the compiled member by its
# canonical res/<type>/<name> path (a literal 0x… id or type/name form answers
# "resource ... not found"), so the tree dump goes through that path after the
# id↔name mapping is proven against the resources table.
_NETWORK_SECURITY_ATTR_NAME = "networkSecurityConfig"
_NETWORK_SECURITY_ATTR_ID = "0x01010527"
_NETWORK_SECURITY_RESOURCE = "network_security_config"
_NETWORK_SECURITY_MEMBER = "res/xml/network_security_config.xml"
_DEBUGGABLE_ATTR_ID = "0x0101000f"

PLAY_FORBIDDEN_PERMISSIONS = (
    "SEND_SMS",
    "RECEIVE_SMS",
    "READ_SMS",
    "MANAGE_EXTERNAL_STORAGE",
    "BIND_ACCESSIBILITY_SERVICE",
    "BIND_VPN_SERVICE",
)
PLAY_DEFINED_CLASS_PREFIXES = (
    "Ldev/librepocket/agent/github/",
    "Ldev/librepocket/agent/foss/",
    "Ldev/librepocket/privilege/github/",
    "Lrikka/shizuku/",
)
PLAY_FORBIDDEN_SUPERCLASSES = frozenset(
    (
        "Landroid/net/VpnService;",
        "Landroid/accessibilityservice/AccessibilityService;",
    )
)
FOSS_MANIFEST_NEEDLES = (
    "com.google.mlkit",
    "com.google.android.gms",
    "com.microsoft.cognitiveservices.speech",
    "rikka.shizuku",
)
FOSS_TYPE_PREFIXES = (
    "Lcom/google/mlkit/",
    "Lcom/google/android/gms/",
    "Lcom/microsoft/cognitiveservices/speech/",
    "Lrikka/shizuku/",
)
# Android root multidex names are classes.dex, then classes2.dex through
# classesN.dex. Accept every canonical decimal N >= 2 (including 10+), while
# rejecting classes1.dex and leading-zero aliases such as classes01.dex.
DEX_ROOT_NAME = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex\Z")


class PolicyError(Exception):
    """Expected fail-closed artifact or tool error."""


class DescriptorBudget:
    """One APK's retained descriptor budget; reserve before string decoding."""

    def __init__(self) -> None:
        self.remaining_units = MAX_APK_DESCRIPTOR_UTF16_UNITS
        self.remaining_bytes = MAX_APK_DESCRIPTOR_ENCODED_BYTES

    def reserve(self, units: int, encoded_bytes: int) -> None:
        if units > self.remaining_units:
            raise PolicyError("APK cumulative descriptor UTF-16 units exceed scanner limit")
        if encoded_bytes > self.remaining_bytes:
            raise PolicyError("APK cumulative descriptor encoded bytes exceed scanner limit")
        self.remaining_units -= units
        self.remaining_bytes -= encoded_bytes


class ParsedDex:
    __slots__ = ("type_descriptors", "defined_classes", "superclasses")

    def __init__(
        self,
        type_descriptors: tuple[str, ...],
        defined_classes: frozenset[str],
        superclasses: frozenset[str],
    ) -> None:
        self.type_descriptors = type_descriptors
        self.defined_classes = defined_classes
        self.superclasses = superclasses


def _u32(data: bytes, offset: int, what: str) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise PolicyError(f"DEX {what} field is outside the file")
    return struct.unpack_from("<I", data, offset)[0]


def _table_bounds(
    *, data: bytes, count: int, offset: int, item_size: int, what: str, alignment: int = 4
) -> None:
    if count < 0 or offset < 0:
        raise PolicyError(f"DEX {what} has a negative count or offset")
    if count == 0:
        if offset != 0:
            raise PolicyError(f"DEX empty {what} table has a nonzero offset")
        return
    if offset == 0 or (alignment and offset % alignment):
        raise PolicyError(f"DEX {what} table has an invalid offset/alignment")
    if count > (len(data) - offset) // item_size:
        raise PolicyError(f"DEX {what} table extends past end of file")


def _read_uleb128(data: bytes, offset: int, what: str) -> tuple[int, int]:
    value = 0
    for index in range(5):
        if offset >= len(data):
            raise PolicyError(f"DEX truncated ULEB128 in {what}")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << (7 * index)
        if not (byte & 0x80):
            if value > 0xFFFFFFFF:
                raise PolicyError(f"DEX ULEB128 overflow in {what}")
            return value, offset
    raise PolicyError(f"DEX ULEB128 is longer than five bytes in {what}")


def _decode_mutf8_string(data: bytes, offset: int, *, end_offset: int | None = None) -> str:
    utf16_size, cursor = _read_uleb128(data, offset, "string_data_item length")
    if utf16_size > MAX_TYPE_DESCRIPTOR_UTF16_UNITS:
        raise PolicyError("DEX type descriptor exceeds the scanner's bounded string limit")

    utf16 = bytearray()
    max_encoded_bytes = 3 * utf16_size
    end_limit = min(
        len(data),
        cursor + max_encoded_bytes + 1,
        len(data) if end_offset is None else end_offset,
    )
    terminated = False
    while cursor < end_limit:
        first = data[cursor]
        cursor += 1
        if first == 0:
            terminated = True
            break
        if 0x01 <= first <= 0x7F:
            unit = first
        elif 0xC0 <= first <= 0xDF:
            if cursor >= end_limit or data[cursor] & 0xC0 != 0x80:
                raise PolicyError("DEX malformed two-byte MUTF-8 sequence")
            second = data[cursor]
            cursor += 1
            unit = ((first & 0x1F) << 6) | (second & 0x3F)
            if unit == 0:
                if first != 0xC0 or second != 0x80:
                    raise PolicyError("DEX invalid MUTF-8 null encoding")
            elif unit < 0x80:
                raise PolicyError("DEX overlong two-byte MUTF-8 sequence")
        elif 0xE0 <= first <= 0xEF:
            if cursor + 1 >= end_limit:
                raise PolicyError("DEX truncated three-byte MUTF-8 sequence")
            second, third = data[cursor], data[cursor + 1]
            if second & 0xC0 != 0x80 or third & 0xC0 != 0x80:
                raise PolicyError("DEX malformed three-byte MUTF-8 sequence")
            cursor += 2
            unit = ((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F)
            if unit < 0x800:
                raise PolicyError("DEX overlong three-byte MUTF-8 sequence")
        else:
            # MUTF-8 encodes UTF-16 code units with one-to-three-byte forms;
            # raw NUL and four-byte UTF-8 sequences are not valid string data.
            raise PolicyError("DEX contains an unsupported MUTF-8 lead byte")
        utf16.extend((unit & 0xFF, (unit >> 8) & 0xFF))
        if len(utf16) // 2 > utf16_size:
            raise PolicyError("DEX MUTF-8 string exceeds declared UTF-16 length")

    if not terminated:
        raise PolicyError("DEX MUTF-8 string is unterminated or exceeds its bound")
    if len(utf16) // 2 != utf16_size:
        raise PolicyError("DEX MUTF-8 UTF-16 length does not match string_data_item")
    try:
        return bytes(utf16).decode("utf-16-le", errors="surrogatepass")
    except UnicodeDecodeError as exc:
        raise PolicyError(f"DEX MUTF-8 string cannot be decoded: {exc}") from exc


def _validate_type_descriptor(descriptor: str) -> None:
    if not descriptor:
        raise PolicyError("DEX type_ids contains an empty descriptor")
    dimensions = len(descriptor) - len(descriptor.lstrip("["))
    if dimensions > 255:
        raise PolicyError("DEX type descriptor exceeds array dimension limit")
    base = descriptor[dimensions:]
    if base in {"Z", "B", "S", "C", "I", "J", "F", "D"}:
        return
    if base == "V" and dimensions == 0:
        return
    if not (base.startswith("L") and base.endswith(";")):
        raise PolicyError(f"DEX malformed type descriptor: {descriptor!r}")
    internal_name = base[1:-1]
    if (
        not internal_name
        or internal_name.startswith("/")
        or internal_name.endswith("/")
        or "//" in internal_name
        or any(character in internal_name for character in ".;[\x00")
    ):
        raise PolicyError(f"DEX malformed object type descriptor: {descriptor!r}")


def _validate_map(
    data: bytes, map_offset: int, required: dict[int, tuple[int, int]]
) -> dict[int, tuple[int, int]]:
    if map_offset == 0 or map_offset % 4 or map_offset + 4 > len(data):
        raise PolicyError("DEX map_list offset is invalid")
    map_count = _u32(data, map_offset, "map_list size")
    if map_count == 0 or map_count > (len(data) - map_offset - 4) // 12:
        raise PolicyError("DEX map_list is empty or truncated")
    seen: dict[int, tuple[int, int]] = {}
    offsets: list[int] = []
    for index in range(map_count):
        cursor = map_offset + 4 + index * 12
        item_type, unused, item_count, item_offset = struct.unpack_from("<HHII", data, cursor)
        if unused != 0 or item_count == 0 or item_offset >= len(data):
            raise PolicyError("DEX map_list contains an invalid map_item")
        if item_type in seen:
            raise PolicyError("DEX map_list contains a duplicate item type")
        seen[item_type] = (item_count, item_offset)
        offsets.append(item_offset)
    if offsets != sorted(offsets):
        raise PolicyError("DEX map_list entries are not ordered by file offset")
    if seen.get(0x0000) != (1, 0) or seen.get(0x1000) != (1, map_offset):
        raise PolicyError("DEX map_list does not describe its header/map_list")
    for item_type, values in required.items():
        if seen.get(item_type) != values:
            raise PolicyError(f"DEX map_list disagrees with header for item type 0x{item_type:04x}")
    return seen


def parse_dex_type_tables(data: bytes, *, descriptor_budget: DescriptorBudget | None = None) -> ParsedDex:
    """Parse bounded DEX type/class tables needed by policy checks only."""
    if len(data) < DEX_HEADER_SIZE or data[:4] != b"dex\n" or data[7] != 0:
        raise PolicyError("DEX magic/header is truncated or invalid")
    version = data[4:7]
    if version not in SUPPORTED_DEX_VERSIONS:
        raise PolicyError(f"unsupported DEX version {version.decode('ascii', errors='replace')!r}")
    declared_file_size = _u32(data, 32, "file_size")
    header_size = _u32(data, 36, "header_size")
    endian_tag = _u32(data, 40, "endian_tag")
    if declared_file_size != len(data):
        raise PolicyError("DEX file_size does not match extracted DEX bytes")
    if header_size != DEX_HEADER_SIZE:
        raise PolicyError("unsupported DEX header_size")
    if endian_tag == REVERSE_ENDIAN_TAG:
        raise PolicyError("reverse-endian DEX is unsupported")
    if endian_tag != LITTLE_ENDIAN_TAG:
        raise PolicyError("DEX endian_tag is unknown")
    # These are the DEX header's own integrity fields, not APK signing
    # verification or an authenticity claim.
    if data[12:32] != hashlib.sha1(data[32:], usedforsecurity=False).digest():
        raise PolicyError("DEX SHA-1 signature field does not match file contents")
    if _u32(data, 8, "checksum") != (zlib.adler32(data[12:]) & 0xFFFFFFFF):
        raise PolicyError("DEX Adler-32 checksum field does not match file contents")

    map_offset = _u32(data, 52, "map_off")
    string_count = _u32(data, 56, "string_ids_size")
    string_offset = _u32(data, 60, "string_ids_off")
    type_count = _u32(data, 64, "type_ids_size")
    type_offset = _u32(data, 68, "type_ids_off")
    class_count = _u32(data, 96, "class_defs_size")
    class_offset = _u32(data, 100, "class_defs_off")
    data_size = _u32(data, 104, "data_size")
    data_offset = _u32(data, 108, "data_off")

    if string_count > MAX_STRING_IDS:
        raise PolicyError("DEX string_ids_size exceeds scanner limit before offset table allocation")
    if type_count == 0:
        raise PolicyError("DEX has no type_ids to inspect")
    if type_count > 65_535:
        raise PolicyError("DEX type_ids_size exceeds format limit")
    if data_offset < header_size or data_offset % 4 or data_offset + data_size != len(data):
        raise PolicyError("DEX data section bounds are invalid")
    if not (data_offset <= map_offset < len(data)):
        raise PolicyError("DEX map_list is outside the data section")

    _table_bounds(data=data, count=string_count, offset=string_offset, item_size=4, what="string_ids")
    _table_bounds(data=data, count=type_count, offset=type_offset, item_size=4, what="type_ids")
    _table_bounds(data=data, count=class_count, offset=class_offset, item_size=32, what="class_defs")
    for count, offset, item_size, name in (
        (string_count, string_offset, 4, "string_ids"),
        (type_count, type_offset, 4, "type_ids"),
        (class_count, class_offset, 32, "class_defs"),
    ):
        if count and offset + count * item_size > data_offset:
            raise PolicyError(f"DEX {name} table overlaps the data section")
    required_map: dict[int, tuple[int, int]] = {}
    if string_count:
        required_map[0x0001] = (string_count, string_offset)
    if type_count:
        required_map[0x0002] = (type_count, type_offset)
    if class_count:
        required_map[0x0006] = (class_count, class_offset)
    map_items = _validate_map(data, map_offset, required_map)
    if string_count:
        string_data_count, string_data_offset = map_items.get(0x2002, (0, 0))
        if string_data_count != string_count or not (data_offset <= string_data_offset < len(data)):
            raise PolicyError("DEX map_list string_data_item range disagrees with string_ids")
    else:
        raise PolicyError("DEX type_ids require a nonempty string_ids table")
    string_data_end = min(
        (offset for _count, offset in map_items.values() if offset > string_data_offset),
        default=len(data),
    )

    # Sort only physical offsets, not type indices or values. The table-count
    # limit is checked above before this bounded allocation. Lookup for a
    # type's string_id stays in the original table; sorting cannot hide an
    # unsorted descriptor_idx or skip a reference.
    sorted_offsets = sorted(
        _u32(data, string_offset + 4 * index, f"string_ids[{index}]")
        for index in range(string_count)
    )
    previous_offset = -1
    for offset in sorted_offsets:
        if not (string_data_offset <= offset < string_data_end):
            raise PolicyError("DEX string_id points outside mapped string_data_item range")
        if offset == previous_offset:
            raise PolicyError("DEX string_ids contains a duplicate string_data offset")
        previous_offset = offset

    # Preflight *all* referenced strings before allocating any decoded text.
    # Searching raw NUL finds the MUTF-8 terminator without materializing a
    # payload; the decoder still validates MUTF-8/UTF-16 lengths afterwards.
    # The next physical string offset bounds each scan, rejecting aliasing
    # intervals instead of repeatedly decoding overlapping long payloads.
    descriptor_ranges: list[tuple[int, int]] = []
    cumulative_units = 0
    cumulative_bytes = 0
    previous_descriptor_index = -1
    for index in range(type_count):
        descriptor_index = _u32(data, type_offset + 4 * index, f"type_ids[{index}].descriptor_idx")
        if descriptor_index >= string_count:
            raise PolicyError("DEX type_id descriptor_idx exceeds string_ids_size")
        if descriptor_index <= previous_descriptor_index:
            raise PolicyError("DEX type_ids are duplicated or not descriptor-sorted")
        previous_descriptor_index = descriptor_index
        offset = _u32(data, string_offset + 4 * descriptor_index, "descriptor string_id")
        utf16_size, start = _read_uleb128(data, offset, "descriptor length preflight")
        if utf16_size > MAX_TYPE_DESCRIPTOR_UTF16_UNITS:
            raise PolicyError("DEX type descriptor exceeds the scanner's bounded string limit")
        cumulative_units += utf16_size
        if cumulative_units > MAX_DEX_DESCRIPTOR_UTF16_UNITS:
            raise PolicyError("DEX cumulative descriptor UTF-16 units exceed scanner limit")
        next_index = bisect.bisect_right(sorted_offsets, offset)
        next_offset = sorted_offsets[next_index] if next_index < string_count else string_data_end
        end_limit = min(next_offset, start + 3 * utf16_size + 1)
        terminator = data.find(b"\0", start, end_limit)
        if terminator < 0:
            raise PolicyError("DEX descriptor overlaps another string_data item or is unterminated")
        end_offset = terminator + 1
        cumulative_bytes += end_offset - offset  # includes ULEB128 and terminator
        if cumulative_bytes > MAX_DEX_DESCRIPTOR_ENCODED_BYTES:
            raise PolicyError("DEX cumulative descriptor encoded bytes exceed scanner limit")
        descriptor_ranges.append((offset, end_offset))
    del sorted_offsets
    if descriptor_budget is not None:
        descriptor_budget.reserve(cumulative_units, cumulative_bytes)

    type_descriptors: list[str] = []
    seen_descriptors: set[str] = set()
    for offset, end_offset in descriptor_ranges:
        descriptor = _decode_mutf8_string(data, offset, end_offset=end_offset)
        _validate_type_descriptor(descriptor)
        if descriptor in seen_descriptors:
            raise PolicyError("DEX type_ids contains a duplicate type descriptor value")
        seen_descriptors.add(descriptor)
        type_descriptors.append(descriptor)

    defined_classes: set[str] = set()
    superclasses: set[str] = set()
    seen_class_indices: set[int] = set()
    for index in range(class_count):
        cursor = class_offset + 32 * index
        class_index, _access_flags, superclass_index = struct.unpack_from("<III", data, cursor)
        if class_index >= type_count:
            raise PolicyError("DEX class_def class_idx exceeds type_ids_size")
        if class_index in seen_class_indices:
            raise PolicyError("DEX class_defs contains a duplicate class_idx")
        seen_class_indices.add(class_index)
        class_descriptor = type_descriptors[class_index]
        if not (class_descriptor.startswith("L") and class_descriptor.endswith(";")):
            raise PolicyError("DEX class_def does not name an object type")
        defined_classes.add(class_descriptor)
        if superclass_index != NO_INDEX:
            if superclass_index >= type_count:
                raise PolicyError("DEX class_def superclass_idx exceeds type_ids_size")
            superclass = type_descriptors[superclass_index]
            if not (superclass.startswith("L") and superclass.endswith(";")):
                raise PolicyError("DEX superclass_idx does not name an object type")
            superclasses.add(superclass)

    return ParsedDex(tuple(type_descriptors), frozenset(defined_classes), frozenset(superclasses))


def _canonical_member_name(name: str) -> str:
    if not name or "\x00" in name or "\\" in name or name.startswith("/"):
        raise PolicyError(f"APK ZIP contains an unsafe member path: {name!r}")
    raw = name[:-1] if name.endswith("/") else name
    parts = raw.split("/")
    if not raw or any(part in ("", ".", "..") for part in parts):
        raise PolicyError(f"APK ZIP contains a non-canonical member path: {name!r}")
    if re.match(r"^[A-Za-z]:", parts[0]):
        raise PolicyError(f"APK ZIP contains a drive-qualified member path: {name!r}")
    return "/".join(parts)


def _validate_archive(apk: Path) -> tuple[list[zipfile.ZipInfo], list[zipfile.ZipInfo]]:
    try:
        archive_size = apk.stat().st_size
    except OSError as exc:
        raise PolicyError(f"cannot stat APK: {exc}") from exc
    if archive_size <= 0 or archive_size > MAX_APK_BYTES:
        raise PolicyError("APK archive size is empty or exceeds scanner limit")

    try:
        with zipfile.ZipFile(apk, "r") as archive:
            infos = archive.infolist()
            if not infos or len(infos) > MAX_ZIP_ENTRIES:
                raise PolicyError("APK ZIP entry count is empty or exceeds scanner limit")
            names: set[str] = set()
            total_uncompressed = 0
            dex_infos: list[zipfile.ZipInfo] = []
            manifest_count = 0
            for info in infos:
                # CPython's zipfile truncates ZipInfo.filename at the first NUL
                # while keeping the unmodified bytes in orig_filename. Validate
                # the original representation so a raw "classes.dex\x00ab" entry
                # is never treated as canonical root classes.dex.
                raw_name = info.orig_filename
                if "\x00" in raw_name:
                    raise PolicyError(f"APK ZIP contains NUL in member name: {raw_name!r}")
                if raw_name != info.filename:
                    raise PolicyError(
                        "APK ZIP member name was normalized by zipfile: "
                        f"{raw_name!r} != {info.filename!r}"
                    )
                canonical = _canonical_member_name(info.filename)
                if canonical in names:
                    raise PolicyError(f"APK ZIP has duplicate normalized member: {canonical!r}")
                names.add(canonical)
                unix_mode = (info.external_attr >> 16) & 0o170000
                if unix_mode == stat.S_IFLNK:
                    raise PolicyError(f"APK ZIP symlink member is unsupported: {canonical!r}")
                if info.flag_bits & 0x1:
                    raise PolicyError(f"APK ZIP encrypted member is unsupported: {canonical!r}")
                if info.file_size < 0 or info.compress_size < 0:
                    raise PolicyError(f"APK ZIP member has invalid sizes: {canonical!r}")
                total_uncompressed += info.file_size
                if total_uncompressed > MAX_UNCOMPRESSED_BYTES:
                    raise PolicyError("APK ZIP uncompressed size exceeds scanner limit")
                if canonical == "AndroidManifest.xml" and not info.is_dir():
                    manifest_count += 1
                if canonical.lower().endswith(".dex") and not info.is_dir():
                    if "/" in canonical or not DEX_ROOT_NAME.fullmatch(canonical):
                        raise PolicyError(f"APK DEX member is not a supported root classes*.dex: {canonical!r}")
                    if info.file_size > MAX_SINGLE_DEX_BYTES:
                        raise PolicyError(f"APK DEX member exceeds per-file scanner limit: {canonical}")
                    dex_infos.append(info)

            if manifest_count != 1:
                raise PolicyError("APK must contain exactly one root AndroidManifest.xml")
            # Use the validated dex entries (non-directory members that passed the
            # orig_filename/canonical checks above). Checking the plain names
            # set would also match a "classes.dex/" directory entry.
            if not any(info.filename == "classes.dex" for info in dex_infos):
                raise PolicyError("APK must contain root classes.dex")
            if not dex_infos or len(dex_infos) > MAX_DEX_COUNT:
                raise PolicyError("APK DEX count is empty or exceeds scanner limit")
            total_dex_size = sum(info.file_size for info in dex_infos)
            if total_dex_size > MAX_TOTAL_DEX_BYTES:
                raise PolicyError("APK total DEX size exceeds scanner limit")

            # Stream to EOF instead of using an unbounded read/testzip path.
            # ZipExtFile verifies each CRC at EOF; this also caps *actual*
            # expanded bytes, not only central-directory declarations.
            actual_total = 0
            for info in infos:
                actual_member = 0
                with archive.open(info, "r") as member:
                    while True:
                        block = member.read(64 * 1024)
                        if not block:
                            break
                        actual_member += len(block)
                        actual_total += len(block)
                        if actual_member > info.file_size:
                            raise PolicyError(
                                f"APK ZIP member expands beyond declared size: {info.filename!r}"
                            )
                        if actual_total > MAX_UNCOMPRESSED_BYTES:
                            raise PolicyError("APK actual ZIP expansion exceeds scanner limit")
                if actual_member != info.file_size:
                    raise PolicyError(
                        f"APK ZIP member size disagrees with directory: {info.filename!r}"
                    )
            return infos, dex_infos
    except PolicyError:
        raise
    except (OSError, EOFError, RuntimeError, NotImplementedError, zipfile.BadZipFile, zipfile.LargeZipFile, zlib.error) as exc:
        raise PolicyError(f"APK ZIP structure/parser failure: {exc}") from exc


def _kill_and_reap(process: subprocess.Popen[bytes]) -> None:
    """Kill a tool process group and reap its leader without an unbounded wait."""
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        try:
            process.kill()
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            process.kill()
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            # Do not let cleanup itself defeat the tool deadline. SIGKILL has
            # already been sent; the OS will reap a process exiting from an
            # uninterruptible kernel wait when it becomes schedulable.
            pass


def _run_bounded_stdout(command: list[str], label: str, max_bytes: int = MAX_AAPT_OUTPUT_BYTES) -> str:
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except OSError as exc:
        raise PolicyError(f"cannot start {label}: {exc}") from exc
    assert process.stdout is not None
    output = bytearray()
    deadline = time.monotonic() + TOOL_TIMEOUT_SECONDS
    selector = selectors.DefaultSelector()
    try:
        file_descriptor = process.stdout.fileno()
        os.set_blocking(file_descriptor, False)
        selector.register(file_descriptor, selectors.EVENT_READ)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise PolicyError(f"{label} timed out after {TOOL_TIMEOUT_SECONDS:g}s")
            if not selector.select(remaining):
                raise PolicyError(f"{label} timed out after {TOOL_TIMEOUT_SECONDS:g}s")
            read_limit = min(64 * 1024, max_bytes + 1 - len(output))
            chunk = os.read(file_descriptor, read_limit)
            if not chunk:
                break
            output.extend(chunk)
            if len(output) > max_bytes:
                raise PolicyError(f"{label} output exceeds bounded parser limit")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise PolicyError(f"{label} timed out after {TOOL_TIMEOUT_SECONDS:g}s")
        try:
            return_code = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired as exc:
            raise PolicyError(f"{label} timed out after {TOOL_TIMEOUT_SECONDS:g}s") from exc
    except BaseException:
        if process.poll() is None:
            _kill_and_reap(process)
        raise
    finally:
        selector.close()
        process.stdout.close()
    if return_code != 0:
        raise PolicyError(f"{label} exited nonzero ({return_code})")
    if not output.strip():
        raise PolicyError(f"{label} returned empty output")
    try:
        return output.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise PolicyError(f"{label} output is not valid UTF-8") from exc


def _validate_aapt_output(aapt: str, apk: Path) -> tuple[str, str]:
    permissions = _run_bounded_stdout(
        [aapt, "dump", "permissions", str(apk)], "aapt dump permissions"
    )
    if not re.search(r"(?m)^package:\s*\S+", permissions):
        raise PolicyError("aapt permissions output lacks a package record")
    xmltree = _run_bounded_stdout(
        [aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml"],
        "aapt dump xmltree",
    )
    if not re.search(r"(?im)^\s*E:\s*manifest\b", xmltree):
        raise PolicyError("aapt xmltree output lacks a manifest root")
    return permissions, xmltree


def _application_manifest_block(xmltree: str) -> str:
    """Slice the merged <application> element's dump lines out of the tree.

    aapt prints one line per element/attribute with two-space indent steps
    (``  E: application``); the block ends at the next ``E:`` element line of
    the same or shallower indentation. A manifest without an application
    element cannot carry either backup binding, so absence fails closed.
    """
    lines = xmltree.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^(\s*)E:\s*application\b", line)
        if match is None:
            continue
        indent = match.group(1)
        end = len(lines)
        for follow in range(index + 1, len(lines)):
            sibling = re.match(r"^(\s*)E:\s", lines[follow])
            if sibling and len(sibling.group(1)) <= len(indent):
                end = follow
                break
        return "\n".join(lines[index:end])
    raise PolicyError(
        "merged manifest lacks an <application> element - the backup and "
        "network security bindings cannot survive a manifest that has no "
        "application to bind"
    )


def _resources_table_resolves(
    resources: str,
    identifier: int,
    resource: str,
    attr_name: str,
    binding_kind: str = "backup",
) -> None:
    """Assert ``identifier`` maps to ``@xml/<resource>`` in a resources dump.

    Shared by the backup and the networkSecurityConfig binding authorities -
    the id↔name column is the merger-proof half of the pin (the attr value
    alone can name a resource another id owns). The aapt spelling tolerance
    (v1 ``spec … pkg:xml/<name>:`` form and aapt2 plain form) prevents an
    output-format change from silently bypassing the check.
    """
    resource_name = re.escape("xml/" + resource)
    # aapt v1: "spec resource 0x7f010000 pkg:xml/backup_rules: flags=…"
    spec_line = re.compile(
        r"^\s*(?:spec\s+)?resource 0x%08x\s+\S+:%s\s*:" % (identifier, resource_name),
        re.MULTILINE,
    )
    # aapt2: "resource 0x7f010000 xml/backup_rules …" (no spec/pkg form).
    aapt2_line = re.compile(
        r"^\s*resource 0x%08x\s+%s\b" % (identifier, resource_name),
        re.MULTILINE,
    )
    if not (spec_line.search(resources) or aapt2_line.search(resources)):
        raise PolicyError(
            f"merged manifest android:{attr_name} resource id 0x{identifier:08x} "
            f"does not resolve to @xml/{resource} in the resources table "
            f"- rebound {binding_kind} binding"
        )


def _assert_merged_backup_bindings(aapt: str, apk: Path, xmltree: str) -> None:
    """Assert the merged <application> still binds the backup rules resources.

    The manifest merger runs between the validated source manifests and the
    bytes aapt sees; a marker such as ``tools:node="replace"`` or
    ``tools:remove="android:fullBackupContent"`` silently deletes the binding,
    and at runtime AOSP FullBackup then copies EVERYTHING (key files included).
    This artifact-level assertion is the authority on the merged bytes - the
    source-level manifest validation (``backup_rules_check.py``) cannot observe
    what the merger does. Both aapt spellings are accepted: the namespace is
    spelled ``android:...`` by aapt v1 dumps and
    ``http://schemas.android.com/apk/res/android:...`` by aapt2; aapt v1 prints
    resource references as ``=@0x…`` and string values as the raw literal.
    """
    resolved: dict[str, tuple[str, int]] = {}
    # Scope the attribute search to the merged <application> block: a binding
    # line on any other node must not satisfy this gate (and a manifest with
    # no application element cannot carry the bindings at all).
    application_block = _application_manifest_block(xmltree)
    for name, resource_id, resource in _BACKUP_BINDING_ATTRS:
        match = re.search(
            r"^\s*A:\s+(?:android:|http://schemas\.android\.com/apk/res/android:)"
            + re.escape(name)
            + r"\(\s*"
            + re.escape(resource_id)
            + r"\s*\)=(.+)$",
            application_block,
            re.MULTILINE,
        )
        if match is None:
            raise PolicyError(
                "merged manifest lost the backup binding "
                f"android:{name} ({resource_id}) on <application>: a "
                'manifest-merger marker (tools:node="replace" / tools:remove) '
                "can strip it; Auto Backup would then copy the protected key files"
            )
        value = match.group(1).strip()
        # aapt v1 prints "@0x7f010000"; aapt2 may spell it "@res/0x7f010000".
        reference = re.match(r"@(?:res/)?0x([0-9a-fA-F]+)", value)
        if reference is not None:
            resolved[name] = (resource, int(reference.group(1), 16))
            continue
        # Plain-string forms print the resource name as a literal instead of
        # an id (older aapt prints Raw string values without an id guess).
        literal = re.search(r"@[\w/]+", value)
        if literal is None or literal.group(0) != f"@xml/{resource}":
            got = literal.group(0) if literal else value
            raise PolicyError(
                f"merged manifest android:{name} does not reference "
                f"@xml/{resource} (got {got}) - rebound backup binding"
            )
    if not resolved:
        return
    resources = _run_bounded_stdout(
        [aapt, "dump", "resources", str(apk)],
        "aapt dump resources",
        max_bytes=MAX_RESOURCES_DUMP_BYTES,
    )
    for name, (resource, identifier) in resolved.items():
        _resources_table_resolves(resources, identifier, resource, name)


def _unescape_dump_string(value: str) -> str:
    """Decode aapt's C-style escapes in dumped quoted strings.

    aapt prints string payloads with ``"\\n"``/``"\\t"``/``"\\r"``/``"\\""``
    /``"\\\\"`` escapes inside the quotes. Unknown escapes are kept literal
    rather than guessed away: the downstream schema checks (nsc_check.py)
    fail closed on the resulting value in both directions, so decoding must
    not become a bypass channel.
    """
    decoded: list[str] = []
    index = 0
    while index < len(value):
        character = value[index]
        if character == "\\" and index + 1 < len(value):
            following = value[index + 1]
            mapped = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(following)
            if mapped is not None:
                decoded.append(mapped)
                index += 2
                continue
        decoded.append(character)
        index += 1
    return "".join(decoded)


def _xmltree_attr_value(raw_value: str, label: str) -> str:
    """Decode one printed ``A: <name>=<value>`` payload from a xmltree dump.

    Measured aapt encodings (real APK dumps, build-tools 35/36):
      * booleans print without quotes as ``(type 0x12)0x0`` /
        ``(type 0x12)0xffffffff``; a boolean exists only as these two values
        in compiled binary XML, so any other typed encoding fails closed
        instead of being guessed into the tree;
      * strings print as ``"value" (Raw: "value")`` - the RAW payload wins
        when both spellings appear and both go through the escape decoder;
      * resource references print unquoted (``@0x…``, ``@res/0x…``,
        ``?0x…``) and pass through verbatim; schema checks reject them
        wherever non-string values are illegal.
    """
    value = raw_value.strip()
    typed = re.match(r"^\(type\s+[^\s)]+\)(.*)$", value)
    if typed is not None:
        payload = typed.group(1).strip()
        if payload == "0x0":
            return "false"
        if payload == "0xffffffff":
            return "true"
        raise PolicyError(
            f"{label}: unrecognized compiled attribute encoding {value!r}"
        )
    quoted = re.match(
        r'^"((?:[^"\\]|\\.)*)"\s*(?:\(Raw:\s*"((?:[^"\\]|\\.)*)"\s*\))?(?:\s*)?$', value
    )
    if quoted is not None:
        if quoted.group(2) is not None:
            return _unescape_dump_string(quoted.group(2))
        return _unescape_dump_string(quoted.group(1))
    if re.fullmatch(r"@(?:res/)?0x[0-9a-fA-F]+", value) or re.fullmatch(
        r"\?0x[0-9a-fA-F]+", value
    ):
        return value
    raise PolicyError(f"{label}: unrecognized attribute value encoding {value!r}")


def _xmltree_text_value(raw_text: str, label: str) -> str:
    """Decode one printed ``C:`` chardata payload (always quoted+escaped)."""
    quoted = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', raw_text.strip())
    if quoted is None:
        raise PolicyError(f"{label}: unrecognized chardata encoding {raw_text!r}")
    return _unescape_dump_string(quoted.group(1))


_XMLTREE_ELEMENT = re.compile(r"^(\s*)E:\s*([^(\s]+)\s*(?:\(line=\d+\))?\s*$")
_XMLTREE_ATTRIBUTE = re.compile(r"^(\s*)A:\s+([^=]+)=(.*)$")
_XMLTREE_TEXT = re.compile(r"^(\s*)C:\s+(.*)$")
_XMLTREE_ERROR = re.compile(r"^\s*ERROR:")
_XMLTREE_ATTR_ID_PARENS = re.compile(r"\(\s*0x[0-9a-fA-F]+\s*\)\s*$")


def _parse_aapt_xmltree(dump: str, label: str) -> ET.Element:
    """Rebuild one ElementTree from ``aapt dump xmltree`` output (fail closed).

    aapt prints one line per element/attribute with two-space indent steps:
    elements ``E: <tag> (line=N)``, attributes ``A: <name>[(<id>)]=<value>``,
    chardata ``C: "text"``, namespace declarations ``N: prefix=uri``. The N:
    lines only rename attribute prefixes and are dropped; indentation defines
    the hierarchy RELATIVE to the first element line, so a dump whose root
    sits deeper (namespace lines above it) still rebuilds.

    Beyond this shape everything fails closed: ERROR lines are detected here
    because at least one observed aapt exits 0 after printing ``ERROR: dump
    failed because resource … not found`` (never trust the exit status
    alone), a second root at indent depth 0 is a parse anomaly, and text
    segments are concatenated (fine for schema checks; the NSC schema allows
    text only where no children may appear).
    """
    root: ET.Element | None = None
    stack: list[tuple[int, ET.Element]] = []
    for line in dump.splitlines():
        if not line.strip():
            continue
        if _XMLTREE_ERROR.match(line):
            raise PolicyError(
                f"{label} reported a dump ERROR line (fail closed): {line.strip()}"
            )
        element_match = _XMLTREE_ELEMENT.match(line)
        if element_match is not None:
            indent, tag = element_match.group(1), element_match.group(2)
            element = ET.Element(tag)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1].append(element)
            else:
                if root is not None:
                    raise PolicyError(f"{label} contains multiple root elements")
                root = element
            stack.append((indent, element))
            continue
        attribute_match = _XMLTREE_ATTRIBUTE.match(line)
        if attribute_match is not None:
            if not stack:
                raise PolicyError(f"{label} has an attribute outside any element")
            name = _XMLTREE_ATTR_ID_PARENS.sub("", attribute_match.group(2).strip())
            stack[-1][1].set(name, _xmltree_attr_value(attribute_match.group(3), label))
            continue
        text_match = _XMLTREE_TEXT.match(line)
        if text_match is not None:
            if not stack:
                raise PolicyError(f"{label} has text outside any element")
            current = stack[-1][1]
            current.text = (current.text or "") + _xmltree_text_value(
                text_match.group(2), label
            )
            continue
        if line.lstrip().startswith("N:"):
            continue
        raise PolicyError(f"{label}: unrecognized dump line: {line.strip()!r}")
    if root is None:
        raise PolicyError(f"{label} contains no element tree")
    return root


def _load_nsc_rules() -> types.ModuleType:
    """Load the shared network-security rule engine (``scripts/nsc_check.py``).

    Inserting this file's own directory keeps ``import nsc_check`` resolving
    when this scanner runs as a bare script (sys.path[0] = scripts/) AND as
    the ``scripts.apk_policy_inspect`` package module (repo root on sys.path):
    either way the compiled XML is validated with the exact rules the source
    gate runs, never a divergent local reimplementation.
    """
    scripts_dir = str(Path(__file__).resolve().parent)
    if scripts_dir not in sys.path:
        sys.path.insert(0, scripts_dir)
    import nsc_check

    return nsc_check


def _network_security_mode(application_block: str) -> str:
    """Derive the NSC rule mode from the merged manifest's debuggable attr.

    Measured merged-manifest encoding (real debug APK):
    ``android:debuggable(0x0101000f)=(type 0x12)0xffffffff`` when the build
    is debuggable; absence and ``(type 0x12)0x0`` are the non-debug forms.
    ONLY the exact true encoding proves DEBUG mode - everything else
    (absent/false/unknown spelling) is validated with the stricter release
    deny-all rules, fail-closed against proof-by-promise.
    """
    debuggable = re.search(
        r"^\s*A:\s+(?:android:|http://schemas\.android\.com/apk/res/android:)"
        + r"debuggable\(\s*"
        + re.escape(_DEBUGGABLE_ATTR_ID)
        + r"\s*\)=(.+)$",
        application_block,
        re.MULTILINE,
    )
    if debuggable is not None and debuggable.group(1).strip() == "(type 0x12)0xffffffff":
        return "debug"
    return "release"


def _assert_merged_network_security_config(aapt: str, apk: Path, xmltree: str) -> None:
    """Assert the merged NSC binding AND the compiled XML's policy shape.

    Both halves are artifact-level authority (the source stage cannot observe
    what ships): a merger marker can strip ``android:networkSecurityConfig``
    so cleartext falls back to the platform default, or rebind it to a
    resource no source gate ever validated. Once the pin survives, the
    compiled resource itself is dumped via ``aapt dump xmltree`` and rebuilt
    by ``_parse_aapt_xmltree`` - a compiled-XML alias cannot differ from
    source undetected - then validated with ``scripts/nsc_check.py
    .validate_tree`` under the mode derived from ``android:debuggable``.
    """
    application_block = _application_manifest_block(xmltree)
    mode = _network_security_mode(application_block)
    match = re.search(
        r"^\s*A:\s+(?:android:|http://schemas\.android\.com/apk/res/android:)"
        + re.escape(_NETWORK_SECURITY_ATTR_NAME)
        + r"\(\s*"
        + re.escape(_NETWORK_SECURITY_ATTR_ID)
        + r"\s*\)=(.+)$",
        application_block,
        re.MULTILINE,
    )
    if match is None:
        raise PolicyError(
            "merged manifest lost the network security config binding "
            "android:networkSecurityConfig (0x01010527) on <application>: a "
            'manifest-merger marker (tools:node="replace" / tools:remove) '
            "can strip it; cleartext policy would fall back to the platform "
            "default"
        )
    value = match.group(1).strip()
    bound_by_id = re.match(r"@(?:res/)?0x([0-9a-fA-F]+)", value)
    if bound_by_id is not None:
        # The id column is the merger-proof half of the pin: prove the
        # referenced id IS xml/network_security_config in the table (an id
        # snapshot alias to another name must not pass).
        resources = _run_bounded_stdout(
            [aapt, "dump", "resources", str(apk)],
            "aapt dump resources",
            max_bytes=MAX_RESOURCES_DUMP_BYTES,
        )
        _resources_table_resolves(
            resources,
            int(bound_by_id.group(1), 16),
            _NETWORK_SECURITY_RESOURCE,
            _NETWORK_SECURITY_ATTR_NAME,
            binding_kind="networkSecurityConfig",
        )
    else:
        # Older aapt prints the resource reference as a quoted string literal
        # instead of an id. Decode the FULL payload first (the Raw spelling is
        # the authority; unknown encodings fail closed inside
        # _xmltree_attr_value) and then demand an EXACT match on the whole
        # decoded value: a search()-style prefix hit would let trailing junk
        # such as "@xml/network_security_config;" satisfy this pin while the
        # runtime actually resolves - or fails to resolve - a different value.
        decoded = _xmltree_attr_value(
            value, f"merged manifest android:{_NETWORK_SECURITY_ATTR_NAME}"
        )
        if decoded != f"@xml/{_NETWORK_SECURITY_RESOURCE}":
            raise PolicyError(
                f"merged manifest android:{_NETWORK_SECURITY_ATTR_NAME} does not "
                f"reference @xml/{_NETWORK_SECURITY_RESOURCE} (got {decoded}) - "
                "rebound networkSecurityConfig binding"
            )
    nsc_dump = _run_bounded_stdout(
        [aapt, "dump", "xmltree", str(apk), _NETWORK_SECURITY_MEMBER],
        "aapt dump xmltree (network security config)",
        max_bytes=MAX_RESOURCES_DUMP_BYTES,
    )
    root = _parse_aapt_xmltree(nsc_dump, "aapt xmltree network security config")
    errors = _load_nsc_rules().validate_tree(
        root, mode, _NETWORK_SECURITY_MEMBER
    )
    if errors:
        raise PolicyError(
            f"compiled {_NETWORK_SECURITY_MEMBER} fails the {mode} network "
            f"security rules (artifact-level authority): {'; '.join(errors)}"
        )


def _validate_dexdump(dexdump: str, dex_name: str, dex_bytes: bytes) -> None:
    try:
        with tempfile.TemporaryDirectory(prefix="librepocket-dex-parse-") as temp_dir:
            dex_path = Path(temp_dir) / dex_name
            dex_path.write_bytes(dex_bytes)
            process = subprocess.Popen(
                [dexdump, str(dex_path)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            try:
                return_code = process.wait(timeout=TOOL_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired as exc:
                _kill_and_reap(process)
                raise PolicyError(
                    f"native dexdump timed out for {dex_name} after {TOOL_TIMEOUT_SECONDS:g}s"
                ) from exc
            finally:
                if process.poll() is None:
                    _kill_and_reap(process)
    except OSError as exc:
        raise PolicyError(f"cannot run native dexdump for {dex_name}: {exc}") from exc
    if return_code != 0:
        raise PolicyError(f"native dexdump rejected {dex_name} (exit {return_code})")


def _check_play(permissions: str, xmltree: str, infos: Iterable[zipfile.ZipInfo], dexes: list[ParsedDex]) -> None:
    manifest_dump = (permissions + "\n" + xmltree).upper()
    for token in PLAY_FORBIDDEN_PERMISSIONS:
        if token in manifest_dump:
            raise PolicyError(f"Play APK declares forbidden permission/reference {token}")
    if re.search(r"accessibilityservice|vpnservice", xmltree, re.IGNORECASE):
        raise PolicyError("Play APK manifest registers an AccessibilityService/VpnService")

    for dex in dexes:
        for descriptor in dex.defined_classes:
            if any(descriptor.startswith(prefix) for prefix in PLAY_DEFINED_CLASS_PREFIXES):
                raise PolicyError(f"Play APK defines self-install-only class {descriptor}")
        for superclass in dex.superclasses:
            if superclass in PLAY_FORBIDDEN_SUPERCLASSES:
                raise PolicyError(f"Play APK subclasses forbidden service {superclass}")

    for info in infos:
        path = info.filename.lower()
        for forbidden in ("proot", "rootfs", "linux/image"):
            if forbidden in path:
                raise PolicyError(f"Play APK embeds on-device Linux payload ({forbidden}): {info.filename}")


def _check_foss(permissions: str, xmltree: str, dexes: list[ParsedDex]) -> None:
    manifest_dump = (permissions + "\n" + xmltree).lower()
    for needle in FOSS_MANIFEST_NEEDLES:
        if needle in manifest_dump:
            raise PolicyError(f"Foss APK manifest references proprietary component {needle}")

    for dex in dexes:
        for descriptor in dex.type_descriptors:
            class_descriptor = descriptor.lstrip("[")
            if any(class_descriptor.startswith(prefix) for prefix in FOSS_TYPE_PREFIXES):
                raise PolicyError(f"Foss APK DEX type_ids references proprietary type {descriptor}")


def inspect_apk(apk: Path, mode: str, aapt: str, dexdump: str) -> None:
    if apk.suffix.lower() == ".aab":
        raise PolicyError("AAB is unsupported by this APK-only policy scanner")
    if apk.suffix.lower() != ".apk":
        raise PolicyError("policy scanner accepts APK files only")
    for name, tool in (("aapt", aapt), ("dexdump", dexdump)):
        if not Path(tool).is_file() or not os.access(tool, os.X_OK):
            raise PolicyError(f"required Android native tool is missing or not executable: {name}")

    infos, dex_infos = _validate_archive(apk)
    permissions, xmltree = _validate_aapt_output(aapt, apk)
    # Backup bindings on the MERGED manifest (artifact-level authority) run
    # for every policy mode: Play and Foss ship the same binding contract.
    _assert_merged_backup_bindings(aapt, apk, xmltree)
    # networkSecurityConfig binding + compiled-XML rule check (same
    # artifact-level authority, scripts/nsc_check.py rule engine); joined
    # execution with the backup gate so one merged-manifest difference keeps
    # every policy contract provable in this artifact.
    _assert_merged_network_security_config(aapt, apk, xmltree)
    parsed_dexes: list[ParsedDex] = []
    descriptor_budget = DescriptorBudget()
    with zipfile.ZipFile(apk, "r") as archive:
        for info in dex_infos:
            with archive.open(info, "r") as member:
                dex_bytes = member.read(MAX_SINGLE_DEX_BYTES + 1)
            if len(dex_bytes) != info.file_size or len(dex_bytes) > MAX_SINGLE_DEX_BYTES:
                raise PolicyError(f"APK DEX expanded size mismatch: {info.filename}")
            parsed = parse_dex_type_tables(dex_bytes, descriptor_budget=descriptor_budget)
            _validate_dexdump(dexdump, info.filename, dex_bytes)
            parsed_dexes.append(parsed)

    if mode == "play":
        _check_play(permissions, xmltree, infos, parsed_dexes)
    elif mode == "foss":
        _check_foss(permissions, xmltree, parsed_dexes)
    else:
        raise PolicyError(f"unknown policy mode: {mode}")
    print(f"  structure: APK ZIP/manifest/root DEX tables OK ({len(dex_infos)} DEX shard(s))")
    print(f"  policy: {mode} OK")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("play", "foss"), required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--dexdump", required=True)
    args = parser.parse_args(argv)
    try:
        inspect_apk(Path(args.apk), args.mode, args.aapt, args.dexdump)
        return 0
    except PolicyError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        # Any unexpected archive, binary-parser, or native-tool error is a
        # nonzero policy result rather than a traceback/success fallback.
        print(f"FAIL: artifact parser error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
