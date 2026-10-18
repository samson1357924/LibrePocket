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
import zipfile
import zlib
from fnmatch import fnmatchcase
from pathlib import Path
from unittest import mock

from scripts import apk_policy_inspect as policy_inspect
from scripts import backup_rules_check

ROOT = Path(__file__).resolve().parents[2]
POLICY_CHECK = ROOT / "scripts" / "play_policy_check.sh"
BUILD_RELEASE = ROOT / "scripts" / "build_release.sh"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
PR_CHECK_WORKFLOW = ROOT / ".github" / "workflows" / "pr-check.yml"

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
            "  'dump permissions') printf '%s\\n' 'package:dev.librepocket.agent' ;;\n"
            "  'dump xmltree') printf '%s\\n' 'E: manifest' '  E: application' ;;\n"
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
    ) -> dict[str, str]:
        env = os.environ.copy()
        env.update(
            {
                "ANDROID_HOME": str(sdk or self.sdk),
                "HOME": str(self.home),
                "PATH": f"{self.fake_bin}:/usr/bin:/bin",
                "FAKE_AAPT_MODE": aapt_mode,
                "FAKE_DEXDUMP_MODE": dexdump_mode,
                "LC_ALL": "C",
            }
        )
        return env

    def run_policy(
        self,
        apk: Path,
        *,
        foss: bool = False,
        aapt_mode: str = "ok",
        dexdump_mode: str = "ok",
        sdk: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = ["sh", str(POLICY_CHECK)]
        if foss:
            command.append("--foss")
        command.append(str(apk))
        return subprocess.run(
            command,
            cwd=ROOT,
            env=self._env(sdk=sdk, aapt_mode=aapt_mode, dexdump_mode=dexdump_mode),
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

    def test_gate_pins_manifest_resource_binding(self) -> None:
        # Runtime binding: the two manifest attributes must reference the
        # validated resource names (attr↔resource↔schema chain closed).
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertIn('android:fullBackupContent="@xml/backup_rules"', text)
        self.assertIn('android:dataExtractionRules="@xml/data_extraction_rules"', text)
        self.assertIn("must reference @xml/backup_rules", text)

    def test_gate_does_not_use_raw_grep_for_excludes(self) -> None:
        text = POLICY_CHECK.read_text(encoding="utf-8")
        self.assertNotRegex(text, r"grep\b[^|\n]*\b-q\b[^|\n]*\bdomain=")


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
        # apk_policy_inspect.py is imported only in the artifact stage; copy it
        # so the tree is self-consistent anyway.
        shutil.copy2(ROOT / "scripts" / "apk_policy_inspect.py", scripts)
        main_res = root / "app" / "src" / "main" / "res" / "xml"
        main_res.mkdir(parents=True)
        manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
        manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <application android:fullBackupContent="@xml/backup_rules"'
            ' android:dataExtractionRules="@xml/data_extraction_rules" />\n'
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

    def _run_gate(self, tree_root: "Path") -> subprocess.CompletedProcess:
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
            ["sh", str(tree_root / "scripts" / "play_policy_check.sh"), str(dummy)],
            capture_output=True,
            text=True,
            env=env,
            cwd=str(tree_root),
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
            self.assertIn("deep-scan of backup rules resources failed", proc.stderr)
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
