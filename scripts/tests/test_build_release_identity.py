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
from scripts import check_release_pins as signer_pins
from scripts import check_release_tag_version as tag_version

ROOT = Path(__file__).resolve().parents[2]
BUILDER = ROOT / "scripts" / "build_release.sh"
VERIFIER = ROOT / "scripts" / "verify_release_apk.py"
STAGER = ROOT / "scripts" / "release_artifact_stage.py"
PINS_CHECKER = ROOT / "scripts" / "check_release_pins.py"
PINS_EXAMPLE = ROOT / "scripts" / "release_signer_pins.example.json"
TAG_CHECKER = ROOT / "scripts" / "check_release_tag_version.py"
APP_BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"

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
elif [ "${1:-}" = "--github" ]; then
    flavor=github
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
        shutil.copy2(TAG_CHECKER, self.root / "scripts" / "check_release_tag_version.py")
        shutil.copy2(PINS_CHECKER, self.root / "scripts" / "check_release_pins.py")
        (self.root / "app" / "build.gradle.kts").write_text(
            "android {\n"
            "    defaultConfig {\n"
            "        versionCode = 7\n"
            '        versionName = "1.2.3"\n'
            "    }\n"
            "}\n",
            encoding="utf-8",
        )

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
            'for arg in "$@"; do\n'
            f'  if [ "$arg" = "{self.root / "scripts" / "release_artifact_stage.py"}" ]; then\n'
            f"    printf '%s\\n' \"$$\" > '{self.helper_pid_file}'\n"
            "    break\n"
            "  fi\n"
            "done\n"
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

    def _create_pins_file(
        self,
        flavors: tuple[str, ...] = FLAVORS,
        *,
        override: dict[str, dict[str, str]] | None = None,
        name: str = "signer-pins.json",
    ) -> Path:
        pins = {
            flavor: {
                "package": EXPECTED[flavor]["package"],
                "cert_sha256": EXPECTED[flavor]["cert"],
            }
            for flavor in flavors
        }
        if override:
            for k, v in override.items():
                pins[k] = v
        path = self.tmp / name
        path.write_text(json.dumps(pins, indent=2), encoding="utf-8")
        return path

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
        self.assertEqual(len(policy_calls), 3, policy_calls)
        self.assertTrue(policy_calls[0].startswith("play\t"), policy_calls)
        self.assertTrue(policy_calls[1].startswith("foss\t"), policy_calls)
        self.assertTrue(policy_calls[2].startswith("github\t"), policy_calls)
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

    def test_github_only_runs_common_policy_not_play_or_foss(self) -> None:
        result = self.run_builder(self.command(("github",)))

        self.assertEqual(result.returncode, 0, result.stdout)
        policy_calls = self.policy_log.read_text().splitlines()
        self.assertEqual(len(policy_calls), 1, policy_calls)
        self.assertTrue(policy_calls[0].startswith("github\t"), policy_calls)
        self.assertTrue(policy_calls[0].endswith("/github/release.apk"), policy_calls)
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

    def test_github_common_policy_rejection_cleans_transaction(self) -> None:
        # Negative fixture: legal identity/signature bytes but the staged
        # github APK fails the common policy gate. The failure must be named
        # against the github flavor and leave zero staged output behind;
        # same fake-policy paradigm as the foss rejection above.
        result = self.run_builder(self.command(("github",)), policy_mode="fail-github")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertIn("github artifact policy rejected", result.stdout)
        self.assertEqual(self._output_roots(), [])
        policy_calls = self.policy_log.read_text().splitlines()
        self.assertEqual(len(policy_calls), 1, policy_calls)
        self.assertTrue(policy_calls[0].startswith("github\t"), policy_calls)

    def test_policy_mutation_is_detected_and_transaction_removed(self) -> None:
        result = self.run_builder(policy_mode="mutate-play")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertIn("checksum", result.stdout.lower())
        self.assertEqual(self._output_roots(), [])

    def test_github_policy_mutation_is_detected_and_transaction_removed(self) -> None:
        # Negative fixture: legal identity/signature bytes but the staged
        # github APK is mutated by the common policy gate (same fake-policy
        # paradigm as mutate-play above). The post-policy checksum recheck
        # must fail closed, leave zero staged output behind, and never
        # print READY; only the github flavor's policy invocation ran.
        result = self.run_builder(self.command(("github",)), policy_mode="mutate-github")

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("READY", result.stdout)
        self.assertIn("checksum", result.stdout.lower())
        self.assertEqual(self._output_roots(), [])
        policy_calls = self.policy_log.read_text().splitlines()
        self.assertEqual(len(policy_calls), 1, policy_calls)
        self.assertTrue(policy_calls[0].startswith("github\t"), policy_calls)

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

    def test_valid_tag_and_pins_pass_preflight_and_build(self) -> None:
        pins_file = self._create_pins_file()
        command = self.command()
        command.extend(("--tag", "v1.2.3", "--pins", str(pins_file)))

        result = self.run_builder(command)

        self.assertEqual(result.returncode, 0, result.stdout)
        response = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(response["status"], "ready")
        self.assertEqual(set(response["flavors"]), set(FLAVORS))
        self.assertEqual(len(self._output_roots()), 1)

    def test_mismatched_expected_version_name_vs_tag_fails_preflight(self) -> None:
        command = self.command()
        command.extend(("--tag", "v1.2.3"))
        idx = command.index("--expected-version-name")
        command[idx + 1] = "play=1.2.4"

        result = self.run_builder(command, passwords=False)

        self._assert_preflight_rejected(result)
        self.assertIn("versionName", result.stdout)

    def test_mismatched_expected_version_code_vs_gradle_fails_preflight(self) -> None:
        command = self.command()
        command.extend(("--tag", "v1.2.3"))
        idx = command.index("--expected-version-code")
        command[idx + 1] = "play=8"

        result = self.run_builder(command, passwords=False)

        self._assert_preflight_rejected(result)
        self.assertIn("versionCode", result.stdout)

    def test_invalid_tag_format_fails_preflight(self) -> None:
        for bad_tag in ("1.2.3", "v1.2", "v1.2.3-rc1", "v01.02.03", "not-a-tag", ""):
            with self.subTest(tag=bad_tag):
                command = self.command()
                command.extend(("--tag", bad_tag))
                result = self.run_builder(command, passwords=False)
                self._assert_preflight_rejected(result)

    def test_mismatched_tag_vs_gradle_fails_preflight(self) -> None:
        command = self.command()
        command.extend(("--tag", "v2.0.0"))

        result = self.run_builder(command, passwords=False)

        self._assert_preflight_rejected(result)

    def test_mismatched_expected_package_vs_pins_fails_preflight(self) -> None:
        pins_file = self._create_pins_file()
        command = self.command()
        command.extend(("--pins", str(pins_file)))
        idx = command.index("--expected-package")
        command[idx + 1] = "play=dev.librepocket.wrong"

        result = self.run_builder(command, passwords=False)

        self._assert_preflight_rejected(result)
        self.assertIn("package", result.stdout)

    def test_mismatched_expected_cert_sha256_vs_pins_fails_preflight(self) -> None:
        pins_file = self._create_pins_file()
        command = self.command()
        command.extend(("--pins", str(pins_file)))
        idx = command.index("--expected-cert-sha256")
        command[idx + 1] = f"play={'44' * 32}"

        result = self.run_builder(command, passwords=False)

        self._assert_preflight_rejected(result)
        self.assertIn("cert", result.stdout.lower())

    def test_missing_flavor_in_pins_file_fails_preflight(self) -> None:
        pins_file = self._create_pins_file(flavors=("play", "foss"))
        command = self.command()
        command.extend(("--pins", str(pins_file)))

        result = self.run_builder(command, passwords=False)

        self._assert_preflight_rejected(result)

    def test_symlink_pins_file_fails_preflight(self) -> None:
        pins_file = self._create_pins_file()
        symlink_path = self.tmp / "pins-symlink.json"
        symlink_path.symlink_to(pins_file)
        command = self.command()
        command.extend(("--pins", str(symlink_path)))

        result = self.run_builder(command, passwords=False)

        self._assert_preflight_rejected(result)
        self.assertIn("symlink", result.stdout.lower())

    def test_duplicate_tag_or_pins_fails_preflight(self) -> None:
        pins_file = self._create_pins_file()

        command_tag = self.command()
        command_tag.extend(("--tag", "v1.2.3", "--tag", "v1.2.3"))
        result_tag = self.run_builder(command_tag, passwords=False)
        self._assert_preflight_rejected(result_tag)
        self.assertIn("duplicate --tag", result_tag.stdout)

        command_pins = self.command()
        command_pins.extend(("--pins", str(pins_file), "--pins", str(pins_file)))
        result_pins = self.run_builder(command_pins, passwords=False)
        self._assert_preflight_rejected(result_pins)
        self.assertIn("duplicate --pins", result_pins.stdout)

    def test_valid_preflight_with_missing_secrets_fails_closed_before_build(self) -> None:
        pins_file = self._create_pins_file()
        command = self.command()
        command.extend(("--tag", "v1.2.3", "--pins", str(pins_file)))

        result = self.run_builder(command, passwords=False)

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.gradle_log.exists(), "Gradle must not run when secrets are missing")
        self.assertFalse(self.bw_log.exists(), "Bitwarden must not be queried when BW_SESSION is unset")
        self.assertEqual(self._output_roots(), [], "preflight failure must not create output transaction")
        self.assertNotIn("READY", result.stdout)
        self.assertIn("BW_SESSION is not set", result.stdout)

    def test_pins_case_insensitive_cert_sha256_matches(self) -> None:
        pins_file = self._create_pins_file(
            override={
                "play": {
                    "package": EXPECTED["play"]["package"],
                    "cert_sha256": EXPECTED["play"]["cert"].upper(),
                }
            }
        )
        command = self.command()
        command.extend(("--tag", "v1.2.3", "--pins", str(pins_file)))
        result = self.run_builder(command)

        self.assertEqual(result.returncode, 0, result.stdout)
        response = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(response["status"], "ready")

    def test_missing_or_unreadable_pins_file_fails_preflight(self) -> None:
        command = self.command()
        command.extend(("--pins", str(self.tmp / "nonexistent-pins.json")))
        result = self.run_builder(command, passwords=False)
        self._assert_preflight_rejected(result)


class PolicyTimeoutBudgetTest(unittest.TestCase):
    """The per-flavor policy timeout budget is part of the release lane's named fail-closed contract, not a silent knob."""

    def test_policy_timeout_seconds_mapping_is_pinned_per_flavor(self) -> None:
        from scripts import release_artifact_stage as stager

        self.assertEqual(
            stager.POLICY_TIMEOUT_SECONDS,
            {"play": 720, "foss": 720, "github": 360},
        )


class SignerPinRegistryTest(unittest.TestCase):
    """Pin-file shape controls for the Issue #12 signer-pin registry gap."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="librepocket-signer-pins-")
        self.addCleanup(self.temporary.cleanup)
        self.tmp = Path(self.temporary.name)

    def _write(self, name: str, payload: object) -> Path:
        path = self.tmp / name
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_valid_single_flavor_pin_normalizes_cert_to_lowercase(self) -> None:
        path = self._write(
            "pins.json",
            {"play": {"package": "dev.librepocket.agent", "cert_sha256": "AB12" * 16}},
        )

        pins = signer_pins.load_pins(str(path))

        self.assertEqual(
            pins,
            {"play": {"package": "dev.librepocket.agent", "cert_sha256": "ab12" * 16}},
        )

    def test_checked_in_example_is_rejected_as_non_production(self) -> None:
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(str(PINS_EXAMPLE))

        result = subprocess.run(
            [sys.executable, str(PINS_CHECKER), "--pins", str(PINS_EXAMPLE)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=30,
        )
        self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_unknown_flavor_is_rejected(self) -> None:
        path = self._write(
            "pins.json",
            {"beta": {"package": "dev.librepocket.agent", "cert_sha256": "ab12" * 16}},
        )

        with self.assertRaisesRegex(signer_pins.PinError, "unknown flavor"):
            signer_pins.load_pins(str(path))

    def test_missing_or_extra_fields_are_rejected(self) -> None:
        missing = self._write(
            "missing.json",
            {"play": {"package": "dev.librepocket.agent"}},
        )
        with self.assertRaisesRegex(signer_pins.PinError, "exactly package/cert_sha256"):
            signer_pins.load_pins(str(missing))

        extra = self._write(
            "extra.json",
            {
                "play": {
                    "package": "dev.librepocket.agent",
                    "cert_sha256": "ab12" * 16,
                    "owner": "someone",
                }
            },
        )
        with self.assertRaisesRegex(signer_pins.PinError, "exactly package/cert_sha256"):
            signer_pins.load_pins(str(extra))

    def test_invalid_package_and_cert_values_are_rejected(self) -> None:
        bad_cases = [
            ({"package": "not a package", "cert_sha256": "ab12" * 16}, "package"),
            ({"package": "single", "cert_sha256": "ab12" * 16}, "package"),
            ({"package": "dev.librepocket.agent", "cert_sha256": "ab12" * 15 + "ab1"}, "cert"),
            ({"package": "dev.librepocket.agent", "cert_sha256": "zz" * 32}, "cert"),
            ({"package": "dev.librepocket.agent", "cert_sha256": ""}, "cert"),
            (
                {"package": "dev.librepocket.agent", "cert_sha256": "PUT_REAL_64_HEX_PIN_HERE_FOR_PLAY"},
                "cert",
            ),
        ]
        for entry, _field in bad_cases:
            with self.subTest(entry=entry):
                path = self._write("pins.json", {"play": entry})
                with self.assertRaises(signer_pins.PinError):
                    signer_pins.load_pins(str(path))

    def test_empty_and_non_object_registries_are_rejected(self) -> None:
        for payload in ({}, [], "pins"):
            path = self._write("pins.json", payload)
            with self.subTest(payload=payload):
                with self.assertRaises(signer_pins.PinError):
                    signer_pins.load_pins(str(path))

    def test_require_flavors_enforces_the_exact_expected_set(self) -> None:
        path = self._write(
            "pins.json",
            {"play": {"package": "dev.librepocket.agent", "cert_sha256": "ab12" * 16}},
        )
        with self.assertRaisesRegex(signer_pins.PinError, "do not match required"):
            signer_pins.load_pins(str(path), require_flavors=("play", "foss", "github"))
        pins = signer_pins.load_pins(str(path), require_flavors=("play",))
        self.assertEqual(set(pins), {"play"})

    def test_require_flavors_rejects_duplicates_unknown_and_empty_via_api(self) -> None:
        path = self._write(
            "pins.json",
            {"play": {"package": "dev.librepocket.agent", "cert_sha256": "ab12" * 16}},
        )
        for bad in ((), ("play", "play"), ("play", "Play"), ("beta",)):
            with self.subTest(require=bad):
                with self.assertRaises(signer_pins.PinError):
                    signer_pins.load_pins(str(path), require_flavors=bad)

    def test_require_flavors_rejects_non_sequence_container(self) -> None:
        path = self._write(
            "pins.json",
            {"play": {"package": "dev.librepocket.agent", "cert_sha256": "ab12" * 16}},
        )
        for bad in (123, "play", {"play"}):
            with self.subTest(require=bad):
                with self.assertRaises(signer_pins.PinError):
                    signer_pins.load_pins(str(path), require_flavors=bad)  # type: ignore[arg-type]

    def test_duplicate_json_keys_are_rejected(self) -> None:
        dup = self.tmp / "dup.json"
        dup.write_text(
            '{"play": {"package": "dev.librepocket.agent", "cert_sha256": "'
            + "ab12" * 16
            + '"}, "play": {"package": "dev.librepocket.agent", "cert_sha256": "'
            + "cd34" * 16
            + '"}}',
            encoding="utf-8",
        )
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(str(dup))

    def test_empty_and_oversize_pins_are_rejected(self) -> None:
        empty = self.tmp / "empty.json"
        empty.write_text("", encoding="utf-8")
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(str(empty))
        big = self.tmp / "big.json"
        big.write_bytes(b'{"play": {"package": "dev.librepocket.agent", "cert_sha256": "')
        with open(big, "ab") as handle:
            handle.write(b"ab12" * 16384)
            handle.write(b'"}}')
        with self.assertRaisesRegex(signer_pins.PinError, "exceeds"):
            signer_pins.load_pins(str(big))

    def test_non_string_path_is_rejected_fail_closed(self) -> None:
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(None)  # type: ignore[arg-type]

    def test_package_length_boundary_and_cert_variants(self) -> None:
        long_ok = "a." + "b" * 251 + ".c"  # 255 chars, valid dotted shape
        self.assertEqual(len(long_ok), 255)
        path = self._write(
            "pins.json",
            {"play": {"package": long_ok, "cert_sha256": "ab12" * 16}},
        )
        pins = signer_pins.load_pins(str(path))
        self.assertEqual(pins["play"]["package"], long_ok)
        long_bad = long_ok + "d"  # 256 chars
        path = self._write(
            "pins256.json",
            {"play": {"package": long_bad, "cert_sha256": "ab12" * 16}},
        )
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(str(path))
        for bad_cert in ("ab12" * 16 + "ab1", "0x" + "ab12" * 15 + "ab", "ab12" * 15 + "ab\n"):
            with self.subTest(cert=bad_cert[:20]):
                path = self._write(
                    "pins.json",
                    {"play": {"package": "dev.librepocket.agent", "cert_sha256": bad_cert}},
                )
                with self.assertRaises(signer_pins.PinError):
                    signer_pins.load_pins(str(path))

    def test_non_dict_entry_and_mixed_case_cert(self) -> None:
        path = self._write("pins.json", {"play": "not-a-dict"})
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(str(path))
        path = self._write(
            "pins.json",
            {"play": {"package": "dev.librepocket.agent", "cert_sha256": "Ab12" * 16}},
        )
        pins = signer_pins.load_pins(str(path))
        self.assertEqual(pins["play"]["cert_sha256"], "ab12" * 16)

    def test_missing_file_and_invalid_json_are_rejected(self) -> None:
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(str(self.tmp / "does-not-exist.json"))
        broken = self.tmp / "broken.json"
        broken.write_text("{not json", encoding="utf-8")
        with self.assertRaises(signer_pins.PinError):
            signer_pins.load_pins(str(broken))


class ReleaseTagVersionTest(unittest.TestCase):
    """Tag <-> Gradle version consistency controls (Issue #12 acceptance)."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="librepocket-tag-version-")
        self.addCleanup(self.temporary.cleanup)
        self.tmp = Path(self.temporary.name)

    def _write_gradle(self, *, version_code: str, version_name_line: str) -> Path:
        path = self.tmp / "build.gradle.kts"
        path.write_text(
            "android {\n"
            "    defaultConfig {\n"
            f"        versionCode = {version_code}\n"
            f"        {version_name_line}\n"
            "    }\n"
            "    buildTypes {\n"
            '        debug { versionNameSuffix = "-debug" }\n'
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        return path

    def test_checked_in_gradle_parses_and_round_trips_with_its_own_tag(self) -> None:
        code, name = tag_version.parse_gradle_version(str(APP_BUILD_GRADLE))

        result = tag_version.check_tag_version(f"v{name}", code, name)

        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["versionCode"], code)

    def test_matching_tag_passes_on_a_fixture(self) -> None:
        path = self._write_gradle(version_code="7", version_name_line='versionName = "1.2.3"')
        code, name = tag_version.parse_gradle_version(str(path))

        self.assertEqual((code, name), (7, "1.2.3"))
        self.assertEqual(
            tag_version.check_tag_version("v1.2.3", code, name)["status"], "ok"
        )

    def test_debug_suffix_line_does_not_create_a_second_version_name(self) -> None:
        path = self._write_gradle(version_code="7", version_name_line='versionName = "1.2.3"')

        code, name = tag_version.parse_gradle_version(str(path))

        self.assertEqual(name, "1.2.3")
        self.assertEqual(code, 7)

    def test_mismatched_tag_is_rejected(self) -> None:
        with self.assertRaisesRegex(tag_version.TagVersionError, "does not equal"):
            tag_version.check_tag_version("v9.9.9", 7, "1.2.3")

    def test_non_semver_tags_and_versions_are_rejected(self) -> None:
        for bad_tag in ("1.2.3", "v1.2", "v1.2.3-rc1", "v1.2.3 ", "", "v01"):
            with self.subTest(tag=bad_tag):
                with self.assertRaises(tag_version.TagVersionError):
                    tag_version.check_tag_version(bad_tag, 7, "1.2.3")
        for bad_name in ("1.2", "1.2.3-rc1", "", "v1.2.3"):
            with self.subTest(version=bad_name):
                with self.assertRaises(tag_version.TagVersionError):
                    tag_version.check_tag_version("v1.2.3", 7, bad_name)

    def test_unicode_digits_and_leading_zeros_are_rejected(self) -> None:
        for bad_tag in ("v\u0661.\u0662.\u0663", "v01.02.03", "v1.02.3", "v1.2.03"):
            with self.subTest(tag=bad_tag):
                with self.assertRaises(tag_version.TagVersionError):
                    tag_version.check_tag_version(bad_tag, 7, "1.2.3")
        for bad_name in ("\u0661.\u0662.\u0663", "01.02.03"):
            with self.subTest(version=bad_name):
                with self.assertRaises(tag_version.TagVersionError):
                    tag_version.check_tag_version("v1.2.3", 7, bad_name)

    def test_non_string_and_bool_inputs_are_rejected_fail_closed(self) -> None:
        for bad_tag, bad_code, bad_name in (
            (None, 7, "1.2.3"),
            (123, 7, "1.2.3"),
            ("v1.2.3", True, "1.2.3"),
            ("v1.2.3", "7", "1.2.3"),
            ("v1.2.3", 7, None),
            ("v1.2.3", 7, 123),
        ):
            with self.subTest(tag=bad_tag, code=bad_code, name=bad_name):
                with self.assertRaises(tag_version.TagVersionError):
                    tag_version.check_tag_version(bad_tag, bad_code, bad_name)  # type: ignore[arg-type]

    def test_version_code_boundaries_pass(self) -> None:
        for good_code in (1, 2_147_483_647):
            with self.subTest(code=good_code):
                result = tag_version.check_tag_version("v1.2.3", good_code, "1.2.3")
                self.assertEqual(result["status"], "ok")

    def test_overlong_tag_and_version_are_rejected(self) -> None:
        long_name = "1." + "2" * 300
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.check_tag_version(f"v{long_name}", 7, long_name)
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.check_tag_version("v" + "1" * 300, 7, "1.2.3")

    def test_trailing_comment_is_accepted_and_leading_zero_code_rejected(self) -> None:
        path = self.tmp / "comment.gradle.kts"
        path.write_text(
            'versionCode = 7 // release code\nversionName = "1.2.3" // comment\n',
            encoding="utf-8",
        )
        self.assertEqual(tag_version.parse_gradle_version(str(path)), (7, "1.2.3"))
        leading = self.tmp / "leading.gradle.kts"
        leading.write_text(
            'versionCode = 007\nversionName = "1.2.3"\n', encoding="utf-8"
        )
        with self.assertRaisesRegex(tag_version.TagVersionError, "leading zero"):
            tag_version.parse_gradle_version(str(leading))

    def test_public_build_parser_alias_exists(self) -> None:
        from scripts import verify_release_apk as verifier

        self.assertTrue(hasattr(verifier, "build_parser"))
        parser = verifier.build_parser()
        namespace = parser.parse_args(
            [
                "--candidate-dir", "cand",
                "--stage-dir", "stage",
                "--expected-package", "dev.librepocket.agent",
                "--expected-version-code", "7",
                "--expected-version-name", "1.2.3",
                "--expected-cert-sha256", "ab12" * 16,
                "--apksigner", "apksigner",
                "--aapt", "aapt",
            ]
        )
        self.assertEqual(namespace.expected_package, "dev.librepocket.agent")

    def test_rehearsal_rejects_unexpected_argv_without_env_mutation(self) -> None:
        from scripts import fake_keystore_rehearsal as rehearsal

        marker_play = os.environ.get("PLAY_KEYSTORE_PASSWORD", "<absent>")
        marker_bw = os.environ.get("BW_SESSION", "<absent>")
        self.assertEqual(rehearsal.main(["--unexpected"]), 1)
        self.assertEqual(os.environ.get("PLAY_KEYSTORE_PASSWORD", "<absent>"), marker_play)
        self.assertEqual(os.environ.get("BW_SESSION", "<absent>"), marker_bw)

    def test_rehearsal_restores_env_on_harness_error(self) -> None:
        import unittest.mock as mock

        from scripts import fake_keystore_rehearsal as rehearsal

        orig_play = os.environ.get("PLAY_KEYSTORE_PASSWORD")
        orig_bw = os.environ.get("BW_SESSION")
        self.addCleanup(
            lambda: (
                os.environ.__setitem__("PLAY_KEYSTORE_PASSWORD", orig_play)
                if orig_play is not None
                else os.environ.pop("PLAY_KEYSTORE_PASSWORD", None),
                os.environ.__setitem__("BW_SESSION", orig_bw)
                if orig_bw is not None
                else os.environ.pop("BW_SESSION", None),
            )
        )
        os.environ["PLAY_KEYSTORE_PASSWORD"] = "before-play"
        os.environ["BW_SESSION"] = "before-bw"
        with mock.patch.object(
            rehearsal, "_run_case", side_effect=RuntimeError("boom")
        ):
            with self.assertRaises(RuntimeError):
                rehearsal.main([])
        self.assertEqual(os.environ.get("PLAY_KEYSTORE_PASSWORD"), "before-play")
        self.assertEqual(os.environ.get("BW_SESSION"), "before-bw")

    def test_fake_shims_reject_malformed_payload_with_distinct_codes(self) -> None:
        import textwrap

        from scripts import fake_keystore_rehearsal as rehearsal

        bad_cert = self.tmp / "bad-cert.json"
        bad_cert.write_text('{"signed": true}', encoding="utf-8")
        shim = self.tmp / "apksigner"
        shim.write_text(
            rehearsal.FAKE_APKSIGNER.replace("{python}", sys.executable),
            encoding="utf-8",
        )
        shim.chmod(0o755)
        result = subprocess.run(
            [sys.executable, str(shim), "verify", "--verbose", "--print-certs", "-Werr",
             str(bad_cert)],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=False, timeout=30,
        )
        self.assertEqual(result.returncode, 93, result.stdout)
        bad_pkg = self.tmp / "bad-pkg.json"
        bad_pkg.write_text('{"package": 123}', encoding="utf-8")
        aapt = self.tmp / "aapt"
        aapt.write_text(
            rehearsal.FAKE_AAPT.replace("{python}", sys.executable), encoding="utf-8"
        )
        aapt.chmod(0o755)
        result = subprocess.run(
            [sys.executable, str(aapt), "dump", "badging", str(bad_pkg)],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=False, timeout=30,
        )
        self.assertEqual(result.returncode, 83, result.stdout)

    def test_version_code_range_is_rejected(self) -> None:
        for bad_code in (0, -1, 2_147_483_648):
            with self.subTest(code=bad_code):
                with self.assertRaises(tag_version.TagVersionError):
                    tag_version.check_tag_version("v1.2.3", bad_code, "1.2.3")

    def test_missing_file_and_duplicate_assignments_are_rejected(self) -> None:
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.parse_gradle_version(str(self.tmp / "does-not-exist.gradle.kts"))
        duplicated = self.tmp / "duplicated.gradle.kts"
        duplicated.write_text(
            'versionCode = 7\nversionCode = 8\nversionName = "1.2.3"\n',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(tag_version.TagVersionError, "exactly one versionCode"):
            tag_version.parse_gradle_version(str(duplicated))

    def test_duplicate_version_name_missing_fields_and_single_quote(self) -> None:
        dup_name = self.tmp / "dup-name.gradle.kts"
        dup_name.write_text(
            'versionCode = 7\nversionName = "1.2.3"\nversionName = "1.2.3"\n',
            encoding="utf-8",
        )
        with self.assertRaisesRegex(tag_version.TagVersionError, "exactly one versionName"):
            tag_version.parse_gradle_version(str(dup_name))
        missing_code = self.tmp / "missing-code.gradle.kts"
        missing_code.write_text('versionName = "1.2.3"\n', encoding="utf-8")
        with self.assertRaisesRegex(tag_version.TagVersionError, "exactly one versionCode"):
            tag_version.parse_gradle_version(str(missing_code))
        missing_name = self.tmp / "missing-name.gradle.kts"
        missing_name.write_text("versionCode = 7\n", encoding="utf-8")
        with self.assertRaisesRegex(tag_version.TagVersionError, "exactly one versionName"):
            tag_version.parse_gradle_version(str(missing_name))
        single = self.tmp / "single.gradle.kts"
        single.write_text(
            "versionCode = 7\nversionName = '1.2.3'\n", encoding="utf-8"
        )
        with self.assertRaisesRegex(tag_version.TagVersionError, "double-quoted"):
            tag_version.parse_gradle_version(str(single))

    def test_oversize_gradle_is_rejected(self) -> None:
        big = self.tmp / "big.gradle.kts"
        big.write_bytes(b'versionCode = 7\nversionName = "1.2.3"\n' + b"x" * (512 * 1024))
        with self.assertRaisesRegex(tag_version.TagVersionError, "exceeds"):
            tag_version.parse_gradle_version(str(big))

    def test_block_commented_literals_cannot_satisfy_the_gate(self) -> None:
        commented = self.tmp / "commented.gradle.kts"
        commented.write_text(
            "/*\n"
            'versionCode = 1\nversionName = "0.1.0"\n'
            "*/\n"
            "android {\n"
            "  defaultConfig {\n"
            '    versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 9\n'
            '    versionName = System.getenv("VERSION_NAME") ?: "9.9.9"\n'
            "  }\n"
            "}\n",
            encoding="utf-8",
        )
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.parse_gradle_version(str(commented))

    def test_kdoc_nested_and_inline_block_comments(self) -> None:
        kdoc = self.tmp / "kdoc.gradle.kts"
        kdoc.write_text(
            "/**\n"
            ' * versionCode = 1\n * versionName = "0.1.0"\n'
            " */\n"
            'versionCode = 7\nversionName = "1.2.3"\n',
            encoding="utf-8",
        )
        self.assertEqual(tag_version.parse_gradle_version(str(kdoc)), (7, "1.2.3"))
        nested = self.tmp / "nested.gradle.kts"
        nested.write_text(
            "/* outer /* inner */ still comment\n"
            'versionCode = 1\nversionName = "0.1.0"\n'
            "*/\n"
            'versionCode = 7\nversionName = "1.2.3"\n',
            encoding="utf-8",
        )
        self.assertEqual(tag_version.parse_gradle_version(str(nested)), (7, "1.2.3"))
        inline = self.tmp / "inline.gradle.kts"
        inline.write_text(
            "versionCode = 7 /* trailing block */\n"
            'versionName = "1.2.3" /* trailing block */\n',
            encoding="utf-8",
        )
        self.assertEqual(tag_version.parse_gradle_version(str(inline)), (7, "1.2.3"))

    def test_line_comment_markers_inside_strings_are_preserved(self) -> None:
        full_line = self.tmp / "full-line.gradle.kts"
        full_line.write_text(
            "// versionCode = 999\n"
            '// versionName = "9.9.9"\n'
            'versionCode = 7\nversionName = "1.2.3"\n',
            encoding="utf-8",
        )
        self.assertEqual(tag_version.parse_gradle_version(str(full_line)), (7, "1.2.3"))
        in_string = self.tmp / "in-string.gradle.kts"
        in_string.write_text(
            'versionCode = 7\nversionName = "1.2.3 // x"\n', encoding="utf-8"
        )
        code, name = tag_version.parse_gradle_version(str(in_string))
        self.assertEqual((code, name), (7, "1.2.3 // x"))
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.check_tag_version("v1.2.3", code, name)

    def test_char_literal_cannot_shadow_comments(self) -> None:
        # A '"' inside a char literal must not open a phantom string that
        # protects a commented-out value: the commented 999 must not win.
        tricky = self.tmp / "char-literal.gradle.kts"
        tricky.write_text(
            "val c = '\"'\n"
            "versionCode = 1 // keep\n"
            "/*\n"
            "versionCode = 999\n"
            "*/\n"
            'versionName = "1.2.3"\n',
            encoding="utf-8",
        )
        self.assertEqual(tag_version.parse_gradle_version(str(tricky)), (1, "1.2.3"))
        escaped = self.tmp / "char-escape.gradle.kts"
        escaped.write_text(
            "val q = '\\u0027'\n"
            "versionCode = 7 // comment\n"
            'versionName = "1.2.3"\n',
            encoding="utf-8",
        )
        self.assertEqual(tag_version.parse_gradle_version(str(escaped)), (7, "1.2.3"))

    def test_cli_accepts_matching_tag_and_rejects_mismatch(self) -> None:
        path = self._write_gradle(version_code="7", version_name_line='versionName = "1.2.3"')
        good = subprocess.run(
            [sys.executable, str(TAG_CHECKER), "--tag", "v1.2.3",
             "--app-build-gradle", str(path)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=30,
        )
        self.assertEqual(good.returncode, 0, good.stdout)
        bad = subprocess.run(
            [sys.executable, str(TAG_CHECKER), "--tag", "v9.9.9",
             "--app-build-gradle", str(path)],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=30,
        )
        self.assertNotEqual(bad.returncode, 0, bad.stdout)

    def test_cli_rejects_bad_tag_missing_file_and_bad_require_flavors(self) -> None:
        path = self._write_gradle(version_code="7", version_name_line='versionName = "1.2.3"')
        bad_tag = subprocess.run(
            [sys.executable, str(TAG_CHECKER), "--tag", "v01.02.03",
             "--app-build-gradle", str(path)],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=False, timeout=30,
        )
        self.assertNotEqual(bad_tag.returncode, 0, bad_tag.stdout)
        missing = subprocess.run(
            [sys.executable, str(TAG_CHECKER), "--tag", "v1.2.3",
             "--app-build-gradle", str(self.tmp / "nope.gradle.kts")],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=False, timeout=30,
        )
        self.assertNotEqual(missing.returncode, 0, missing.stdout)
        pins = self.tmp / "pins.json"
        pins.write_text(
            json.dumps(
                {"play": {"package": "dev.librepocket.agent", "cert_sha256": "ab12" * 16}}
            ),
            encoding="utf-8",
        )
        bad_flavors = subprocess.run(
            [sys.executable, str(PINS_CHECKER), "--pins", str(pins),
             "--require-flavors", "play play"],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=False, timeout=30,
        )
        self.assertNotEqual(bad_flavors.returncode, 0, bad_flavors.stdout)
        good_pins = subprocess.run(
            [sys.executable, str(PINS_CHECKER), "--pins", str(pins),
             "--require-flavors", "play"],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=False, timeout=30,
        )
        self.assertEqual(good_pins.returncode, 0, good_pins.stdout)
        result_json = json.loads(good_pins.stdout)
        self.assertEqual(result_json.get("status"), "ok")

    def test_raw_string_literals_cannot_satisfy_the_gate(self) -> None:
        path = self.tmp / "raw-string.gradle.kts"
        path.write_text(
            'val releaseCode = 9\n'
            'val releaseName = "9.9.9"\n'
            'val documentation = """\n'
            'versionCode = 1\n'
            'versionName = "0.1.0"\n'
            '"""\n'
            '\n'
            'android {\n'
            '    defaultConfig {\n'
            '        versionCode = releaseCode\n'
            '        versionName = releaseName\n'
            '    }\n'
            '}\n',
            encoding="utf-8",
        )
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.parse_gradle_version(str(path))

    def test_unrelated_scope_literals_cannot_satisfy_the_gate(self) -> None:
        path = self.tmp / "unrelated-scope.gradle.kts"
        path.write_text(
            'versionCode = 1\n'
            'versionName = "0.1.0"\n'
            '\n'
            'android {\n'
            '    defaultConfig {\n'
            '        versionCode = releaseCode\n'
            '        versionName = releaseName\n'
            '    }\n'
            '}\n',
            encoding="utf-8",
        )
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.parse_gradle_version(str(path))

    def test_multiple_default_config_blocks_are_rejected(self) -> None:
        path = self.tmp / "multi-default.gradle.kts"
        path.write_text(
            "android {\n"
            "    defaultConfig {\n"
            '        versionCode = 1\n'
            '        versionName = "0.1.0"\n'
            "    }\n"
            "    defaultConfig {\n"
            '        versionCode = 9\n'
            '        versionName = "9.9.9"\n'
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.parse_gradle_version(str(path))

    def test_multiple_android_blocks_are_rejected(self) -> None:
        path = self.tmp / "multi-android.gradle.kts"
        path.write_text(
            "android {\n"
            "    defaultConfig {\n"
            '        versionCode = 1\n'
            '        versionName = "0.1.0"\n'
            "    }\n"
            "}\n"
            "android {\n"
            "    defaultConfig {\n"
            '        versionCode = 9\n'
            '        versionName = "9.9.9"\n'
            "    }\n"
            "}\n",
            encoding="utf-8",
        )
        with self.assertRaises(tag_version.TagVersionError):
            tag_version.parse_gradle_version(str(path))


if __name__ == "__main__":
    unittest.main()
