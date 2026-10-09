#!/usr/bin/env python3
"""Synthetic contract tests for the standalone release APK verifier."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import verify_release_apk as verifier
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERIFIER = ROOT / "scripts" / "verify_release_apk.py"
EXPECTED_PACKAGE = "dev.librepocket.agent"
EXPECTED_VERSION_CODE = "7"
EXPECTED_VERSION_NAME = "1.2.3"
EXPECTED_CERT = "0123456789abcdef" * 4


def valid_signer_output(
    *,
    cert: str = EXPECTED_CERT,
    subject: str = "CN=Release Test, O=LibrePocket, C=US",
    signers: int = 1,
    extra: str = "",
) -> str:
    return "\n".join(
        (
            "Verifies",
            "Verified using v1 scheme (JAR signing): false",
            "Verified using v2 scheme (APK Signature Scheme v2): false",
            "Verified using v3 scheme (APK Signature Scheme v3): true",
            "Verified using v3.1 scheme (APK Signature Scheme v3.1): false",
            "Verified using v4 scheme (APK Signature Scheme v4): false",
            "Verified for SourceStamp: false",
            f"Number of signers: {signers}",
            f"Signer #1 certificate DN: {subject}",
            f"Signer #1 certificate SHA-256 digest: {cert}",
            "Signer #1 certificate SHA-1 digest: 0123456789012345678901234567890123456789",
            "Signer #1 certificate MD5 digest: 01234567890123456789012345678901",
            "Signer #1 key algorithm: RSA",
            "Signer #1 key size (bits): 2048",
            f"Signer #1 public key SHA-256 digest: {'ab' * 32}",
            f"Signer #1 public key SHA-1 digest: {'ab' * 20}",
            f"Signer #1 public key MD5 digest: {'ab' * 16}",
            extra,
        )
    ).rstrip("\n") + "\n"


def valid_aapt_output(
    *,
    package: str = EXPECTED_PACKAGE,
    version_code: str = EXPECTED_VERSION_CODE,
    version_name: str = EXPECTED_VERSION_NAME,
    debuggable: bool = False,
    extra: str = "",
) -> str:
    lines = [
        f"package: name='{package}' versionCode='{version_code}' versionName='{version_name}' "
        "platformBuildVersionName='17' platformBuildVersionCode='37' "
        "compileSdkVersion='37' compileSdkVersionCodename='17'",
        "sdkVersion:'33'",
        "targetSdkVersion:'36'",
        "application: label='' icon=''",
    ]
    if debuggable:
        lines.append("application-debuggable")
    lines.extend(("feature-group: label=''", extra))
    return "\n".join(line for line in lines if line) + "\n"


class ReleaseArtifactVerifierTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="librepocket-release-identity-")
        self.addCleanup(self.temp.cleanup)
        self.tmp = Path(self.temp.name)
        self.candidates = self.tmp / "candidates"
        self.candidates.mkdir()
        self.tools = self.tmp / "tools"
        self.tools.mkdir()
        self.stage = self.tmp / "staged-release"
        self.tool_log = self.tmp / "tool-invocations.log"
        self.apk_bytes = b"synthetic, non-APK fixture for contract testing\n"
        self._write_apksigner()
        self._write_aapt()

    def _write_fake_tool(
        self,
        path: Path,
        *,
        name: str,
        expected_args: list[str],
        output: str,
        exit_code: int = 0,
        behavior: str = "",
    ) -> None:
        script = f'''#!{sys.executable}
import os
import pathlib
import subprocess
import sys
import time

LOG = pathlib.Path({str(self.tool_log)!r})
EXPECTED_ARGS = {expected_args!r}
OUTPUT = {output!r}
BEHAVIOR = {behavior!r}
with LOG.open("a", encoding="utf-8") as log:
    log.write({name!r} + " " + " ".join(sys.argv[1:]) + "\\n")
args = sys.argv[1:]
if len(args) != len(EXPECTED_ARGS):
    sys.exit(91)
for wanted, actual in zip(EXPECTED_ARGS, args):
    if wanted is not None and wanted != actual:
        sys.exit(92)
if BEHAVIOR == "timeout":
    time.sleep(5)
elif BEHAVIOR == "output-flood":
    sys.stdout.buffer.write(b"X" * (2 * 1024 * 1024))
    sys.exit(0)
elif BEHAVIOR.startswith("timeout-child:"):
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"])
    pathlib.Path(BEHAVIOR.split(":", 1)[1]).write_text(str(child.pid), encoding="ascii")
    time.sleep(120)
elif BEHAVIOR == "mutate-staged":
    apk = pathlib.Path(args[-1])
    apk.chmod(0o644)
    apk.write_bytes(b"mutated staged bytes")
elif BEHAVIOR.startswith("mutate-source:"):
    pathlib.Path(BEHAVIOR.split(":", 1)[1]).write_bytes(b"changed source after snapshot")
sys.stdout.write(OUTPUT)
sys.exit({exit_code})
'''
        path.write_text(script, encoding="utf-8")
        path.chmod(0o755)

    def _write_apksigner(
        self,
        *,
        output: str | None = None,
        exit_code: int = 0,
        behavior: str = "",
    ) -> None:
        self.apksigner = self.tools / "apksigner"
        self._write_fake_tool(
            self.apksigner,
            name="apksigner",
            expected_args=["verify", "--verbose", "--print-certs", "-Werr", None],
            output=valid_signer_output() if output is None else output,
            exit_code=exit_code,
            behavior=behavior,
        )

    def _write_aapt(
        self,
        *,
        output: str | None = None,
        exit_code: int = 0,
        behavior: str = "",
    ) -> None:
        self.aapt = self.tools / "aapt"
        self._write_fake_tool(
            self.aapt,
            name="aapt",
            expected_args=["dump", "badging", None],
            output=valid_aapt_output() if output is None else output,
            exit_code=exit_code,
            behavior=behavior,
        )

    def _env(self) -> dict[str, str]:
        # The verifier intentionally passes only a small environment allowlist
        # to parsers; fake tools are configured in their fixture files.
        return os.environ.copy()

    def run_verifier(
        self,
        *,
        stage: Path | None = None,
        candidates: Path | None = None,
        timeout: int | None = None,
        tool_paths: bool = True,
        expected_cert: str = EXPECTED_CERT,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            sys.executable,
            str(VERIFIER),
            "--candidate-dir",
            str(candidates or self.candidates),
            "--stage-dir",
            str(stage or self.stage),
            "--expected-package",
            EXPECTED_PACKAGE,
            "--expected-version-code",
            EXPECTED_VERSION_CODE,
            "--expected-version-name",
            EXPECTED_VERSION_NAME,
            "--expected-cert-sha256",
            expected_cert,
        ]
        if tool_paths:
            command.extend(("--apksigner", str(self.apksigner), "--aapt", str(self.aapt)))
        if timeout is not None:
            command.extend(("--tool-timeout-seconds", str(timeout)))
        return subprocess.run(
            command,
            cwd=ROOT,
            env=self._env(),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def add_apk(self, name: str = "unsigned.apk", contents: bytes | None = None) -> Path:
        path = self.candidates / name
        path.write_bytes(self.apk_bytes if contents is None else contents)
        return path

    def assert_rejected(
        self,
        result: subprocess.CompletedProcess[str],
        message: str | None = None,
        *,
        stage: Path | None = None,
    ) -> None:
        self.assertNotEqual(result.returncode, 0, f"expected fail-closed rejection:\n{result.stdout}")
        if message:
            self.assertIn(message, result.stdout)
        self.assertFalse((stage or self.stage).exists(), "failed verification must not leave staged outputs")

    def test_snapshot_rejects_filesystem_that_does_not_enforce_read_only_mode(self) -> None:
        candidate = self.add_apk()
        stage = self.tmp / "mode-check-stage"
        stage.mkdir(mode=0o700)
        source_fd = os.open(candidate, os.O_RDONLY)
        stage_fd = os.open(stage, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            with mock.patch.object(verifier.os, "fchmod", return_value=None):
                with self.assertRaisesRegex(
                    verifier.VerificationError, "filesystem did not preserve required 0444 mode"
                ):
                    verifier._copy_candidate_snapshot(source_fd, stage_fd)
        finally:
            os.close(source_fd)
            os.close(stage_fd)

    def test_valid_single_synthetic_candidate_is_verified_staged_and_checksummed(self) -> None:
        self.add_apk()

        result = self.run_verifier()

        self.assertEqual(result.returncode, 0, result.stdout)
        staged = self.stage / "release.apk"
        self.assertEqual(staged.read_bytes(), self.apk_bytes)
        digest = hashlib.sha256(self.apk_bytes).hexdigest()
        self.assertEqual((self.stage / "SHA256SUMS").read_text(), f"{digest}  release.apk\n")
        manifest = json.loads((self.stage / "artifact-identity.json").read_text())
        self.assertEqual(manifest["sha256"], digest)
        self.assertEqual(manifest["signerCertificateSha256"], EXPECTED_CERT)
        self.assertEqual(staged.stat().st_mode & 0o777, 0o444)
        self.assertEqual(self.stage.stat().st_mode & 0o777, 0o500)
        invocations = self.tool_log.read_text().splitlines()
        self.assertEqual(len(invocations), 2, invocations)
        self.assertTrue(all(str(staged) in line for line in invocations), invocations)
        self.assertIn("apksigner verify --verbose --print-certs -Werr", invocations[0])
        self.assertIn("aapt dump badging", invocations[1])

    def test_source_mutation_after_snapshot_does_not_change_verified_staged_bytes(self) -> None:
        candidate = self.add_apk()
        self._write_apksigner(behavior=f"mutate-source:{candidate}")

        result = self.run_verifier()

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(candidate.read_bytes(), b"changed source after snapshot")
        self.assertEqual((self.stage / "release.apk").read_bytes(), self.apk_bytes)
        expected = hashlib.sha256(self.apk_bytes).hexdigest()
        self.assertEqual((self.stage / "SHA256SUMS").read_text(), f"{expected}  release.apk\n")

    def test_mutation_of_staged_bytes_by_verifier_is_rejected(self) -> None:
        self.add_apk()
        self._write_apksigner(behavior="mutate-staged")

        result = self.run_verifier()

        self.assert_rejected(result, "staged APK size changed")

    def test_multiple_apks_are_rejected_not_selected_by_filename(self) -> None:
        self.add_apk("unsigned.apk")
        self.add_apk("app-signed.apk", b"second synthetic APK")

        self.assert_rejected(self.run_verifier(), "exactly one APK")

    def test_aab_only_is_rejected_without_fallback(self) -> None:
        (self.candidates / "app-release.aab").write_bytes(b"synthetic bundle")

        self.assert_rejected(self.run_verifier(), "AAB inputs are unsupported")

    def test_empty_candidate_directory_is_rejected(self) -> None:
        self.assert_rejected(self.run_verifier(), "contains no APK")

    def test_candidate_symlink_is_rejected(self) -> None:
        target = self.tmp / "outside.apk"
        target.write_bytes(self.apk_bytes)
        (self.candidates / "candidate.apk").symlink_to(target)

        self.assert_rejected(self.run_verifier(), "must not be a symlink")

    def test_candidate_directory_symlink_is_rejected(self) -> None:
        self.add_apk()
        linked = self.tmp / "candidate-link"
        linked.symlink_to(self.candidates, target_is_directory=True)

        self.assert_rejected(self.run_verifier(candidates=linked), "symlink")

    def test_existing_or_symlink_stage_path_is_rejected_without_overwrite(self) -> None:
        self.add_apk()
        self.stage.mkdir()
        marker = self.stage / "keep.txt"
        marker.write_text("preserve")

        result = self.run_verifier()

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(), "preserve")
        import shutil
        shutil.rmtree(self.stage)
        linked_stage = self.tmp / "stage-link"
        linked_stage.symlink_to(self.tmp, target_is_directory=True)
        result = self.run_verifier(stage=linked_stage)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(linked_stage.is_symlink())

    def test_missing_tool_arguments_fail_closed(self) -> None:
        self.add_apk()

        self.assertNotEqual(self.run_verifier(tool_paths=False).returncode, 0)
        self.assertFalse(self.stage.exists())

    def test_unavailable_tool_fails_closed(self) -> None:
        self.add_apk()
        self.apksigner.unlink()

        self.assert_rejected(self.run_verifier(), "apksigner is unavailable")

    def test_nonzero_apksigner_or_aapt_fails_closed(self) -> None:
        self.add_apk()
        self._write_apksigner(exit_code=23)
        self.assert_rejected(self.run_verifier(), "exited with status 23")

        self._write_apksigner()
        self._write_aapt(exit_code=24)
        self.assert_rejected(self.run_verifier(), "exited with status 24")

    def test_unpadded_informational_md5_is_tolerated_but_signer_sha256_stays_fixed_width(self) -> None:
        self.add_apk()
        output = valid_signer_output().replace(
            "Signer #1 certificate MD5 digest: 01234567890123456789012345678901",
            "Signer #1 certificate MD5 digest: 1234567890123456789012345678901",
        )
        self._write_apksigner(output=output)
        self.assertEqual(self.run_verifier().returncode, 0)

        malformed_sha256 = valid_signer_output().replace(
            f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERT}",
            f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERT[:-1]}",
        )
        self._write_apksigner(output=malformed_sha256)
        self.assert_rejected(
            self.run_verifier(stage=self.tmp / "malformed-sha256-stage"),
            "malformed or ambiguous",
            stage=self.tmp / "malformed-sha256-stage",
        )

    def test_wrong_or_missing_certificate_is_rejected(self) -> None:
        self.add_apk()
        self._write_apksigner(output=valid_signer_output(cert="ff" * 32))
        self.assert_rejected(self.run_verifier(), "does not match")

        missing_cert = "\n".join(
            line for line in valid_signer_output().splitlines()
            if "certificate DN:" not in line and "certificate SHA-256 digest:" not in line
        ) + "\n"
        self._write_apksigner(output=missing_cert)
        self.assert_rejected(self.run_verifier(), "complete signer certificate")

    def test_multiple_or_ambiguous_certificates_are_rejected(self) -> None:
        self.add_apk()
        self._write_apksigner(output=valid_signer_output(signers=2))
        self.assert_rejected(self.run_verifier(), "exactly one")

        duplicate = valid_signer_output() + f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERT}\n"
        self._write_apksigner(output=duplicate)
        self.assert_rejected(self.run_verifier(), "duplicate signer certificate fingerprints")

        unknown = valid_signer_output() + "Signer #1 certificate Name: unexpected\n"
        self._write_apksigner(output=unknown)
        self.assert_rejected(self.run_verifier(), "malformed or ambiguous")

    def test_debug_signer_subject_and_debuggable_manifest_are_rejected(self) -> None:
        self.add_apk()
        self._write_apksigner(output=valid_signer_output(subject="CN=Android Debug, O=Android, C=US"))
        self.assert_rejected(self.run_verifier(), "debug signing certificate")

        self._write_apksigner()
        self._write_aapt(output=valid_aapt_output(debuggable=True))
        self.assert_rejected(self.run_verifier(), "debuggable APKs")

    def test_package_version_and_ambiguous_aapt_records_are_rejected(self) -> None:
        self.add_apk()
        self._write_aapt(output=valid_aapt_output(package="dev.librepocket.agent.wrong"))
        self.assert_rejected(self.run_verifier(), "does not match")

        self._write_aapt(output=valid_aapt_output(version_code="8"))
        self.assert_rejected(self.run_verifier(), "does not match")

        self._write_aapt(output=valid_aapt_output(version_name="1.2.4"))
        self.assert_rejected(self.run_verifier(), "does not match")

        self._write_aapt(output=valid_aapt_output() + "package: malformed\n")
        self.assert_rejected(self.run_verifier(), "exactly one package")

        duplicate_identity = valid_aapt_output().replace(
            "compileSdkVersion='37'", "compileSdkVersion='37' versionCode='99'"
        )
        self._write_aapt(output=duplicate_identity)
        self.assert_rejected(self.run_verifier(), "repeats or ambiguously redefines")

    def test_malformed_apksigner_and_empty_aapt_output_are_rejected(self) -> None:
        self.add_apk()
        self._write_apksigner(output="Verifies\nVerifies\n")
        self.assert_rejected(self.run_verifier(), "unambiguous successful verification")

        self._write_apksigner()
        self._write_aapt(output="")
        self.assert_rejected(self.run_verifier(), "exactly one package")

    def test_tool_output_cap_and_timeout_fail_closed(self) -> None:
        self.add_apk()
        self._write_apksigner(behavior="output-flood")
        self.assert_rejected(self.run_verifier(), "output exceeds")

        self._write_apksigner(behavior="timeout")
        self.assert_rejected(self.run_verifier(timeout=1), "timed out")

    def test_tool_timeout_kills_and_reaps_descendant_process_group(self) -> None:
        self.add_apk()
        child_pid_file = self.tmp / "timeout-child.pid"
        self._write_apksigner(behavior=f"timeout-child:{child_pid_file}")

        result = self.run_verifier(timeout=1)

        self.assert_rejected(result, "timed out")
        self.assertTrue(child_pid_file.is_file())
        self.assert_pid_not_running(int(child_pid_file.read_text(encoding="ascii")))

    @staticmethod
    def assert_pid_not_running(pid: int) -> None:
        import time
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            proc_stat = Path(f"/proc/{pid}/stat")
            if not proc_stat.exists():
                return
            try:
                state = proc_stat.read_text().split()[2]
            except (FileNotFoundError, IndexError):
                return
            if state == "Z":
                return
            time.sleep(0.05)
        raise AssertionError(f"fake tool descendant {pid} is still running")

    def test_expected_fingerprint_must_be_explicitly_valid(self) -> None:
        self.add_apk()

        result = self.run_verifier(expected_cert="")

        self.assert_rejected(result, "64 hexadecimal")


if __name__ == "__main__":
    unittest.main()
