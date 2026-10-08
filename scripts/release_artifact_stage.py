#!/usr/bin/env python3
"""Verify, policy-check, and stage one all-or-nothing APK distribution set."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import NoReturn

import verify_release_apk as apk_verifier

FLAVORS = ("play", "foss", "github")
APK_NAME = "release.apk"
CHECKSUM_NAME = "SHA256SUMS"
IDENTITY_NAME = "artifact-identity.json"
MAX_MANIFEST_BYTES = 64 * 1024
MAX_POLICY_OUTPUT_BYTES = 2 * 1024 * 1024
_ACTIVE_POLICY_PROCESS: subprocess.Popen[bytes] | None = None


class StageError(Exception):
    """A release artifact transaction failed closed."""


def _fail(message: str) -> NoReturn:
    raise StageError(message)


def _safe_environment() -> dict[str, str]:
    allowed = ("PATH", "HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "JAVA_HOME", "TMPDIR")
    env = {name: os.environ[name] for name in allowed if os.environ.get(name)}
    env["LC_ALL"] = "C"
    return env


def _existing_directory(value: str, label: str) -> Path:
    path = Path(os.path.abspath(value))
    current = Path(path.parts[0])
    for component in path.parts[1:]:
        current = current / component
        try:
            info = os.lstat(current)
        except OSError as exc:
            _fail(f"{label} path is unavailable: {current}: {exc.strerror or exc}")
        if stat.S_ISLNK(info.st_mode):
            _fail(f"{label} path must not contain symlinks: {current}")
        if not stat.S_ISDIR(info.st_mode):
            _fail(f"{label} path component is not a directory: {current}")
    return path


def _validate_tool(value: str, label: str) -> str:
    if not os.path.isabs(value):
        _fail(f"{label} path must be absolute")
    try:
        info = os.stat(value)
    except OSError as exc:
        _fail(f"{label} is unavailable: {exc.strerror or exc}")
    if not stat.S_ISREG(info.st_mode) or not os.access(value, os.X_OK):
        _fail(f"{label} must be an executable regular file")
    return value


def _validate_expected(flavor: str, args: argparse.Namespace) -> dict[str, str]:
    package = getattr(args, f"{flavor}_package")
    version_code = getattr(args, f"{flavor}_version_code")
    version_name = getattr(args, f"{flavor}_version_name")
    cert = getattr(args, f"{flavor}_cert_sha256").lower()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
        _fail(f"invalid expected package for {flavor}")
    if not re.fullmatch(r"[1-9][0-9]*", version_code) or int(version_code) > 2_147_483_647:
        _fail(f"invalid expected versionCode for {flavor}")
    if not version_name or len(version_name) > 256 or any(ord(char) < 0x20 for char in version_name):
        _fail(f"invalid expected versionName for {flavor}")
    if not re.fullmatch(r"[0-9a-f]{64}", cert):
        _fail(f"invalid expected certificate SHA-256 for {flavor}")
    return {
        "package": package,
        "versionCode": version_code,
        "versionName": version_name,
        "certificateSha256": cert,
    }


def _validate_args(args: argparse.Namespace) -> tuple[tuple[str, ...], dict[str, dict[str, str]], Path, Path, str, str]:
    flavors = tuple(args.flavors.split())
    if not flavors or len(set(flavors)) != len(flavors) or any(f not in FLAVORS for f in flavors):
        _fail("--flavors must contain unique known flavor names")
    identities = {flavor: _validate_expected(flavor, args) for flavor in flavors}
    output_parent = _existing_directory(args.output_parent, "output parent")
    if not os.access(output_parent, os.W_OK):
        _fail("output parent is not writable")
    candidate_root = _existing_directory(args.candidate_root, "APK candidate root")
    apksigner = _validate_tool(args.apksigner, "apksigner")
    aapt = _validate_tool(args.aapt, "aapt")
    for flavor in flavors:
        _existing_directory(str(candidate_root / flavor / "release"), f"{flavor} APK candidate")
    return flavors, identities, output_parent, candidate_root, apksigner, aapt


def _kill_policy_process_group(process: subprocess.Popen[bytes]) -> None:
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        try:
            process.kill()
        except ProcessLookupError:
            pass


def _terminate_pipeline(signum: int, _frame: object) -> None:
    policy_process = _ACTIVE_POLICY_PROCESS
    if policy_process is not None:
        _kill_policy_process_group(policy_process)
        try:
            policy_process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            policy_process.kill()
            policy_process.wait()
    apk_verifier.terminate_active_tool()
    raise StageError(f"interrupted by signal {signum}; active verifier/policy process groups were stopped")


def _run(command: list[str], *, cwd: Path, timeout_seconds: int = 360) -> subprocess.CompletedProcess[bytes]:
    global _ACTIVE_POLICY_PROCESS
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=_safe_environment(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            close_fds=True,
            start_new_session=True,
            bufsize=0,
        )
    except (OSError, ValueError) as exc:
        _fail(f"cannot run {Path(command[0]).name}: {exc}")
    _ACTIVE_POLICY_PROCESS = process
    completed = False
    assert process.stdout is not None and process.stderr is not None
    selector = selectors.DefaultSelector()
    stdout = bytearray()
    stderr = bytearray()
    try:
        for stream, target in ((process.stdout, stdout), (process.stderr, stderr)):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ, target)
        deadline = time.monotonic() + timeout_seconds
        total = 0
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                _fail(f"command timed out after {timeout_seconds} seconds: {Path(command[0]).name}")
            for key, _ in selector.select(min(remaining, 0.25)):
                try:
                    chunk = os.read(key.fileobj.fileno(), 65536)
                except OSError as exc:
                    _fail(f"cannot read {Path(command[0]).name} output: {exc}")
                if not chunk:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                total += len(chunk)
                if total > MAX_POLICY_OUTPUT_BYTES:
                    _fail(
                        f"{Path(command[0]).name} output exceeds {MAX_POLICY_OUTPUT_BYTES} bytes"
                    )
                key.data.extend(chunk)
        try:
            returncode = process.wait(timeout=max(0.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            _fail(f"command timed out after {timeout_seconds} seconds: {Path(command[0]).name}")
        completed = True
        return subprocess.CompletedProcess(command, returncode, bytes(stdout), bytes(stderr))
    finally:
        if not completed:
            _kill_policy_process_group(process)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if _ACTIVE_POLICY_PROCESS is process:
            _ACTIVE_POLICY_PROCESS = None
        selector.close()
        for stream in (process.stdout, process.stderr):
            if not stream.closed:
                stream.close()


def _output_text(data: bytes) -> str:
    return data.decode("utf-8", errors="replace")[-16_384:]


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _invoke_verifier(
    *,
    stage_dir: Path,
    candidate_dir: Path,
    identity: dict[str, str],
    apksigner: str,
    aapt: str,
) -> dict[str, object]:
    verifier_args = [
        "--candidate-dir",
        str(candidate_dir),
        "--stage-dir",
        str(stage_dir),
        "--expected-package",
        identity["package"],
        "--expected-version-code",
        identity["versionCode"],
        "--expected-version-name",
        identity["versionName"],
        "--expected-cert-sha256",
        identity["certificateSha256"],
        "--apksigner",
        apksigner,
        "--aapt",
        aapt,
    ]
    try:
        verifier_args_namespace = apk_verifier._build_parser().parse_args(verifier_args)
        value = apk_verifier.verify_and_stage(verifier_args_namespace)
    except apk_verifier.VerificationError as exc:
        _fail(f"{stage_dir.name} final APK verification failed: {exc}")
    if not isinstance(value, dict) or not re.fullmatch(r"[0-9a-f]{64}", str(value.get("sha256", ""))):
        _fail(f"{stage_dir.name} verifier result lacks a valid SHA-256")
    if value.get("stagedPath") != str(stage_dir / APK_NAME):
        _fail(f"{stage_dir.name} verifier result refers to an unexpected staged path")
    return value


def _assert_stage_integrity(
    stage_dir: Path,
    expected_digest: str,
    expected_identity: dict[str, str],
) -> None:
    apk = stage_dir / APK_NAME
    try:
        apk_info = os.lstat(apk)
        if stat.S_ISLNK(apk_info.st_mode) or not stat.S_ISREG(apk_info.st_mode):
            _fail(f"{stage_dir.name} staged APK is not a regular file")
        digest = _sha256_file(apk)
        identity_path = stage_dir / IDENTITY_NAME
        identity_info = os.lstat(identity_path)
        if stat.S_ISLNK(identity_info.st_mode) or not stat.S_ISREG(identity_info.st_mode):
            _fail(f"{stage_dir.name} identity manifest is not a regular file")
        if identity_info.st_size > MAX_MANIFEST_BYTES:
            _fail(f"{stage_dir.name} identity manifest exceeds the size limit")
        identity_data = json.loads(identity_path.read_text(encoding="utf-8"))
        checksum_info = os.lstat(stage_dir / CHECKSUM_NAME)
        if stat.S_ISLNK(checksum_info.st_mode) or not stat.S_ISREG(checksum_info.st_mode):
            _fail(f"{stage_dir.name} checksum manifest is not a regular file")
        checksum_data = (stage_dir / CHECKSUM_NAME).read_bytes()
    except StageError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        _fail(f"cannot recheck {stage_dir.name} staged identity/checksum: {exc}")
    expected_checksum = f"{expected_digest}  {APK_NAME}\n".encode("ascii")
    if digest != expected_digest:
        _fail(f"{stage_dir.name} staged APK checksum changed after verification/policy")
    if checksum_data != expected_checksum:
        _fail(f"{stage_dir.name} SHA256SUMS does not bind the verified APK bytes")
    if identity_data.get("sha256") != expected_digest:
        _fail(f"{stage_dir.name} identity manifest checksum does not match verifier result")
    if (
        identity_data.get("package") != expected_identity["package"]
        or identity_data.get("versionCode") != int(expected_identity["versionCode"])
        or identity_data.get("versionName") != expected_identity["versionName"]
        or identity_data.get("signerCertificateSha256") != expected_identity["certificateSha256"]
    ):
        _fail(f"{stage_dir.name} identity manifest does not match trusted caller inputs")


def _run_policy(flavor: str, apk: Path, *, root: Path) -> None:
    if flavor not in ("play", "foss"):
        return
    command = ["sh", str(root / "scripts" / "play_policy_check.sh")]
    if flavor == "foss":
        command.append("--foss")
    command.append(str(apk))
    result = _run(command, cwd=root, timeout_seconds=360)
    if result.returncode != 0:
        detail = _output_text(result.stderr) or _output_text(result.stdout)
        _fail(f"{flavor} artifact policy rejected staged APK (exit {result.returncode}): {detail.strip()}")


def _write_exclusive(path: Path, content: bytes, mode: int = 0o444) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        view = memoryview(content)
        while view:
            written = os.write(fd, view)
            if written <= 0:
                _fail(f"short write while creating {path.name}")
            view = view[written:]
        os.fsync(fd)
        os.fchmod(fd, mode)
        os.fsync(fd)
        if stat.S_IMODE(os.fstat(fd).st_mode) != mode:
            _fail(f"output filesystem did not preserve required {mode:04o} mode for {path.name}")
    finally:
        os.close(fd)


def _remove_owned_transaction(root: Path, original: os.stat_result) -> bool:
    try:
        current = os.lstat(root)
        if not stat.S_ISDIR(current.st_mode) or (current.st_dev, current.st_ino) != (
            original.st_dev,
            original.st_ino,
        ):
            return False
        os.chmod(root, 0o700)
        for child in root.iterdir():
            try:
                info = os.lstat(child)
                if stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode):
                    os.chmod(child, 0o700)
            except FileNotFoundError:
                continue
        shutil.rmtree(root)
        return True
    except OSError:
        return False


def _run_transaction(args: argparse.Namespace) -> dict[str, object]:
    flavors, identities, output_parent, candidate_root, apksigner, aapt = _validate_args(args)
    root = Path(__file__).resolve().parent.parent
    try:
        transaction = Path(tempfile.mkdtemp(prefix="librepocket-verified-", dir=output_parent))
    except OSError as exc:
        _fail(f"cannot create unique output transaction: {exc}")
    transaction_info = os.lstat(transaction)
    success = False
    try:
        results: dict[str, dict[str, object]] = {}
        expected_digests: dict[str, str] = {}
        for flavor in flavors:
            stage_dir = transaction / flavor
            candidate_dir = candidate_root / flavor / "release"
            result = _invoke_verifier(
                stage_dir=stage_dir,
                candidate_dir=candidate_dir,
                identity=identities[flavor],
                apksigner=apksigner,
                aapt=aapt,
            )
            digest = str(result["sha256"])
            _assert_stage_integrity(stage_dir, digest, identities[flavor])
            _run_policy(flavor, stage_dir / APK_NAME, root=root)
            _assert_stage_integrity(stage_dir, digest, identities[flavor])
            results[flavor] = {
                "apk": str(stage_dir / APK_NAME),
                "sha256": digest,
                "package": identities[flavor]["package"],
                "versionCode": int(identities[flavor]["versionCode"]),
                "versionName": identities[flavor]["versionName"],
                "signerCertificateSha256": identities[flavor]["certificateSha256"],
            }
            expected_digests[flavor] = digest

        # Recheck every flavor after the last policy process, so a later gate
        # cannot silently mutate an earlier flavor's already-checked bytes.
        for flavor in flavors:
            _assert_stage_integrity(transaction / flavor, expected_digests[flavor], identities[flavor])

        aggregate = "".join(
            f"{expected_digests[flavor]}  {flavor}/{APK_NAME}\n" for flavor in flavors
        ).encode("ascii")
        _write_exclusive(transaction / CHECKSUM_NAME, aggregate)
        for flavor in flavors:
            _assert_stage_integrity(transaction / flavor, expected_digests[flavor], identities[flavor])
        aggregate_path = transaction / CHECKSUM_NAME
        aggregate_info = os.lstat(aggregate_path)
        if stat.S_ISLNK(aggregate_info.st_mode) or not stat.S_ISREG(aggregate_info.st_mode):
            _fail("aggregate SHA256SUMS is not a regular file")
        expected_aggregate = "".join(
            f"{expected_digests[flavor]}  {flavor}/{APK_NAME}\n" for flavor in flavors
        )
        if aggregate_path.read_text(encoding="ascii") != expected_aggregate:
            _fail("aggregate SHA256SUMS failed final recheck")
        transaction_fd = os.open(
            transaction, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
        )
        try:
            os.fchmod(transaction_fd, 0o500)
            os.fsync(transaction_fd)
            if stat.S_IMODE(os.fstat(transaction_fd).st_mode) != 0o500:
                _fail("output filesystem did not preserve required 0500 transaction-directory mode")
        finally:
            os.close(transaction_fd)
        response: dict[str, object] = {
            "status": "ready",
            "outputDirectory": str(transaction),
            "flavors": results,
            "sha256sums": str(aggregate_path),
        }
        success = True
        return response
    finally:
        if not success and not _remove_owned_transaction(transaction, transaction_info):
            print(
                f"FAIL: unable to clean owned failed transaction; unverified temporary path remains: {transaction}",
                file=sys.stderr,
            )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flavors", required=True)
    parser.add_argument("--candidate-root", required=True)
    parser.add_argument("--output-parent", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt", required=True)
    for flavor in FLAVORS:
        parser.add_argument(f"--{flavor}-package", required=True)
        parser.add_argument(f"--{flavor}-version-code", required=True)
        parser.add_argument(f"--{flavor}-version-name", required=True)
        parser.add_argument(f"--{flavor}-cert-sha256", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    signal.signal(signal.SIGTERM, _terminate_pipeline)
    signal.signal(signal.SIGINT, _terminate_pipeline)
    try:
        result = _run_transaction(args)
    except StageError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
