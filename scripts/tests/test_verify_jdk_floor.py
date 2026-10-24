"""Stdlib regression harness for scripts/verify_jdk_floor.sh (CVE-2026-91403).

Pins:
- Minimum security floor of 25.0.5 and patched Temurin build >= 8.
- Version comparisons: older versions reject, version == floor requires build >= 8,
  newer versions pass with any build.
- Build extraction from various version banner formats.
- Fail-closed behavior on malformed inputs, unknown/missing CLI flags.
- JAVA_HOME consistency validation and distrust policy inspection.

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_verify_jdk_floor.py
"""

from __future__ import annotations

import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parent.parent / "verify_jdk_floor.sh"


class VerifyJdkFloorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.assertTrue(SCRIPT_PATH.is_file(), f"{SCRIPT_PATH} does not exist")
        self.assertTrue(os.access(SCRIPT_PATH, os.X_OK), f"{SCRIPT_PATH} is not executable")

    def run_verify(
        self,
        args: list[str],
        *,
        env_extra: dict[str, str] | None = None,
        clear_java_home: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        env = dict(os.environ)
        if clear_java_home:
            env.pop("JAVA_HOME", None)
        if env_extra:
            env.update(env_extra)
        return subprocess.run(
            ["bash", str(SCRIPT_PATH), *args],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def test_version_floor_rejections(self) -> None:
        """Versions below 25.0.5 reject with exit code 1."""
        for version in (
            "25.0.4",
            "25.0.4.1+1",
            "25.0.4.1",
            "24.0.2+8",
            "21.0.6+7",
            "17.0.12+7",
        ):
            with self.subTest(version=version):
                proc = self.run_verify(["--version-string", version])
                self.assertEqual(
                    proc.returncode,
                    1,
                    f"Expected rejection for '{version}', got returncode {proc.returncode}. Output:\n{proc.stdout}\n{proc.stderr}",
                )
                self.assertIn("FAILED", proc.stderr)

    def test_build_floor_rejections_when_version_matches_floor(self) -> None:
        """When version is 25.0.5, build must be >= 8. Missing build or build < 8 rejects."""
        for version in (
            "25.0.5",  # without build suffix
            "25.0.5+0",
            "25.0.5+1",
            "25.0.5+5",
            "25.0.5+7",
            "Temurin-25.0.5+7",
        ):
            with self.subTest(version=version):
                proc = self.run_verify(["--version-string", version])
                self.assertEqual(
                    proc.returncode,
                    1,
                    f"Expected rejection for '{version}', got returncode {proc.returncode}. Output:\n{proc.stdout}\n{proc.stderr}",
                )
                self.assertIn("FAILED", proc.stderr)

    def test_floor_and_higher_builds_pass(self) -> None:
        """When version is 25.0.5 and build >= 8, verification passes."""
        for version in (
            "Temurin-25.0.5+8",
            "Temurin-25.0.5+9",
            "25.0.5+8",
            "25.0.5+9",
            "25.0.5+10",
            "build 25.0.5+8",
            "build 25.0.5+12",
        ):
            with self.subTest(version=version):
                proc = self.run_verify(["--version-string", version])
                self.assertEqual(
                    proc.returncode,
                    0,
                    f"Expected success for '{version}', got returncode {proc.returncode}. Output:\n{proc.stdout}\n{proc.stderr}",
                )
                self.assertIn("PASSED", proc.stdout)

    def test_higher_versions_pass_any_build(self) -> None:
        """When version > 25.0.5, any build passes."""
        for version in (
            "25.0.6+1",
            "25.0.6",
            "26.0.0+1",
            "26.0.0",
            "27.0.0+0",
        ):
            with self.subTest(version=version):
                proc = self.run_verify(["--version-string", version])
                self.assertEqual(
                    proc.returncode,
                    0,
                    f"Expected success for '{version}', got returncode {proc.returncode}. Output:\n{proc.stdout}\n{proc.stderr}",
                )
                self.assertIn("PASSED", proc.stdout)

    def test_malformed_input_rejects(self) -> None:
        """Malformed version strings reject fail-closed with exit code 1."""
        for bad in (
            "",
            "   ",
            "\t\n",
            "invalid text",
            "non-numeric",
            "25.a.b",
            "25.0.5+abc",
            "25.0.5+",
            "+8",
            "...",
            "Temurin-",
        ):
            with self.subTest(bad=bad):
                proc = self.run_verify(["--version-string", bad])
                self.assertEqual(
                    proc.returncode,
                    1,
                    f"Expected rejection for malformed '{bad}', got {proc.returncode}",
                )
                self.assertIn("ERROR", proc.stderr)

    def test_missing_or_invalid_cli_flags_reject(self) -> None:
        """Missing flag arguments or invalid values fail closed."""
        cases: list[list[str]] = [
            ["--floor"],
            ["--floor", ""],
            ["--floor", "25.0.alpha"],
            ["--floor=25.0.alpha"],
            ["--min-build"],
            ["--min-build", ""],
            ["--min-build", "abc"],
            ["--min-build", "-1"],
            ["--min-build=xyz"],
            ["--version-string"],
            ["--version-string", ""],
            ["--require-vendor"],
            ["--require-vendor", ""],
            ["--unknown-flag"],
            ["-x"],
        ]
        for args in cases:
            with self.subTest(args=args):
                proc = self.run_verify(args)
                self.assertEqual(
                    proc.returncode,
                    1,
                    f"Expected failure for args {args}, got {proc.returncode}. Output:\n{proc.stdout}\n{proc.stderr}",
                )
                self.assertIn("ERROR", proc.stderr)

    def test_require_vendor_flag(self) -> None:
        """--require-vendor requires the specified vendor to appear in version output."""
        # Temurin present
        proc = self.run_verify(
            ["--version-string", "Temurin-25.0.5+8", "--require-vendor", "Temurin"]
        )
        self.assertEqual(proc.returncode, 0)
        self.assertIn("verified vendor 'Temurin' present", proc.stdout)

        # Temurin required but not in output
        proc = self.run_verify(
            ["--version-string", "25.0.5+8", "--require-vendor", "Temurin"]
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("required vendor 'Temurin' not found", proc.stderr)

        # Different vendor specified
        proc = self.run_verify(
            ["--version-string", "Temurin-25.0.5+8", "--require-vendor", "OtherVendor"]
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("required vendor 'OtherVendor' not found", proc.stderr)

    def test_custom_floor_and_min_build_flags(self) -> None:
        """Custom --floor and --min-build flags are respected."""
        # 25.0.5+7 passes if --min-build is 7
        proc = self.run_verify(
            ["--version-string", "25.0.5+7", "--floor", "25.0.5", "--min-build", "7"]
        )
        self.assertEqual(proc.returncode, 0)

        # 25.0.5+8 rejects if --min-build is 9
        proc = self.run_verify(
            ["--version-string", "25.0.5+8", "--floor", "25.0.5", "--min-build", "9"]
        )
        self.assertEqual(proc.returncode, 1)

        # 25.0.5 without build passes if --min-build is 0
        proc = self.run_verify(
            ["--version-string", "25.0.5", "--floor", "25.0.5", "--min-build", "0"]
        )
        self.assertEqual(proc.returncode, 0)

        # 24.0.0 passes if --floor is 24.0.0 and --min-build is 0
        proc = self.run_verify(
            ["--version-string", "24.0.0", "--floor", "24.0.0", "--min-build", "0"]
        )
        self.assertEqual(proc.returncode, 0)

    def test_multiline_real_world_jdk_outputs(self) -> None:
        """Verify full banner outputs from OpenJDK / Temurin distributions."""
        temurin_25_0_5_plus_8 = (
            'openjdk version "25.0.5" 2026-10-20\n'
            "OpenJDK Runtime Environment Temurin-25.0.5+8 (build 25.0.5+8)\n"
            "OpenJDK 64-Bit Server VM Temurin-25.0.5+8 (build 25.0.5+8, mixed mode, sharing)\n"
        )
        proc = self.run_verify(
            ["--version-string", temurin_25_0_5_plus_8, "--require-vendor", "Temurin"]
        )
        self.assertEqual(proc.returncode, 0)
        self.assertIn("PASSED", proc.stdout)

        unpatched_temurin = (
            'openjdk version "25.0.5" 2026-10-20\n'
            "OpenJDK Runtime Environment Temurin-25.0.5+7 (build 25.0.5+7)\n"
            "OpenJDK 64-Bit Server VM Temurin-25.0.5+7 (build 25.0.5+7, mixed mode, sharing)\n"
        )
        proc = self.run_verify(["--version-string", unpatched_temurin])
        self.assertEqual(proc.returncode, 1)
        self.assertIn("FAILED", proc.stderr)

        older_ubuntu = (
            'openjdk version "25.0.4.1" 2026-08-18\n'
            "OpenJDK Runtime Environment (build 25.0.4.1+1-1-26.04.4-Ubuntu)\n"
            "OpenJDK 64-Bit Server VM (build 25.0.4.1+1-1-26.04.4-Ubuntu, mixed mode, sharing)\n"
        )
        proc = self.run_verify(["--version-string", older_ubuntu])
        self.assertEqual(proc.returncode, 1)
        self.assertIn("FAILED", proc.stderr)

    def test_help_flag(self) -> None:
        """-h and --help print usage and exit 0."""
        for flag in ("-h", "--help"):
            with self.subTest(flag=flag):
                proc = self.run_verify([flag])
                self.assertEqual(proc.returncode, 0)
                self.assertIn("Usage:", proc.stdout)

    def test_java_home_mismatch_rejects(self) -> None:
        """When JAVA_HOME is inconsistent with java on PATH, verification rejects (exit 1)."""
        # 1. Non-existent directory
        proc = self.run_verify(
            ["--version-string", "Temurin-25.0.5+8"],
            env_extra={"JAVA_HOME": "/nonexistent/java_home_dir"},
            clear_java_home=False,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("ERROR: $JAVA_HOME is set", proc.stderr)

        # 2. Directory without bin/java
        with tempfile.TemporaryDirectory() as empty_dir:
            proc = self.run_verify(
                ["--version-string", "Temurin-25.0.5+8"],
                env_extra={"JAVA_HOME": empty_dir},
                clear_java_home=False,
            )
            self.assertEqual(proc.returncode, 1)
            self.assertIn("does not exist or is not executable", proc.stderr)

        # 3. Directory with bin/java that is NOT executable
        with tempfile.TemporaryDirectory() as d:
            bin_dir = Path(d) / "bin"
            bin_dir.mkdir()
            fake_java = bin_dir / "java"
            fake_java.write_text("#!/bin/sh\nexit 0\n")
            proc = self.run_verify(
                ["--version-string", "Temurin-25.0.5+8"],
                env_extra={"JAVA_HOME": d},
                clear_java_home=False,
            )
            self.assertEqual(proc.returncode, 1)
            self.assertIn("does not exist or is not executable", proc.stderr)

        # 4. Directory with executable bin/java whose realpath differs from command -v java
        with tempfile.TemporaryDirectory() as d:
            bin_dir = Path(d) / "bin"
            bin_dir.mkdir()
            fake_java = bin_dir / "java"
            fake_java.write_text("#!/bin/sh\nexit 0\n")
            fake_java.chmod(fake_java.stat().st_mode | stat.S_IXUSR)
            proc = self.run_verify(
                ["--version-string", "Temurin-25.0.5+8"],
                env_extra={"JAVA_HOME": d},
                clear_java_home=False,
            )
            self.assertEqual(proc.returncode, 1)
            self.assertIn("does not match 'java' on PATH", proc.stderr)

    def test_java_home_matching_and_distrust_policy_inspection(self) -> None:
        """When JAVA_HOME matches PATH java, consistency passes and distrust configuration is inspected."""
        with tempfile.TemporaryDirectory() as d:
            home = Path(d)
            bin_dir = home / "bin"
            bin_dir.mkdir()
            fake_java = bin_dir / "java"
            fake_java.write_text('#!/bin/sh\necho "Temurin-25.0.5+8"\n')
            fake_java.chmod(fake_java.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

            sec_dir = home / "conf" / "security"
            sec_dir.mkdir(parents=True)
            distrust_text = (
                "jdk.security.caDistrustPolicies=SYMANTEC_TLS,ENTRUST_TLS,CAMERFIRMA_TLS,\\\n"
                "    CHUNGHWA_TLS\n"
            )
            (sec_dir / "java.security").write_text(distrust_text)

            current_path = os.environ.get("PATH", "")
            env_override = {
                "JAVA_HOME": str(home),
                "PATH": f"{bin_dir}:{current_path}",
            }

            # Running without --version-string executes fake_java and inspects java.security
            proc = self.run_verify([], env_extra=env_override, clear_java_home=False)
            self.assertEqual(
                proc.returncode,
                0,
                f"Expected success, got {proc.returncode}. Output:\n{proc.stdout}\n{proc.stderr}",
            )
            self.assertIn("verified $JAVA_HOME consistency", proc.stdout)
            self.assertIn("inspecting distrust configuration", proc.stdout)
            self.assertIn("jdk.security.caDistrustPolicies=SYMANTEC_TLS", proc.stdout)
            self.assertIn("PASSED", proc.stdout)


if __name__ == "__main__":
    unittest.main()
