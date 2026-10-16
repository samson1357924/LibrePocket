#!/usr/bin/env python3
"""Fake-keystore release rehearsal: pass/reject matrix without real keys.

Runs the REAL ``scripts/verify_release_apk.py`` gate against synthetic
candidate bytes plus fake ``apksigner``/``aapt`` shims. No production
keystore, password, Bitwarden state, or Gradle build is touched; the
payloads below stand in for signed bytes the way the stdlib harness
fixtures do. A green rehearsal proves the gate's fail-closed matrix, not
that any real APK or Android SDK toolchain passed.

Matrix (each fail-closed; failed cases must leave no staged output):
  pass-clean-signed ......... correct identity + signed -> PASS
  reject-wrong-cert ......... caller pin differs from payload cert
  reject-unsigned ........... payload reports unsigned/tampered
  reject-debug-cert ......... ``CN=Android Debug`` subject (pin matches)
  reject-wrong-package ...... package mismatch
  reject-wrong-version-code . versionCode mismatch
  reject-wrong-version-name . versionName mismatch
  reject-debuggable ......... payload reports debuggable
  reject-multiple-apks ...... signed + unsigned coexist -> rejected,
                              never filtered by filename
  reject-aab-only ........... bundle present, no APK -> no fallback

Blank-runner property: verification needs only the candidate bytes, the
caller-supplied pins, and explicit tool paths. The rehearsal pollutes
``PLAY_KEYSTORE_PASSWORD``/``BW_SESSION`` with garbage before the pass
case and still expects the same staged SHA-256, then re-verifies a copy
of the staged bytes in a fresh candidate directory and expects the
identical SHA-256.

Usage:
  python3 scripts/fake_keystore_rehearsal.py
Exit 0 only when every case behaves as expected.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Literal

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import verify_release_apk as verifier

if not hasattr(verifier, "build_parser"):
    raise ImportError("verify_release_apk.build_parser is required by the rehearsal")

FAKE_PACKAGE = "dev.librepocket.agent"
FAKE_VERSION_CODE = "7"
FAKE_VERSION_NAME = "1.2.3"
FAKE_CERT = "aa" * 32  # ephemeral rehearsal pin only; never a production key

FAKE_APKSIGNER = """#!{python}
import json
import pathlib
import sys

if len(sys.argv) != 6 or sys.argv[1:5] != ["verify", "--verbose", "--print-certs", "-Werr"]:
    sys.exit(91)
try:
    data = json.loads(pathlib.Path(sys.argv[5]).read_text(encoding="utf-8"))
except Exception:
    sys.exit(92)
if not data.get("signed"):
    print("synthetic unsigned or tampered APK", file=sys.stderr)
    sys.exit(1)
subject = data.get("subject", "CN=LibrePocket Synthetic Rehearsal")
cert = data.get("cert")
if not isinstance(cert, str):
    sys.exit(93)
print("Verifies")
print("Verified using v3 scheme (APK Signature Scheme v3): true")
print("Number of signers: 1")
print("Signer #1 certificate DN: " + subject)
print("Signer #1 certificate SHA-256 digest: " + cert)
print("Signer #1 certificate SHA-1 digest: " + "ab" * 20)
print("Signer #1 certificate MD5 digest: " + "ab" * 16)
"""

FAKE_AAPT = """#!{python}
import json
import pathlib
import sys

if len(sys.argv) != 4 or sys.argv[1:3] != ["dump", "badging"]:
    sys.exit(81)
try:
    data = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))
except Exception:
    sys.exit(82)
package = data.get("package")
version_code = data.get("version_code")
version_name = data.get("version_name")
if not isinstance(package, str) or not isinstance(version_code, str) or not isinstance(version_name, str):
    sys.exit(83)
print("package: name='{}' versionCode='{}' versionName='{}' platformBuildVersionName='17'".format(
    package, version_code, version_name
))
print("application: label='Synthetic' icon=''")
if data.get("debuggable"):
    print("application-debuggable")
"""


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def _payload(**overrides: object) -> bytes:
    base: dict[str, object] = {
        "package": FAKE_PACKAGE,
        "version_code": FAKE_VERSION_CODE,
        "version_name": FAKE_VERSION_NAME,
        "cert": FAKE_CERT,
        "signed": True,
    }
    base.update(overrides)
    return json.dumps(base, sort_keys=True).encode("utf-8")


def _run_case(
    *,
    name: str,
    files: dict[str, bytes],
    expect: Literal["pass", "reject"],
    apksigner: str,
    aapt: str,
    tmp: Path,
) -> tuple[bool, str]:
    candidate_dir = tmp / f"cand-{name}"
    candidate_dir.mkdir()
    for filename, content in files.items():
        (candidate_dir / filename).write_bytes(content)
    stage_dir = tmp / f"stage-{name}"
    namespace = verifier.build_parser().parse_args(
        [
            "--candidate-dir", str(candidate_dir),
            "--stage-dir", str(stage_dir),
            "--expected-package", FAKE_PACKAGE,
            "--expected-version-code", FAKE_VERSION_CODE,
            "--expected-version-name", FAKE_VERSION_NAME,
            "--expected-cert-sha256", FAKE_CERT,
            "--apksigner", apksigner,
            "--aapt", aapt,
        ]
    )
    try:
        result = verifier.verify_and_stage(namespace)
    except verifier.VerificationError as exc:
        leftover = stage_dir.exists()
        if expect == "pass":
            return False, f"REHEARSAL {name}: expected PASS but gate rejected: {exc}"
        if leftover:
            return False, f"REHEARSAL {name}: rejected but staged output remains"
        return True, f"REHEARSAL {name}: EXPECTED_REJECT -> REJECT ({exc})"
    except Exception as exc:  # unexpected harness error, never a pass
        return False, f"REHEARSAL {name}: harness error (not a gate verdict): {exc}"
    if expect != "pass":
        return False, f"REHEARSAL {name}: expected REJECT but gate passed"
    staged = stage_dir / "release.apk"
    try:
        staged_bytes = staged.read_bytes()
        digest = hashlib.sha256(staged_bytes).hexdigest()
    except OSError as exc:
        return False, f"REHEARSAL {name}: cannot rehash staged bytes: {exc}"
    if digest != result.get("sha256"):
        return False, f"REHEARSAL {name}: staged SHA-256 does not match manifest"
    # Blank-runner re-verification: the staged bytes alone, with the same
    # pins and no secrets, must verify to the identical SHA-256.
    recand = tmp / f"recand-{name}"
    recand.mkdir()
    (recand / "copy.apk").write_bytes(staged_bytes)
    restage = tmp / f"restage-{name}"
    re_namespace = verifier.build_parser().parse_args(
        [
            "--candidate-dir", str(recand),
            "--stage-dir", str(restage),
            "--expected-package", FAKE_PACKAGE,
            "--expected-version-code", FAKE_VERSION_CODE,
            "--expected-version-name", FAKE_VERSION_NAME,
            "--expected-cert-sha256", FAKE_CERT,
            "--apksigner", apksigner,
            "--aapt", aapt,
        ]
    )
    try:
        re_result = verifier.verify_and_stage(re_namespace)
    except verifier.VerificationError as exc:
        return False, f"REHEARSAL {name}: re-verification of staged bytes failed: {exc}"
    if re_result.get("sha256") != digest:
        return False, f"REHEARSAL {name}: re-verification SHA-256 changed"
    return True, f"REHEARSAL {name}: EXPECTED_PASS -> PASS (sha256={digest[:16]}..., re-verified same)"


def main(argv: list[str] | None = None) -> int:
    if argv is not None and len(argv) > 0:
        print(f"FAIL: rehearsal takes no arguments (got {argv!r})", file=sys.stderr)
        return 1
    cases: list[tuple[str, dict[str, bytes], Literal["pass", "reject"]]] = [
        ("pass-clean-signed", {"candidate.apk": _payload()}, "pass"),
        ("reject-wrong-cert", {"candidate.apk": _payload(cert="bb" * 32)}, "reject"),
        ("reject-unsigned", {"candidate.apk": _payload(signed=False)}, "reject"),
        (
            "reject-debug-cert",
            {
                "candidate.apk": _payload(
                    subject="CN=Android Debug, O=Android, C=US"
                )
            },
            "reject",
        ),
        (
            "reject-wrong-package",
            {"candidate.apk": _payload(package=FAKE_PACKAGE + ".wrong")},
            "reject",
        ),
        (
            "reject-wrong-version-code",
            {"candidate.apk": _payload(version_code="999")},
            "reject",
        ),
        (
            "reject-wrong-version-name",
            {"candidate.apk": _payload(version_name="9.9.9")},
            "reject",
        ),
        ("reject-debuggable", {"candidate.apk": _payload(debuggable=True)}, "reject"),
        (
            "reject-multiple-apks",
            {
                "app-signed.apk": _payload(),
                "unsigned.apk": _payload(signed=False),
            },
            "reject",
        ),
        ("reject-aab-only", {"app-release.aab": b"synthetic bundle"}, "reject"),
    ]
    # Prove no secret is needed: pollute credential env with garbage; the
    # verifier scrubs its tool environment and must still pass identically.
    # Saved/restored so importing this module and calling main() never leaks
    # into the caller's environment.
    saved_play = os.environ.get("PLAY_KEYSTORE_PASSWORD")
    saved_bw = os.environ.get("BW_SESSION")
    os.environ["PLAY_KEYSTORE_PASSWORD"] = "garbage-rehearsal-value"
    os.environ["BW_SESSION"] = "garbage-rehearsal-value"
    try:
        passed = 0
        with tempfile.TemporaryDirectory(prefix="librepocket-fake-keystore-rehearsal-") as raw:
            tmp = Path(raw)
            tools = tmp / "tools"
            tools.mkdir()
            apksigner = tools / "apksigner"
            aapt = tools / "aapt"
            _write_executable(apksigner, FAKE_APKSIGNER.replace("{python}", sys.executable))
            _write_executable(aapt, FAKE_AAPT.replace("{python}", sys.executable))
            for name, files, expect in cases:
                ok, line = _run_case(
                    name=name, files=files, expect=expect,
                    apksigner=str(apksigner), aapt=str(aapt), tmp=tmp,
                )
                print(line, flush=True)
                if ok:
                    passed += 1
    finally:
        if saved_play is None:
            os.environ.pop("PLAY_KEYSTORE_PASSWORD", None)
        else:
            os.environ["PLAY_KEYSTORE_PASSWORD"] = saved_play
        if saved_bw is None:
            os.environ.pop("BW_SESSION", None)
        else:
            os.environ["BW_SESSION"] = saved_bw
    print(f"REHEARSAL SUMMARY: {passed}/{len(cases)} cases behaved as expected", flush=True)
    if passed != len(cases):
        print("FAIL: fake-keystore rehearsal matrix did not match expectations", file=sys.stderr)
        return 1
    print("REHEARSAL RESULT: PASS (synthetic fixtures only; not real-APK/SDK evidence)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
