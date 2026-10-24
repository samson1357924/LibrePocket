#!/usr/bin/env python3
"""Supply-chain regression harness for Gradle wrapper integrity and checksum verification.

Pins:
1. gradle/wrapper/gradle-wrapper.properties defines distributionSha256Sum.
2. distributionSha256Sum matches the canonical Gradle 9.7.1 distribution checksum:
   acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a.
3. gradle/wrapper/gradle-wrapper.jar SHA-256 matches the official 9.7.1 wrapper jar:
   7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d.
4. validateDistributionUrl is set to 'true'.
5. networkTimeout is set to '10000'.
6. Simulated tampering (negative controls): any mismatch or missing security parameter
   is detected and rejected (fail-closed).

Run with: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_gradle_wrapper_checksum.py
"""

from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

CANONICAL_GRADLE_DISTRIBUTION_SHA256 = (
    "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
)
OFFICIAL_GRADLE_WRAPPER_JAR_SHA256 = (
    "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
)
EXPECTED_VALIDATE_DISTRIBUTION_URL = "true"
EXPECTED_NETWORK_TIMEOUT = "10000"

ROOT = Path(__file__).resolve().parents[2]
PROPERTIES_PATH = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
WRAPPER_JAR_PATH = ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"


def parse_properties(text: str) -> dict[str, str]:
    """Parse Java .properties file ignoring blank lines and comments (# or !)."""
    props: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
        elif ":" in line:
            k, v = line.split(":", 1)
            props[k.strip()] = v.strip()
    return props


def compute_sha256(path: Path) -> str:
    """Compute lowercase SHA-256 hex digest of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()


def verify_wrapper_integrity(
    properties_path: Path,
    jar_path: Path,
    *,
    expected_distribution_sha256: str = CANONICAL_GRADLE_DISTRIBUTION_SHA256,
    expected_jar_sha256: str = OFFICIAL_GRADLE_WRAPPER_JAR_SHA256,
    expected_validate_url: str = EXPECTED_VALIDATE_DISTRIBUTION_URL,
    expected_network_timeout: str = EXPECTED_NETWORK_TIMEOUT,
) -> list[str]:
    """Fail-closed integrity check for Gradle wrapper properties and jar.

    Returns a list of error descriptions; an empty list indicates full integrity.
    """
    errors: list[str] = []
    if not properties_path.is_file():
        errors.append(f"Missing gradle-wrapper.properties at {properties_path}")
        return errors

    content = properties_path.read_text(encoding="utf-8")
    props = parse_properties(content)

    # 1. distributionSha256Sum presence and match
    if "distributionSha256Sum" not in props:
        errors.append("gradle-wrapper.properties is missing 'distributionSha256Sum'")
    else:
        actual_dist_sum = props["distributionSha256Sum"].lower()
        if actual_dist_sum != expected_distribution_sha256.lower():
            errors.append(
                f"distributionSha256Sum mismatch: expected {expected_distribution_sha256}, "
                f"got {actual_dist_sum}"
            )

    # 2. validateDistributionUrl check
    actual_validate_url = props.get("validateDistributionUrl")
    if actual_validate_url != expected_validate_url:
        errors.append(
            f"validateDistributionUrl must be {expected_validate_url!r}, "
            f"got {actual_validate_url!r}"
        )

    # 3. networkTimeout check
    actual_timeout = props.get("networkTimeout")
    if actual_timeout != expected_network_timeout:
        errors.append(
            f"networkTimeout must be {expected_network_timeout!r}, "
            f"got {actual_timeout!r}"
        )

    # 4. wrapper jar existence and sha256 check
    if not jar_path.is_file():
        errors.append(f"Missing gradle-wrapper.jar at {jar_path}")
    else:
        actual_jar_sha = compute_sha256(jar_path)
        if actual_jar_sha != expected_jar_sha256.lower():
            errors.append(
                f"gradle-wrapper.jar SHA-256 mismatch: expected {expected_jar_sha256}, "
                f"got {actual_jar_sha}"
            )

    return errors


class GradleWrapperChecksumTest(unittest.TestCase):
    """Test verification and fail-closed security invariants for the Gradle wrapper."""

    def test_repo_wrapper_properties_exists(self) -> None:
        self.assertTrue(
            PROPERTIES_PATH.is_file(),
            f"Expected gradle-wrapper.properties at {PROPERTIES_PATH}",
        )

    def test_repo_wrapper_properties_contains_distribution_sha256(self) -> None:
        props = parse_properties(PROPERTIES_PATH.read_text(encoding="utf-8"))
        self.assertIn(
            "distributionSha256Sum",
            props,
            "gradle-wrapper.properties must contain 'distributionSha256Sum'",
        )
        self.assertEqual(len(props["distributionSha256Sum"]), 64)

    def test_repo_distribution_sha256_matches_canonical_gradle_9_7_1(self) -> None:
        props = parse_properties(PROPERTIES_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            props.get("distributionSha256Sum", "").lower(),
            CANONICAL_GRADLE_DISTRIBUTION_SHA256.lower(),
            "distributionSha256Sum does not match canonical Gradle 9.7.1 distribution checksum",
        )

    def test_repo_wrapper_jar_sha256_matches_official_gradle_9_7_1(self) -> None:
        self.assertTrue(
            WRAPPER_JAR_PATH.is_file(),
            f"Expected gradle-wrapper.jar at {WRAPPER_JAR_PATH}",
        )
        actual_sha256 = compute_sha256(WRAPPER_JAR_PATH)
        self.assertEqual(
            actual_sha256,
            OFFICIAL_GRADLE_WRAPPER_JAR_SHA256.lower(),
            "gradle-wrapper.jar SHA-256 does not match official Gradle 9.7.1 wrapper jar",
        )

    def test_repo_wrapper_properties_security_settings(self) -> None:
        props = parse_properties(PROPERTIES_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            props.get("validateDistributionUrl"),
            EXPECTED_VALIDATE_DISTRIBUTION_URL,
            "validateDistributionUrl must be 'true'",
        )
        self.assertEqual(
            props.get("networkTimeout"),
            EXPECTED_NETWORK_TIMEOUT,
            "networkTimeout must be '10000'",
        )

    def test_repo_wrapper_passes_full_integrity_check(self) -> None:
        errors = verify_wrapper_integrity(PROPERTIES_PATH, WRAPPER_JAR_PATH)
        self.assertEqual(errors, [], f"Wrapper integrity failed: {errors}")

    def test_tampered_distribution_sha256_is_detected_and_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            fake_props = Path(td) / "gradle-wrapper.properties"
            tampered_sha = "0" * 64
            fake_props.write_text(
                f"distributionBase=GRADLE_USER_HOME\n"
                f"distributionPath=wrapper/dists\n"
                f"distributionSha256Sum={tampered_sha}\n"
                f"distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
                f"networkTimeout=10000\n"
                f"validateDistributionUrl=true\n",
                encoding="utf-8",
            )
            errors = verify_wrapper_integrity(fake_props, WRAPPER_JAR_PATH)
            self.assertTrue(
                any("distributionSha256Sum mismatch" in e for e in errors),
                f"Expected distributionSha256Sum mismatch error, got: {errors}",
            )

    def test_missing_distribution_sha256_is_detected_and_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            fake_props = Path(td) / "gradle-wrapper.properties"
            fake_props.write_text(
                f"distributionBase=GRADLE_USER_HOME\n"
                f"distributionPath=wrapper/dists\n"
                f"distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
                f"networkTimeout=10000\n"
                f"validateDistributionUrl=true\n",
                encoding="utf-8",
            )
            errors = verify_wrapper_integrity(fake_props, WRAPPER_JAR_PATH)
            self.assertTrue(
                any("missing 'distributionSha256Sum'" in e for e in errors),
                f"Expected missing distributionSha256Sum error, got: {errors}",
            )

    def test_tampered_wrapper_jar_is_detected_and_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            fake_jar = Path(td) / "gradle-wrapper.jar"
            fake_jar.write_bytes(b"tampered content")
            errors = verify_wrapper_integrity(PROPERTIES_PATH, fake_jar)
            self.assertTrue(
                any("gradle-wrapper.jar SHA-256 mismatch" in e for e in errors),
                f"Expected gradle-wrapper.jar SHA-256 mismatch error, got: {errors}",
            )

    def test_tampered_validate_distribution_url_is_detected_and_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            fake_props = Path(td) / "gradle-wrapper.properties"
            fake_props.write_text(
                f"distributionSha256Sum={CANONICAL_GRADLE_DISTRIBUTION_SHA256}\n"
                f"distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
                f"networkTimeout=10000\n"
                f"validateDistributionUrl=false\n",
                encoding="utf-8",
            )
            errors = verify_wrapper_integrity(fake_props, WRAPPER_JAR_PATH)
            self.assertTrue(
                any("validateDistributionUrl" in e for e in errors),
                f"Expected validateDistributionUrl error, got: {errors}",
            )

    def test_tampered_network_timeout_is_detected_and_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            fake_props = Path(td) / "gradle-wrapper.properties"
            fake_props.write_text(
                f"distributionSha256Sum={CANONICAL_GRADLE_DISTRIBUTION_SHA256}\n"
                f"distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n"
                f"networkTimeout=30000\n"
                f"validateDistributionUrl=true\n",
                encoding="utf-8",
            )
            errors = verify_wrapper_integrity(fake_props, WRAPPER_JAR_PATH)
            self.assertTrue(
                any("networkTimeout" in e for e in errors),
                f"Expected networkTimeout error, got: {errors}",
            )

    def test_missing_files_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            nonexistent_props = Path(td) / "does_not_exist.properties"
            nonexistent_jar = Path(td) / "does_not_exist.jar"
            errors_props = verify_wrapper_integrity(nonexistent_props, WRAPPER_JAR_PATH)
            self.assertTrue(any("Missing gradle-wrapper.properties" in e for e in errors_props))

            errors_jar = verify_wrapper_integrity(PROPERTIES_PATH, nonexistent_jar)
            self.assertTrue(any("Missing gradle-wrapper.jar" in e for e in errors_jar))


if __name__ == "__main__":
    unittest.main()
