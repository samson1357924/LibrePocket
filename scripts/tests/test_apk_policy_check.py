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

import hashlib
import os
import struct
import subprocess
import tempfile
import unittest
import warnings
import zipfile
import zlib
from pathlib import Path
from unittest import mock

from scripts import apk_policy_inspect as policy_inspect

ROOT = Path(__file__).resolve().parents[2]
POLICY_CHECK = ROOT / "scripts" / "play_policy_check.sh"
BUILD_RELEASE = ROOT / "scripts" / "build_release.sh"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
