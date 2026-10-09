#!/usr/bin/env python3
"""Verify one APK, then publish a private, read-only staged snapshot and SHA-256."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
from pathlib import Path
from typing import NoReturn

MAX_APK_BYTES = 2 * 1024 * 1024 * 1024
MAX_TOOL_OUTPUT_BYTES = 1024 * 1024
DEFAULT_TOOL_TIMEOUT_SECONDS = 120
MAX_TOOL_TIMEOUT_SECONDS = 300
_ACTIVE_TOOL_PROCESS: subprocess.Popen[bytes] | None = None
APK_NAME = "release.apk"
CHECKSUM_NAME = "SHA256SUMS"
IDENTITY_NAME = "artifact-identity.json"


class VerificationError(Exception):
    """An input, tool, or artifact failed the fail-closed contract."""


def _fail(message: str) -> NoReturn:
    raise VerificationError(message)


def _absolute_path_without_symlinks(value: str, *, final_may_be_missing: bool) -> Path:
    path = Path(os.path.abspath(value))
    parts = path.parts
    current = Path(parts[0])
    for index, component in enumerate(parts[1:], start=1):
        current = current / component
        is_final = index == len(parts) - 1
        try:
            info = os.lstat(current)
        except FileNotFoundError:
            if is_final and final_may_be_missing:
                continue
            _fail(f"path component does not exist: {current}")
        except OSError as exc:
            _fail(f"cannot inspect path component {current}: {exc.strerror or exc}")
        if stat.S_ISLNK(info.st_mode):
            _fail(f"symlink path component is not allowed: {current}")
        if not is_final or not final_may_be_missing:
            if not stat.S_ISDIR(info.st_mode):
                _fail(f"path component is not a directory: {current}")
        elif not is_final:
            if not stat.S_ISDIR(info.st_mode):
                _fail(f"path component is not a directory: {current}")
    return path


def _validate_paths(candidate_value: str, stage_value: str) -> tuple[Path, Path]:
    candidate_dir = _absolute_path_without_symlinks(
        candidate_value, final_may_be_missing=False
    )
    if not candidate_dir.is_dir():
        _fail(f"candidate path is not a directory: {candidate_dir}")
    stage_dir = _absolute_path_without_symlinks(stage_value, final_may_be_missing=True)
    if os.path.lexists(stage_dir):
        _fail(f"staging directory already exists (overwrite is forbidden): {stage_dir}")
    if stage_dir.parent == stage_dir:
        _fail("staging directory cannot be a filesystem root")
    try:
        common = Path(os.path.commonpath((candidate_dir, stage_dir)))
    except ValueError:
        common = None
    if common in (candidate_dir, stage_dir):
        _fail("candidate and staging directories must not overlap")
    return candidate_dir, stage_dir


def _open_candidate_directory(path: Path) -> int:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags | getattr(os, "O_CLOEXEC", 0))
    except OSError as exc:
        _fail(f"cannot safely open candidate directory: {exc.strerror or exc}")
    if not stat.S_ISDIR(os.fstat(fd).st_mode):
        os.close(fd)
        _fail("candidate path is not a regular directory")
    return fd


def _find_unique_candidate(directory_fd: int) -> str:
    try:
        names = os.listdir(directory_fd)
    except OSError as exc:
        _fail(f"cannot enumerate candidate directory: {exc.strerror or exc}")

    apk_names: list[str] = []
    aab_names: list[str] = []
    for name in names:
        lowered = name.lower()
        if lowered.endswith(".aab"):
            aab_names.append(name)
        if lowered.endswith(".apk"):
            try:
                info = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            except OSError as exc:
                _fail(f"cannot inspect APK candidate {name!r}: {exc.strerror or exc}")
            if stat.S_ISLNK(info.st_mode):
                _fail(f"APK candidate must not be a symlink: {name}")
            if not stat.S_ISREG(info.st_mode):
                _fail(f"APK candidate is not a regular file: {name}")
            apk_names.append(name)
    if aab_names:
        _fail("AAB inputs are unsupported; no APK fallback is permitted")
    if not apk_names:
        _fail("candidate directory contains no APK")
    if len(apk_names) != 1:
        _fail(f"candidate directory must contain exactly one APK; found {len(apk_names)}")
    return apk_names[0]


def _same_file_snapshot(before: os.stat_result, after: os.stat_result) -> bool:
    return (
        before.st_dev == after.st_dev
        and before.st_ino == after.st_ino
        and before.st_size == after.st_size
        and before.st_mtime_ns == after.st_mtime_ns
        and before.st_ctime_ns == after.st_ctime_ns
    )


def _hash_fd(fd: int, *, max_bytes: int | None = None) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    os.lseek(fd, 0, os.SEEK_SET)
    while True:
        chunk = os.read(fd, 1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if max_bytes is not None and total > max_bytes:
            _fail(f"APK exceeds the {max_bytes}-byte size limit")
        digest.update(chunk)
    return digest.hexdigest(), total


def _same_directory_snapshot(before: os.stat_result, after: os.stat_result) -> bool:
    return (
        before.st_dev == after.st_dev
        and before.st_ino == after.st_ino
        and before.st_mtime_ns == after.st_mtime_ns
        and before.st_ctime_ns == after.st_ctime_ns
    )


def _copy_candidate_snapshot(source_fd: int, stage_fd: int) -> tuple[str, os.stat_result]:
    before = os.fstat(source_fd)
    if not stat.S_ISREG(before.st_mode):
        _fail("APK candidate is not a regular file")
    if before.st_size <= 0:
        _fail("APK candidate is empty")
    if before.st_size > MAX_APK_BYTES:
        _fail(f"APK exceeds the {MAX_APK_BYTES}-byte size limit")

    destination_fd = os.open(
        APK_NAME,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
        0o600,
        dir_fd=stage_fd,
    )
    digest = hashlib.sha256()
    total = 0
    try:
        os.lseek(source_fd, 0, os.SEEK_SET)
        while True:
            chunk = os.read(source_fd, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_APK_BYTES:
                _fail(f"APK exceeds the {MAX_APK_BYTES}-byte size limit")
            digest.update(chunk)
            view = memoryview(chunk)
            while view:
                written = os.write(destination_fd, view)
                if written <= 0:
                    _fail("short write while staging APK")
                view = view[written:]
        after = os.fstat(source_fd)
        if not _same_file_snapshot(before, after) or total != before.st_size:
            _fail("APK candidate changed while its staged snapshot was being created")
        os.fsync(destination_fd)
        os.fchmod(destination_fd, 0o444)
        os.fsync(destination_fd)
        staged_info = os.fstat(destination_fd)
        if stat.S_IMODE(staged_info.st_mode) != 0o444:
            _fail("staging filesystem did not preserve required 0444 mode for the APK")
        return digest.hexdigest(), staged_info
    finally:
        os.close(destination_fd)


def _child_environment() -> dict[str, str]:
    # Do not pass release passwords, Bitwarden tokens, or arbitrary Java options
    # to native parsers. Retain only the environment needed to locate/run SDK tools.
    environment = {"LC_ALL": "C"}
    for name in ("PATH", "JAVA_HOME", "TMPDIR"):
        value = os.environ.get(name)
        if value:
            environment[name] = value
    return environment


def _kill_process_group(process: subprocess.Popen[bytes]) -> None:
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        try:
            process.kill()
        except ProcessLookupError:
            pass


def terminate_active_tool() -> None:
    """Kill and reap the active SDK tool group when the owning pipeline stops."""
    process = _ACTIVE_TOOL_PROCESS
    if process is None:
        return
    _kill_process_group(process)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def _run_bounded(command: list[str], *, timeout_seconds: int) -> tuple[bytes, bytes]:
    global _ACTIVE_TOOL_PROCESS
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=_child_environment(),
            close_fds=True,
            start_new_session=True,
            bufsize=0,
        )
    except (OSError, ValueError) as exc:
        _fail(f"cannot start required tool {Path(command[0]).name}: {exc}")
    _ACTIVE_TOOL_PROCESS = process
    completed = False
    assert process.stdout is not None and process.stderr is not None
    selector = selectors.DefaultSelector()
    stdout = bytearray()
    stderr = bytearray()
    streams = ((process.stdout, stdout), (process.stderr, stderr))
    try:
        for stream, buffer in streams:
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ, buffer)
        deadline = time.monotonic() + timeout_seconds
        total = 0
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                _kill_process_group(process)
                process.wait()
                _fail(f"required tool timed out after {timeout_seconds} seconds")
            events = selector.select(min(remaining, 0.25))
            for key, _ in events:
                try:
                    chunk = os.read(key.fileobj.fileno(), 65536)
                except OSError as exc:
                    _kill_process_group(process)
                    process.wait()
                    _fail(f"cannot read required tool output: {exc}")
                if not chunk:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                total += len(chunk)
                if total > MAX_TOOL_OUTPUT_BYTES:
                    _kill_process_group(process)
                    process.wait()
                    _fail(f"required tool output exceeds {MAX_TOOL_OUTPUT_BYTES} bytes")
                key.data.extend(chunk)
        returncode = process.wait(timeout=max(0.1, deadline - time.monotonic()))
        if returncode != 0:
            _fail(f"required tool {Path(command[0]).name} exited with status {returncode}")
        completed = True
        return bytes(stdout), bytes(stderr)
    except subprocess.TimeoutExpired:
        _kill_process_group(process)
        process.wait()
        _fail(f"required tool timed out after {timeout_seconds} seconds")
    finally:
        if not completed:
            _kill_process_group(process)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if _ACTIVE_TOOL_PROCESS is process:
            _ACTIVE_TOOL_PROCESS = None
        selector.close()
        for stream in (process.stdout, process.stderr):
            if not stream.closed:
                stream.close()


def _decode_output(output: bytes, tool_name: str) -> str:
    try:
        return output.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        _fail(f"{tool_name} output is not valid UTF-8")


def _parse_apksigner_output(output: bytes, expected_cert: str) -> tuple[str, str]:
    text = _decode_output(output, "apksigner")
    lines = text.splitlines()
    if lines.count("Verifies") != 1:
        _fail("apksigner output lacks one unambiguous successful verification marker")

    signer_count_lines = [line for line in lines if line.startswith("Number of signers:")]
    signer_count_match = (
        re.fullmatch(r"Number of signers: ([0-9]+)", signer_count_lines[0])
        if len(signer_count_lines) == 1
        else None
    )
    if signer_count_match is None or signer_count_match.group(1) != "1":
        _fail("APK must have exactly one unambiguously reported signer")

    scheme_lines = [line for line in lines if line.startswith("Verified using ")]
    scheme_records = [
        re.fullmatch(r"Verified using .+: (true|false)", line) for line in scheme_lines
    ]
    if not scheme_records or any(record is None for record in scheme_records):
        _fail("apksigner signature-scheme output is malformed or ambiguous")
    if not any(record.group(1) == "true" for record in scheme_records if record is not None):
        _fail("apksigner output contains no verified signature scheme")

    dn_records: dict[int, str] = {}
    digest_records: dict[int, str] = {}
    optional_signer_records = (
        re.compile(r"Signer #([0-9]+) certificate SHA-1 digest: [0-9A-Fa-f]{40}"),
        # Tolerate an unpadded informational MD5 token (fixture/control
        # compatibility only); it is never an identity input. The required
        # certificate SHA-256 fingerprint remains exactly 64 hexadecimal chars.
        re.compile(r"Signer #([0-9]+) certificate MD5 digest: [0-9A-Fa-f]{1,32}"),
        re.compile(r"Signer #([0-9]+) key algorithm: .+"),
        re.compile(r"Signer #([0-9]+) key size \(bits\): [0-9]+"),
        re.compile(r"Signer #([0-9]+) public key SHA-256 digest: [0-9A-Fa-f]{64}"),
        re.compile(r"Signer #([0-9]+) public key SHA-1 digest: [0-9A-Fa-f]{40}"),
        re.compile(r"Signer #([0-9]+) public key MD5 digest: [0-9A-Fa-f]{1,32}"),
    )
    for line in lines:
        if not line.startswith("Signer #"):
            continue
        match = re.fullmatch(r"Signer #([0-9]+) certificate DN: (.+)", line)
        if match:
            signer = int(match.group(1))
            if signer in dn_records:
                _fail("apksigner output contains duplicate signer certificate subjects")
            dn_records[signer] = match.group(2)
            continue
        match = re.fullmatch(
            r"Signer #([0-9]+) certificate SHA-256 digest: ([0-9A-Fa-f]{64})", line
        )
        if match:
            signer = int(match.group(1))
            if signer in digest_records:
                _fail("apksigner output contains duplicate signer certificate fingerprints")
            digest_records[signer] = match.group(2).lower()
            continue
        optional_match = None
        for pattern in optional_signer_records:
            optional_match = pattern.fullmatch(line)
            if optional_match is not None:
                break
        if optional_match is not None:
            if optional_match.group(1) != "1":
                _fail("apksigner output contains additional signer records")
            continue
        _fail("apksigner signer-certificate output is malformed or ambiguous")

    if set(dn_records) != {1} or set(digest_records) != {1}:
        _fail("apksigner must report exactly one complete signer certificate")
    subject = dn_records[1]
    if re.search(r"android\s+debug", subject, flags=re.IGNORECASE):
        _fail("Android debug signing certificate subject is forbidden")
    fingerprint = digest_records[1]
    if fingerprint != expected_cert:
        _fail("APK signer certificate SHA-256 does not match the caller-expected value")
    return subject, fingerprint


_PACKAGE_LINE = re.compile(
    r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'(.*)$"
)


def _parse_aapt_output(
    output: bytes, *, expected_package: str, expected_version_code: str, expected_version_name: str
) -> None:
    text = _decode_output(output, "aapt")
    lines = text.splitlines()
    package_lines = [line for line in lines if line.startswith("package:")]
    if len(package_lines) != 1:
        _fail("aapt output must contain exactly one package identity record")
    match = _PACKAGE_LINE.fullmatch(package_lines[0])
    if not match:
        _fail("aapt package identity record is malformed")
    package, version_code, version_name, remainder = match.groups()
    if remainder and not remainder[0].isspace():
        _fail("aapt package record has malformed trailing fields")
    if re.search(r"(?:^|\s)(?:name|versionCode|versionName)(?:=|\s|$)", remainder):
        _fail("aapt package record repeats or ambiguously redefines an identity field")
    if (package, version_code, version_name) != (
        expected_package,
        expected_version_code,
        expected_version_name,
    ):
        _fail("APK package/version identity does not match caller-expected values")

    applications = [line for line in lines if line.startswith("application:")]
    if len(applications) != 1:
        _fail("aapt output must contain exactly one application record")
    if any(line.lstrip().startswith("application-debuggable") for line in lines):
        _fail("debuggable APKs are forbidden")


def _validate_expected(args: argparse.Namespace) -> tuple[str, str, str, str, int]:
    package = args.expected_package
    if len(package) > 255 or not re.fullmatch(
        r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package
    ):
        _fail("--expected-package must be a valid dotted Android package identifier")
    if not re.fullmatch(r"[1-9][0-9]*", args.expected_version_code):
        _fail("--expected-version-code must be a positive decimal integer")
    if int(args.expected_version_code) > 2_147_483_647:
        _fail("--expected-version-code exceeds the Android versionCode range")
    version_name = args.expected_version_name
    if not version_name or len(version_name) > 256 or any(ord(ch) < 0x20 for ch in version_name):
        _fail("--expected-version-name must be 1..256 printable characters")
    cert = args.expected_cert_sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", cert):
        _fail("--expected-cert-sha256 must be exactly 64 hexadecimal characters")
    timeout = args.tool_timeout_seconds
    if not 1 <= timeout <= MAX_TOOL_TIMEOUT_SECONDS:
        _fail(f"--tool-timeout-seconds must be between 1 and {MAX_TOOL_TIMEOUT_SECONDS}")
    return package, args.expected_version_code, version_name, cert, timeout


def _validate_tool(path_value: str, tool_name: str) -> str:
    path = Path(os.path.abspath(path_value))
    try:
        info = os.stat(path)
    except OSError as exc:
        _fail(f"required {tool_name} is unavailable: {exc.strerror or exc}")
    if not stat.S_ISREG(info.st_mode) or not os.access(path, os.X_OK):
        _fail(f"required {tool_name} is not an executable regular file")
    return str(path)


def _write_exclusive(stage_fd: int, name: str, data: bytes) -> None:
    fd = os.open(
        name,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
        0o444,
        dir_fd=stage_fd,
    )
    try:
        view = memoryview(data)
        while view:
            written = os.write(fd, view)
            if written <= 0:
                _fail(f"short write while creating {name}")
            view = view[written:]
        os.fsync(fd)
        os.fchmod(fd, 0o444)
        os.fsync(fd)
        if stat.S_IMODE(os.fstat(fd).st_mode) != 0o444:
            _fail(f"staging filesystem did not preserve required 0444 mode for {name}")
    finally:
        os.close(fd)


def _hash_staged_file(stage_fd: int, expected_info: os.stat_result) -> str:
    fd = os.open(APK_NAME, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0), dir_fd=stage_fd)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or (info.st_dev, info.st_ino) != (
            expected_info.st_dev,
            expected_info.st_ino,
        ):
            _fail("staged APK was replaced or is not a regular file")
        digest, length = _hash_fd(fd, max_bytes=MAX_APK_BYTES)
        if length != expected_info.st_size:
            _fail("staged APK size changed after snapshot creation")
        return digest
    finally:
        os.close(fd)


def _remove_owned_stage(path: Path, stage_fd: int, stage_info: os.stat_result) -> None:
    try:
        current = os.lstat(path)
        if not stat.S_ISDIR(current.st_mode) or (current.st_dev, current.st_ino) != (
            stage_info.st_dev,
            stage_info.st_ino,
        ):
            return
        os.fchmod(stage_fd, 0o700)
        for name in (APK_NAME, CHECKSUM_NAME, IDENTITY_NAME):
            try:
                os.unlink(name, dir_fd=stage_fd)
            except FileNotFoundError:
                pass
        os.rmdir(path)
    except OSError:
        # Never broaden cleanup to a path that may no longer be ours.
        pass


def verify_and_stage(args: argparse.Namespace) -> dict[str, object]:
    package, version_code, version_name, expected_cert, timeout = _validate_expected(args)
    apksigner = _validate_tool(args.apksigner, "apksigner")
    aapt = _validate_tool(args.aapt, "aapt")
    candidate_dir, stage_dir = _validate_paths(args.candidate_dir, args.stage_dir)

    candidate_dir_fd = _open_candidate_directory(candidate_dir)
    stage_fd = -1
    stage_info: os.stat_result | None = None
    source_fd = -1
    try:
        candidate_directory_before = os.fstat(candidate_dir_fd)
        candidate_name = _find_unique_candidate(candidate_dir_fd)
        try:
            source_fd = os.open(
                candidate_name,
                os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0),
                dir_fd=candidate_dir_fd,
            )
        except OSError as exc:
            _fail(f"cannot safely open APK candidate: {exc.strerror or exc}")
        candidate_info = os.fstat(source_fd)
        if not stat.S_ISREG(candidate_info.st_mode):
            _fail("APK candidate is not a regular file")

        try:
            os.mkdir(stage_dir, 0o700)
        except FileExistsError:
            _fail(f"staging directory already exists (overwrite is forbidden): {stage_dir}")
        except OSError as exc:
            _fail(f"cannot create staging directory: {exc.strerror or exc}")
        stage_fd = os.open(
            stage_dir,
            os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        os.fchmod(stage_fd, 0o700)
        stage_info = os.fstat(stage_fd)
        if stat.S_IMODE(stage_info.st_mode) != 0o700:
            _fail("staging filesystem did not preserve required 0700 working-directory mode")
        snapshot_digest, apk_info = _copy_candidate_snapshot(source_fd, stage_fd)
        candidate_directory_after = os.fstat(candidate_dir_fd)
        if not _same_directory_snapshot(candidate_directory_before, candidate_directory_after):
            _fail("candidate directory changed while the unique APK snapshot was being created")
        os.fsync(stage_fd)

        staged_path = stage_dir / APK_NAME
        # Both independent parsers inspect this exact staged snapshot.
        signer_output, _ = _run_bounded(
            [apksigner, "verify", "--verbose", "--print-certs", "-Werr", str(staged_path)],
            timeout_seconds=timeout,
        )
        subject, fingerprint = _parse_apksigner_output(signer_output, expected_cert)
        aapt_output, _ = _run_bounded(
            [aapt, "dump", "badging", str(staged_path)], timeout_seconds=timeout
        )
        _parse_aapt_output(
            aapt_output,
            expected_package=package,
            expected_version_code=version_code,
            expected_version_name=version_name,
        )

        final_digest = _hash_staged_file(stage_fd, apk_info)
        if final_digest != snapshot_digest:
            _fail("staged APK bytes changed during verification")
        manifest: dict[str, object] = {
            "artifact": APK_NAME,
            "sha256": final_digest,
            "package": package,
            "versionCode": int(version_code),
            "versionName": version_name,
            "signerCertificateSha256": fingerprint,
            "signerCertificateSubject": subject,
            "candidateFile": candidate_name,
            "verification": "apksigner verify --verbose --print-certs -Werr plus aapt dump badging",
        }
        manifest_bytes = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode("utf-8")
        _write_exclusive(stage_fd, CHECKSUM_NAME, f"{final_digest}  {APK_NAME}\n".encode("ascii"))
        _write_exclusive(stage_fd, IDENTITY_NAME, manifest_bytes)
        os.fsync(stage_fd)
        if _hash_staged_file(stage_fd, apk_info) != final_digest:
            _fail("staged APK bytes changed while writing the identity/checksum records")
        # Restrict later replacement/unlinking through the published stage path.
        # The caller can still explicitly change permissions, so the checksum is
        # the integrity binding; the staged bytes are never re-copied from input.
        os.fchmod(stage_fd, 0o500)
        os.fsync(stage_fd)
        if stat.S_IMODE(os.fstat(stage_fd).st_mode) != 0o500:
            _fail("staging filesystem did not preserve required 0500 published-directory mode")
        return {**manifest, "stagedPath": str(staged_path)}
    except VerificationError:
        if stage_fd >= 0 and stage_info is not None:
            _remove_owned_stage(stage_dir, stage_fd, stage_info)
        raise
    except OSError as exc:
        if stage_fd >= 0 and stage_info is not None:
            _remove_owned_stage(stage_dir, stage_fd, stage_info)
        _fail(f"filesystem operation failed: {exc.strerror or exc}")
    finally:
        if source_fd >= 0:
            os.close(source_fd)
        if stage_fd >= 0:
            os.close(stage_fd)
        os.close(candidate_dir_fd)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-dir", required=True, help="directory containing exactly one APK")
    parser.add_argument("--stage-dir", required=True, help="new, non-existent private staging directory")
    parser.add_argument("--expected-package", required=True, help="trusted expected Android package ID")
    parser.add_argument("--expected-version-code", required=True, help="trusted expected versionCode")
    parser.add_argument("--expected-version-name", required=True, help="trusted expected versionName")
    parser.add_argument(
        "--expected-cert-sha256", required=True, help="trusted expected signer certificate SHA-256"
    )
    parser.add_argument("--apksigner", required=True, help="explicit Android SDK apksigner executable")
    parser.add_argument("--aapt", required=True, help="explicit Android SDK aapt executable")
    parser.add_argument(
        "--tool-timeout-seconds",
        type=int,
        default=DEFAULT_TOOL_TIMEOUT_SECONDS,
        help=f"bounded parser timeout (1..{MAX_TOOL_TIMEOUT_SECONDS}; default {DEFAULT_TOOL_TIMEOUT_SECONDS})",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        result = verify_and_stage(args)
    except VerificationError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
