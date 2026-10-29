#!/usr/bin/env python3
"""Regression tests for wrapper bytes and the CI guard wiring (stdlib only)."""

import fnmatch
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/verify_gradle_wrapper.py"
FILES = ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
         "gradle/wrapper/gradle-wrapper.properties")
WORKFLOWS = {
    "pr-check.yml": {"unit-tests", "lint", "build-and-policy-gate"},
    "codeql.yml": {"codeql"},
    "nightly-instrumented.yml": {"nightly-unit-stress", "instrumented-test"},
}
# Review every workflow change, including new files and indirect invocations.
# This is a change-detection convention, not a shell/action call-graph analysis.
WORKFLOW_SNAPSHOTS = {
    "codeql.yml": "1f37e9b4fe81cd4b5024302a4b06d16ff51b585ef764ee3b539f9c054398866a",
    "nightly-instrumented.yml": "ca9c43a47b238b63a5ddf18e75e9f1436dcf9c00b0a4345e9cab0ba36db7360b",
    "pocketguard.yml": "9e6d291fe012a4932126c5dba96d0a27e1c68299bbbb1847ea8ab4d69db87df6",
    "pr-check.yml": "6f7cf483d18396193071b63c0eeec6eeaa8d5e52b3da15b746dae587f613fa77",
    "release.yml": "acf8d39620362fce1190f6ffb6d75d088ae19c4d9de636be64af0dcbc0cd7d1f",
    "security-audit.yml": "f836a7d3ed4daf6ecec33baf577375bca6504513d54c89e2d2cb6da4d5d83bfa",
}


def workflow_snapshots(directory):
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in directory.iterdir() if p.suffix.lower() in (".yml", ".yaml")}


def workflow_jobs(text):
    return dict(re.findall(r"(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)", text))


def valid_guard(job):
    # Include unnamed run/uses steps as well as named steps. Snapshots require
    # human review for indirect invocations; this check covers literal calls.
    steps = re.split(r"(?m)^      - ", job)[1:]
    guards = [i for i, step in enumerate(steps) if step.splitlines()[0] == "name: Verify Gradle wrapper integrity"]
    setups = [i for i, step in enumerate(steps) if re.search(
        r"(?m)^(?:uses:|        uses:) gradle/actions/setup-gradle@", step)]
    invocations = [i for i, step in enumerate(steps) if "./gradlew" in step]
    if len(guards) != 1 or not setups or not invocations:
        return False
    if any(guards[0] >= i for i in setups + invocations):
        return False
    # An explicit status function (e.g. always() or failure()) can run after a
    # failed guard. Reject all explicit conditions on protected steps rather
    # than attempting to interpret the Actions expression language.
    if any(re.search(r"(?m)^(?:if:|        if:)", steps[i]) for i in setups + invocations):
        return False
    step = steps[guards[0]]
    return bool(re.search(r"(?m)^        run: python3 scripts/verify_gradle_wrapper\.py$", step)) and not re.search(
        r"(?m)^        (?:if|continue-on-error|working-directory|shell):", step
    )


def canonical_properties(content):
    """Independently require the complete reviewed Java properties format."""
    return content == (
        b"distributionBase=GRADLE_USER_HOME\n"
        b"distributionPath=wrapper/dists\n"
        b"distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607\n"
        b"distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.2-bin.zip\n"
        b"networkTimeout=10000\n"
        b"retries=0\n"
        b"retryBackOffMs=500\n"
        b"validateDistributionUrl=true\n"
        b"zipStoreBase=GRADLE_USER_HOME\n"
        b"zipStorePath=wrapper/dists\n"
    )


class GradleWrapperChecksumTest(unittest.TestCase):
    def run_checker(self, root):
        return subprocess.run([sys.executable, str(CHECKER), "--root", str(root)],
                              capture_output=True, text=True, check=False)

    def copy_wrapper(self, root):
        for name in FILES:
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)

    def test_reviewed_repo_passes(self):
        result = self.run_checker(ROOT)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_reviewed_properties_and_official_generated_bytes(self):
        expected = {
            "gradlew": "a5a5c199ba02189ae8c46a334223371a20599d9c298ef65e7540ede4a3f72d59",
            "gradlew.bat": "f17917f8dbe61182b149273ee78090fec1197442c3f38276af907bfa5190db16",
            "gradle/wrapper/gradle-wrapper.jar": "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98",
            "gradle/wrapper/gradle-wrapper.properties": "62779c1edca4389b50577428c5cab724a2ddd3ea4cac0685ce48bab709cce6c6",
        }
        for name, digest in expected.items():
            with self.subTest(name=name):
                self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), digest)

    def test_canonical_properties_independent_of_digest(self):
        original = (ROOT / FILES[-1]).read_bytes()
        self.assertTrue(canonical_properties(original))
        for old, new in (
            (b"gradle-9.8.2-bin.zip", b"gradle-9.8.2-all.zip"),
            (b"21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607", b"0" * 64),
            (b"validateDistributionUrl=true", b"validateDistributionUrl=false"),
            (b"networkTimeout=10000", b"networkTimeout=30000"),
        ):
            with self.subTest(old=old):
                mutated = original.replace(old, new)
                self.assertNotEqual(mutated, original)
                # Even if both digest pins were updated to the new bytes, the
                # independent canonical values still reject the mutation.
                self.assertFalse(canonical_properties(mutated))

    def test_each_file_missing_or_modified_fails_cli(self):
        for name in FILES:
            for mutation in ("missing", "tampered"):
                with self.subTest(name=name, mutation=mutation), tempfile.TemporaryDirectory() as td:
                    root = Path(td)
                    self.copy_wrapper(root)
                    path = root / name
                    if mutation == "missing":
                        path.unlink()
                    else:
                        path.write_bytes(path.read_bytes() + b"\n# tampered\n")
                    result = self.run_checker(root)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(name, result.stderr)

    def test_symlink_and_nonregular_pinned_files_fail(self):
        for name in FILES:
            for mutation in ("symlink", "directory", "fifo"):
                with self.subTest(name=name, mutation=mutation), tempfile.TemporaryDirectory() as td:
                    root = Path(td)
                    self.copy_wrapper(root)
                    path = root / name
                    if mutation == "symlink":
                        target = root / "identical-copy"
                        shutil.copyfile(path, target)
                        path.unlink()
                        path.symlink_to(target)
                    else:
                        path.unlink()
                        if mutation == "directory":
                            path.mkdir()
                        else:
                            os.mkfifo(path)
                    result = self.run_checker(root)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(name, result.stderr)

    def test_symlinked_wrapper_parents_and_root_fail(self):
        for name in ("gradle", "gradle/wrapper"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as td:
                root = Path(td) / "checkout"
                root.mkdir()
                self.copy_wrapper(root)
                path = root / name
                target = path.with_name(path.name + "-real")
                path.rename(target)
                path.symlink_to(target, target_is_directory=True)
                self.assertNotEqual(self.run_checker(root).returncode, 0)
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "checkout"
            root.mkdir()
            self.copy_wrapper(root)
            link = Path(td) / "link"
            link.symlink_to(root, target_is_directory=True)
            self.assertNotEqual(self.run_checker(link).returncode, 0)

    def test_launcher_symlink_to_identical_bytes_beside_unverified_wrapper_fails(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "checkout"
            root.mkdir()
            self.copy_wrapper(root)
            alternate = Path(td) / "alternate"
            alternate.mkdir()
            shutil.copyfile(root / "gradlew", alternate / "gradlew")
            (alternate / "gradle/wrapper").mkdir(parents=True)
            (alternate / "gradle/wrapper/gradle-wrapper.jar").write_bytes(b"unverified jar")
            (alternate / "gradle/wrapper/gradle-wrapper.properties").write_bytes(b"unverified properties")
            (root / "gradlew").unlink()
            (root / "gradlew").symlink_to(alternate / "gradlew")
            result = self.run_checker(root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("gradlew", result.stderr)

    def test_all_workflows_require_review_on_change(self):
        directory = ROOT / ".github/workflows"
        self.assertEqual(workflow_snapshots(directory), WORKFLOW_SNAPSHOTS,
                         "Review workflow changes, including indirect Gradle calls, and update snapshots")
        with tempfile.TemporaryDirectory() as td:
            copy = Path(td)
            for path in directory.iterdir():
                if path.suffix.lower() in (".yml", ".yaml"):
                    shutil.copyfile(path, copy / path.name)
            self.assertEqual(workflow_snapshots(copy), WORKFLOW_SNAPSHOTS)
            new = copy / "new.yaml"
            new.write_text("jobs:\n  build:\n    steps:\n      - run: bash scripts/build_release.sh\n")
            self.assertNotEqual(workflow_snapshots(copy), WORKFLOW_SNAPSHOTS)
            new.unlink()
            path = copy / "release.yml"
            path.write_text(path.read_text() + "\n# indirect invocation\n# bash scripts/build_release.sh\n")
            self.assertNotEqual(workflow_snapshots(copy), WORKFLOW_SNAPSHOTS)
            path.unlink()
            self.assertNotEqual(workflow_snapshots(copy), WORKFLOW_SNAPSHOTS)

    def test_java_properties_ambiguities_rejected(self):
        original = (ROOT / FILES[-1]).read_bytes()
        variants = (
            original + b"distributionUrl=https\\://evil.example/gradle.zip\n",
            original + b"distribution\\u0055rl=https\\://evil.example/gradle.zip\n",
            original + b"unknownKey=value\n",
            original.replace(b"distributionUrl=https", b"distributionUrl=\\\n  https"),
            original.replace(b"distributionSha256Sum=", b"distributionSha256Sum=0"),
            original.replace(b"validateDistributionUrl=true", b"validateDistributionUrl=false"),
            original.replace(b"networkTimeout=10000", b"networkTimeout=30000"),
        )
        for content in variants:
            with self.subTest(content=content), tempfile.TemporaryDirectory() as td:
                self.assertNotEqual(content, original, "mutation must change the properties")
                # This must fail even if both whole-file digest pins are updated.
                self.assertFalse(canonical_properties(content))
                root = Path(td)
                self.copy_wrapper(root)
                (root / FILES[-1]).write_bytes(content)
                self.assertNotEqual(self.run_checker(root).returncode, 0)

    def test_workflow_job_guards_and_negative_controls(self):
        for filename, expected in WORKFLOWS.items():
            jobs = workflow_jobs((ROOT / ".github/workflows" / filename).read_text())
            executing = {name for name, job in jobs.items() if "./gradlew" in job}
            self.assertEqual(executing, expected)
            for name in expected:
                with self.subTest(workflow=filename, job=name):
                    job = jobs[name]
                    self.assertTrue(valid_guard(job))
                    command = "run: python3 scripts/verify_gradle_wrapper.py"
                    for replacement in ("run: true", command + "\n        continue-on-error: true", command + "\n        if: false"):
                        mutated = job.replace(command, replacement)
                        self.assertNotEqual(mutated, job)
                        self.assertFalse(valid_guard(mutated))
                    guard = "      - name: Verify Gradle wrapper integrity\n        run: python3 scripts/verify_gradle_wrapper.py\n\n"
                    self.assertIn(guard, job)
                    mutated = job.replace(guard, "") + "\n" + guard
                    self.assertNotEqual(mutated, job)
                    self.assertFalse(valid_guard(mutated))
                    setup = "      - name: Setup Gradle\n"
                    self.assertIn(setup, job)
                    # Move the guard just after setup instead, but before calls.
                    setup_step = re.search(r"(?ms)^      - name: Setup Gradle\n.*?(?=^      - |\Z)", job).group()
                    mutated = job.replace(guard, "").replace(setup_step, setup_step + guard)
                    self.assertNotEqual(mutated, job)
                    self.assertFalse(valid_guard(mutated))
                    steps = re.split(r"(?m)^      - ", job)[1:]
                    protected = [step for step in steps if "./gradlew" in step or "gradle/actions/setup-gradle@" in step]
                    for step in protected:
                        for condition in ("always()", "failure()", "${{ always() }}"):
                            replacement = step.replace("\n", "\n        if: " + condition + "\n", 1)
                            mutated = job.replace("      - " + step, "      - " + replacement)
                            self.assertNotEqual(mutated, job)
                            self.assertFalse(valid_guard(mutated))
                    extra = "      - run: ./gradlew help\n"
                    mutated = job.replace(guard, extra + guard)
                    self.assertNotEqual(mutated, job)
                    self.assertFalse(valid_guard(mutated))
                    # A later second invocation must be checked too, even if
                    # the original invocation is properly guarded.
                    mutated = job + "\n      - run: ./gradlew help\n        if: failure()\n"
                    self.assertNotEqual(mutated, job)
                    self.assertFalse(valid_guard(mutated))

    def test_launcher_only_filters_and_gate(self):
        text = (ROOT / ".github/workflows/pr-check.yml").read_text()
        for key in ("code", "policy"):
            section = re.search(rf"(?ms)^            {key}:\n(.*?)(?=^            \w+:)", text).group(1)
            patterns = re.findall(r"(?m)^\s+- '([^']+)'$", section)
            for name in FILES[:2]:
                self.assertTrue(any(fnmatch.fnmatchcase(name, p) for p in patterns), (key, name))
                self.assertFalse(any(fnmatch.fnmatchcase(name, p) for p in patterns if p != name), (key, name))
            if key == "policy":
                self.assertIn(".github/workflows/**", patterns)
                for workflow in WORKFLOW_SNAPSHOTS:
                    self.assertTrue(any(fnmatch.fnmatchcase(".github/workflows/" + workflow, p) for p in patterns))
                self.assertTrue(any(fnmatch.fnmatchcase(".github/workflows/new.yaml", p) for p in patterns))
        jobs = workflow_jobs(text)
        self.assertIn("needs.changes.outputs.code == 'true'", jobs["unit-tests"])
        self.assertIn("needs.changes.outputs.code == 'true'", jobs["lint"])
        self.assertIn("needs.changes.outputs.policy == 'true'", jobs["policy-harness"])
        self.assertIn("needs.changes.outputs.policy == 'true'", jobs["build-and-policy-gate"])
        gate = jobs["pr-gate"]
        script = gate.split("        run: |\n", 1)[1]
        script = "\n".join(line[10:] for line in script.splitlines())
        env = dict(os.environ, CHANGES_RESULT="success", DOCS="success", CODE="true",
                   POLICY="true", UNIT="success", LINT="success", BUILD="success",
                   HARNESS="success", WORKFLOW="success", BOT="skipped", BOT_PATHS="false")
        self.assertEqual(subprocess.run(["bash", "-c", script], env=env, capture_output=True).returncode, 0)
        for job in ("UNIT", "LINT", "BUILD", "HARNESS", "WORKFLOW"):
            for status in ("skipped", "failure"):
                with self.subTest(job=job, status=status):
                    self.assertNotEqual(subprocess.run(["bash", "-c", script], env=dict(env, **{job: status}), capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
