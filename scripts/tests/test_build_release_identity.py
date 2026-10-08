#!/usr/bin/env python3
"""Synthetic integration tests for the APK release-builder identity contract."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILDER = ROOT / "scripts" / "build_release.sh"
VERIFIER = ROOT / "scripts" / "verify_release_apk.py"
STAGER = ROOT / "scripts" / "release_artifact_stage.py"
FLAVORS = ("play", "foss", "github")
EXPECTED = {
    flavor: {
        "package": {
            "play": "dev.librepocket.agent",
            "foss": "dev.librepocket.agent.foss",
            "github": "dev.librepocket.agent.github",
        }[flavor],
        "version_code": "7",
        "version_name": "1.2.3",
        "cert": {
            "play": "11" * 32,
            "foss": "22" * 32,
            "github": "33" * 32,
        }[flavor],
    }
    for flavor in FLAVORS
}

FAKE_GRADLE = r'''#!{python}
import json
import os
import pathlib
import sys

root = pathlib.Path.cwd()
log = pathlib.Path(os.environ["FAKE_GRADLE_LOG"])
with log.open("a", encoding="utf-8") as stream:
    stream.write(" ".join(sys.argv[1:]) + "\\n")
if os.environ.get("FAKE_GRADLE_MODE") == "fail":
    sys.exit(37)
selected = []
for arg in sys.argv[1:]:
    for flavor, variant in (("play", "Play"), ("foss", "Foss"), ("github", "Github")):
        if arg == ":app:assemble" + variant + "Release":
            selected.append(flavor)
for flavor in selected:
    info = json.loads(os.environ["FAKE_EXPECTED_" + flavor.upper()])
    mode = os.environ.get("FAKE_GRADLE_MODE", "pass")
    payload = {
        "package": info["package"],
        "version_code": info["version_code"],
        "version_name": info["version_name"],
        "cert": info["cert"],
        "signed": mode != "unsigned-" + flavor,
        "debuggable": mode == "debuggable-" + flavor,
        "hang": mode == "hang-play" and flavor == "play",
    }
    if mode == "wrong-package-" + flavor:
        payload["package"] += ".wrong"
    if mode == "wrong-version-" + flavor:
        payload["version_code"] = "999"
    if mode == "debug-cert-" + flavor:
        payload["cert"] = "dd" * 32
        payload["subject"] = "CN=Android Debug, O=Android, C=US"
    out = root / "app" / "build" / "outputs" / "apk" / flavor / "release"
    out.mkdir(parents=True, exist_ok=True)
    (out / ("app-" + flavor + "-release.apk")).write_text(
        json.dumps(payload, sort_keys=True), encoding="utf-8"
    )
    if mode == "multiple-" + flavor:
        (out / "app-signed.apk").write_text(json.dumps(payload), encoding="utf-8")
    if mode == "aab-" + flavor:
        (out / "app-release.aab").write_bytes(b"synthetic bundle")
'''

FAKE_APKSIGNER = r'''#!{python}
import json
import pathlib
import subprocess
import sys
import time

if len(sys.argv) != 6 or sys.argv[1:5] != ["verify", "--verbose", "--print-certs", "-Werr"]:
    sys.exit(91)
try:
    data = json.loads(pathlib.Path(sys.argv[5]).read_text(encoding="utf-8"))
except Exception:
    sys.exit(92)
if not data.get("signed"):
    print("synthetic unsigned or tampered APK", file=sys.stderr)
    sys.exit(1)
if data.get("hang"):
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"])
    pathlib.Path("__CHILD_PID_FILE__").write_text(str(child.pid), encoding="ascii")
    time.sleep(120)
subject = data.get("subject", "CN=LibrePocket Synthetic Fixture")
print("Verifies")
print("Verified using v3 scheme (APK Signature Scheme v3): true")
print("Number of signers: 1")
print("Signer #1 certificate DN: " + subject)
print("Signer #1 certificate SHA-256 digest: " + data["cert"])
print("Signer #1 certificate SHA-1 digest: " + "ab" * 20)
print("Signer #1 certificate MD5 digest: " + "ab" * 16)
'''

FAKE_AAPT = r'''#!{python}
import json
import pathlib
import sys

if len(sys.argv) != 4 or sys.argv[1:3] != ["dump", "badging"]:
    sys.exit(81)
try:
    data = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
except Exception:
    sys.exit(82)
print("package: name='{}' versionCode='{}' versionName='{}' platformBuildVersionName='17'".format(
    data["package"], data["version_code"], data["version_name"]
))
print("application: label='Synthetic' icon=''")
if data.get("debuggable"):
    print("application-debuggable")
'''

FAKE_POLICY = r'''#!/bin/sh
set -eu
if [ "${1:-}" = "--foss" ]; then
    flavor=foss
else
    flavor=play
fi
apk=
for arg in "$@"; do apk=$arg; done
printf '%s\t%s\n' "$flavor" "$apk" >> '__POLICY_LOG__'
mode=$(cat '__POLICY_MODE_FILE__')
if [ "$mode" = "fail-$flavor" ]; then
    echo 'synthetic policy rejection' >&2
    exit 41
fi
if [ "$mode" = "mutate-$flavor" ]; then
    chmod 644 "$apk"
    printf '%s' 'policy-mutated bytes' > "$apk"
fi
if [ "$mode" = "flood-$flavor" ]; then
    python3 -c 'import os; block = b"X" * 65536; [os.write(1, block) for _ in range(40)]'
    exit 0
fi
printf '%s\n' 'synthetic policy pass'
'''

FAKE_BW = r'''#!{python}
import os
import pathlib
import sys
with pathlib.Path(os.environ["FAKE_BW_LOG"]).open("a", encoding="utf-8") as stream:
    stream.write(" ".join(sys.argv[1:]) + "\\n")
if sys.argv[1:2] == ["status"]:
    print('{"status":"unlocked"}')
elif sys.argv[1:2] == ["get"]:
    print("synthetic-test-password")
else:
    sys.exit(2)
'''


class ReleaseBuilderHarness(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="librepocket-build-release-")
        self.addCleanup(self.temporary.cleanup)
        self.tmp = Path(self.temporary.name)
        self.root = self.tmp / "repo"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "scripts" / "tests").mkdir()
        (self.root / "app").mkdir()
        shutil.copy2(BUILDER, self.root / "scripts" / "build_release.sh")
        shutil.copy2(VERIFIER, self.root / "scripts" / "verify_release_apk.py")
        shutil.copy2(STAGER, self.root / "scripts" / "release_artifact_stage.py")

        self.fake_bin = self.tmp / "bin"
        self.fake_bin.mkdir()
        self.fake_tools = self.tmp / "sdk-tools"
        self.fake_tools.mkdir()
        self.helper_pid_file = self.tmp / "stage-helper.pid"
        self.child_pid_file = self.tmp / "fake-native-child.pid"
        self._write_executable(self.root / "gradlew", FAKE_GRADLE.replace("{python}", sys.executable))
        self._write_executable(self.fake_bin / "bw", FAKE_BW.replace("{python}", sys.executable))
        self._write_executable(
            self.fake_bin / "python3",
            "#!/bin/sh\n"
            f"if [ \"${{1:-}}\" = \"{self.root / 'scripts' / 'release_artifact_stage.py'}\" ]; then printf '%s\\n' \"$$\" > '{self.helper_pid_file}'; fi\n"
            f"exec '{sys.executable}' \"$@\"\n",
        )
        self._write_executable(
            self.fake_tools / "apksigner",
            FAKE_APKSIGNER.replace("{python}", sys.executable).replace(
                "__CHILD_PID_FILE__", str(self.child_pid_file)
            ),
        )
        self._write_executable(self.fake_tools / "aapt", FAKE_AAPT.replace("{python}", sys.executable))
        self.output_parent = self.tmp / "outputs"
        self.output_parent.mkdir()
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.gradle_log = self.tmp / "gradle.log"
        self.policy_log = self.tmp / "policy.log"
        self.policy_mode_file = self.tmp / "policy-mode.txt"
        self.policy_mode_file.write_text("pass", encoding="utf-8")
        policy_script = self.root / "scripts" / "play_policy_check.sh"
        policy_script.write_text(
            FAKE_POLICY.replace("__POLICY_LOG__", str(self.policy_log)).replace(
                "__POLICY_MODE_FILE__", str(self.policy_mode_file)
            ),
            encoding="utf-8",
        )
        policy_script.chmod(0o755)
        self.bw_log = self.tmp / "bw.log"

    @staticmethod
    def _write_executable(path: Path, content: str) -> None:
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)

    def _identity_args(self, flavors: tuple[str, ...] = FLAVORS) -> list[str]:
        args: list[str] = []
        for flavor in flavors:
            values = EXPECTED[flavor]
            args.extend(("--expected-package", f"{flavor}={values['package']}"))
            args.extend(("--expected-version-code", f"{flavor}={values['version_code']}"))
            args.extend(("--expected-version-name", f"{flavor}={values['version_name']}"))
            args.extend(("--expected-cert-sha256", f"{flavor}={values['cert']}"))
        return args

    def command(
        self,
        flavors: tuple[str, ...] = FLAVORS,
        *,
        include_apk: bool = True,
        identities: bool = True,
        output_parent: Path | None = None,
        tools: bool = True,
    ) -> list[str]:
        command = [
            "sh",
            str(self.root / "scripts" / "build_release.sh"),
        ]
        if include_apk:
            command.append("--apk")
        command.extend(flavors)
        if identities:
            command.extend(self._identity_args(flavors))
        if tools:
            command.extend(("--apksigner", str(self.fake_tools / "apksigner")))
            command.extend(("--aapt", str(self.fake_tools / "aapt")))
        command.extend(("--output-parent", str(output_parent or self.output_parent)))
        return command

    def env(self, *, mode: str = "pass", policy_mode: str = "pass", passwords: bool = True) -> dict[str, str]:
        path_parts = [str(self.fake_bin), str(Path(sys.executable).parent), "/usr/bin", "/bin"]
        env = {
            "PATH": os.pathsep.join(path_parts),
            "HOME": str(self.home),
            "LC_ALL": "C",
            "PYTHONDONTWRITEBYTECODE": "1",
            "FAKE_GRADLE_MODE": mode,
            "FAKE_POLICY_MODE": policy_mode,
            "FAKE_GRADLE_LOG": str(self.gradle_log),
            "FAKE_POLICY_LOG": str(self.policy_log),
            "FAKE_BW_LOG": str(self.bw_log),
        }
        for flavor, values in EXPECTED.items():
            env["FAKE_EXPECTED_" + flavor.upper()] = json.dumps(
                {
                    "package": values["package"],
                    "version_code": values["version_code"],
                    "version_name": values["version_name"],
                    "cert": values["cert"],
                }
            )
        if passwords:
            env.update(
                {
                    "PLAY_KEYSTORE_PASSWORD": "synthetic-play-password",
                    "FOSS_KEYSTORE_PASSWORD": "synthetic-foss-password",
                    "DIRECT_KEYSTORE_PASSWORD": "synthetic-github-password",
                }
            )
        return env

    def run_builder(
        self,
        command: list[str] | None = None,
        *,
        mode: str = "pass",
        policy_mode: str = "pass",
        passwords: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        self.policy_mode_file.write_text(policy_mode, encoding="utf-8")
        return subprocess.run(
            command or self.command(),
            cwd=self.root,
            env=self.env(mode=mode, policy_mode=policy_mode, passwords=passwords),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=20,
        )

    def _output_roots(self) -> list[Path]:
        return sorted(path for path in self.output_parent.iterdir() if path.is_dir())

    def _assert_preflight_rejected(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.gradle_log.exists(), "Gradle must not run on preflight failure")
        self.assertFalse(self.bw_log.exists(), "Bitwarden must not be invoked on preflight failure")
        self.assertEqual(self._output_roots(), [], "preflight failure must not create output transaction")
        self.assertNotIn("READY", result.stdout)

    def test_valid_three_flavor_build_reports_only_complete_verified_transaction(self) -> None:
        result = self.run_builder()

        self.assertEqual(result.returncode, 0, result.stdout)
        response = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(response["status"], "ready")
        self.assertEqual(set(response["flavors"]), set(FLAVORS))
        self.assertEqual(len(self._output_roots()), 1)
        transaction = Path(response["outputDirectory"])
        self.assertEqual(transaction.parent, self.output_parent)
        self.assertTrue(transaction.is_dir())
        policy_calls = self.policy_log.read_text().splitlines()
        self.assertEqual(len(policy_calls), 2, policy_calls)
        self.assertTrue(policy_calls[0].startswith("play\t"), policy_calls)
        self.assertTrue(policy_calls[1].startswith("foss\t"), policy_calls)
        for flavor in FLAVORS:
            stage = transaction / flavor
            apk = stage / "release.apk"
            expected = response["flavors"][flavor]["sha256"]
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), expected)
            self.assertEqual((stage / "SHA256SUMS").read_text(), f"{expected}  release.apk\n")
            manifest = json.loads((stage / "artifact-identity.json").read_text())
            self.assertEqual(manifest["package"], EXPECTED[flavor]["package"])
            self.assertEqual(manifest["signerCertificateSha256"], EXPECTED[flavor]["cert"])
        aggregate_lines = (transaction / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(len(aggregate_lines), 3)
        for line in aggregate_lines:
            digest, relative = line.split("  ", 1)
            self.assertEqual(hashlib.sha256((transaction / relative).read_bytes()).hexdigest(), digest)

    def test_github_only_does_not_run_play_or_foss_policy(self) -> None:
        result = self.run_builder(self.command(("github",)))

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.policy_log.exists())
        self.assertEqual(set(json.loads(result.stdout.strip().splitlines()[-1])["flavors"]), {"github"})

    def test_missing_expected_certificate_fails_before_credentials_or_build(self) -> None:
        command = self.command(identities=False)
        command.extend(self._identity_args(FLAVORS)[:-2])

        self._assert_preflight_rejected(self.run_builder(command, passwords=False))

    def test_duplicate_expected_identity_fails_preflight(self) -> None:
        command = self.command()
        command.extend(("--expected-package", f"play={EXPECTED['play']['package']}"))

        self._assert_preflight_rejected(self.run_builder(command, passwords=False))

    def test_invalid_fingerprint_fails_preflight(self) -> None:
        command = self.command(identities=False)
        identity_args = self._identity_args(FLAVORS)
        cert_flag = identity_args.index("--expected-cert-sha256")
        identity_args[cert_flag + 1] = "play=not-a-fingerprint"
        command.extend(identity_args)

        self._assert_preflight_rejected(self.run_builder(command, passwords=False))

    def test_version_name_shell_metacharacters_are_not_evaluated(self) -> None:
        marker = self.tmp / "should-not-exist"
        command = self.command(("github",))
        index = command.index("--expected-version-name")
        command[index + 1] = f"github=1.2.3$(touch {marker})"

        result = self.run_builder(command)

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertTrue(self.gradle_log.is_file(), "printable input should reach the build as data")
        self.assertFalse(marker.exists(), "version name must never be evaluated as shell code")
        self.assertEqual(self._output_roots(), [])

    def test_missing_apk_switch_fails_before_credentials_or_build(self) -> None:
        self._assert_preflight_rejected(self.run_builder(self.command(include_apk=False), passwords=False))

    def test_missing_tool_fails_preflight(self) -> None:
        self._assert_preflight_rejected(self.run_builder(self.command(tools=False), passwords=False))

    def test_invalid_output_parent_fails_preflight(self) -> None:
        missing_parent = self.tmp / "does-not-exist"
        command = self.command(output_parent=missing_parent)

        self._assert_preflight_rejected(self.run_builder(command, passwords=False))

    def test_multiple_apks_are_rejected_without_filename_preference(self) -> None:
        result = self.run_builder(mode="multiple-play")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertEqual(self._output_roots(), [])

    def test_aab_is_not_a_fallback(self) -> None:
        result = self.run_builder(mode="aab-play")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertEqual(self._output_roots(), [])

    def test_wrong_identity_unsigned_debug_and_debuggable_fail_closed(self) -> None:
        for mode in (
            "unsigned-play",
            "debug-cert-play",
            "wrong-package-play",
            "wrong-version-play",
            "debuggable-play",
        ):
            with self.subTest(mode=mode):
                result = self.run_builder(mode=mode)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertNotIn("READY", result.stdout)
                self.assertEqual(self._output_roots(), [])

    def test_policy_rejection_cleans_all_flavors_without_partial_ready_claim(self) -> None:
        result = self.run_builder(policy_mode="fail-foss")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertEqual(self._output_roots(), [])
        policy_calls = self.policy_log.read_text().splitlines()
        self.assertEqual(len(policy_calls), 2, policy_calls)

    def test_policy_mutation_is_detected_and_transaction_removed(self) -> None:
        result = self.run_builder(policy_mode="mutate-play")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertIn("checksum", result.stdout.lower())
        self.assertEqual(self._output_roots(), [])

    def test_policy_output_is_capped_before_full_capture_and_transaction_removed(self) -> None:
        result = self.run_builder(policy_mode="flood-play")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertIn("output exceeds", result.stdout.lower())
        self.assertEqual(self._output_roots(), [])

    def test_helper_termination_kills_hanging_native_tool_process_tree(self) -> None:
        command = self.command()
        process = subprocess.Popen(
            command,
            cwd=self.root,
            env=self.env(mode="hang-play"),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        child_pid = None
        try:
            deadline = __import__("time").monotonic() + 8
            while __import__("time").monotonic() < deadline:
                if self.helper_pid_file.exists() and self.child_pid_file.exists():
                    break
                if process.poll() is not None:
                    break
                __import__("time").sleep(0.05)
            self.assertTrue(self.helper_pid_file.exists(), "stage helper did not start")
            self.assertTrue(self.child_pid_file.exists(), "fake signer did not spawn its sentinel child")
            helper_pid = int(self.helper_pid_file.read_text())
            child_pid = int(self.child_pid_file.read_text())
            os.kill(helper_pid, 15)
            stdout, _ = process.communicate(timeout=10)
            self.assertNotEqual(process.returncode, 0, stdout)
            self.assertNotIn("READY", stdout)
            self.assertEqual(self._output_roots(), [])
            self.assert_pid_not_running(child_pid)
        finally:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, 9)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)
            if child_pid is not None:
                self._kill_process_group_if_present(child_pid)

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
        raise AssertionError(f"fake native child {pid} is still running")

    @staticmethod
    def _kill_process_group_if_present(pid: int) -> None:
        try:
            os.killpg(pid, 9)
        except ProcessLookupError:
            pass

    def test_gradle_failure_does_not_leave_or_report_transaction(self) -> None:
        result = self.run_builder(mode="fail")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertEqual(self._output_roots(), [])

    def test_repeated_successes_use_unique_output_directories(self) -> None:
        first = self.run_builder(self.command(("github",)))
        second = self.run_builder(self.command(("github",)))

        self.assertEqual(first.returncode, 0, first.stdout)
        self.assertEqual(second.returncode, 0, second.stdout)
        self.assertEqual(len(self._output_roots()), 2)
        first_root = json.loads(first.stdout.strip().splitlines()[-1])["outputDirectory"]
        second_root = json.loads(second.stdout.strip().splitlines()[-1])["outputDirectory"]
        self.assertNotEqual(first_root, second_root)


if __name__ == "__main__":
    unittest.main()
