"""PyYAML structural regression harness for scripts/check_workflow_policy.py.

The baseline fixtures are copies of the real checked-in workflows; each test
mutates a copy and asserts that the structural checker reports a finding. This
pins supply-chain, permissions, release fail-closed shape, nightly trigger,
pull_request_target checkout and pr-check wiring invariants without trusting a
text grep.

Run with: python3 -m unittest scripts/tests/test_workflow_policy.py
"""

from __future__ import annotations

import io
import re
import shutil
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from scripts import check_workflow_policy
from scripts.check_workflow_policy import check_repo

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"
PINNED_SHA = "11d5960a326750d5838078e36cf38b85af677262"
PR_CHECK = (WORKFLOWS_DIR / "pr-check.yml").read_text(encoding="utf-8")
NIGHTLY = (
    WORKFLOWS_DIR / "nightly-instrumented.yml"
).read_text(encoding="utf-8")
RELEASE = (WORKFLOWS_DIR / "release.yml").read_text(encoding="utf-8")
CODEQL = (WORKFLOWS_DIR / "codeql.yml").read_text(encoding="utf-8")
SECURITY_AUDIT = (
    WORKFLOWS_DIR / "security-audit.yml"
).read_text(encoding="utf-8")
POCKETGUARD = (
    WORKFLOWS_DIR / "pocketguard.yml"
).read_text(encoding="utf-8")
WORKFLOW_TEXTS = {
    "pr-check.yml": PR_CHECK,
    "nightly-instrumented.yml": NIGHTLY,
    "release.yml": RELEASE,
    "codeql.yml": CODEQL,
    "security-audit.yml": SECURITY_AUDIT,
    "pocketguard.yml": POCKETGUARD,
}
CHECKOUT_STEP = (
    "      - name: Checkout repository\n"
    f"        uses: actions/checkout@{PINNED_SHA} # v4.4.0\n"
)


def remove_job(text: str, name: str) -> str:
    """Remove a top-level job block from workflow text."""
    lines = text.splitlines(keepends=True)
    start = None
    for index, line in enumerate(lines):
        if re.match(rf"^  {re.escape(name)}:\s*$", line):
            start = index
            break
    if start is None:
        raise AssertionError(f"job {name!r} not found")
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if re.match(r"^  [A-Za-z0-9_.-]+:\s*$", lines[index]):
            end = index
            break
    return "".join(lines[:start] + lines[end:])


class WorkflowPolicyHarness(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="librepocket-workflow-policy-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        scripts_dir = self.root / "scripts"
        scripts_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(
            REPO_ROOT / "scripts" / "requirements-docs.txt",
            scripts_dir / "requirements-docs.txt",
        )
        self._reset_workflows()

    def _reset_workflows(self) -> None:
        for name, text in WORKFLOW_TEXTS.items():
            self._write(name, text)

    def _write(self, name: str, text: str) -> Path:
        path = self.root / ".github" / "workflows" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def _write_action(self, name: str, text: str) -> Path:
        path = self.root / ".github" / "actions" / name / "action.yml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def _findings(self) -> list[check_workflow_policy.Finding]:
        # Semantic fixtures intentionally mutate workflow copies; the raw-byte
        # pin has its own tests below (synthetic repos cannot match the real
        # checked-in hashes after a mutation).
        return check_repo(self.root, enforce_hashes=False)

    def _assert_rejected(self, expected: str) -> None:
        findings = self._findings()
        self.assertTrue(findings, "expected a workflow policy finding")
        messages = [finding.render() for finding in findings]
        self.assertTrue(
            any(expected in message for message in messages),
            f"expected {expected!r} in findings: {messages}",
        )

    def _assert_passes(self) -> None:
        findings = self._findings()
        self.assertEqual(
            [], [finding.render() for finding in findings], "expected no findings"
        )

    # --- Baseline and uses/pinning -------------------------------------

    def test_valid_pinned_workflows_pass(self) -> None:
        self._assert_passes()

    def test_real_repository_passes(self) -> None:
        self.assertEqual([], check_repo(REPO_ROOT, enforce_hashes=True))

    def test_real_repository_passes_with_default_hash_enforcement(self) -> None:
        self.assertEqual([], check_repo(REPO_ROOT))

    def _copy_real_workflows(self) -> None:
        for name in WORKFLOW_TEXTS:
            destination = self.root / ".github" / "workflows" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(WORKFLOWS_DIR / name, destination)

    def test_workflow_sha256_pin_accepts_real_bytes(self) -> None:
        self._copy_real_workflows()
        self.assertEqual([], check_repo(self.root, enforce_hashes=True))

    def test_workflow_sha256_pin_rejects_one_byte_mutation(self) -> None:
        self._copy_real_workflows()
        target = self.root / ".github" / "workflows" / "pr-check.yml"
        original = target.read_bytes()
        target.write_bytes(original.replace(b"name: PR Check", b"name: PR ChecK", 1))
        findings = check_repo(self.root, enforce_hashes=True)
        messages = [finding.render() for finding in findings]
        self.assertTrue(
            any("workflow bytes do not match the reviewed SHA-256 pin" in m for m in messages),
            messages,
        )
        self.assertTrue(
            any(".github/workflows/pr-check.yml:" in m for m in messages), messages
        )
        # Semantic checks alone do not see this mutation; only the byte pin.
        self.assertEqual([], check_repo(self.root, enforce_hashes=False))

    def test_pr_check_extra_step_fails_hash_pin(self) -> None:
        self._copy_real_workflows()
        target = self.root / ".github" / "workflows" / "pr-check.yml"
        mutated = PR_CHECK.replace(
            "      - name: Check test write budgets (Issue #86, gate)\n",
            "      - run: 'true'\n"
            "      - name: Check test write budgets (Issue #86, gate)\n",
            1,
        )
        self.assertNotEqual(PR_CHECK, mutated)
        target.write_text(mutated, encoding="utf-8")
        self.assertEqual([], check_repo(self.root, enforce_hashes=False))
        findings = [f.render() for f in check_repo(self.root, enforce_hashes=True)]
        self.assertTrue(
            any("pr-check.yml:1: workflow bytes do not match" in f for f in findings),
            findings,
        )

    def test_nightly_extra_step_fails_hash_pin(self) -> None:
        self._copy_real_workflows()
        target = self.root / ".github" / "workflows" / "nightly-instrumented.yml"
        mutated = NIGHTLY.replace(
            "      - name: Run byte-cap full stress (Foss, NIGHTLY_STRESS=1)\n",
            "      - run: 'true'\n"
            "      - name: Run byte-cap full stress (Foss, NIGHTLY_STRESS=1)\n",
            1,
        )
        self.assertNotEqual(NIGHTLY, mutated)
        target.write_text(mutated, encoding="utf-8")
        self.assertEqual([], check_repo(self.root, enforce_hashes=False))
        findings = [f.render() for f in check_repo(self.root, enforce_hashes=True)]
        self.assertTrue(
            any(
                "nightly-instrumented.yml:1: workflow bytes do not match" in f
                for f in findings
            ),
            findings,
        )

    def test_pocketguard_review_send_extra_step_fails_hash_pin(self) -> None:
        self._copy_real_workflows()
        target = self.root / ".github" / "workflows" / "pocketguard.yml"
        mutated = POCKETGUARD.replace(
            "      - name: Run trusted single-turn review\n",
            "      - run: 'true'\n      - name: Run trusted single-turn review\n",
            1,
        )
        self.assertNotEqual(POCKETGUARD, mutated)
        target.write_text(mutated, encoding="utf-8")
        self.assertEqual([], check_repo(self.root, enforce_hashes=False))
        findings = [f.render() for f in check_repo(self.root, enforce_hashes=True)]
        self.assertTrue(
            any("pocketguard.yml:1: workflow bytes do not match" in f for f in findings),
            findings,
        )

    def test_checker_rejects_python_shadow_modules(self) -> None:
        (self.root / "yaml.py").write_text("", encoding="utf-8")
        (self.root / "scripts").mkdir(parents=True, exist_ok=True)
        (self.root / "scripts" / "unittest.py").write_text("", encoding="utf-8")
        (self.root / "sitecustomize.py").write_text("", encoding="utf-8")
        findings = [f.render() for f in self._findings()]
        for expected in ("yaml.py:", "scripts/unittest.py:", "sitecustomize.py:"):
            self.assertTrue(
                any(expected in finding for finding in findings), findings
            )

    def test_checker_ignores_shadow_modules_under_dot_git(self) -> None:
        (self.root / ".git").mkdir()
        (self.root / ".git" / "yaml.py").write_text("", encoding="utf-8")
        self._assert_passes()

    def test_checker_rejects_missing_requirements_docs(self) -> None:
        req = self.root / "scripts" / "requirements-docs.txt"
        if req.exists():
            req.unlink()
        findings = [f.render() for f in self._findings()]
        self.assertTrue(
            any(
                "scripts/requirements-docs.txt:1: missing dependency manifest" in f
                for f in findings
            ),
            findings,
        )

    def test_check_python_shadow_modules_reports_missing_requirements_docs(
        self,
    ) -> None:
        req = self.root / "scripts" / "requirements-docs.txt"
        if req.exists():
            req.unlink()
        findings = check_workflow_policy.check_python_shadow_modules(self.root)
        self.assertEqual(
            [
                check_workflow_policy.Finding(
                    "scripts/requirements-docs.txt",
                    1,
                    "missing dependency manifest",
                )
            ],
            findings,
        )

    def test_python_shadow_preflight_script(self) -> None:
        script = REPO_ROOT / "scripts" / "check_python_shadowing.sh"
        clean = self.root / "clean"
        clean.mkdir()
        (clean / "scripts").mkdir()
        (clean / "scripts" / "check_workflow_policy.py").write_text(
            "", encoding="utf-8"
        )
        shutil.copyfile(
            REPO_ROOT / "scripts" / "requirements-docs.txt",
            clean / "scripts" / "requirements-docs.txt",
        )
        (clean / ".git").mkdir()
        (clean / ".git" / "yaml.py").write_text("", encoding="utf-8")
        result = subprocess.run(
            ["bash", str(script), str(clean)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS", result.stdout)
        (clean / "yaml.py").write_text("", encoding="utf-8")
        (clean / "scripts" / "unittest.py").write_text("", encoding="utf-8")
        (clean / "sitecustomize.py").write_text("", encoding="utf-8")
        result = subprocess.run(
            ["bash", str(script), str(clean)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(1, result.returncode)
        for expected in ("yaml.py", "scripts/unittest.py", "sitecustomize.py"):
            self.assertIn(expected, result.stdout)
        self.assertIn("FAIL", result.stderr)

    def test_local_action_is_allowed(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  lint:\n")
        end = workflow.index("  policy-harness:\n")
        block = workflow[start:end].replace(
            CHECKOUT_STEP,
            "      - name: Checkout repository\n"
            "        uses: ./.github/actions/local-check\n",
            1,
        )
        self._write("pr-check.yml", workflow[:start] + block + workflow[end:])
        self._write_action(
            "local-check",
            "name: Local check\nruns:\n  using: composite\n  steps: []\n",
        )
        self._assert_passes()

    def test_pinned_composite_action_is_allowed(self) -> None:
        self._write_action(
            "local-check",
            f"""\
name: Local check
runs:
  using: composite
  steps:
    - uses: actions/checkout@{PINNED_SHA}
""",
        )
        self._assert_passes()

    def test_composite_action_unpinned_uses_is_rejected(self) -> None:
        self._write_action(
            "local-check",
            """\
name: Local check
runs:
  using: composite
  steps:
    - uses: actions/checkout@v4
""",
        )
        self._assert_rejected("unpinned or unsupported uses ref")

    def test_composite_action_contents_write_is_rejected(self) -> None:
        self._write_action(
            "local-check",
            f"""\
name: Local check
permissions:
  contents: write
runs:
  using: composite
  steps:
    - uses: actions/checkout@{PINNED_SHA}
""",
        )
        self._assert_rejected("permissions must not grant contents: write")

    def test_tag_ref_is_rejected(self) -> None:
        self._write("pr-check.yml", PR_CHECK.replace(f"@{PINNED_SHA}", "@v4", 1))
        self._assert_rejected("unpinned or unsupported uses ref")

    def test_short_sha_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(f"@{PINNED_SHA}", f"@{PINNED_SHA[:7]}", 1),
        )
        self._assert_rejected("unpinned or unsupported uses ref")

    def test_flow_style_uses_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                CHECKOUT_STEP + "        with:\n          fetch-depth: 0\n",
                "      - { name: Checkout repository, uses: actions/checkout@v4 }\n",
                1,
            ),
        )
        self._assert_rejected("unpinned or unsupported uses ref")

    def test_flow_style_uses_after_name_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                CHECKOUT_STEP + "        with:\n          fetch-depth: 0\n",
                "      - {name: test, uses: owner/repo@v1}\n",
                1,
            ),
        )
        self._assert_rejected("unpinned or unsupported uses ref")

    def test_quoted_uses_key_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                f"        uses: actions/checkout@{PINNED_SHA} # v4.4.0\n",
                f'        "uses": actions/checkout@{PINNED_SHA[:7]}\n',
                1,
            ),
        )
        self._assert_rejected("unpinned or unsupported uses ref")

    def test_next_line_uses_scalar_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                f"        uses: actions/checkout@{PINNED_SHA} # v4.4.0\n",
                "        uses:\n          actions/checkout@v4\n",
                1,
            ),
        )
        self._assert_rejected("unpinned or unsupported uses ref")

    def test_local_action_outside_actions_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                CHECKOUT_STEP,
                "      - name: Checkout repository\n"
                "        uses: ./.github/outside/action\n",
                1,
            ),
        )
        self._assert_rejected("must resolve under .github/actions/")

    def test_local_action_traversal_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                CHECKOUT_STEP,
                "      - name: Checkout repository\n"
                "        uses: ./.github/actions/../../outside\n",
                1,
            ),
        )
        self._assert_rejected("must resolve under .github/actions/")

    def test_uses_in_comment_is_ignored(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "jobs:\n", "jobs:\n  # uses: actions/checkout@v4\n", 1
            ),
        )
        self._assert_passes()

    def test_extra_workflow_file_is_rejected(self) -> None:
        self._write(
            "publish.yml",
            """\
name: Publish
on:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - run: echo publish
""",
        )
        self._assert_rejected("workflow file is not in the reviewed allowlist")

    # --- Permissions, defaults and publisher repurposing ----------------

    def test_top_level_write_all_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n  contents: read", "permissions: write-all", 1
            ),
        )
        self._assert_rejected("permissions must not grant write-all")

    def test_job_level_write_all_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "    permissions:\n      contents: read\n",
                "    permissions: write-all\n",
                1,
            ),
        )
        self._assert_rejected("permissions must not grant write-all")

    def test_job_level_contents_write_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "    permissions:\n      contents: read\n",
                "    permissions:\n      contents: write\n",
                1,
            ),
        )
        self._assert_rejected("permissions must not grant contents: write")

    def test_quoted_permissions_key_and_value_are_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n  contents: read",
                'permissions:\n  "contents": "write"',
                1,
            ),
        )
        self._assert_rejected("permissions must not grant contents: write")

    def test_flow_mapping_permissions_write_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n  contents: read",
                'permissions: { "contents": "write", "pull-requests": "write" }',
                1,
            ),
        )
        self._assert_rejected("permissions must not grant contents: write")

    def test_anchored_permissions_write_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n  contents: read",
                "permissions: &perms\n  contents: write",
                1,
            ),
        )
        self._assert_rejected("permissions must not grant contents: write")

    def test_pull_requests_write_is_allowed(self) -> None:
        self._write(
            "pocketguard.yml",
            POCKETGUARD.replace(
                "permissions:\n  contents: read",
                "permissions:\n  contents: read\n  pull-requests: write\n  issues: write",
                1,
            ),
        )
        self._assert_passes()

    def test_missing_top_level_permissions_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace("permissions:\n  contents: read\n", "", 1),
        )
        self._assert_rejected("workflow must declare explicit top-level permissions")

    def test_top_level_permissions_null_is_rejected(self) -> None:
        for null_value in ("permissions: null\n", "permissions:\n"):
            with self.subTest(value=null_value):
                self._reset_workflows()
                self._write(
                    "pr-check.yml",
                    PR_CHECK.replace(
                        "permissions:\n  contents: read\n",
                        null_value,
                        1,
                    ),
                )
                self._assert_rejected(
                    "workflow must declare explicit top-level permissions"
                )

    def test_forbidden_actions_write_permission_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n  contents: read",
                "permissions:\n  contents: read\n  actions: write",
                1,
            ),
        )
        self._assert_rejected("permissions must not grant actions: write")

    def test_security_events_write_forbidden_in_pr_check(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n  contents: read",
                "permissions:\n  contents: read\n  security-events: write",
                1,
            ),
        )
        self._assert_rejected("permissions must not grant security-events: write")

    def test_top_level_defaults_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n",
                "defaults:\n  run:\n    shell: bash\n\npermissions:\n",
                1,
            ),
        )
        self._assert_rejected("defaults")

    def test_job_defaults_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  changes:\n",
                "  changes:\n    defaults:\n      run:\n        shell: bash\n",
                1,
            ),
        )
        self._assert_rejected("defaults blocks are not allowed anywhere")

    def test_top_level_env_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n",
                "env:\n  UNSAFE: '1'\n\npermissions:\n",
                1,
            ),
        )
        self._assert_rejected("top-level env is not allowed")

    def test_allowlisted_workflow_cannot_become_publisher(self) -> None:
        self._write(
            "codeql.yml",
            """\
name: Repurposed CodeQL
on:
  push:
    tags:
      - 'v*'
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - run: gh release create v1
      - run: echo "${{ secrets.RELEASE_TOKEN }}"
""",
        )
        self._assert_rejected("only release.yml may declare a push.tags trigger")
        self._assert_rejected("non-release workflow must not invoke 'gh release'")
        self._assert_rejected(
            "non-release workflow must not reference secrets.RELEASE_TOKEN"
        )

    def test_non_release_publisher_catches_tokens_and_commands_in_lists(self) -> None:
        self._write(
            "codeql.yml",
            """\
name: Repurposed CodeQL
on:
  push:
    branches: [ main ]
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - run: echo ok
        args:
          - "gh release create v1"
          - "${{ secrets.RELEASE_TOKEN }}"
""",
        )
        self._assert_rejected("non-release workflow must not invoke 'gh release'")
        self._assert_rejected(
            "non-release workflow must not reference secrets.RELEASE_TOKEN"
        )

    def test_check_non_release_publisher_catches_tokens_and_commands_in_nested_list(
        self,
    ) -> None:
        data = {
            "name": "pr-check",
            "on": "pull_request",
            "jobs": {
                "step_args": {
                    "steps": [
                        {"args": ["gh release", "create"]},
                        {"args": ["echo", "${{ secrets.RELEASE_TOKEN }}"]},
                        {"nested": [["secrets.GH_PAT"]]},
                    ]
                }
            },
        }
        findings = check_workflow_policy.check_non_release_publisher(
            "pr-check.yml", data, is_release=False
        )
        messages = [f.message for f in findings]
        self.assertIn("non-release workflow must not invoke 'gh release'", messages)
        self.assertIn(
            "non-release workflow must not reference secrets.RELEASE_TOKEN or secrets.GH_PAT",
            messages,
        )

    def test_duplicate_yaml_key_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "permissions:\n  contents: read\n",
                "permissions:\n  contents: read\npermissions:\n  contents: read\n",
                1,
            ),
        )
        self._assert_rejected("duplicate key")

    def test_duplicate_key_normalized_string_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "on:\n",
                "'on':\n  push:\n    branches: [ main ]\non:\n",
                1,
            ),
        )
        self._assert_rejected("duplicate key")

    def test_duplicate_key_semantic_collision_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "on:\n",
                "on:\n  push:\n    branches: [ main ]\ntrue:\n  push:\n    branches: [ main ]\n",
                1,
            ),
        )
        self._assert_rejected("duplicate key")

    def test_yaml_merge_key_is_rejected(self) -> None:
        workflow_with_merge = (
            "common: &common\n"
            "  contents: read\n"
            "permissions:\n"
            "  <<: *common\n"
        ) + PR_CHECK.replace("permissions:\n  contents: read\n", "", 1)
        self._write("pr-check.yml", workflow_with_merge)
        self._assert_rejected("YAML merge keys (<<) are forbidden")

    def test_flow_style_on_is_parsed(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "on:\n"
                "  pull_request:\n"
                "    branches: [ main ]\n"
                "  push:\n"
                "    branches: [ main ]\n",
                "on: {pull_request: {branches: [main]}, push: {branches: [main]}}\n",
                1,
            ),
        )
        self._assert_passes()

    # --- pull_request_target ------------------------------------------

    def _pull_request_target(self) -> str:
        return POCKETGUARD

    def test_pull_request_target_head_checkout_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          ref: ${{ github.event.repository.default_branch }}\n",
            "          ref: ${{ github.event.pull_request.head.sha }}\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("pull_request_target checkout must not use a PR head")

    def test_pull_request_target_folded_head_ref_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          ref: ${{ github.event.repository.default_branch }}\n",
            "          ref: >-\n"
            "            ${{ github.event.pull_request.head.sha }}\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("pull_request_target checkout must not use a PR head")

    def test_pull_request_target_merge_commit_sha_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          ref: ${{ github.event.repository.default_branch }}\n",
            "          ref: ${{ github.event.pull_request.merge_commit_sha }}\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("pull_request_target")

    def test_pull_request_target_repository_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          ref: ${{ github.event.repository.default_branch }}\n",
            "          repository: forks/example\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("checkout input 'repository' is not allowed")

    def test_pull_request_target_case_variant_ref_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          ref: ${{ github.event.repository.default_branch }}\n",
            "          Ref: ${{ github.event.repository.default_branch }}\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("checkout input 'Ref' is not allowed")

    def test_pull_request_target_repository_case_variant_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          ref: ${{ github.event.repository.default_branch }}\n",
            "          Repository: forks/example\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("checkout input 'Repository' is not allowed")

    def test_pull_request_target_persist_credentials_true_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          persist-credentials: false\n",
            "          persist-credentials: true\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("persist-credentials must be boolean false")

    def test_pull_request_target_token_input_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          persist-credentials: false\n",
            "          token: ${{ secrets.GITHUB_TOKEN }}\n"
            "          persist-credentials: false\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("checkout input 'token' is not allowed")

    def test_pull_request_target_local_action_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "      - name: Checkout default branch\n"
            "        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 "
            "# v7.0.1\n",
            "      - name: Checkout repository\n"
            "        uses: ./.github/actions/local-check\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected(
            "pull_request_target workflow must not use local action"
        )

    def test_pull_request_target_anchor_is_rejected(self) -> None:
        workflow = self._pull_request_target().replace(
            "          fetch-depth: 0\n",
            "          fetch-depth: &depth 0\n",
            1,
        )
        self._write("pocketguard.yml", workflow)
        self._assert_rejected("must not use YAML anchors/aliases")

    def test_pull_request_target_default_branch_checkout_is_allowed(self) -> None:
        self._write("pocketguard.yml", self._pull_request_target())
        self._assert_passes()

    def test_pull_request_target_omitted_ref_is_allowed(self) -> None:
        self._write(
            "pocketguard.yml",
            self._pull_request_target().replace(
                "          ref: ${{ github.event.repository.default_branch }}\n",
                "",
                1,
            ),
        )
        self._assert_passes()

    # --- release.yml exact shape --------------------------------------

    def test_release_write_permission_is_rejected(self) -> None:
        self._write(
            "release.yml", RELEASE.replace("contents: read", 'contents: "write"', 1)
        )
        self._assert_rejected("release workflow must not grant any write permission")

    def test_release_quoted_permissions_key_is_still_scanned(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "permissions:\n  contents: read",
                '"permissions":\n  "contents": "write"',
                1,
            ),
        )
        self._assert_rejected("release workflow must not grant any write permission")

    def test_release_empty_permissions_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace("permissions:\n  contents: read", "permissions: {}", 1),
        )
        self._assert_rejected("must declare exactly one top-level permissions")

    def test_release_flow_mapping_write_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "permissions:\n  contents: read",
                'permissions: { "contents": "write" }',
                1,
            ),
        )
        self._assert_rejected("release workflow must not grant any write permission")

    def test_release_job_level_write_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "  release-disabled:\n",
                "  release-disabled:\n"
                "    permissions:\n      contents: write\n",
                1,
            ),
        )
        self._assert_rejected(
            "release workflow permissions must be exactly contents: read"
        )

    def test_release_non_contents_read_permission_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "  contents: read\n", "  contents: read\n  issues: read\n", 1
            ),
        )
        self._assert_rejected(
            "release workflow permissions must be exactly contents: read"
        )

    def test_release_second_publish_job_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "jobs:\n",
                "jobs:\n"
                "  publish:\n"
                "    runs-on: ubuntu-latest\n"
                "    steps:\n"
                "      - run: echo publish\n",
                1,
            ),
        )
        self._assert_rejected(
            "release workflow must have exactly one job named release-disabled"
        )
        self._assert_rejected("release workflow must not define a publish job")

    def test_release_second_step_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "    steps:\n",
                "    steps:\n      - name: extra\n        run: echo extra\n",
                1,
            ),
        )
        self._assert_rejected("release workflow must have exactly one step")

    def test_release_env_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "        shell: bash\n",
                "        shell: bash\n        env:\n          TOKEN: value\n",
                1,
            ),
        )
        self._assert_rejected("release workflow must not contain env:")

    def test_release_continue_on_error_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "        shell: bash\n",
                "        shell: bash\n        continue-on-error: true\n",
                1,
            ),
        )
        self._assert_rejected("release workflow must not contain continue-on-error")

    def test_release_shell_wrapper_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace("        shell: bash\n", "        shell: bash -c\n", 1),
        )
        self._assert_rejected("shell must be exactly 'bash'")

    def test_release_conditional_exit_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                '          echo "::error::Public release publishing is disabled '
                'while Issue #12 release gates remain open."\n          exit 1\n',
                '          echo "::error::Public release publishing is disabled '
                'while Issue #12 release gates remain open."\n'
                '          if [ "${{ github.event_name }}" = "push" ]; then\n'
                "            exit 1\n"
                "          fi\n",
                1,
            ),
        )
        self._assert_rejected(
            "run body must be exactly the checked-in fail-closed"
        )

    def test_release_shell_wrapper_body_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                '          echo "::error::Public release publishing is disabled '
                'while Issue #12 release gates remain open."\n          exit 1\n',
                '          bash -c \'echo "::error::disabled"; exit 1\'\n',
                1,
            ),
        )
        self._assert_rejected(
            "run body must be exactly the checked-in fail-closed"
        )

    def test_release_changed_body_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "Issue #12 release gates remain open.",
                "Issue #12 release gates are still open.",
                1,
            ),
        )
        self._assert_rejected(
            "run body must be exactly the checked-in fail-closed"
        )

    def test_release_step_if_false_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "      - name: Public release publishing is disabled\n",
                "      - name: Public release publishing is disabled\n"
                "        if: false\n",
                1,
            ),
        )
        self._assert_rejected("release workflow must not contain if:")

    def test_release_checkout_case_variant_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "    steps:\n",
                f"    steps:\n      - uses: Actions/Checkout@{PINNED_SHA}\n",
                1,
            ),
        )
        self._assert_rejected("release workflow must not use actions/checkout")

    def test_release_secret_case_variant_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                '          echo "::error::Public release publishing is disabled '
                'while Issue #12 release gates remain open."',
                '          echo "${{ SECRETS.TOKEN }}"',
                1,
            ),
        )
        self._assert_rejected("release workflow must not reference secrets")

    def test_release_github_token_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                '          echo "::error::Public release publishing is disabled '
                'while Issue #12 release gates remain open."',
                '          echo "${{ github.token }}"',
                1,
            ),
        )
        self._assert_rejected("release workflow must not reference github.token")

    def test_release_gh_release_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                '          echo "::error::Public release publishing is disabled '
                'while Issue #12 release gates remain open."',
                '          echo "gh release create"',
                1,
            ),
        )
        self._assert_rejected("release workflow must not invoke 'gh release'")

    def test_release_publish_action_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "    steps:\n",
                f"    steps:\n      - uses: evil/publish@{PINNED_SHA}\n",
                1,
            ),
        )
        self._assert_rejected("release workflow must not use a publish action")

    def test_release_missing_exit_is_rejected(self) -> None:
        self._write("release.yml", RELEASE.replace("          exit 1\n", "", 1))
        self._assert_rejected(
            "run body must be exactly the checked-in fail-closed"
        )

    def test_release_bracket_token_is_rejected(self) -> None:
        for token_expression in ("github['token']", 'github["token"]'):
            with self.subTest(expression=token_expression):
                self._reset_workflows()
                self._write(
                    "release.yml",
                    RELEASE.replace(
                        '          echo "::error::Public release publishing is '
                        'disabled while Issue #12 release gates remain open."',
                        f'          echo "${{{{ {token_expression} }}}}"',
                        1,
                    ),
                )
                self._assert_rejected("must not reference any token")

    def test_release_trigger_is_pinned(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "  workflow_dispatch:\n", "  workflow_dispatch:\n  pull_request:\n", 1
            ),
        )
        self._assert_rejected("release workflow 'on' must be exactly")

    def test_release_defaults_is_rejected(self) -> None:
        self._write(
            "release.yml",
            RELEASE.replace(
                "permissions:\n",
                "defaults:\n  run:\n    shell: bash\n\npermissions:\n",
                1,
            ),
        )
        self._assert_rejected("defaults")

    # --- nightly-instrumented.yml exact shape -------------------------

    def test_nightly_missing_synchronize_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml", NIGHTLY.replace("synchronize, ", "", 1)
        )
        self._assert_rejected("pull_request.types must be exactly")

    def test_nightly_unlabeled_type_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace("opened, reopened", "opened, reopened, unlabeled", 1),
        )
        self._assert_rejected("pull_request.types must be exactly")

    def test_nightly_missing_schedule_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace("  schedule:\n    - cron: '0 20 * * *'\n", "", 1),
        )
        self._assert_rejected("schedule must contain exactly one cron entry")

    def test_nightly_missing_workflow_dispatch_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace("  workflow_dispatch:\n", "", 1),
        )
        self._assert_rejected("missing workflow_dispatch")

    def test_nightly_extra_trigger_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace("  workflow_dispatch:\n", "  workflow_dispatch:\n  push:\n", 1),
        )
        self._assert_rejected("nightly 'on' trigger keys must be exactly")

    def test_nightly_missing_unit_stress_job_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace("  nightly-unit-stress:\n", "  renamed-nightly:\n", 1),
        )
        self._assert_rejected("missing the nightly-unit-stress job")

    def test_nightly_decoy_job_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "jobs:\n",
                "jobs:\n  schedule:\n    runs-on: ubuntu-latest\n    steps: []\n",
                1,
            ),
        )
        self._assert_rejected("nightly jobs must be exactly")
        self._assert_rejected("must not define a 'schedule' job")

    def test_nightly_missing_label_gate_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                " || contains(github.event.pull_request.labels.*.name, "
                "'run-instrumented')",
                "",
                1,
            ),
        )
        self._assert_rejected("instrumented-test if: must be exactly")

    def test_nightly_negated_gate_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "    if: github.event_name != 'pull_request' || contains(",
                '    if: "!(github.event_name != \'pull_request\' || contains(',
                1,
            ).replace(
                "'run-instrumented')\n    name:",
                "'run-instrumented'))\"\n    name:",
                1,
            ),
        )
        self._assert_rejected("instrumented-test if: must be exactly")

    def test_nightly_and_gate_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "github.event_name != 'pull_request' || contains",
                "github.event_name != 'pull_request' && contains",
                1,
            ),
        )
        self._assert_rejected("instrumented-test if: must be exactly")

    def test_nightly_stress_if_is_pinned(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "    if: github.event_name != 'pull_request'\n",
                "    if: always()\n",
                1,
            ),
        )
        self._assert_rejected("nightly-unit-stress if: must be exactly")

    def test_nightly_stress_command_is_pinned(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                '        run: ./gradlew :app:testFossDebugUnitTest --tests '
                '"dev.librepocket.agent.ui.chat.ChatSnapshotByteCapFullStressTest" '
                "--stacktrace\n",
                "        run: echo noop\n",
                1,
            ),
        )
        self._assert_rejected("nightly-unit-stress Gradle command must be exactly")

    def test_nightly_stress_env_is_pinned(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace('          NIGHTLY_STRESS: "1"\n', "          NIGHTLY_STRESS: '0'\n", 1),
        )
        self._assert_rejected("must set NIGHTLY_STRESS=1")

    def test_nightly_instrumented_command_is_pinned(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                '          script: ./gradlew :app:connected${{ matrix.flavor }}'
                "DebugAndroidTest --no-daemon "
                '-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m"\n',
                "          script: echo noop\n",
                1,
            ),
        )
        self._assert_rejected("instrumented-test connectedAndroidTest command")

    def test_nightly_api_matrix_is_pinned(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace("api-level: [33, 34]", "api-level: [33]", 1),
        )
        self._assert_rejected("matrix api-level must be exactly")

    def test_nightly_flavor_matrix_is_pinned(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "flavor: [Play, Foss, Github]", "flavor: [Play, Foss]", 1
            ),
        )
        self._assert_rejected("matrix flavor must be exactly")

    def test_nightly_cancel_false_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}\n",
                "  cancel-in-progress: false\n",
                1,
            ),
        )
        self._assert_rejected("cancel-in-progress must be exactly")

    def test_nightly_missing_cancel_in_progress_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}\n",
                "",
                1,
            ),
        )
        self._assert_rejected("concurrency")

    def test_nightly_flow_style_on_is_parsed(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "on:\n"
                "  schedule:\n"
                "    - cron: '0 20 * * *'\n"
                "  workflow_dispatch:\n"
                "  pull_request:\n"
                "    types: [ labeled, synchronize, opened, reopened ]\n",
                "on: {schedule: [{cron: '0 20 * * *'}], "
                "workflow_dispatch: null, "
                "pull_request: {types: [labeled, synchronize, opened, reopened]}}\n",
                1,
            ),
        )
        self._assert_passes()

    # --- pr-check.yml protected jobs --------------------------------

    def test_pr_gate_needs_must_include_workflow_guard(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "needs: [changes, unit-tests, lint, build-and-policy-gate, "
                "policy-harness, workflow-guard, docs-guard, bot-tests]",
                "needs: [changes]",
                1,
            ),
        )
        self._assert_rejected("pr-gate.needs must contain exactly")

    def test_pr_gate_shadow_needs_token_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "policy-harness, workflow-guard, docs-guard",
                "policy-harness, workflow-guard, workflow-guard-shadow, docs-guard",
                1,
            ),
        )
        self._assert_rejected("pr-gate.needs must contain exactly")

    def test_missing_workflow_guard_job_is_rejected(self) -> None:
        self._write("pr-check.yml", remove_job(PR_CHECK, "workflow-guard"))
        self._assert_rejected("missing workflow-guard job")

    def test_pr_check_unexpected_job_is_rejected(self) -> None:
        extra_job = (
            "  extra-job:\n"
            "    name: Extra Job\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - run: echo 'unexpected'\n"
        )
        self._write("pr-check.yml", PR_CHECK + "\n" + extra_job)
        self._assert_rejected("unexpected jobs")

    def test_workflow_guard_needs_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  workflow-guard:\n    name: Workflow Policy Guard\n",
                "  workflow-guard:\n    name: Workflow Policy Guard\n"
                "    needs: changes\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard job keys must be exactly")

    def test_workflow_guard_if_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  workflow-guard:\n    name: Workflow Policy Guard\n",
                "  workflow-guard:\n    name: Workflow Policy Guard\n"
                "    if: always()\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard job keys must be exactly")

    def test_workflow_guard_extra_run_step_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Run workflow policy harness\n"
                '        run: PYTHONPATH="$PWD" PYTHONDONTWRITEBYTECODE=1 '
                "python3 -P -m unittest scripts/tests/test_workflow_policy.py\n",
                "      - name: Run workflow policy harness\n"
                '        run: PYTHONPATH="$PWD" PYTHONDONTWRITEBYTECODE=1 '
                "python3 -P -m unittest scripts/tests/test_workflow_policy.py\n"
                "      - run: echo shadow\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard must have exactly five steps")

    def test_workflow_guard_missing_run_step_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Check workflow policy\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                "",
                1,
            ),
        )
        self._assert_rejected("workflow-guard must have exactly five steps")

    def test_workflow_guard_missing_shadow_preflight_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Reject Python import shadowing\n"
                "        run: |\n"
                "          chmod +x scripts/check_python_shadowing.sh\n"
                "          scripts/check_python_shadowing.sh\n",
                "",
                1,
            ),
        )
        self._assert_rejected("workflow-guard must have exactly five steps")

    def test_workflow_guard_modified_shadow_preflight_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "          scripts/check_python_shadowing.sh\n",
                "          scripts/check_python_shadowing.sh || true\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard shadow preflight")

    def test_policy_harness_missing_shadow_preflight_is_rejected(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  policy-harness:\n")
        end = workflow.index("  build-and-policy-gate:\n")
        block = workflow[start:end].replace(
            "      - name: Reject Python import shadowing\n"
            "        run: |\n"
            "          chmod +x scripts/check_python_shadowing.sh\n"
            "          scripts/check_python_shadowing.sh\n",
            "",
            1,
        )
        self.assertNotEqual(workflow[start:end], block)
        self._write("pr-check.yml", workflow[:start] + block + workflow[end:])
        self._assert_rejected("policy-harness must contain exactly one")

    def test_docs_guard_missing_shadow_preflight_is_rejected(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  docs-guard:\n")
        end = workflow.index("  bot-tests:\n")
        block = workflow[start:end].replace(
            "      - name: Reject Python import shadowing\n"
            "        run: |\n"
            "          chmod +x scripts/check_python_shadowing.sh\n"
            "          scripts/check_python_shadowing.sh\n",
            "",
            1,
        )
        self.assertNotEqual(workflow[start:end], block)
        self._write("pr-check.yml", workflow[:start] + block + workflow[end:])
        self._assert_rejected("docs-guard must contain exactly one")

    def test_policy_harness_modified_shadow_preflight_is_rejected(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  policy-harness:\n")
        end = workflow.index("  build-and-policy-gate:\n")
        block = workflow[start:end].replace(
            "          scripts/check_python_shadowing.sh\n",
            "          scripts/check_python_shadowing.sh --skip\n",
            1,
        )
        self.assertNotEqual(workflow[start:end], block)
        self._write("pr-check.yml", workflow[:start] + block + workflow[end:])
        self._assert_rejected("policy-harness shadow preflight run must be exactly")

    def test_workflow_guard_missing_install_step_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Install pinned PyYAML\n"
                "        env:\n"
                "          # Ubuntu 24.04 marks the system Python as externally "
                "managed; --user\n"
                "          # installs remain user-scoped while allowing pip to "
                "proceed.\n"
                '          PIP_BREAK_SYSTEM_PACKAGES: "1"\n'
                "        run: |\n"
                "          set -euo pipefail\n"
                "          python3 -m pip install --user --disable-pip-version-check "
                "--ignore-installed --no-deps --only-binary=:all: "
                "--require-hashes -r scripts/requirements-docs.txt\n"
                "          python3 -P -c 'import yaml; assert yaml.__version__ == "
                '"6.0.3", yaml.__version__\'\n',
                "",
                1,
            ),
        )
        self._assert_rejected("workflow-guard must have exactly five steps")

    def test_workflow_guard_unpinned_install_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "          python3 -m pip install --user --disable-pip-version-check "
                "--ignore-installed --no-deps --only-binary=:all: "
                "--require-hashes -r scripts/requirements-docs.txt\n",
                "          python3 -m pip install PyYAML\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard PyYAML install command")

    def test_workflow_guard_run_replaced_with_true_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Check workflow policy\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                "      - name: Check workflow policy\n        run: 'true'\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard run commands must be exactly")

    def test_workflow_guard_step_if_false_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Check workflow policy\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                "      - name: Check workflow policy\n"
                "        if: false\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard steps must not contain if:")

    def test_workflow_guard_continue_on_error_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Check workflow policy\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                "      - name: Check workflow policy\n"
                "        continue-on-error: true\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                1,
            ),
        )
        self._assert_rejected(
            "workflow-guard steps must not contain continue-on-error"
        )

    def test_workflow_guard_step_env_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Check workflow policy\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                "      - name: Check workflow policy\n"
                "        env:\n          SHADOW: '1'\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard checker step keys must be exactly")

    def test_workflow_guard_step_shell_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Check workflow policy\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                "      - name: Check workflow policy\n"
                "        shell: bash -c\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                1,
            ),
        )
        self._assert_rejected("workflow-guard steps must not contain shell:")

    def test_workflow_guard_working_directory_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Check workflow policy\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                "      - name: Check workflow policy\n"
                "        working-directory: scripts\n"
                "        run: python3 -P scripts/check_workflow_policy.py\n",
                1,
            ),
        )
        self._assert_rejected(
            "workflow-guard steps must not contain working-directory"
        )

    def test_pr_gate_reversed_skip_condition_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                'if [ "$WORKFLOW" != "success" ]; then',
                'if [ "$WORKFLOW" = "success" ]; then',
                1,
            ),
        )
        self._assert_rejected("pr-gate run body must match the pinned gate script")

    def test_pr_gate_accepts_skipped_workflow_guard_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                '          if [ "$WORKFLOW" != "success" ]; then\n'
                '            echo "pr-gate: workflow-guard did not succeed '
                '(result=$WORKFLOW)"\n'
                "            fail=1\n"
                "          else\n"
                '            echo "pr-gate: workflow-guard passed"\n'
                "          fi\n",
                '          if [ "$WORKFLOW" != "success" ] && '
                '[ "$WORKFLOW" != "skipped" ]; then\n'
                "            fail=1\n"
                "          fi\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate run body must match the pinned gate script")

    def test_pr_gate_early_exit_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "          fail=0\n",
                "          fail=0\n          exit 0\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate run body must match the pinned gate script")

    def test_pr_gate_extra_step_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                '          echo "pr-gate: PASSED"\n',
                '          echo "pr-gate: PASSED"\n'
                "      - name: Shadow\n        run: echo shadow\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate must have exactly one Evaluate gate step")

    def test_pr_gate_step_if_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Evaluate gate\n",
                "      - name: Evaluate gate\n        if: success()\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate step must not contain if")

    def test_pr_gate_step_continue_on_error_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Evaluate gate\n",
                "      - name: Evaluate gate\n        continue-on-error: true\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate step must not contain continue-on-error")

    def test_pr_gate_step_shell_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Evaluate gate\n",
                "      - name: Evaluate gate\n        shell: bash\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate step must not contain shell")

    def test_pr_gate_step_working_directory_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Evaluate gate\n",
                "      - name: Evaluate gate\n        working-directory: .\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate step must not contain working-directory")

    def test_pr_gate_workflow_env_is_pinned(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "WORKFLOW: ${{ needs.workflow-guard.result }}",
                "WORKFLOW: ${{ needs.workflow-guard.outcome }}",
                1,
            ),
        )
        self._assert_rejected("pr-gate WORKFLOW must be exactly")

    def test_pr_gate_workflow_paths_env_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "          BOT_PATHS: ${{ needs.changes.outputs.bot }}\n",
                "          BOT_PATHS: ${{ needs.changes.outputs.bot }}\n"
                "          WORKFLOW_PATHS: ${{ needs.changes.outputs.workflow }}\n",
                1,
            ),
        )
        self._assert_rejected("pr-gate env must be exactly")

    def test_changes_workflow_output_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      bot: ${{ steps.filter.outputs.bot }}\n",
                "      bot: ${{ steps.filter.outputs.bot }}\n"
                "      workflow: ${{ steps.filter.outputs.workflow }}\n",
                1,
            ),
        )
        self._assert_rejected("changes.outputs must be exactly")

    def test_changes_outputs_are_exact(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      bot: ${{ steps.filter.outputs.bot }}\n",
                "",
                1,
            ),
        )
        self._assert_rejected("changes.outputs must be exactly")

    def test_policy_filter_requires_actions_path(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace("              - '.github/actions/**'\n", "", 1),
        )
        self._assert_rejected("policy filter is missing: .github/actions/**")

    def test_policy_filter_requires_workflows_path(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace("              - '.github/workflows/**'\n", "", 1),
        )
        self._assert_rejected("policy filter is missing: .github/workflows/**")

    def test_policy_filter_rejects_extra_path(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "            policy:\n",
                "            policy:\n              - 'docs/**'\n",
                1,
            ),
        )
        self._assert_rejected("policy filter has unexpected paths")

    def test_policy_filter_rejects_negation(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "              - '.github/actions/**'\n",
                "              - '.github/actions/**'\n"
                "              - '!scripts/**'\n",
                1,
            ),
        )
        self._assert_rejected("policy filter must not contain negated patterns")

    def test_policy_filter_rejects_extra_key(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "            bot:\n",
                "            workflow:\n              - 'docs/**'\n            bot:\n",
                1,
            ),
        )
        self._assert_rejected("paths-filter keys must be exactly")

    def test_policy_filter_rejects_duplicate_path(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "              - '.github/actions/**'\n",
                "              - '.github/actions/**'\n"
                "              - '.github/actions/**'\n",
                1,
            ),
        )
        self._assert_rejected("policy filter must not contain duplicate paths")

    def test_policy_filter_rejects_whitespace(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "              - '.github/actions/**'\n",
                "              - ' .github/actions/**'\n",
                1,
            ),
        )
        self._assert_rejected("no leading or trailing whitespace")

    def test_policy_filter_rejects_duplicate_key(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "            policy:\n",
                "            policy:\n              - 'docs/**'\n"
                "            policy:\n",
                1,
            ),
        )
        self._assert_rejected("duplicate key")

    def test_dorny_step_rejects_extra_with_keys(self) -> None:
        for extra in (
            "          predicate-quantifier: 'some'\n",
            "          Filters: 'code: []'\n",
            "          token: ${{ secrets.GITHUB_TOKEN }}\n",
        ):
            with self.subTest(extra=extra.strip()):
                self._reset_workflows()
                self._write(
                    "pr-check.yml",
                    PR_CHECK.replace(
                        "        with:\n          filters: |\n",
                        "        with:\n" + extra + "          filters: |\n",
                        1,
                    ),
                )
                self._assert_rejected("dorny/paths-filter with keys must be exactly")

    def test_dorny_step_rejects_extra_step_keys(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "        id: filter\n",
                "        id: filter\n        continue-on-error: true\n",
                1,
            ),
        )
        self._assert_rejected("dorny/paths-filter step keys must be exactly")

    def test_policy_harness_command_includes_workflow_test(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  policy-harness:\n")
        end = workflow.index("  build-and-policy-gate:\n")
        block = workflow[start:end]
        mutated = block.replace(" scripts/tests/test_workflow_policy.py", "", 1)
        self.assertNotEqual(block, mutated)
        self._write(
            "pr-check.yml", workflow[:start] + mutated + workflow[end:]
        )
        self._assert_rejected("policy-harness core command must appear exactly once")

    def test_policy_harness_command_includes_verify_jdk_floor_test(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  policy-harness:\n")
        end = workflow.index("  build-and-policy-gate:\n")
        block = workflow[start:end]
        mutated = block.replace(" scripts/tests/test_verify_jdk_floor.py", "", 1)
        self.assertNotEqual(block, mutated)
        self._write(
            "pr-check.yml", workflow[:start] + mutated + workflow[end:]
        )
        self._assert_rejected("policy-harness core command must appear exactly once")

    def test_policy_harness_pyyaml_install_is_pinned(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  policy-harness:\n")
        end = workflow.index("  build-and-policy-gate:\n")
        block = workflow[start:end]
        mutated = block.replace(
            "          python3 -m pip install --user --disable-pip-version-check "
            "--ignore-installed --no-deps --only-binary=:all: "
            "--require-hashes -r scripts/requirements-docs.txt\n",
            "          python3 -m pip install yaml\n",
            1,
        )
        self.assertNotEqual(block, mutated)
        self._write(
            "pr-check.yml", workflow[:start] + mutated + workflow[end:]
        )
        self._assert_rejected("policy-harness must install the pinned PyYAML exactly once")

    # --- required workflow/job pins --------------------------------

    def test_required_workflow_files_must_exist(self) -> None:
        for name in WORKFLOW_TEXTS:
            with self.subTest(name=name):
                self._reset_workflows()
                (self.root / ".github" / "workflows" / name).unlink()
                self._assert_rejected("required workflow is missing")

    def test_required_security_audit_jobs_are_pinned(self) -> None:
        for job in ("gitleaks", "dependency-review"):
            with self.subTest(job=job):
                self._reset_workflows()
                self._write(
                    "security-audit.yml", remove_job(SECURITY_AUDIT, job)
                )
                self._assert_rejected("security-audit jobs must be exactly")

    def test_required_codeql_job_is_pinned(self) -> None:
        self._write("codeql.yml", remove_job(CODEQL, "codeql"))
        self._assert_rejected("codeql.yml must define jobs")

    def test_required_pocketguard_jobs_are_pinned(self) -> None:
        for job in tuple(check_workflow_policy.EXPECTED_POCKETGUARD_JOBS):
            with self.subTest(job=job):
                self._reset_workflows()
                self._write("pocketguard.yml", remove_job(POCKETGUARD, job))
                self._assert_rejected("pocketguard jobs must be exactly")

    def test_required_gitleaks_action_cannot_be_noop(self) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "        uses: gitleaks/gitleaks-action@"
                "e0c47f4fc60447087851a4f009ee13d80093635a # v3.0.0\n",
                "        run: 'true'\n",
                1,
            ),
        )
        self._assert_rejected("security-audit gitleaks must use exactly one pinned")

    def test_required_codeql_init_cannot_be_noop(self) -> None:
        self._write(
            "codeql.yml",
            CODEQL.replace(
                "        uses: github/codeql-action/init@"
                "9f759ee644a3e7c15c1390abf49868036c00067b # v3.38.3\n",
                "        run: 'true'\n",
                1,
            ),
        )
        self._assert_rejected("codeql init must use exactly one pinned")

    def test_security_audit_trigger_is_pinned(self) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "  schedule:\n    - cron: '0 12 * * 1'\n",
                "  schedule:\n    - cron: '0 13 * * 1'\n",
                1,
            ),
        )
        self._assert_rejected("security-audit.yml 'on' must be exactly")

    def test_security_audit_gitleaks_job_if_is_rejected(self) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "  gitleaks:\n    name:",
                "  gitleaks:\n    if: false\n    name:",
                1,
            ),
        )
        self._assert_rejected("security-audit gitleaks job must not contain if")

    def test_security_audit_gitleaks_step_conditions_are_rejected(self) -> None:
        for line, expected in (
            ("        if: false\n", "steps must not contain if:"),
            (
                "        continue-on-error: true\n",
                "steps must not contain continue-on-error",
            ),
        ):
            with self.subTest(line=line.strip()):
                self._reset_workflows()
                self._write(
                    "security-audit.yml",
                    SECURITY_AUDIT.replace(
                        "      - name: Run Gitleaks\n",
                        "      - name: Run Gitleaks\n" + line,
                        1,
                    ),
                )
                self._assert_rejected(f"security-audit gitleaks {expected}")

    def test_security_audit_gitleaks_env_is_pinned(self) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "          GITLEAKS_CONFIG: .gitleaks.toml\n",
                "          GITLEAKS_CONFIG: attacker.toml\n",
                1,
            ),
        )
        self._assert_rejected("security-audit gitleaks action step env must be exactly")

    def test_security_audit_gitleaks_extra_env_is_rejected(self) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "          GITLEAKS_CONFIG: .gitleaks.toml\n",
                "          GITLEAKS_CONFIG: .gitleaks.toml\n"
                "          SHADOW: '1'\n",
                1,
            ),
        )
        self._assert_rejected(
            "security-audit gitleaks action step env must be exactly"
        )

    def test_security_audit_dependency_review_if_is_pinned(self) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "    if: github.event_name == 'pull_request'\n",
                "    if: github.event_name != 'pull_request'\n",
                1,
            ),
        )
        self._assert_rejected(
            "security-audit dependency-review if must be exactly"
        )

    def test_security_audit_dependency_review_continue_on_error_is_rejected(
        self,
    ) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "  dependency-review:\n",
                "  dependency-review:\n    continue-on-error: true\n",
                1,
            ),
        )
        self._assert_rejected(
            "security-audit dependency-review job must not contain continue-on-error"
        )

    def test_check_security_audit_rejects_continue_on_error_direct(self) -> None:
        raw, findings = check_workflow_policy.load_yaml(
            "security-audit.yml", SECURITY_AUDIT
        )
        self.assertEqual([], findings)
        raw["jobs"]["dependency-review"]["continue-on-error"] = True
        audit_findings = check_workflow_policy.check_security_audit(
            ".github/workflows/security-audit.yml", raw
        )
        messages = [f.message for f in audit_findings]
        self.assertIn(
            "security-audit dependency-review job must not contain continue-on-error",
            messages,
        )

    def test_security_audit_dependency_review_extra_with_is_rejected(self) -> None:
        self._write(
            "security-audit.yml",
            SECURITY_AUDIT.replace(
                "        uses: actions/dependency-review-action@"
                "a1d282b3dbe92b7fb3f84860b295be1522f74154 # v5.0.0\n",
                "        uses: actions/dependency-review-action@"
                "a1d282b3dbe92b7fb3f84860b295be1522f74154 # v5.0.0\n"
                "        with:\n          fail-on-severity: low\n",
                1,
            ),
        )
        self._assert_rejected(
            "security-audit dependency-review action step keys must be exactly"
        )

    def test_codeql_trigger_and_paths_ignore_are_pinned(self) -> None:
        cases = (
            (
                "  schedule:\n    - cron: '0 12 * * 1'\n",
                "  schedule:\n    - cron: '0 13 * * 1'\n",
                "codeql.yml 'on' must be exactly",
            ),
            (
                "      - 'docs/**'\n",
                "      - '**'\n",
                "codeql push paths-ignore contains an unreviewed pattern",
            ),
            (
                "      - 'NOTICE*'\n",
                "      - '**/*.kts'\n",
                "codeql push paths-ignore contains an unreviewed pattern",
            ),
        )
        for old, new, expected in cases:
            with self.subTest(old=old.strip()):
                self._reset_workflows()
                self._write("codeql.yml", CODEQL.replace(old, new, 1))
                self._assert_rejected(expected)

    def test_codeql_job_conditions_are_rejected(self) -> None:
        for line, expected in (
            ("    if: false\n", "codeql job must not contain if:"),
            (
                "    continue-on-error: true\n",
                "codeql job must not contain continue-on-error",
            ),
        ):
            with self.subTest(line=line.strip()):
                self._reset_workflows()
                self._write(
                    "codeql.yml",
                    CODEQL.replace(
                        "  codeql:\n    name:",
                        "  codeql:\n" + line + "    name:",
                        1,
                    ),
                )
                self._assert_rejected(expected)

    def test_codeql_action_step_conditions_are_rejected(self) -> None:
        for step_name, label in (
            ("Initialize CodeQL", "init"),
            ("Perform CodeQL Analysis", "analyze"),
        ):
            for line, expected in (
                ("        if: false\n", "codeql steps must not contain if:"),
                (
                    "        continue-on-error: true\n",
                    "codeql steps must not contain continue-on-error",
                ),
            ):
                with self.subTest(step=step_name, line=line.strip()):
                    self._reset_workflows()
                    self._write(
                        "codeql.yml",
                        CODEQL.replace(
                            f"      - name: {step_name}\n",
                            f"      - name: {step_name}\n" + line,
                            1,
                        ),
                    )
                    self._assert_rejected(expected)

    def test_pocketguard_trigger_is_pinned(self) -> None:
        for old, new in (
            ("types: [opened, reopened, synchronize]", "types: [opened]"),
            ("issues:\n    types: [opened, edited, reopened]", "issues:\n    types: [opened]"),
            ("issue_comment:\n    types: [created, edited]", "issue_comment:\n    types: [created]"),
        ):
            with self.subTest(old=old):
                self._reset_workflows()
                self._write("pocketguard.yml", POCKETGUARD.replace(old, new, 1))
                self._assert_rejected("pocketguard.yml 'on' must be exactly")

    def test_pocketguard_job_continue_on_error_is_rejected(self) -> None:
        for job in tuple(check_workflow_policy.EXPECTED_POCKETGUARD_JOBS):
            with self.subTest(job=job):
                self._reset_workflows()
                self._write(
                    "pocketguard.yml",
                    POCKETGUARD.replace(
                        f"  {job}:\n",
                        f"  {job}:\n    continue-on-error: true\n",
                        1,
                    ),
                )
                self._assert_rejected(
                    f"pocketguard {job} job must not contain continue-on-error"
                )

    def test_pocketguard_job_if_is_pinned(self) -> None:
        cases = (
            (
                "prepare-tag",
                "  prepare-tag:\n    name:",
                "  prepare-tag:\n    if: false\n    name:",
            ),
            (
                "claim-slot",
                "needs.prepare-tag.outputs.review_gate == 'auto')",
                "needs.prepare-tag.outputs.review_gate != 'none')",
            ),
            (
                "review-send",
                "review_gate == 'auto' &&\n"
                "      needs.claim-slot.outputs.claimed == 'true')",
                "review_gate == 'auto' &&\n"
                "      needs.claim-slot.outputs.claimed != 'false')",
            ),
            (
                "publish",
                "always() && needs.prepare-tag.result == 'success'",
                "always() || needs.prepare-tag.result == 'success'",
            ),
        )
        for job, old, new in cases:
            with self.subTest(job=job):
                self._reset_workflows()
                self._write("pocketguard.yml", POCKETGUARD.replace(old, new, 1))
                self._assert_rejected(
                    f"pocketguard {job} job-level if must be exactly"
                    if job != "prepare-tag"
                    else "pocketguard prepare-tag job must not have a job-level if"
                )

    def test_required_job_steps_replaced_with_true_is_rejected(self) -> None:
        cases = (
            (
                "security-audit.yml",
                SECURITY_AUDIT,
                "  gitleaks:\n",
                "  dependency-review:\n",
                "security-audit gitleaks must use exactly one pinned",
            ),
            (
                "codeql.yml",
                CODEQL,
                "  codeql:\n",
                None,
                "codeql init must use exactly one pinned",
            ),
        )
        for name, text, start_marker, end_marker, expected in cases:
            with self.subTest(name=name):
                self._reset_workflows()
                start = text.index(start_marker)
                end = text.index(end_marker) if end_marker else len(text)
                block = text[start:end]
                steps_at = block.index("    steps:\n")
                block = block[:steps_at] + "    steps:\n      - run: 'true'\n"
                self._write(name, text[:start] + block + text[end:])
                self._assert_rejected(expected)

    def test_pocketguard_publish_requires_steps(self) -> None:
        workflow = POCKETGUARD
        start = workflow.index("  publish:\n")
        self._write(
            "pocketguard.yml",
            workflow[:start]
            + "  publish:\n    runs-on: ubuntu-latest\n    steps: []\n",
        )
        self._assert_rejected("pocketguard publish job must have at least one step")

    def test_pocketguard_review_send_requires_bot_command(self) -> None:
        workflow = POCKETGUARD
        start = workflow.index("  review-send:\n")
        end = workflow.index("  publish:\n")
        block = workflow[start:end]
        for mode in ("review", "tag"):
            block = block.replace(
                "        run: ./node_modules/.bin/ts-node "
                f"src/github_runner.ts --mode={mode}\n",
                "        run: 'true'\n",
            )
        self._write("pocketguard.yml", workflow[:start] + block + workflow[end:])
        self._assert_rejected("review-send must run the bot")

    def test_non_release_local_action_cannot_run_gh_release(self) -> None:
        self._write_action(
            "local-check",
            f"""\
name: Local check
runs:
  using: composite
  steps:
    - run: gh release create v1
    - uses: actions/checkout@{PINNED_SHA}
""",
        )
        self._assert_rejected("non-release workflow must not invoke 'gh release'")

    def test_non_release_workflow_rejects_release_action(self) -> None:
        workflow = PR_CHECK
        start = workflow.index("  lint:\n")
        end = workflow.index("  policy-harness:\n")
        block = workflow[start:end].replace(
            CHECKOUT_STEP,
            "      - name: Checkout repository\n"
            f"        uses: softprops/action-gh-release@{PINNED_SHA}\n",
            1,
        )
        self._write("pr-check.yml", workflow[:start] + block + workflow[end:])
        self._assert_rejected("names release or publish")

    def test_non_release_workflow_rejects_gh_api_releases(self) -> None:
        cases = (
            (
                "gh api",
                "        run: gh api -X POST repos/example/repo/releases "
                "-f tag_name=v1\n"
                "        env:\n"
                "          GH_TOKEN: ${{ secrets.GH_PAT }}\n",
                "must not call the GitHub releases API",
            ),
            (
                "api.github.com",
                "        run: curl -fsS -X POST "
                "https://api.github.com/repos/example/repo/releases "
                "-d tag_name=v1\n",
                "must not call the GitHub releases API",
            ),
            (
                "releases/assets",
                "        run: curl -fsS "
                "https://api.github.com/repos/example/repo/releases/assets/1\n",
                "must not call the GitHub releases API",
            ),
        )
        for label, step, expected in cases:
            with self.subTest(label=label):
                self._reset_workflows()
                workflow = PR_CHECK
                start = workflow.index("  lint:\n")
                end = workflow.index("  policy-harness:\n")
                block = workflow[start:end].replace(
                    CHECKOUT_STEP,
                    CHECKOUT_STEP + step,
                    1,
                )
                self._write(
                    "pr-check.yml", workflow[:start] + block + workflow[end:]
                )
                self._assert_rejected(expected)
                if label == "gh api":
                    self._assert_rejected(
                        "must not reference secrets.RELEASE_TOKEN or "
                        "secrets.GH_PAT"
                    )

    def test_non_release_local_action_rejects_gh_api_release(self) -> None:
        self._write_action(
            "local-check",
            f"""\
name: Local check
runs:
  using: composite
  steps:
    - run: gh api -X POST repos/example/repo/releases -f tag_name=v1
      env:
        GH_TOKEN: ${{{{ secrets['GH_PAT'] }}}}
    - uses: actions/checkout@{PINNED_SHA}
""",
        )
        self._assert_rejected("must not call the GitHub releases API")
        self._assert_rejected(
            "must not reference secrets.RELEASE_TOKEN or secrets.GH_PAT"
        )

    # --- required PR job pins --------------------------------------

    def test_pr_job_core_run_cannot_be_replaced_with_true(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "        run: ./gradlew :app:lintPlayDebug :app:lintFossDebug "
                ":app:lintGithubDebug\n",
                "        run: 'true'\n",
                1,
            ),
        )
        self._assert_rejected("lint core command must appear exactly once")

    def test_pr_job_step_if_false_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Run Android Lint (Play, Foss & GitHub Flavors)\n",
                "      - name: Run Android Lint (Play, Foss & GitHub Flavors)\n"
                "        if: false\n",
                1,
            ),
        )
        self._assert_rejected("lint core step keys must be exactly")

    def test_pr_job_step_continue_on_error_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "      - name: Run Android Lint (Play, Foss & GitHub Flavors)\n",
                "      - name: Run Android Lint (Play, Foss & GitHub Flavors)\n"
                "        continue-on-error: true\n",
                1,
            ),
        )
        self._assert_rejected("lint core step keys must be exactly")

    def test_pr_job_if_false_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "    if: needs.changes.outputs.code == 'true'\n",
                "    if: false\n",
                1,
            ),
        )
        self._assert_rejected("unit-tests job-level if must be exactly")

    def test_pr_job_env_is_pinned(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  lint:\n    name: Android Lint Check\n",
                "  lint:\n    name: Android Lint Check\n    env:\n      SHADOW: '1'\n",
                1,
            ),
        )
        self._assert_rejected("lint must not have a job-level env")

    def test_pr_job_continue_on_error_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  lint:\n    name: Android Lint Check\n",
                "  lint:\n    name: Android Lint Check\n"
                "    continue-on-error: true\n",
                1,
            ),
        )
        self._assert_rejected("lint must not contain job-level continue-on-error")

    def test_changes_job_extra_key_is_rejected(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  changes:\n    name: Detect changed paths\n",
                "  changes:\n    name: Detect changed paths\n    if: true\n",
                1,
            ),
        )
        self._assert_rejected("changes job keys must be exactly")

    # --- pr-check trigger pin --------------------------------------

    def test_trigger_pin_rejects_pull_request_target(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace("  pull_request:\n", "  pull_request_target:\n", 1),
        )
        self._assert_rejected("pr-check.yml 'on' must be exactly")

    def test_trigger_pin_rejects_extra_trigger(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  push:\n    branches: [ main ]\n",
                "  push:\n    branches: [ main ]\n  workflow_dispatch:\n",
                1,
            ),
        )
        self._assert_rejected("pr-check.yml 'on' must be exactly")

    def test_trigger_pin_rejects_paths(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  pull_request:\n    branches: [ main ]\n",
                "  pull_request:\n    branches: [ main ]\n"
                "    paths-ignore:\n      - 'docs/**'\n",
                1,
            ),
        )
        self._assert_rejected("pr-check.yml 'on' must be exactly")

    def test_trigger_pin_rejects_types(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(
                "  pull_request:\n    branches: [ main ]\n",
                "  pull_request:\n    branches: [ main ]\n"
                "    types: [opened]\n",
                1,
            ),
        )
        self._assert_rejected("pr-check.yml 'on' must be exactly")

    def test_trigger_pin_rejects_branch_mismatch(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace("branches: [ main ]", "branches: [ develop ]", 1),
        )
        self._assert_rejected("pr-check.yml 'on' must be exactly")

    # --- nightly hardening -----------------------------------------

    def test_nightly_job_continue_on_error_is_rejected(self) -> None:
        for job in ("nightly-unit-stress", "instrumented-test"):
            with self.subTest(job=job):
                self._reset_workflows()
                self._write(
                    "nightly-instrumented.yml",
                    NIGHTLY.replace(
                        f"  {job}:\n",
                        f"  {job}:\n    continue-on-error: true\n",
                        1,
                    ),
                )
                self._assert_rejected("must not contain continue-on-error")

    def test_nightly_matrix_exclude_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "        arch: [x86_64]\n",
                "        arch: [x86_64]\n"
                "        exclude:\n          - flavor: Play\n",
                1,
            ),
        )
        self._assert_rejected("instrumented-test matrix keys must be exactly")

    def test_nightly_instrumented_fail_fast_true_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace("      fail-fast: false\n", "      fail-fast: true\n", 1),
        )
        self._assert_rejected("strategy.fail-fast must be false")

    def test_nightly_noop_stress_step_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "        run: ./gradlew :app:testFossDebugUnitTest --tests "
                '"dev.librepocket.agent.ui.chat.ChatSnapshotByteCapFullStressTest" '
                "--stacktrace\n",
                "        run: 'true'\n",
                1,
            ),
        )
        self._assert_rejected("Gradle command must be exactly")

    def test_nightly_noop_instrumented_step_is_rejected(self) -> None:
        self._write(
            "nightly-instrumented.yml",
            NIGHTLY.replace(
                "          script: ./gradlew :app:connected${{ matrix.flavor }}"
                "DebugAndroidTest --no-daemon "
                '-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m"\n',
                "          script: 'true'\n",
                1,
            ),
        )
        self._assert_rejected("connectedAndroidTest command must be exactly")

    def test_real_workflow_copies_reject_mutations(self) -> None:
        mutations = (
            (
                "pr-check.yml",
                remove_job(PR_CHECK, "workflow-guard"),
                "missing workflow-guard job",
            ),
            (
                "release.yml",
                RELEASE.replace("          exit 1\n", "", 1),
                "run body must be exactly the checked-in fail-closed",
            ),
            (
                "nightly-instrumented.yml",
                NIGHTLY.replace(
                    "  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}\n",
                    "  cancel-in-progress: false\n",
                    1,
                ),
                "cancel-in-progress must be exactly",
            ),
        )
        for name, text, expected in mutations:
            with self.subTest(name=name):
                self._reset_workflows()
                self._write(name, text)
                self._assert_rejected(expected)

    def test_walk_strings_yields_strings_inside_lists_and_nested_structures(
        self,
    ) -> None:
        tree = {
            "dict_key": [
                "list_item_1",
                {
                    "inner_key": [
                        "nested_list_item",
                        100,
                        None,
                        True,
                        "another_nested_item",
                    ]
                },
                ["sublist_item_1", ["deep_sublist_item"]],
            ],
            "top_string": "top_value",
        }
        strings_with_keys = set(
            check_workflow_policy.walk_strings(tree, include_keys=True)
        )
        self.assertEqual(
            {
                "dict_key",
                "list_item_1",
                "inner_key",
                "nested_list_item",
                "another_nested_item",
                "sublist_item_1",
                "deep_sublist_item",
                "top_string",
                "top_value",
            },
            strings_with_keys,
        )

        strings_without_keys = set(
            check_workflow_policy.walk_strings(tree, include_keys=False)
        )
        self.assertEqual(
            {
                "list_item_1",
                "nested_list_item",
                "another_nested_item",
                "sublist_item_1",
                "deep_sublist_item",
                "top_value",
            },
            strings_without_keys,
        )

        list_root = ["elem1", ["elem2", {"nested": "elem3"}]]
        self.assertEqual(
            {"elem1", "elem2", "nested", "elem3"},
            set(check_workflow_policy.walk_strings(list_root, include_keys=True)),
        )
        self.assertEqual(
            {"elem1", "elem2", "elem3"},
            set(check_workflow_policy.walk_strings(list_root, include_keys=False)),
        )

        # Cycles in lists and dicts
        cycle_list: list[Any] = []
        cycle_list.append(cycle_list)
        self.assertEqual([], list(check_workflow_policy.walk_strings(cycle_list)))

        cycle_dict: dict[str, Any] = {}
        cycle_dict["self"] = cycle_dict
        self.assertEqual(["self"], list(check_workflow_policy.walk_strings(cycle_dict)))

    def test_cli_returns_nonzero_and_prints_location(self) -> None:
        self._write(
            "pr-check.yml",
            PR_CHECK.replace(f"@{PINNED_SHA}", "@main", 1),
        )
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            status = check_workflow_policy.main([str(self.root)])
        self.assertEqual(1, status)
        self.assertIn(".github/workflows/pr-check.yml:", stderr.getvalue())
        self.assertIn("FAIL:", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
