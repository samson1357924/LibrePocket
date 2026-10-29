#!/usr/bin/env python3
"""Fail-closed structural policy checks for checked-in GitHub Actions workflows.

The checker parses YAML with PyYAML (pinned, hash-checked in
``scripts/requirements-docs.txt``) instead of matching workflow text. Parsing
makes flow style, quoted keys, anchors/aliases and next-line scalars follow the
same rules as GitHub's YAML loader; a custom loader additionally rejects
duplicate mapping keys, which PyYAML would otherwise silently last-win.

Scope is deliberately small: catch workflow-only bypasses that a Gradle/policy
path filter cannot see, not re-implement the Actions expression engine.

Checks:
  * every step/job ``uses`` is either a local ``./...`` action resolved under
    ``.github/actions/`` or a remote ``owner/repo[/path]@<40 lowercase hex>``
    ref (tags, branches, short SHAs and docker refs are rejected); referenced
    composite actions are parsed recursively with the same rules and the
    non-release publisher checks;
  * only the reviewed workflow filename allowlist may exist (all six are
    required, and codeql/security-audit/pocketguard keep their exact job sets
    and pinned scanner actions), and no top-level ``defaults``/``env`` or any
    ``defaults`` block is allowed;
  * every ``permissions`` mapping rejects ``write-all`` and ``contents: write``;
  * non-release workflows/actions may not become publishers (``push.tags``,
    ``gh release``, ``secrets.RELEASE_TOKEN``, release/publish action refs);
  * ``pull_request_target`` workflows cannot check out PR head refs or use
    local actions, may not use anchors/aliases, and only accept lower-case
    ``ref``/``persist-credentials``/``fetch-depth`` checkout inputs with the
    exact default-branch/false/0 values;
  * ``release.yml`` stays disabled with an exact one-job/one-step shape,
    ``contents: read`` only, pinned triggers, and the exact checked-in
    fail-closed echo + ``exit 1`` body (no token references);
  * ``nightly-instrumented.yml`` pins its trigger map, schedule, concurrency,
    exact job/matrix shapes, job-level failure semantics, stress env/command
    and instrumented command;
  * ``pr-check.yml`` pins its trigger, the ``changes`` outputs/filter/dorny
    step shape, the unconditional ``workflow-guard`` job, the exact
    ``pr-gate`` env and hash-pinned gate script, and the core run commands and
    job-level if/env of the required PR jobs.

The six reviewed workflow files are also pinned to the SHA-256 of their raw
checked-in bytes. This is a review regression guard for extra-step/no-op edits
that the semantic checks do not model, not a tamper-proof seal: a PR that edits
this checker and a workflow together can update the pin. Updating
``EXPECTED_WORKFLOW_SHA256`` is therefore an explicit, reviewed change in the
same PR as the workflow edit. The real boundary remains branch protection plus
CODEOWNERS/review rules.

A repo-wide preflight rejects Python import-shadowing modules (``yaml``,
``unittest``, ``sitecustomize``/``usercustomize`` and their ``.py`` forms)
outside ``.git``; CI also runs ``scripts/check_python_shadowing.sh`` before any
Python import.

Usage: python3 -P scripts/check_workflow_policy.py [repo-root]
Exit status: 0 = PASS, 1 = one or more findings (printed as file:line).
"""

from __future__ import annotations

import hashlib
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

try:
    import yaml
except ImportError as import_error:  # pragma: no cover - exercised in CI failure
    yaml = None  # type: ignore[assignment]
    YAML_IMPORT_ERROR: ImportError | None = import_error
else:
    YAML_IMPORT_ERROR = None

ALLOWED_WORKFLOW_FILES = frozenset(
    {
        "codeql.yml",
        "nightly-instrumented.yml",
        "pocketguard.yml",
        "pr-check.yml",
        "release.yml",
        "security-audit.yml",
    }
)

# SHA-256 of each reviewed workflow's raw checked-in bytes. Update these in the
# same reviewed PR as any intentional workflow edit; never in a separate
# "make CI green" change. This pin is a regression guard, not tamper-proof.
EXPECTED_WORKFLOW_SHA256 = {
    "codeql.yml": "1f37e9b4fe81cd4b5024302a4b06d16ff51b585ef764ee3b539f9c054398866a",
    "nightly-instrumented.yml": (
        "ca9c43a47b238b63a5ddf18e75e9f1436dcf9c00b0a4345e9cab0ba36db7360b"
    ),
    "pocketguard.yml": (
        "9e6d291fe012a4932126c5dba96d0a27e1c68299bbbb1847ea8ab4d69db87df6"
    ),
    "pr-check.yml": (
        "6f7cf483d18396193071b63c0eeec6eeaa8d5e52b3da15b746dae587f613fa77"
    ),
    "release.yml": (
        "acf8d39620362fce1190f6ffb6d75d088ae19c4d9de636be64af0dcbc0cd7d1f"
    ),
    "security-audit.yml": (
        "f836a7d3ed4daf6ecec33baf577375bca6504513d54c89e2d2cb6da4d5d83bfa"
    ),
}

REMOTE_USES_RE = re.compile(
    r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"
    r"@[0-9a-f]{40}$"
)
GH_RELEASE_RE = re.compile(r"(?i)\bgh\s+[\"']?release[\"']?\b")
GH_API_RE = re.compile(r"(?i)\bgh\s+api\b")
GH_API_HOST_RE = re.compile(r"(?i)\bapi\.github\.com\b")
RELEASES_PATH_RE = re.compile(r"(?i)(?:^|[/\s\"'`])releases(?:/assets)?(?:[/\s\"'`]|$)")
SECRETS_PUBLISH_TOKEN_RE = re.compile(
    r"(?i)secrets\s*\.\s*(?:release_token|gh_pat)\b"
    r"|secrets\s*\[\s*[\"'](?:release_token|gh_pat)[\"']\s*\]"
)
PUBLISH_WORD_RE = re.compile(r"(?i)\bpublish\b")
PUBLISH_OR_RELEASE_RE = re.compile(r"(?i)\b(?:release|publish)\b")
DEFAULT_BRANCH_REF = "${{ github.event.repository.default_branch }}"
EXPECTED_CHECKOUT = (
    "actions/checkout@11d5960a326750d5838078e36cf38b85af677262"
)
REQUIRED_PR_TYPES = ("labeled", "synchronize", "opened", "reopened")
EXPECTED_PR_GATE_NEEDS = frozenset(
    {
        "changes",
        "unit-tests",
        "lint",
        "build-and-policy-gate",
        "policy-harness",
        "workflow-guard",
        "docs-guard",
        "bot-tests",
    }
)
EXPECTED_GUARD_KEYS = frozenset(
    {"name", "runs-on", "timeout-minutes", "steps"}
)
GUARD_CHECK_RUN = "python3 -P scripts/check_workflow_policy.py"
GUARD_HARNESS_RUN = (
    'PYTHONPATH="$PWD" PYTHONDONTWRITEBYTECODE=1 python3 -P -m unittest '
    "scripts/tests/test_workflow_policy.py"
)
GUARD_CHECK_NAME = "Check workflow policy"
GUARD_HARNESS_NAME = "Run workflow policy harness"
GUARD_INSTALL_NAME = "Install pinned PyYAML"
SHADOW_PREFLIGHT_NAME = "Reject Python import shadowing"
EXPECTED_SHADOW_RUN = (
    "chmod +x scripts/check_python_shadowing.sh\n"
    "scripts/check_python_shadowing.sh\n"
)
PYYAML_INSTALL_RUN = "\n".join(
    (
        "set -euo pipefail",
        "python3 -m pip install --user --disable-pip-version-check "
        "--ignore-installed --no-deps --only-binary=:all: --require-hashes "
        "-r scripts/requirements-docs.txt",
        "python3 -P -c 'import yaml; assert yaml.__version__ == \"6.0.3\", "
        "yaml.__version__'",
    )
)
EXPECTED_PR_GATE_KEYS = frozenset(
    {"name", "runs-on", "timeout-minutes", "needs", "if", "steps"}
)
EXPECTED_PR_GATE_ENV = {
    "CHANGES_RESULT": "${{ needs.changes.result }}",
    "CODE": "${{ needs.changes.outputs.code }}",
    "POLICY": "${{ needs.changes.outputs.policy }}",
    "UNIT": "${{ needs.unit-tests.result }}",
    "LINT": "${{ needs.lint.result }}",
    "BUILD": "${{ needs.build-and-policy-gate.result }}",
    "HARNESS": "${{ needs.policy-harness.result }}",
    "WORKFLOW": "${{ needs.workflow-guard.result }}",
    "DOCS": "${{ needs.docs-guard.result }}",
    "BOT": "${{ needs.bot-tests.result }}",
    "BOT_PATHS": "${{ needs.changes.outputs.bot }}",
}
EXPECTED_PR_GATE_RUN_SHA256 = (
    "964b2a8c1571dcfde594d4460cb6a5f8f95a6d1be4f8c71ee2d5ccd0aba96224"
)
EXPECTED_POLICY_HARNESS_RUN = (
    'PYTHONPATH="$PWD" PYTHONDONTWRITEBYTECODE=1 python3 -P -m unittest '
    "scripts/tests/test_apk_policy_check.py "
    "scripts/tests/test_release_artifact_verifier.py "
    "scripts/tests/test_build_release_identity.py "
    "scripts/tests/test_summarize_junit.py "
    "scripts/tests/test_check_test_budgets.py "
    "scripts/tests/test_check_junit_durations.py "
    "scripts/tests/test_gradle_wrapper_checksum.py "
    "scripts/tests/test_verify_jdk_floor.py "
    "scripts/tests/test_workflow_policy.py"
)
EXPECTED_CODE_FILTER_PATHS = frozenset(
    {
        "app/src/**",
        "app/build.gradle.kts",
        "app/lint.xml",
        "gradle/**",
        "gradlew",
        "gradlew.bat",
        "settings.gradle.kts",
        "build.gradle.kts",
        "gradle.properties",
        ".github/workflows/pr-check.yml",
    }
)
EXPECTED_POLICY_FILTER_PATHS = frozenset(
    {
        "gradle/wrapper/**",
        "gradlew",
        "gradlew.bat",
        "scripts/play_policy_check.sh",
        "scripts/apk_policy_inspect.py",
        "scripts/backup_rules_check.py",
        "scripts/nsc_check.py",
        "scripts/summarize_junit.py",
        "scripts/check_test_budgets.py",
        "scripts/check_junit_durations.py",
        "scripts/tests/test_apk_policy_check.py",
        "scripts/tests/test_summarize_junit.py",
        "scripts/tests/test_check_test_budgets.py",
        "scripts/tests/test_check_junit_durations.py",
        "scripts/build_release.sh",
        "scripts/verify_release_apk.py",
        "scripts/release_artifact_stage.py",
        "scripts/check_release_pins.py",
        "scripts/check_release_tag_version.py",
        "scripts/fake_keystore_rehearsal.py",
        "scripts/release_signer_pins.example.json",
        "scripts/tests/test_release_artifact_verifier.py",
        "scripts/tests/test_build_release_identity.py",
        "scripts/tests/test_gradle_wrapper_checksum.py",
        "scripts/tests/test_verify_jdk_floor.py",
        "scripts/check_workflow_policy.py",
        "scripts/tests/test_workflow_policy.py",
        "scripts/check_python_shadowing.sh",
        ".github/workflows/pr-check.yml",
        ".github/workflows/codeql.yml",
        ".github/workflows/nightly-instrumented.yml",
        ".github/workflows/**",
        ".github/actions/**",
        "scripts/**",
    }
)
EXPECTED_BOT_FILTER_PATHS = frozenset(
    {
        "github_bot/**",
        ".github/workflows/pocketguard.yml",
        ".github/workflows/pr-check.yml",
    }
)
EXPECTED_FILTERS = {
    "code": EXPECTED_CODE_FILTER_PATHS,
    "policy": EXPECTED_POLICY_FILTER_PATHS,
    "bot": EXPECTED_BOT_FILTER_PATHS,
}
EXPECTED_CHANGES_KEYS = frozenset(
    {"name", "runs-on", "timeout-minutes", "permissions", "outputs", "steps"}
)
EXPECTED_CHANGES_OUTPUTS = {
    name: f"${{{{ steps.filter.outputs.{name} }}}}"
    for name in ("code", "policy", "bot")
}
PATH_FILTER_USES = (
    "dorny/paths-filter@0e4a8c6effa4802afeda77dc8d303f8176d7dfad"
)
EXPECTED_PR_CHECK_ON = {
    "pull_request": {"branches": ["main"]},
    "push": {"branches": ["main"]},
}
EXPECTED_PR_JOB_IF = {
    "unit-tests": "needs.changes.outputs.code == 'true'",
    "lint": "needs.changes.outputs.code == 'true'",
    "policy-harness": "needs.changes.outputs.policy == 'true'",
    "build-and-policy-gate": (
        "needs.changes.outputs.code == 'true' || "
        "needs.changes.outputs.policy == 'true'"
    ),
    "docs-guard": None,
    "bot-tests": "needs.changes.outputs.bot == 'true'",
}
EXPECTED_PR_JOB_ENV = {
    "unit-tests": {"PYTHONDONTWRITEBYTECODE": "1"},
    "lint": None,
    "policy-harness": {"PYTHONDONTWRITEBYTECODE": "1"},
    "build-and-policy-gate": {"PYTHONDONTWRITEBYTECODE": "1"},
    "docs-guard": None,
    "bot-tests": None,
}
UNIT_BUDGETS_RUN = "python3 scripts/check_test_budgets.py"
UNIT_TEST_MATRIX_RUN = (
    'case "${{ matrix.flavor }}" in\n'
    "  play) ./gradlew :app:testPlayDebugUnitTest --stacktrace ;;\n"
    "  foss) ./gradlew :app:testFossDebugUnitTest --stacktrace ;;\n"
    "  github) ./gradlew :app:testGithubDebugUnitTest --stacktrace ;;\n"
    '  *) echo "unknown flavor: ${{ matrix.flavor }}"; exit 1 ;;\n'
    "esac"
)
UNIT_DURATIONS_RUN = "python3 scripts/check_junit_durations.py"
LINT_RUN = "./gradlew :app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug"
POLICY_BUDGETS_RUN = (
    "PYTHONDONTWRITEBYTECODE=1 python3 scripts/check_test_budgets.py"
)
ASSEMBLE_RUN = (
    "./gradlew :app:assemblePlayDebug :app:assembleFossDebug "
    ":app:assembleGithubDebug :app:assemblePlayRelease "
    ":app:assembleFossRelease :app:assembleGithubRelease"
)
PLAY_DEBUG_POLICY_RUN = (
    "chmod +x scripts/play_policy_check.sh\n"
    "scripts/play_policy_check.sh "
    "app/build/outputs/apk/play/debug/app-play-debug.apk"
)
FOSS_DEBUG_POLICY_RUN = (
    "chmod +x scripts/play_policy_check.sh\n"
    "scripts/play_policy_check.sh --foss "
    "app/build/outputs/apk/foss/debug/app-foss-debug.apk"
)
PLAY_RELEASE_POLICY_RUN = (
    "chmod +x scripts/play_policy_check.sh\n"
    "set -- app/build/outputs/apk/play/release/*.apk\n"
    '[ -f "$1" ] || { echo "::error::no assembled release APK for play '
    'under app/build/outputs/apk/play/release/"; exit 1; }\n'
    'scripts/play_policy_check.sh "$@"'
)
FOSS_RELEASE_POLICY_RUN = (
    "chmod +x scripts/play_policy_check.sh\n"
    "set -- app/build/outputs/apk/foss/release/*.apk\n"
    '[ -f "$1" ] || { echo "::error::no assembled release APK for foss '
    'under app/build/outputs/apk/foss/release/"; exit 1; }\n'
    'scripts/play_policy_check.sh --foss "$@"'
)
GITHUB_DEBUG_INSPECT_RUN = (
    "# set -e turns a bare failing `X=$(command -v ...)` into an opaque\n"
    "# exit 1; report the missing tool named (fail-closed, not skipped).\n"
    'AAPT=$(command -v aapt) || { echo "::error::aapt not found: '
    'build-tools PATH step failed"; exit 1; }\n'
    'DEXDUMP=$(command -v dexdump) || { echo "::error::dexdump not found: '
    'build-tools PATH step failed"; exit 1; }\n'
    "python3 scripts/apk_policy_inspect.py --mode github --apk "
    'app/build/outputs/apk/github/debug/app-github-debug.apk --aapt "$AAPT" '
    '--dexdump "$DEXDUMP"\n'
    "main=app/src/main/AndroidManifest.xml\n"
    "overlay=app/src/github/AndroidManifest.xml\n"
    'for m in "$main" "$overlay"; do\n'
    '  [ -f "$m" ] || { echo "::error::missing manifest: $m"; exit 1; }\n'
    '  python3 scripts/backup_rules_check.py --check-permission-removals '
    '"SEND_SMS,RECEIVE_SMS,READ_SMS,MANAGE_EXTERNAL_STORAGE,BIND_VPN_SERVICE" '
    '"$m"\n'
    "done\n"
    "for m in app/src/*/AndroidManifest.xml; do\n"
    '  case "$m" in\n'
    "    app/src/main/AndroidManifest.xml|"
    "app/src/github/AndroidManifest.xml) continue ;;\n"
    "    app/src/debug/AndroidManifest.xml|"
    "app/src/release/AndroidManifest.xml) ;;\n"
    "    app/src/github*/AndroidManifest.xml) ;;\n"
    "    app/src/test*/AndroidManifest.xml|"
    "app/src/androidTest*/AndroidManifest.xml) continue ;;\n"
    "    *) continue ;;\n"
    "  esac\n"
    '  python3 scripts/backup_rules_check.py --check-permission-removals '
    '"SEND_SMS,RECEIVE_SMS,READ_SMS,MANAGE_EXTERNAL_STORAGE,BIND_VPN_SERVICE" '
    '"$m"\n'
    "done"
)
GITHUB_RELEASE_INSPECT_RUN = (
    'AAPT=$(command -v aapt) || { echo "::error::aapt not found: '
    'build-tools PATH step failed"; exit 1; }\n'
    'DEXDUMP=$(command -v dexdump) || { echo "::error::dexdump not found: '
    'build-tools PATH step failed"; exit 1; }\n'
    "set -- app/build/outputs/apk/github/release/*.apk\n"
    '[ -f "$1" ] || { echo "::error::no assembled release APK for github '
    'under app/build/outputs/apk/github/release/"; exit 1; }\n'
    'for apk in "$@"; do\n'
    '  python3 scripts/apk_policy_inspect.py --mode github --apk "$apk" '
    '--aapt "$AAPT" --dexdump "$DEXDUMP"\n'
    "done"
)
DOCS_SELFTEST_RUN = (
    "chmod +x scripts/test_docs_claim_check.sh\n"
    "scripts/test_docs_claim_check.sh"
)
DOCS_CHECK_RUN = (
    "chmod +x scripts/docs_claim_check.sh\nscripts/docs_claim_check.sh"
)
BOT_INSTALL_RUN = "npm ci --ignore-scripts"
BOT_TEST_RUN = "npm test"
BOT_BUILD_RUN = "npm run build"
EXPECTED_PR_JOB_CORE_STEPS = {
    "unit-tests": (
        (UNIT_BUDGETS_RUN, None),
        (UNIT_TEST_MATRIX_RUN, None),
        (UNIT_DURATIONS_RUN, None),
    ),
    "lint": ((LINT_RUN, None),),
    "policy-harness": (
        (EXPECTED_POLICY_HARNESS_RUN, None),
        (POLICY_BUDGETS_RUN, None),
    ),
    "build-and-policy-gate": (
        (ASSEMBLE_RUN, None),
        (PLAY_DEBUG_POLICY_RUN, None),
        (FOSS_DEBUG_POLICY_RUN, None),
        (GITHUB_DEBUG_INSPECT_RUN, None),
        (PLAY_RELEASE_POLICY_RUN, None),
        (FOSS_RELEASE_POLICY_RUN, None),
        (GITHUB_RELEASE_INSPECT_RUN, None),
    ),
    "docs-guard": ((DOCS_SELFTEST_RUN, None), (DOCS_CHECK_RUN, None)),
    "bot-tests": (
        (BOT_INSTALL_RUN, "github_bot"),
        (BOT_TEST_RUN, "github_bot"),
        (BOT_BUILD_RUN, "github_bot"),
    ),
}
EXPECTED_NIGHTLY_STRESS_KEYS = frozenset(
    {"if", "name", "runs-on", "timeout-minutes", "steps"}
)
EXPECTED_INSTRUMENTED_KEYS = frozenset(
    {"if", "name", "runs-on", "timeout-minutes", "strategy", "steps"}
)
EXPECTED_STRATEGY_KEYS = frozenset({"fail-fast", "matrix"})
EXPECTED_MATRIX_KEYS = frozenset({"api-level", "flavor", "target", "arch", "include"})
EXPECTED_MATRIX_VALUES = {
    "api-level": [33, 34],
    "flavor": ["Play", "Foss", "Github"],
    "target": ["google_apis"],
    "arch": ["x86_64"],
    "include": [
        {
            "api-level": 36,
            "flavor": "Foss",
            "target": "google_apis",
            "arch": "x86_64",
        }
    ],
}
EXPECTED_RELEASE_ON = {
    "push": {"tags": ["v[0-9]*.[0-9]*.[0-9]*"]},
    "workflow_dispatch": None,
}
EXPECTED_RELEASE_RUN = (
    'echo "::error::Public release publishing is disabled while Issue #12 '
    'release gates remain open."\nexit 1'
)
GITLEAKS_ACTION_RE = re.compile(r"^gitleaks/gitleaks-action@[0-9a-f]{40}$")
DEPENDENCY_REVIEW_ACTION_RE = re.compile(
    r"^actions/dependency-review-action@[0-9a-f]{40}$"
)
CODEQL_INIT_ACTION_RE = re.compile(
    r"^github/codeql-action/init@[0-9a-f]{40}$"
)
CODEQL_ANALYZE_ACTION_RE = re.compile(
    r"^github/codeql-action/analyze@[0-9a-f]{40}$"
)
EXPECTED_SECURITY_JOBS = frozenset({"gitleaks", "dependency-review"})
EXPECTED_SECURITY_ON = {
    "push": {"branches": ["main"]},
    "pull_request": {"branches": ["main"]},
    "schedule": [{"cron": "0 12 * * 1"}],
    "workflow_dispatch": None,
}
EXPECTED_GITLEAKS_STEP_ENV = {
    "GITHUB_TOKEN": "${{ secrets.GITHUB_TOKEN }}",
    "GITLEAKS_CONFIG": ".gitleaks.toml",
}
EXPECTED_DEPENDENCY_REVIEW_IF = "github.event_name == 'pull_request'"
EXPECTED_CODEQL_JOBS = frozenset({"codeql"})
EXPECTED_CODEQL_PATHS_IGNORE = (
    "docs/**",
    "**/*.md",
    "LICENSE*",
    "TRADEMARKS.md",
    "NOTICE*",
)
EXPECTED_CODEQL_ON = {
    "push": {
        "branches": ["main"],
        "paths-ignore": list(EXPECTED_CODEQL_PATHS_IGNORE),
    },
    "pull_request": {
        "branches": ["main"],
        "paths-ignore": list(EXPECTED_CODEQL_PATHS_IGNORE),
    },
    "schedule": [{"cron": "0 12 * * 1"}],
    "workflow_dispatch": None,
}
EXPECTED_POCKETGUARD_JOBS = frozenset(
    {"prepare-tag", "claim-slot", "review-send", "publish"}
)
EXPECTED_POCKETGUARD_ON = {
    "pull_request_target": {
        "types": ["opened", "reopened", "synchronize"],
    },
    "issues": {"types": ["opened", "edited", "reopened"]},
    "issue_comment": {"types": ["created", "edited"]},
}
EXPECTED_POCKETGUARD_IF = {
    "prepare-tag": None,
    "claim-slot": (
        "(needs.prepare-tag.outputs.target == 'pull-request' && "
        "needs.prepare-tag.outputs.should_review == 'true' && "
        "needs.prepare-tag.outputs.review_gate == 'auto') || "
        "(needs.prepare-tag.outputs.target == 'pull-request' && "
        "needs.prepare-tag.outputs.should_review == 'true' && "
        "needs.prepare-tag.outputs.review_gate == 'manual' && "
        "needs.prepare-tag.outputs.authorized == 'true') || "
        "(needs.prepare-tag.outputs.target == 'issue' && "
        "needs.prepare-tag.outputs.should_review == 'true' && "
        "(needs.prepare-tag.outputs.route_kind == 'first-review' || "
        "needs.prepare-tag.outputs.route_kind == 'issue-update'))"
    ),
    "review-send": (
        "(needs.prepare-tag.outputs.target == 'pull-request' && "
        "needs.prepare-tag.outputs.should_review == 'true' && "
        "needs.prepare-tag.outputs.review_gate == 'auto' && "
        "needs.claim-slot.outputs.claimed == 'true') || "
        "(needs.prepare-tag.outputs.target == 'pull-request' && "
        "needs.prepare-tag.outputs.should_review == 'true' && "
        "needs.prepare-tag.outputs.review_gate == 'manual' && "
        "needs.prepare-tag.outputs.authorized == 'true' && "
        "needs.claim-slot.outputs.claimed == 'true') || "
        "(needs.prepare-tag.outputs.target == 'issue' && "
        "needs.prepare-tag.outputs.should_review == 'true' && "
        "(needs.prepare-tag.outputs.route_kind == 'first-review' || "
        "needs.prepare-tag.outputs.route_kind == 'issue-update'))"
    ),
    "publish": (
        "always() && needs.prepare-tag.result == 'success' && "
        "((github.event_name != 'issue_comment' && "
        "needs.prepare-tag.outputs.review_gate != 'none') || "
        "(needs.prepare-tag.outputs.authorized == 'true' && "
        "needs.prepare-tag.outputs.review_gate != 'none') || "
        "(needs.prepare-tag.outputs.target == 'issue' && "
        "needs.prepare-tag.outputs.should_review == 'true' && "
        "needs.prepare-tag.outputs.route_kind != 'ignore'))"
    ),
}
BOT_RUN_RE = re.compile(
    r"(?i)github_runner|ts-node|\bnode\b|\bnpx\b|"
    r"\bnpm\s+(?:test|run)\b|(?:^|\s)\./"
)
DECOY_RUN_RE = re.compile(r"(?i)^\s*(?:echo|true|:)\b")
EXPECTED_NIGHTLY_ON_KEYS = frozenset(
    {"schedule", "workflow_dispatch", "pull_request"}
)
EXPECTED_NIGHTLY_JOBS = frozenset({"nightly-unit-stress", "instrumented-test"})
EXPECTED_NIGHTLY_IF = "github.event_name != 'pull_request'"
EXPECTED_INSTRUMENTED_IF = (
    "github.event_name != 'pull_request' || "
    "contains(github.event.pull_request.labels.*.name, 'run-instrumented')"
)
EXPECTED_NIGHTLY_GROUP = (
    "${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}"
)
EXPECTED_CANCEL = "${{ github.ref != 'refs/heads/main' }}"
EXPECTED_NIGHTLY_GRADLE = (
    './gradlew :app:testFossDebugUnitTest --tests '
    '"dev.librepocket.agent.ui.chat.ChatSnapshotByteCapFullStressTest" '
    "--stacktrace"
)
EXPECTED_INSTRUMENTED_SCRIPT = (
    "./gradlew :app:connected${{ matrix.flavor }}DebugAndroidTest --no-daemon "
    '-Dorg.gradle.jvmargs="-Xmx2048m -XX:MaxMetaspaceSize=512m"'
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    message: str

    def render(self) -> str:
        return f"{self.path}:{self.line}: {self.message}"


if yaml is not None:

    class DuplicateKeySafeLoader(yaml.SafeLoader):
        """SafeLoader that rejects duplicate keys instead of last-winning."""

        def construct_mapping(self, node: Any, deep: bool = False) -> Any:
            if not isinstance(node, yaml.MappingNode):
                raise yaml.constructor.ConstructorError(
                    None, None, "expected a mapping node", node.start_mark
                )
            seen_keys: set[Any] = set()
            seen_cmp: set[str] = set()
            for key_node, _value_node in node.value:
                if key_node.tag == "tag:yaml.org,2002:merge":
                    raise yaml.constructor.ConstructorError(
                        "while constructing a mapping",
                        node.start_mark,
                        "YAML merge keys (<<) are forbidden",
                        key_node.start_mark,
                    )
                key = self.construct_object(key_node, deep=True)
                key_cmp = str(getattr(key_node, "value", key))
                try:
                    duplicate = key in seen_keys or key_cmp in seen_cmp
                    seen_keys.add(key)
                    seen_cmp.add(key_cmp)
                except TypeError as error:
                    raise yaml.constructor.ConstructorError(
                        "while constructing a mapping",
                        node.start_mark,
                        "found an unhashable mapping key",
                        key_node.start_mark,
                    ) from error
                if duplicate:
                    raise yaml.constructor.ConstructorError(
                        "while constructing a mapping",
                        node.start_mark,
                        f"found duplicate key {key!r}",
                        key_node.start_mark,
                    )
            return super().construct_mapping(node, deep=deep)


def _yaml_line(error: Any) -> int:
    mark = getattr(error, "problem_mark", None) or getattr(
        error, "context_mark", None
    )
    return int(getattr(mark, "line", 0)) + 1 if mark is not None else 1


def load_yaml(path: str, text: str) -> tuple[Any, list[Finding]]:
    """Parse text with duplicate-key rejection; return (data, findings)."""
    if yaml is None:
        message = (
            "PyYAML is required for structural workflow checks; install the "
            "pinned dependency with: python3 -m pip install --require-hashes "
            "-r scripts/requirements-docs.txt"
        )
        if YAML_IMPORT_ERROR is not None:
            message += f" (import error: {YAML_IMPORT_ERROR})"
        return None, [Finding(path, 1, message)]
    try:
        return yaml.load(text, Loader=DuplicateKeySafeLoader), []
    except yaml.YAMLError as error:
        return None, [
            Finding(path, _yaml_line(error), f"invalid or ambiguous YAML: {error}")
        ]


def normalize_run(text: str) -> str:
    """Stable run-script normalization for exact command/hash comparisons."""
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = [line.rstrip() for line in normalized.split("\n")]
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines)


def run_sha256(text: str) -> str:
    return hashlib.sha256(normalize_run(text).encode("utf-8")).hexdigest()


def walk_mappings(value: Any) -> Iterator[dict[Any, Any]]:
    """Yield every mapping in a loaded YAML tree without looping on aliases."""
    stack = [value]
    seen: set[int] = set()
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if id(node) in seen:
                continue
            seen.add(id(node))
            yield node
            stack.extend(node.values())
        elif isinstance(node, list):
            stack.extend(node)


def walk_strings(value: Any, include_keys: bool = True) -> Iterator[str]:
    """Yield every string scalar (and optionally mapping keys) in the YAML tree."""
    stack = [value]
    seen: set[int] = set()
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if id(node) in seen:
                continue
            seen.add(id(node))
            for key, item in node.items():
                if include_keys and isinstance(key, str):
                    yield key
                stack.append(item)
        elif isinstance(node, list):
            if id(node) in seen:
                continue
            seen.add(id(node))
            stack.extend(node)
        elif isinstance(node, str):
            yield node


def mapping_at(value: Any, key: str) -> dict[Any, Any] | None:
    return value if isinstance(value, dict) and key in value else None


def trigger_names(on_value: Any) -> set[str]:
    if isinstance(on_value, dict):
        return {str(key).lower() for key in on_value}
    if isinstance(on_value, str):
        return {on_value.lower()}
    if isinstance(on_value, list):
        return {str(item).lower() for item in on_value}
    return set()


def has_push_tags(on_value: Any) -> bool:
    if not isinstance(on_value, dict):
        return False
    push = on_value.get("push")
    return isinstance(push, dict) and "tags" in push


def exact_keys(
    path: str, label: str, value: Any, expected: frozenset[str]
) -> list[Finding]:
    if not isinstance(value, dict):
        return [Finding(path, 1, f"{label} must be a mapping")]
    actual = {str(key) for key in value}
    if actual == set(expected):
        return []
    missing = sorted(set(expected) - actual)
    extra = sorted(actual - set(expected))
    return [
        Finding(
            path,
            1,
            f"{label} keys must be exactly {sorted(expected)} "
            f"(missing: {missing}; extra: {extra})",
        )
    ]


def find_key_mappings(value: Any, key: str) -> list[dict[Any, Any]]:
    return [mapping for mapping in walk_mappings(value) if key in mapping]


def check_defaults_and_env(path: str, data: dict[Any, Any]) -> list[Finding]:
    findings: list[Finding] = []
    if "defaults" in data:
        findings.append(Finding(path, 1, "top-level defaults are not allowed"))
    if "env" in data:
        findings.append(Finding(path, 1, "top-level env is not allowed"))
    for mapping in walk_mappings(data):
        if "defaults" in mapping and mapping is not data:
            findings.append(
                Finding(path, 1, "defaults blocks are not allowed anywhere")
            )
    return findings


def check_permissions(
    path: str, data: Any, allow_pr_write: bool = False
) -> list[Finding]:
    findings: list[Finding] = []
    if isinstance(data, dict) and "jobs" in data and data.get("permissions") is None:
        findings.append(
            Finding(
                path,
                1,
                "workflow must declare explicit top-level permissions",
            )
        )
    for mapping in find_key_mappings(data, "permissions"):
        permissions = mapping["permissions"]
        if permissions is None:
            continue
        if isinstance(permissions, str):
            perm_str = permissions.strip().lower()
            if perm_str == "write-all":
                findings.append(
                    Finding(path, 1, "permissions must not grant write-all")
                )
            elif perm_str == "write":
                findings.append(
                    Finding(path, 1, "permissions must not grant write")
                )
            continue
        if not isinstance(permissions, dict):
            findings.append(
                Finding(
                    path,
                    1,
                    "permissions must be a mapping (or write-all scalar) and "
                    "must not grant write-all/contents: write",
                )
            )
            continue
        for key, grant in permissions.items():
            key_text = str(key).lower()
            grant_text = str(grant).lower() if grant is not None else ""
            if key_text == "write-all" or grant_text == "write-all":
                findings.append(
                    Finding(path, 1, "permissions must not grant write-all")
                )
            elif grant_text == "write":
                if (allow_pr_write and key_text in ("pull-requests", "issues")) or (
                    key_text == "security-events" and path.endswith("codeql.yml")
                ):
                    continue
                findings.append(
                    Finding(path, 1, f"permissions must not grant {key_text}: write")
                )
            elif grant is not None and not isinstance(grant, str):
                findings.append(
                    Finding(
                        path,
                        1,
                        f"permissions entry {key!r} must be a string grant",
                    )
                )
    return findings


def resolve_local_action(
    root: Path, path: str, value: str
) -> tuple[Path | None, list[Finding]]:
    findings: list[Finding] = []
    actions_root = (root / ".github" / "actions").resolve()
    raw = value[2:]
    target = (root / raw).resolve()
    try:
        target.relative_to(actions_root)
    except ValueError:
        return None, [
            Finding(
                path,
                1,
                f"local action {value!r} must resolve under .github/actions/ "
                "(traversal, absolute and outside paths are rejected)",
            )
        ]
    if target == actions_root:
        return None, [
            Finding(
                path,
                1,
                f"local action {value!r} must name an action directory below "
                ".github/actions/",
            )
        ]
    if target.is_file() and target.name in ("action.yml", "action.yaml"):
        return target, []
    if target.is_dir():
        for name in ("action.yml", "action.yaml"):
            candidate = target / name
            if candidate.is_file():
                return candidate, []
    return None, [
        Finding(
            path,
            1,
            f"local action {value!r} has no readable action.yml/action.yaml "
            "metadata under .github/actions/",
        )
    ]


def check_uses(
    path: str, data: Any, root: Path, pull_request_target: bool
) -> tuple[list[Finding], list[Path]]:
    findings: list[Finding] = []
    action_paths: list[Path] = []
    for mapping in find_key_mappings(data, "uses"):
        value = mapping["uses"]
        if not isinstance(value, str) or not value.strip():
            findings.append(
                Finding(path, 1, "uses must be a non-empty string action reference")
            )
            continue
        if value.startswith("./"):
            if pull_request_target:
                findings.append(
                    Finding(
                        path,
                        1,
                        f"pull_request_target workflow must not use local action "
                        f"{value!r}",
                    )
                )
                continue
            action_path, local_findings = resolve_local_action(root, path, value)
            findings.extend(local_findings)
            if action_path is not None:
                action_paths.append(action_path)
            continue
        if REMOTE_USES_RE.match(value):
            continue
        findings.append(
            Finding(
                path,
                1,
                f"unpinned or unsupported uses ref {value!r}: use a local './...' "
                "action or owner/repo[/path]@<40 lowercase hex SHA",
            )
        )
    return findings, action_paths


def has_anchor_or_alias(text: str) -> bool:
    if yaml is None:
        return True
    try:
        for event in yaml.parse(text, Loader=yaml.SafeLoader):
            if isinstance(event, yaml.AliasEvent) or getattr(event, "anchor", None):
                return True
    except yaml.YAMLError:
        return True
    return False


def check_pull_request_target(
    path: str, data: dict[Any, Any], text: str
) -> list[Finding]:
    findings: list[Finding] = []
    on_value = data.get("on")
    if "pull_request_target" not in trigger_names(on_value):
        return findings
    if has_anchor_or_alias(text):
        findings.append(
            Finding(
                path,
                1,
                "pull_request_target workflow must not use YAML anchors/aliases",
            )
        )
    allowed_inputs = {"ref", "persist-credentials", "fetch-depth"}
    for mapping in find_key_mappings(data, "uses"):
        uses = mapping.get("uses")
        if not isinstance(uses, str) or "actions/checkout" not in uses.lower():
            continue
        with_value = mapping.get("with")
        if with_value is not None and not isinstance(with_value, dict):
            findings.append(
                Finding(path, 1, "pull_request_target checkout 'with' must be a mapping")
            )
            continue
        inputs = with_value if isinstance(with_value, dict) else {}
        for raw_key, raw_value in inputs.items():
            key = str(raw_key)
            normalized = key.lower()
            if key != normalized or normalized not in allowed_inputs:
                findings.append(
                    Finding(
                        path,
                        1,
                        "pull_request_target checkout input "
                        f"{key!r} is not allowed; only lower-case "
                        "ref/persist-credentials/fetch-depth are accepted",
                    )
                )
                continue
            if normalized == "ref" and raw_value != DEFAULT_BRANCH_REF:
                findings.append(
                    Finding(
                        path,
                        1,
                        "pull_request_target checkout must not use a PR head ref or "
                        "literal branch; only ref: "
                        f"{DEFAULT_BRANCH_REF} (or no ref) is allowed",
                    )
                )
            elif normalized == "persist-credentials" and raw_value is not False:
                findings.append(
                    Finding(
                        path,
                        1,
                        "pull_request_target checkout persist-credentials must be "
                        "boolean false",
                    )
                )
            elif normalized == "fetch-depth" and not (
                isinstance(raw_value, int)
                and not isinstance(raw_value, bool)
                and raw_value == 0
            ):
                findings.append(
                    Finding(
                        path,
                        1,
                        "pull_request_target checkout fetch-depth must be 0",
                    )
                )
        for scalar in walk_strings(mapping):
            candidate = scalar.replace(DEFAULT_BRANCH_REF, "")
            lowered = candidate.lower()
            if "merge_commit_sha" in lowered:
                findings.append(
                    Finding(
                        path,
                        1,
                        "pull_request_target workflow must not use "
                        "merge_commit_sha",
                    )
                )
                break
            if "repository" in lowered:
                findings.append(
                    Finding(
                        path,
                        1,
                        "pull_request_target workflow must not set or reference "
                        "a repository override (only the default-branch checkout "
                        "is allowed)",
                    )
                )
                break
    return findings


def check_non_release_publisher(
    path: str, data: dict[Any, Any], is_release: bool
) -> list[Finding]:
    if is_release:
        return []
    findings: list[Finding] = []
    if has_push_tags(data.get("on")):
        findings.append(
            Finding(
                path,
                1,
                "only release.yml may declare a push.tags trigger",
            )
        )
    for mapping in find_key_mappings(data, "uses"):
        uses = mapping.get("uses")
        if isinstance(uses, str) and PUBLISH_OR_RELEASE_RE.search(uses):
            findings.append(
                Finding(
                    path,
                    1,
                    "non-release workflow/action must not use an action whose "
                    f"owner/repo/path names release or publish ({uses!r})",
                )
            )
            break
    for scalar in walk_strings(data):
        if GH_RELEASE_RE.search(scalar):
            findings.append(
                Finding(path, 1, "non-release workflow must not invoke 'gh release'")
            )
            break
    for scalar in walk_strings(data):
        if RELEASES_PATH_RE.search(scalar) and (
            GH_API_RE.search(scalar) or GH_API_HOST_RE.search(scalar)
        ):
            findings.append(
                Finding(
                    path,
                    1,
                    "non-release workflow must not call the GitHub releases API "
                    "(gh api / api.github.com .../releases, including "
                    "releases/assets)",
                )
            )
            break
    for scalar in walk_strings(data):
        if SECRETS_PUBLISH_TOKEN_RE.search(scalar):
            findings.append(
                Finding(
                    path,
                    1,
                    "non-release workflow must not reference "
                    "secrets.RELEASE_TOKEN or secrets.GH_PAT",
                )
            )
            break
    return findings


def check_release(path: str, data: dict[Any, Any]) -> list[Finding]:
    findings: list[Finding] = []
    permissions = data.get("permissions")
    if not (
        isinstance(permissions, dict)
        and set(map(str, permissions)) == {"contents"}
        and str(permissions.get("contents", "")).lower() == "read"
    ):
        findings.append(
            Finding(
                path,
                1,
                "release workflow must declare exactly one top-level "
                "permissions: contents: read",
            )
        )
        if isinstance(permissions, dict):
            findings.append(
                Finding(
                    path,
                    1,
                    "release workflow permissions must be exactly contents: read",
                )
            )

    if data.get("on") != EXPECTED_RELEASE_ON:
        findings.append(
            Finding(
                path,
                1,
                "release workflow 'on' must be exactly push.tags "
                "[v[0-9]*.[0-9]*.[0-9]*] plus workflow_dispatch",
            )
        )

    jobs = data.get("jobs")
    job_names = list(jobs) if isinstance(jobs, dict) else []
    if len(job_names) != 1 or str(job_names[0]) != "release-disabled":
        findings.append(
            Finding(
                path,
                1,
                "release workflow must have exactly one job named "
                "release-disabled",
            )
        )
    for job_name in job_names:
        lowered = str(job_name).lower()
        if "publish" in lowered:
            findings.append(
                Finding(path, 1, "release workflow must not define a publish job")
            )

    all_uses = [mapping["uses"] for mapping in find_key_mappings(data, "uses")]
    for uses in all_uses:
        findings.append(
            Finding(path, 1, "release workflow must not use any action")
        )
        if isinstance(uses, str) and "checkout" in uses.lower():
            findings.append(
                Finding(path, 1, "release workflow must not use actions/checkout")
            )
        if isinstance(uses, str) and "publish" in uses.lower():
            findings.append(
                Finding(path, 1, "release workflow must not use a publish action")
            )

    for mapping in walk_mappings(data):
        if "if" in mapping:
            findings.append(
                Finding(
                    path,
                    1,
                    "release workflow must not contain if: (conditional exit "
                    "is not fail-closed)",
                )
            )
        if "continue-on-error" in mapping:
            findings.append(
                Finding(
                    path,
                    1,
                    "release workflow must not contain continue-on-error",
                )
            )
        if "env" in mapping:
            findings.append(Finding(path, 1, "release workflow must not contain env:"))
        if "permissions" in mapping and mapping is not data:
            findings.append(
                Finding(
                    path,
                    1,
                    "release workflow permissions must be exactly contents: read",
                )
            )
            permission_grant = mapping["permissions"]
            if isinstance(permission_grant, dict):
                for grant in permission_grant.values():
                    if str(grant).lower() == "write":
                        findings.append(
                            Finding(
                                path,
                                1,
                                "release workflow must not grant any write permission",
                            )
                        )
            elif str(permission_grant).lower() == "write-all":
                findings.append(
                    Finding(
                        path,
                        1,
                        "release workflow must not grant any write permission",
                    )
                )
    if isinstance(permissions, dict):
        for key, grant in permissions.items():
            if str(key).lower() == "write-all" or str(grant).lower() == "write":
                findings.append(
                    Finding(
                        path,
                        1,
                        "release workflow must not grant any write permission",
                    )
                )
    for scalar in walk_strings(data):
        lowered = scalar.lower()
        if "secrets" in lowered:
            findings.append(
                Finding(path, 1, "release workflow must not reference secrets")
            )
        if "github.token" in lowered:
            findings.append(
                Finding(path, 1, "release workflow must not reference github.token")
            )
        if re.search(r"(?i)\btoken\b", scalar):
            findings.append(
                Finding(
                    path,
                    1,
                    "release workflow must not reference any token "
                    "(github.token, github['token'], secrets.*)",
                )
            )
        if re.search(r"(?i):\s*write\b", scalar):
            findings.append(
                Finding(
                    path,
                    1,
                    "release workflow must not contain any ': write' grant",
                )
            )
        if GH_RELEASE_RE.search(scalar):
            findings.append(
                Finding(path, 1, "release workflow must not invoke 'gh release'")
            )
        if PUBLISH_WORD_RE.search(scalar):
            findings.append(
                Finding(path, 1, "release workflow must not publish")
            )

    job = jobs.get("release-disabled") if isinstance(jobs, dict) else None
    if not isinstance(job, dict):
        return findings
    findings.extend(
        exact_keys(
            path,
            "release-disabled job",
            job,
            frozenset({"name", "runs-on", "steps"}),
        )
    )
    steps = job.get("steps")
    if not isinstance(steps, list) or len(steps) != 1:
        findings.append(
            Finding(path, 1, "release workflow must have exactly one step")
        )
        return findings
    step = steps[0]
    findings.extend(
        exact_keys(
            path,
            "release-disabled step",
            step,
            frozenset({"name", "shell", "run"}),
        )
    )
    if not isinstance(step, dict):
        return findings
    if step.get("shell") != "bash":
        findings.append(
            Finding(path, 1, "release workflow step shell must be exactly 'bash'")
        )
    run = step.get("run")
    if not isinstance(run, str):
        findings.append(
            Finding(path, 1, "release workflow step must run exit 1")
        )
        return findings
    if normalize_run(run) != EXPECTED_RELEASE_RUN:
        findings.append(
            Finding(
                path,
                1,
                "release workflow run body must be exactly the checked-in "
                "fail-closed echo + exit 1 body (shell wrappers, conditionals, "
                "substitutions and multi-line variants are rejected)",
            )
        )
    return findings


def _schedule_crons(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    crons: list[str] = []
    for entry in value:
        if isinstance(entry, dict) and "cron" in entry:
            crons.append(str(entry["cron"]))
    return crons


def check_nightly(path: str, data: dict[Any, Any]) -> list[Finding]:
    findings: list[Finding] = []
    on_value = data.get("on")
    if not isinstance(on_value, dict):
        findings.append(
            Finding(
                path,
                1,
                "nightly workflow 'on' must be a mapping with exactly "
                "schedule/workflow_dispatch/pull_request",
            )
        )
        on_value = {}
    else:
        findings.extend(
            exact_keys(path, "nightly 'on' trigger", on_value, EXPECTED_NIGHTLY_ON_KEYS)
        )
    if "workflow_dispatch" not in on_value:
        findings.append(
            Finding(path, 1, "nightly workflow is missing workflow_dispatch")
        )
    if _schedule_crons(on_value.get("schedule")) != ["0 20 * * *"]:
        findings.append(
            Finding(
                path,
                1,
                "nightly schedule must contain exactly one cron entry equal to "
                "'0 20 * * *'",
            )
        )
    pull_request = on_value.get("pull_request")
    if not isinstance(pull_request, dict):
        findings.append(
            Finding(path, 1, "pull_request.types is missing")
        )
    else:
        findings.extend(
            exact_keys(
                path,
                "nightly pull_request trigger",
                pull_request,
                frozenset({"types"}),
            )
        )
        types_value = pull_request.get("types")
        if not isinstance(types_value, list) or sorted(map(str, types_value)) != sorted(
            REQUIRED_PR_TYPES
        ):
            findings.append(
                Finding(
                    path,
                    1,
                    "pull_request.types must be exactly: "
                    + ", ".join(REQUIRED_PR_TYPES),
                )
            )

    concurrency = data.get("concurrency")
    if not isinstance(concurrency, dict):
        findings.append(
            Finding(path, 1, "concurrency.cancel-in-progress is missing or duplicated")
        )
    else:
        findings.extend(
            exact_keys(
                path,
                "concurrency",
                concurrency,
                frozenset({"group", "cancel-in-progress"}),
            )
        )
        if concurrency.get("group") != EXPECTED_NIGHTLY_GROUP:
            findings.append(
                Finding(
                    path,
                    1,
                    f"concurrency.group must be exactly {EXPECTED_NIGHTLY_GROUP!r}",
                )
            )
        if concurrency.get("cancel-in-progress") != EXPECTED_CANCEL:
            findings.append(
                Finding(
                    path,
                    1,
                    "concurrency.cancel-in-progress must be exactly "
                    f"{EXPECTED_CANCEL!r}",
                )
            )

    jobs = data.get("jobs")
    if not isinstance(jobs, dict):
        findings.append(Finding(path, 1, "nightly workflow must define jobs"))
        return findings
    job_names = {str(name) for name in jobs}
    if job_names != set(EXPECTED_NIGHTLY_JOBS):
        findings.append(
            Finding(
                path,
                1,
                "nightly jobs must be exactly "
                f"{sorted(EXPECTED_NIGHTLY_JOBS)} (found {sorted(job_names)})",
            )
        )
    for decoy in ("schedule", "pull_request", "concurrency"):
        if decoy in job_names:
            findings.append(
                Finding(path, 1, f"nightly workflow must not define a {decoy!r} job")
            )
    if "nightly-unit-stress" not in jobs:
        findings.append(
            Finding(path, 1, "nightly workflow is missing the nightly-unit-stress job")
        )
    else:
        stress = jobs["nightly-unit-stress"]
        if not isinstance(stress, dict):
            findings.append(Finding(path, 1, "nightly-unit-stress must be a mapping"))
        else:
            findings.extend(
                exact_keys(
                    path,
                    "nightly-unit-stress job",
                    stress,
                    EXPECTED_NIGHTLY_STRESS_KEYS,
                )
            )
            if stress.get("if") != EXPECTED_NIGHTLY_IF:
                findings.append(
                    Finding(
                        path,
                        1,
                        f"nightly-unit-stress if: must be exactly "
                        f"{EXPECTED_NIGHTLY_IF!r}",
                    )
                )
            if stress.get("timeout-minutes") != 35:
                findings.append(
                    Finding(
                        path, 1, "nightly-unit-stress timeout-minutes must be 35"
                    )
                )
            if "continue-on-error" in stress:
                findings.append(
                    Finding(
                        path,
                        1,
                        "nightly-unit-stress must not contain continue-on-error",
                    )
                )
            steps = stress.get("steps")
            matching_steps = []
            if isinstance(steps, list):
                matching_steps = [
                    step
                    for step in steps
                    if isinstance(step, dict)
                    and isinstance(step.get("run"), str)
                    and normalize_run(step["run"]) == EXPECTED_NIGHTLY_GRADLE
                ]
            if len(matching_steps) != 1:
                findings.append(
                    Finding(
                        path,
                        1,
                        "nightly-unit-stress Gradle command must be exactly "
                        f"{EXPECTED_NIGHTLY_GRADLE!r}",
                    )
                )
            else:
                step = matching_steps[0]
                findings.extend(
                    exact_keys(
                        path,
                        "nightly-unit-stress Gradle step",
                        step,
                        frozenset({"name", "env", "run"}),
                    )
                )
                if step.get("env") != {"NIGHTLY_STRESS": "1"}:
                    findings.append(
                        Finding(
                            path,
                            1,
                            "nightly-unit-stress must set NIGHTLY_STRESS=1 on "
                            "the Gradle step",
                        )
                    )

    if "instrumented-test" not in jobs:
        findings.append(
            Finding(path, 1, "nightly workflow is missing the instrumented-test job")
        )
        return findings
    instrumented = jobs["instrumented-test"]
    if not isinstance(instrumented, dict):
        findings.append(Finding(path, 1, "instrumented-test must be a mapping"))
        return findings
    findings.extend(
        exact_keys(
            path,
            "instrumented-test job",
            instrumented,
            EXPECTED_INSTRUMENTED_KEYS,
        )
    )
    if instrumented.get("if") != EXPECTED_INSTRUMENTED_IF:
        findings.append(
            Finding(
                path,
                1,
                f"instrumented-test if: must be exactly {EXPECTED_INSTRUMENTED_IF!r}",
            )
        )
    if "continue-on-error" in instrumented:
        findings.append(
            Finding(
                path,
                1,
                "instrumented-test must not contain continue-on-error",
            )
        )
    strategy = instrumented.get("strategy")
    if not isinstance(strategy, dict):
        findings.append(
            Finding(path, 1, "instrumented-test strategy is missing")
        )
        matrix = None
    else:
        findings.extend(
            exact_keys(
                path,
                "instrumented-test strategy",
                strategy,
                EXPECTED_STRATEGY_KEYS,
            )
        )
        if strategy.get("fail-fast") is not False:
            findings.append(
                Finding(
                    path,
                    1,
                    "instrumented-test strategy.fail-fast must be false",
                )
            )
        matrix = strategy.get("matrix")
    if not isinstance(matrix, dict):
        findings.append(
            Finding(path, 1, "instrumented-test strategy.matrix is missing")
        )
    else:
        findings.extend(
            exact_keys(
                path,
                "instrumented-test matrix",
                matrix,
                EXPECTED_MATRIX_KEYS,
            )
        )
        for key, expected in EXPECTED_MATRIX_VALUES.items():
            if matrix.get(key) != expected:
                findings.append(
                    Finding(
                        path,
                        1,
                        f"instrumented-test matrix {key} must be exactly "
                        f"{expected}",
                    )
                )
    steps = instrumented.get("steps")
    emulator_steps = []
    if isinstance(steps, list):
        emulator_steps = [
            step
            for step in steps
            if isinstance(step, dict)
            and isinstance(step.get("uses"), str)
            and step["uses"].startswith("reactivecircus/android-emulator-runner@")
        ]
    if len(emulator_steps) != 1:
        findings.append(
            Finding(
                path,
                1,
                "instrumented-test must use exactly one "
                "reactivecircus/android-emulator-runner step",
            )
        )
    else:
        step = emulator_steps[0]
        findings.extend(
            exact_keys(
                path,
                "instrumented-test emulator step",
                step,
                frozenset({"name", "uses", "with"}),
            )
        )
        with_value = step.get("with")
        script = with_value.get("script") if isinstance(with_value, dict) else None
        if script != EXPECTED_INSTRUMENTED_SCRIPT:
            findings.append(
                Finding(
                    path,
                    1,
                    "instrumented-test connectedAndroidTest command must be "
                    f"exactly {EXPECTED_INSTRUMENTED_SCRIPT!r}",
                )
            )
    return findings


def _check_workflow_filter(path: str, filters_text: Any) -> list[Finding]:
    findings: list[Finding] = []
    if not isinstance(filters_text, str):
        return [
            Finding(path, 1, "dorny/paths-filter with.filters must be a YAML string")
        ]
    filters, load_findings = load_yaml(path, filters_text)
    findings.extend(load_findings)
    if load_findings:
        return findings
    if not isinstance(filters, dict):
        return [
            Finding(path, 1, "paths-filter YAML must be a mapping of filter names")
        ]
    actual_keys = {str(key) for key in filters}
    expected_keys = set(EXPECTED_FILTERS)
    if actual_keys != expected_keys:
        findings.append(
            Finding(
                path,
                1,
                "paths-filter keys must be exactly "
                f"{sorted(expected_keys)} (found {sorted(actual_keys)})",
            )
        )
    for name, expected_paths in EXPECTED_FILTERS.items():
        if name not in filters:
            findings.append(
                Finding(path, 1, f"{name} filter is missing")
            )
            continue
        patterns = filters[name]
        if not isinstance(patterns, list) or not all(
            isinstance(pattern, str) for pattern in patterns
        ):
            findings.append(
                Finding(path, 1, f"{name} filter must be a list of path strings")
            )
            continue
        if any(pattern.startswith("!") for pattern in patterns):
            findings.append(
                Finding(
                    path,
                    1,
                    f"{name} filter must not contain negated patterns",
                )
            )
        if any(not pattern or pattern != pattern.strip() for pattern in patterns):
            findings.append(
                Finding(
                    path,
                    1,
                    f"{name} filter patterns must be non-empty with no leading "
                    "or trailing whitespace",
                )
            )
        if len(patterns) != len(set(patterns)):
            findings.append(
                Finding(path, 1, f"{name} filter must not contain duplicate paths")
            )
        actual_paths = set(patterns)
        missing = sorted(expected_paths - actual_paths)
        extra = sorted(actual_paths - expected_paths)
        if missing:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{name} filter is missing: " + ", ".join(missing),
                )
            )
        if extra:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{name} filter has unexpected paths: " + ", ".join(extra),
                )
            )
    return findings


def _check_core_step(
    path: str,
    job_name: str,
    steps: list[Any],
    expected_run: str,
    working_directory: str | None,
) -> list[Finding]:
    findings: list[Finding] = []
    matches = [
        step
        for step in steps
        if isinstance(step, dict)
        and isinstance(step.get("run"), str)
        and normalize_run(step["run"]) == expected_run
    ]
    if len(matches) != 1:
        return [
            Finding(
                path,
                1,
                f"{job_name} core command must appear exactly once: "
                f"{expected_run!r}",
            )
        ]
    step = matches[0]
    allowed = {"name", "run"}
    if working_directory is not None:
        allowed.add("working-directory")
    findings.extend(
        exact_keys(path, f"{job_name} core step", step, frozenset(allowed))
    )
    if working_directory is not None:
        if step.get("working-directory") != working_directory:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{job_name} core step working-directory must be exactly "
                    f"{working_directory!r}",
                )
            )
    for forbidden in ("if", "continue-on-error", "shell"):
        if forbidden in step:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{job_name} core step must not contain {forbidden}",
                )
            )
    return findings


def _check_pinned_install_step(
    path: str, job_name: str, steps: list[Any]
) -> list[Finding]:
    matches = [
        step
        for step in steps
        if isinstance(step, dict)
        and isinstance(step.get("run"), str)
        and normalize_run(step["run"]) == PYYAML_INSTALL_RUN
    ]
    if len(matches) != 1:
        return [
            Finding(
                path,
                1,
                f"{job_name} must install the pinned PyYAML exactly once",
            )
        ]
    step = matches[0]
    findings = exact_keys(
        path,
        f"{job_name} PyYAML install step",
        step,
        frozenset({"name", "env", "run"}),
    )
    if step.get("env") != {"PIP_BREAK_SYSTEM_PACKAGES": "1"}:
        findings.append(
            Finding(
                path,
                1,
                f"{job_name} PyYAML install env must be exactly "
                "PIP_BREAK_SYSTEM_PACKAGES=1",
            )
        )
    for forbidden in ("if", "continue-on-error", "shell", "working-directory"):
        if forbidden in step:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{job_name} PyYAML install step must not contain "
                    f"{forbidden}",
                )
            )
    return findings


def _check_shadow_preflight(
    path: str, job_name: str, steps: list[Any]
) -> list[Finding]:
    matches = [
        step
        for step in steps
        if isinstance(step, dict) and step.get("name") == SHADOW_PREFLIGHT_NAME
    ]
    if len(matches) != 1:
        return [
            Finding(
                path,
                1,
                f"{job_name} must contain exactly one "
                f"{SHADOW_PREFLIGHT_NAME!r} step",
            )
        ]
    step = matches[0]
    findings = exact_keys(
        path,
        f"{job_name} shadow preflight step",
        step,
        frozenset({"name", "run"}),
    )
    if step.get("run") != EXPECTED_SHADOW_RUN:
        findings.append(
            Finding(
                path,
                1,
                f"{job_name} shadow preflight run must be exactly "
                f"{EXPECTED_SHADOW_RUN!r}",
            )
        )
    for forbidden in (
        "if",
        "continue-on-error",
        "shell",
        "working-directory",
        "env",
    ):
        if forbidden in step:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{job_name} shadow preflight step must not contain "
                    f"{forbidden}",
                )
            )
    return findings


def _check_pinned_pr_job(
    path: str, job_name: str, job: dict[Any, Any]
) -> list[Finding]:
    findings: list[Finding] = []
    expected_if = EXPECTED_PR_JOB_IF[job_name]
    if expected_if is None:
        if "if" in job:
            findings.append(
                Finding(path, 1, f"{job_name} must not have a job-level if:")
            )
    elif job.get("if") != expected_if:
        findings.append(
            Finding(
                path,
                1,
                f"{job_name} job-level if must be exactly {expected_if!r}",
            )
        )
    expected_env = EXPECTED_PR_JOB_ENV[job_name]
    if expected_env is None:
        if "env" in job:
            findings.append(
                Finding(path, 1, f"{job_name} must not have a job-level env")
            )
    elif job.get("env") != expected_env:
        findings.append(
            Finding(
                path,
                1,
                f"{job_name} job-level env must be exactly {expected_env}",
            )
        )
    for forbidden in ("continue-on-error", "defaults"):
        if forbidden in job:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{job_name} must not contain job-level {forbidden}",
                )
            )
    steps = job.get("steps")
    if not isinstance(steps, list):
        return findings + [Finding(path, 1, f"{job_name} must define steps")]
    for expected_run, working_directory in EXPECTED_PR_JOB_CORE_STEPS[job_name]:
        findings.extend(
            _check_core_step(
                path, job_name, steps, expected_run, working_directory
            )
        )
    if job_name == "policy-harness":
        findings.extend(_check_pinned_install_step(path, job_name, steps))
    if job_name in {
        "unit-tests",
        "lint",
        "policy-harness",
        "build-and-policy-gate",
        "docs-guard",
    }:
        findings.extend(_check_shadow_preflight(path, job_name, steps))
    if job_name == "unit-tests":
        strategy = job.get("strategy")
        matrix = strategy.get("matrix") if isinstance(strategy, dict) else None
        if (
            not isinstance(strategy, dict)
            or set(map(str, strategy)) != {"fail-fast", "matrix"}
            or strategy.get("fail-fast") is not False
            or not isinstance(matrix, dict)
            or set(map(str, matrix)) != {"flavor"}
            or matrix.get("flavor") != ["play", "foss", "github"]
        ):
            findings.append(
                Finding(
                    path,
                    1,
                    "unit-tests strategy must pin fail-fast: false and "
                    "matrix.flavor exactly [play, foss, github]",
                )
            )
    return findings


def check_pr_check(path: str, data: dict[Any, Any]) -> list[Finding]:
    findings: list[Finding] = []
    if data.get("on") != EXPECTED_PR_CHECK_ON:
        findings.append(
            Finding(
                path,
                1,
                "pr-check.yml 'on' must be exactly pull_request/push on main "
                "(no pull_request_target, paths, paths-ignore or types)",
            )
        )
    jobs = data.get("jobs")
    if not isinstance(jobs, dict):
        return [Finding(path, 1, "pr-check.yml must define jobs")]

    expected_jobs = set(EXPECTED_PR_GATE_NEEDS) | {"pr-gate"}
    actual_jobs = {str(name) for name in jobs}
    if actual_jobs != expected_jobs:
        unexpected = sorted(actual_jobs - expected_jobs)
        missing = sorted(expected_jobs - actual_jobs)
        details = []
        if unexpected:
            details.append(f"unexpected jobs: {unexpected}")
        if missing:
            details.append(f"missing jobs: {missing}")
        findings.append(
            Finding(
                path,
                1,
                f"pr-check.yml jobs must be exactly {sorted(expected_jobs)} ({', '.join(details)})",
            )
        )

    changes = jobs.get("changes")
    if not isinstance(changes, dict):
        findings.append(Finding(path, 1, "missing changes job"))
    else:
        findings.extend(
            exact_keys(path, "changes job", changes, EXPECTED_CHANGES_KEYS)
        )
        if changes.get("permissions") != {"contents": "read"}:
            findings.append(
                Finding(
                    path,
                    1,
                    "changes.permissions must be exactly contents: read",
                )
            )
        outputs = changes.get("outputs")
        if not isinstance(outputs, dict) or outputs != EXPECTED_CHANGES_OUTPUTS:
            findings.append(
                Finding(
                    path,
                    1,
                    "changes.outputs must be exactly "
                    f"{sorted(EXPECTED_CHANGES_OUTPUTS)} mapped to "
                    "${{ steps.filter.outputs.<same> }}",
                )
            )
        steps = changes.get("steps")
        if not isinstance(steps, list) or len(steps) != 2:
            findings.append(
                Finding(
                    path,
                    1,
                    "changes must contain exactly two steps: the pinned "
                    "checkout and dorny/paths-filter",
                )
            )
            steps = steps if isinstance(steps, list) else []
        checkout_steps = [
            step
            for step in steps
            if isinstance(step, dict) and step.get("uses") == EXPECTED_CHECKOUT
        ]
        filter_steps = [
            step
            for step in steps
            if isinstance(step, dict) and step.get("id") == "filter"
        ]
        if len(checkout_steps) != 1 or len(steps) < 1 or steps[0] is not checkout_steps[0]:
            findings.append(
                Finding(
                    path,
                    1,
                    "changes first step must be the pinned checkout",
                )
            )
        else:
            checkout = checkout_steps[0]
            findings.extend(
                exact_keys(
                    path,
                    "changes checkout step",
                    checkout,
                    frozenset({"name", "uses", "with"}),
                )
            )
            if checkout.get("with") != {"fetch-depth": 0}:
                findings.append(
                    Finding(
                        path,
                        1,
                        "changes checkout step must set fetch-depth: 0 only",
                    )
                )
        if len(filter_steps) != 1 or len(steps) < 2 or steps[1] is not filter_steps[0]:
            findings.append(
                Finding(
                    path,
                    1,
                    "changes second step must be the dorny/paths-filter step "
                    "with id: filter",
                )
            )
        else:
            filter_step = filter_steps[0]
            findings.extend(
                exact_keys(
                    path,
                    "dorny/paths-filter step",
                    filter_step,
                    frozenset({"name", "id", "uses", "with"}),
                )
            )
            if filter_step.get("uses") != PATH_FILTER_USES:
                findings.append(
                    Finding(
                        path,
                        1,
                        f"dorny/paths-filter uses must be exactly "
                        f"{PATH_FILTER_USES}",
                    )
                )
            with_value = filter_step.get("with")
            findings.extend(
                exact_keys(
                    path,
                    "dorny/paths-filter with",
                    with_value,
                    frozenset({"filters"}),
                )
            )
            filters_text = (
                with_value.get("filters") if isinstance(with_value, dict) else None
            )
            findings.extend(_check_workflow_filter(path, filters_text))

    guard = jobs.get("workflow-guard")
    if not isinstance(guard, dict):
        findings.append(Finding(path, 1, "missing workflow-guard job"))
    else:
        findings.extend(
            exact_keys(path, "workflow-guard job", guard, EXPECTED_GUARD_KEYS)
        )
        if guard.get("name") != "Workflow Policy Guard":
            findings.append(
                Finding(path, 1, "workflow-guard.name must be 'Workflow Policy Guard'")
            )
        if guard.get("runs-on") != "ubuntu-latest":
            findings.append(
                Finding(path, 1, "workflow-guard.runs-on must be ubuntu-latest")
            )
        if guard.get("timeout-minutes") != 5:
            findings.append(
                Finding(path, 1, "workflow-guard.timeout-minutes must be 5")
            )
        steps = guard.get("steps")
        expected_step_count = 5
        if not isinstance(steps, list) or len(steps) != expected_step_count:
            findings.append(
                Finding(
                    path,
                    1,
                    "workflow-guard must have exactly five steps (pinned "
                    "checkout, Python shadow preflight, pinned PyYAML install, "
                    "checker and harness)",
                )
            )
            steps = steps if isinstance(steps, list) else []
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                findings.append(
                    Finding(path, 1, "workflow-guard steps must be mappings")
                )
                continue
            if "if" in step:
                findings.append(
                    Finding(path, 1, "workflow-guard steps must not contain if:")
                )
            if "continue-on-error" in step:
                findings.append(
                    Finding(
                        path,
                        1,
                        "workflow-guard steps must not contain continue-on-error",
                    )
                )
            if "shell" in step:
                findings.append(
                    Finding(path, 1, "workflow-guard steps must not contain shell:")
                )
            if "working-directory" in step:
                findings.append(
                    Finding(
                        path,
                        1,
                        "workflow-guard steps must not contain working-directory",
                    )
                )
            if index == 0:
                findings.extend(
                    exact_keys(
                        path,
                        "workflow-guard checkout step",
                        step,
                        frozenset({"name", "uses"}),
                    )
                )
                if step.get("uses") != EXPECTED_CHECKOUT:
                    findings.append(
                        Finding(
                            path,
                            1,
                            "workflow-guard checkout must use the pinned "
                            f"{EXPECTED_CHECKOUT}",
                        )
                    )
            elif index == 1:
                findings.extend(
                    _check_shadow_preflight(path, "workflow-guard", [step])
                )
                if step.get("name") != SHADOW_PREFLIGHT_NAME:
                    findings.append(
                        Finding(
                            path,
                            1,
                            f"workflow-guard shadow preflight step name must be "
                            f"{SHADOW_PREFLIGHT_NAME!r}",
                        )
                    )
            elif index == 2:
                findings.extend(
                    exact_keys(
                        path,
                        "workflow-guard PyYAML install step",
                        step,
                        frozenset({"name", "env", "run"}),
                    )
                )
                if step.get("name") != GUARD_INSTALL_NAME:
                    findings.append(
                        Finding(
                            path,
                            1,
                            f"workflow-guard install step name must be "
                            f"{GUARD_INSTALL_NAME!r}",
                        )
                    )
                if step.get("env") != {"PIP_BREAK_SYSTEM_PACKAGES": "1"}:
                    findings.append(
                        Finding(
                            path,
                            1,
                            "workflow-guard install step env must be exactly "
                            "PIP_BREAK_SYSTEM_PACKAGES=1",
                        )
                    )
                if not isinstance(step.get("run"), str) or normalize_run(
                    step["run"]
                ) != PYYAML_INSTALL_RUN:
                    findings.append(
                        Finding(
                            path,
                            1,
                            "workflow-guard PyYAML install command must be "
                            "the pinned --require-hashes requirements install",
                        )
                    )
            elif index == 3:
                findings.extend(
                    exact_keys(
                        path,
                        "workflow-guard checker step",
                        step,
                        frozenset({"name", "run"}),
                    )
                )
                if step.get("name") != GUARD_CHECK_NAME or step.get(
                    "run"
                ) != GUARD_CHECK_RUN:
                    findings.append(
                        Finding(
                            path,
                            1,
                            "workflow-guard run commands must be exactly: "
                            + " | ".join((GUARD_CHECK_RUN, GUARD_HARNESS_RUN)),
                        )
                    )
            elif index == 4:
                findings.extend(
                    exact_keys(
                        path,
                        "workflow-guard harness step",
                        step,
                        frozenset({"name", "run"}),
                    )
                )
                if step.get("name") != GUARD_HARNESS_NAME or step.get(
                    "run"
                ) != GUARD_HARNESS_RUN:
                    findings.append(
                        Finding(
                            path,
                            1,
                            "workflow-guard run commands must be exactly: "
                            + " | ".join((GUARD_CHECK_RUN, GUARD_HARNESS_RUN)),
                        )
                    )
        if not steps:
            findings.append(
                Finding(
                    path,
                    1,
                    "workflow-guard must have exactly five steps (pinned "
                    "checkout, Python shadow preflight, pinned PyYAML install, "
                    "checker and harness)",
                )
            )

    gate = jobs.get("pr-gate")
    if not isinstance(gate, dict):
        findings.append(Finding(path, 1, "missing pr-gate job"))
    else:
        findings.extend(exact_keys(path, "pr-gate job", gate, EXPECTED_PR_GATE_KEYS))
        if gate.get("name") != "PR Gate":
            findings.append(Finding(path, 1, "pr-gate.name must be 'PR Gate'"))
        if gate.get("runs-on") != "ubuntu-latest":
            findings.append(
                Finding(path, 1, "pr-gate.runs-on must be ubuntu-latest")
            )
        if gate.get("timeout-minutes") != 5:
            findings.append(Finding(path, 1, "pr-gate.timeout-minutes must be 5"))
        if gate.get("if") != "always()":
            findings.append(Finding(path, 1, "pr-gate.if must be exactly 'always()'"))
        needs = gate.get("needs")
        needs_tokens = [needs] if isinstance(needs, str) else needs
        if (
            not isinstance(needs_tokens, list)
            or len(needs_tokens) != len(set(map(str, needs_tokens)))
            or {str(token) for token in needs_tokens} != set(EXPECTED_PR_GATE_NEEDS)
        ):
            findings.append(
                Finding(
                    path,
                    1,
                    "pr-gate.needs must contain exactly the expected jobs "
                    "including the workflow-guard token",
                )
            )
        steps = gate.get("steps")
        if not isinstance(steps, list) or len(steps) != 1:
            findings.append(
                Finding(path, 1, "pr-gate must have exactly one Evaluate gate step")
            )
        else:
            step = steps[0]
            findings.extend(
                exact_keys(
                    path,
                    "pr-gate step",
                    step,
                    frozenset({"name", "env", "run"}),
                )
            )
            if isinstance(step, dict):
                for forbidden in ("if", "continue-on-error", "shell", "working-directory"):
                    if forbidden in step:
                        findings.append(
                            Finding(
                                path,
                                1,
                                f"pr-gate step must not contain {forbidden}",
                            )
                        )
                if step.get("name") != "Evaluate gate":
                    findings.append(
                        Finding(path, 1, "pr-gate step name must be 'Evaluate gate'")
                    )
                if step.get("env") != EXPECTED_PR_GATE_ENV:
                    findings.append(
                        Finding(
                            path,
                            1,
                            "pr-gate env must be exactly the pinned 11 result/output "
                            "values",
                        )
                    )
                    env_value = step.get("env")
                    if isinstance(env_value, dict):
                        if env_value.get("WORKFLOW") != (
                            "${{ needs.workflow-guard.result }}"
                        ):
                            findings.append(
                                Finding(
                                    path,
                                    1,
                                    "pr-gate WORKFLOW must be exactly "
                                    "${{ needs.workflow-guard.result }}",
                                )
                            )
                        if "WORKFLOW_PATHS" in env_value:
                            findings.append(
                                Finding(
                                    path,
                                    1,
                                    "pr-gate must not carry a WORKFLOW_PATHS output "
                                    "(workflow-guard is unconditional)",
                                )
                            )
                run = step.get("run")
                if (
                    not isinstance(run, str)
                    or run_sha256(run) != EXPECTED_PR_GATE_RUN_SHA256
                ):
                    findings.append(
                        Finding(
                            path,
                            1,
                            "pr-gate run body must match the pinned gate script "
                            "(early exits, conditionals and step wrappers are "
                            "rejected)",
                        )
                    )
                if isinstance(run, str):
                    if '[ "$WORKFLOW" != "success" ]' not in run:
                        findings.append(
                            Finding(
                                path,
                                1,
                                "pr-gate run body must fail unless WORKFLOW is "
                                "exactly success (no skipped acceptance)",
                            )
                        )
                    if "WORKFLOW_PATHS" in run:
                        findings.append(
                            Finding(
                                path,
                                1,
                                "pr-gate run body must not accept a skipped "
                                "workflow-guard via WORKFLOW_PATHS",
                            )
                        )

    for job_name in EXPECTED_PR_JOB_IF:
        job = jobs.get(job_name)
        if not isinstance(job, dict):
            findings.append(Finding(path, 1, f"missing {job_name} job"))
            continue
        findings.extend(_check_pinned_pr_job(path, job_name, job))
    for protected in ("changes", "workflow-guard", "pr-gate"):
        protected_job = jobs.get(protected)
        if isinstance(protected_job, dict) and find_key_mappings(
            protected_job, "continue-on-error"
        ):
            findings.append(
                Finding(
                    path,
                    1,
                    f"{protected} must not contain continue-on-error",
                )
            )
    return findings


def _require_action_step(
    path: str,
    label: str,
    job: dict[Any, Any],
    pattern: re.Pattern[str],
) -> tuple[list[Finding], dict[Any, Any] | None]:
    steps = job.get("steps")
    if not isinstance(steps, list):
        return [Finding(path, 1, f"{label} must define steps")], None
    matches = [
        step
        for step in steps
        if isinstance(step, dict)
        and isinstance(step.get("uses"), str)
        and pattern.match(step["uses"])
    ]
    if len(matches) != 1:
        return (
            [
                Finding(
                    path,
                    1,
                    f"{label} must use exactly one pinned action matching "
                    f"{pattern.pattern}",
                )
            ],
            None,
        )
    return [], matches[0]


def _check_no_job_or_step_conditions(
    path: str, label: str, job: dict[Any, Any]
) -> list[Finding]:
    findings: list[Finding] = []
    if "if" in job:
        findings.append(
            Finding(path, 1, f"{label} job must not contain if:")
        )
    if "continue-on-error" in job:
        findings.append(
            Finding(
                path,
                1,
                f"{label} job must not contain continue-on-error",
            )
        )
    steps = job.get("steps")
    if isinstance(steps, list):
        for step in steps:
            if not isinstance(step, dict):
                continue
            if "if" in step:
                findings.append(
                    Finding(path, 1, f"{label} steps must not contain if:")
                )
            if "continue-on-error" in step:
                findings.append(
                    Finding(
                        path,
                        1,
                        f"{label} steps must not contain continue-on-error",
                    )
                )
    return findings


def check_security_audit(path: str, data: dict[Any, Any]) -> list[Finding]:
    findings: list[Finding] = []
    if data.get("on") != EXPECTED_SECURITY_ON:
        findings.append(
            Finding(
                path,
                1,
                "security-audit.yml 'on' must be exactly push/pull_request on "
                "main, the Monday 12:00 schedule and workflow_dispatch",
            )
        )
    jobs = data.get("jobs")
    if not isinstance(jobs, dict):
        return findings + [
            Finding(path, 1, "security-audit.yml must define jobs")
        ]
    job_names = {str(name) for name in jobs}
    if job_names != set(EXPECTED_SECURITY_JOBS):
        findings.append(
            Finding(
                path,
                1,
                "security-audit jobs must be exactly "
                f"{sorted(EXPECTED_SECURITY_JOBS)} (found {sorted(job_names)})",
            )
        )
    for job_name, label, pattern, expected_keys, expected_env, expected_with in (
        (
            "gitleaks",
            "security-audit gitleaks",
            GITLEAKS_ACTION_RE,
            frozenset({"name", "uses", "env"}),
            EXPECTED_GITLEAKS_STEP_ENV,
            None,
        ),
        (
            "dependency-review",
            "security-audit dependency-review",
            DEPENDENCY_REVIEW_ACTION_RE,
            frozenset({"name", "uses"}),
            None,
            None,
        ),
    ):
        job = jobs.get(job_name)
        if not isinstance(job, dict):
            findings.append(
                Finding(path, 1, f"security-audit is missing {job_name}")
            )
            continue
        if job_name == "gitleaks":
            # dependency-review's job-level if is pinned separately below.
            findings.extend(_check_no_job_or_step_conditions(path, label, job))
        action_findings, step = _require_action_step(path, label, job, pattern)
        findings.extend(action_findings)
        if step is None:
            continue
        findings.extend(
            exact_keys(path, f"{label} action step", step, expected_keys)
        )
        if step.get("env") != expected_env:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{label} action step env must be exactly "
                    f"{expected_env!r}",
                )
            )
        if step.get("with") != expected_with:
            findings.append(
                Finding(
                    path,
                    1,
                    f"{label} action step with must be exactly "
                    f"{expected_with!r}",
                )
            )
    dependency_review = jobs.get("dependency-review")
    if isinstance(dependency_review, dict):
        if "continue-on-error" in dependency_review:
            findings.append(
                Finding(path, 1, "security-audit dependency-review job must not contain continue-on-error")
            )
        if dependency_review.get("if") != EXPECTED_DEPENDENCY_REVIEW_IF:
            findings.append(
                Finding(
                    path,
                    1,
                    f"security-audit dependency-review if must be exactly {EXPECTED_DEPENDENCY_REVIEW_IF!r}",
                )
            )
    return findings


def check_codeql(path: str, data: dict[Any, Any]) -> list[Finding]:
    findings: list[Finding] = []
    on_value = data.get("on")
    if on_value != EXPECTED_CODEQL_ON:
        findings.append(
            Finding(
                path,
                1,
                "codeql.yml 'on' must be exactly push/pull_request on main with "
                "the reviewed paths-ignore list, the Monday 12:00 schedule and "
                "workflow_dispatch",
            )
        )
    if isinstance(on_value, dict):
        for trigger in ("push", "pull_request"):
            trigger_value = on_value.get(trigger)
            paths_ignore = (
                trigger_value.get("paths-ignore")
                if isinstance(trigger_value, dict)
                else None
            )
            if not isinstance(paths_ignore, list):
                continue
            for pattern in paths_ignore:
                if not isinstance(pattern, str) or pattern not in (
                    EXPECTED_CODEQL_PATHS_IGNORE
                ):
                    findings.append(
                        Finding(
                            path,
                            1,
                            f"codeql {trigger} paths-ignore contains an "
                            f"unreviewed pattern {pattern!r}",
                        )
                    )
    jobs = data.get("jobs")
    if not isinstance(jobs, dict):
        return findings + [Finding(path, 1, "codeql.yml must define jobs")]
    job_names = {str(name) for name in jobs}
    if job_names != set(EXPECTED_CODEQL_JOBS):
        findings.append(
            Finding(
                path,
                1,
                "codeql jobs must be exactly "
                f"{sorted(EXPECTED_CODEQL_JOBS)} (found {sorted(job_names)})",
            )
        )
    job = jobs.get("codeql")
    if not isinstance(job, dict):
        findings.append(Finding(path, 1, "codeql.yml is missing the codeql job"))
        return findings
    findings.extend(_check_no_job_or_step_conditions(path, "codeql", job))
    for label, pattern in (
        ("codeql init", CODEQL_INIT_ACTION_RE),
        ("codeql analyze", CODEQL_ANALYZE_ACTION_RE),
    ):
        action_findings, _step = _require_action_step(path, label, job, pattern)
        findings.extend(action_findings)
    return findings


def check_pocketguard(path: str, data: dict[Any, Any]) -> list[Finding]:
    findings: list[Finding] = []
    if data.get("on") != EXPECTED_POCKETGUARD_ON:
        findings.append(
            Finding(
                path,
                1,
                "pocketguard.yml 'on' must be exactly pull_request_target "
                "(opened/reopened/synchronize), issues "
                "(opened/edited/reopened) and issue_comment (created/edited)",
            )
        )
    jobs = data.get("jobs")
    if not isinstance(jobs, dict):
        return findings + [
            Finding(path, 1, "pocketguard.yml must define jobs")
        ]
    job_names = {str(name) for name in jobs}
    if job_names != set(EXPECTED_POCKETGUARD_JOBS):
        findings.append(
            Finding(
                path,
                1,
                "pocketguard jobs must be exactly "
                f"{sorted(EXPECTED_POCKETGUARD_JOBS)} "
                f"(found {sorted(job_names)})",
            )
        )
    for job_name, expected_if in EXPECTED_POCKETGUARD_IF.items():
        job = jobs.get(job_name)
        if not isinstance(job, dict):
            continue
        if "continue-on-error" in job:
            findings.append(
                Finding(
                    path,
                    1,
                    f"pocketguard {job_name} job must not contain "
                    "continue-on-error",
                )
            )
        if expected_if is None:
            if "if" in job:
                findings.append(
                    Finding(
                        path,
                        1,
                        f"pocketguard {job_name} job must not have a job-level if:",
                    )
                )
        elif job.get("if") != expected_if:
            findings.append(
                Finding(
                    path,
                    1,
                    f"pocketguard {job_name} job-level if must be exactly "
                    f"{expected_if!r}",
                )
            )
    publish = jobs.get("publish")
    if not isinstance(publish, dict):
        findings.append(Finding(path, 1, "pocketguard is missing the publish job"))
    else:
        publish_steps = publish.get("steps")
        if not isinstance(publish_steps, list) or not publish_steps:
            findings.append(
                Finding(
                    path,
                    1,
                    "pocketguard publish job must have at least one step",
                )
            )
    review_send = jobs.get("review-send")
    if not isinstance(review_send, dict):
        findings.append(
            Finding(path, 1, "pocketguard is missing the review-send job")
        )
    else:
        steps = review_send.get("steps")
        bot_runs = [
            step.get("run")
            for step in steps
            if isinstance(step, dict)
            and isinstance(step.get("run"), str)
            and BOT_RUN_RE.search(step["run"])
            and not DECOY_RUN_RE.match(step["run"])
        ] if isinstance(steps, list) else []
        if not bot_runs:
            findings.append(
                Finding(
                    path,
                    1,
                    "pocketguard review-send must run the bot via a node/npm "
                    "command or a local script",
                )
            )
    return findings


def check_action_file(
    root: Path,
    action_file: Path,
    visited: set[Path],
    findings: list[Finding],
) -> None:
    resolved = action_file.resolve()
    if resolved in visited:
        return
    visited.add(resolved)
    try:
        relative = resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        relative = resolved.as_posix()
        findings.append(
            Finding(
                relative,
                1,
                "action metadata resolves outside the repository",
            )
        )
        return
    try:
        text = resolved.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        findings.append(Finding(relative, 1, f"cannot read action metadata: {error}"))
        return
    data, load_findings = load_yaml(relative, text)
    findings.extend(load_findings)
    if load_findings:
        return
    if not isinstance(data, dict):
        findings.append(
            Finding(relative, 1, "action metadata root must be a mapping")
        )
        return
    findings.extend(check_permissions(relative, data))
    findings.extend(check_non_release_publisher(relative, data, is_release=False))
    uses_findings, local_actions = check_uses(
        relative, data, root, pull_request_target=False
    )
    findings.extend(uses_findings)
    for local_action in local_actions:
        check_action_file(root, local_action, visited, findings)


def check_workflow(
    root: Path,
    workflow: Path,
    visited_actions: set[Path],
) -> list[Finding]:
    relative = workflow.relative_to(root).as_posix()
    findings: list[Finding] = []
    if workflow.name not in ALLOWED_WORKFLOW_FILES:
        findings.append(
            Finding(
                relative,
                1,
                "workflow file is not in the reviewed allowlist "
                f"({', '.join(sorted(ALLOWED_WORKFLOW_FILES))})",
            )
        )
    try:
        text = workflow.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        return [Finding(relative, 1, f"cannot read workflow: {error}")]
    data, load_findings = load_yaml(relative, text)
    findings.extend(load_findings)
    if load_findings:
        return findings
    if not isinstance(data, dict):
        findings.append(Finding(relative, 1, "workflow root must be a YAML mapping"))
        return findings
    if True in data:
        if "on" in data:
            findings.append(
                Finding(
                    relative,
                    1,
                    "workflow contains both 'on' and YAML-1.1 boolean on keys",
                )
            )
        else:
            data["on"] = data.pop(True)
    findings.extend(check_defaults_and_env(relative, data))
    allow_pr_write = workflow.name == "pocketguard.yml"
    findings.extend(check_permissions(relative, data, allow_pr_write=allow_pr_write))
    is_release = workflow.name == "release.yml"
    findings.extend(check_non_release_publisher(relative, data, is_release))
    pull_request_target = "pull_request_target" in trigger_names(data.get("on"))
    uses_findings, local_actions = check_uses(
        relative, data, root, pull_request_target
    )
    findings.extend(uses_findings)
    findings.extend(check_pull_request_target(relative, data, text))
    if is_release:
        findings.extend(check_release(relative, data))
    if workflow.name == "nightly-instrumented.yml":
        findings.extend(check_nightly(relative, data))
    if workflow.name == "pr-check.yml":
        findings.extend(check_pr_check(relative, data))
    if workflow.name == "security-audit.yml":
        findings.extend(check_security_audit(relative, data))
    if workflow.name == "codeql.yml":
        findings.extend(check_codeql(relative, data))
    if workflow.name == "pocketguard.yml":
        findings.extend(check_pocketguard(relative, data))
    for local_action in local_actions:
        check_action_file(root, local_action, visited_actions, findings)
    return findings


EXPECTED_REQUIREMENTS_SHA256 = (
    "b7078b93108cf8befcc2286b3adea4bc4042ebe9795c7b7dc69a6b7d9b258762"
)
SHADOW_SUFFIXES = (".py", ".pyc", ".pyd", ".so")


def _shadow_module_name(name: str) -> str | None:
    for suffix in SHADOW_SUFFIXES:
        if name.endswith(suffix):
            return name[: -len(suffix)].split(".", 1)[0]
    return None


FORBIDDEN_PYTHON_MODULE_NAMES = frozenset(
    {
        "yaml",
        "_yaml",
        "sitecustomize",
        "usercustomize",
        "pip",
        "wheel",
        "setuptools",
    }
)


def check_python_shadow_modules(root: Path) -> list[Finding]:
    """Reject repo-local modules that would shadow stdlib/PyYAML imports.

    Only the directories that become ``sys.path`` roots for the CI commands
    are scanned at top level: the repo root (``python3 -m unittest`` with
    ``PYTHONPATH=$PWD``) and ``scripts/`` (``python3 scripts/...``). The set
    is the running interpreter's stdlib names plus PyYAML/site hooks; ``.py``,
    ``.pyc``, ``.pyd`` and ``.so`` variants and package directories are
    rejected. A checked-in ``.pth`` is never legitimate here.
    """
    findings: list[Finding] = []
    forbidden = set(sys.stdlib_module_names) | FORBIDDEN_PYTHON_MODULE_NAMES
    for import_root in (root, root / "scripts"):
        if not import_root.is_dir():
            continue
        for entry in sorted(import_root.iterdir()):
            name = entry.name
            module_name = name if entry.is_dir() else _shadow_module_name(name)
            if module_name is not None and module_name in forbidden:
                findings.append(
                    Finding(
                        entry.relative_to(root).as_posix(),
                        1,
                        "forbidden Python import shadow (would shadow stdlib/"
                        "PyYAML/unittest imports)",
                    )
                )
    for dirpath, dirnames, filenames in os.walk(root):
        current = Path(dirpath)
        dirnames[:] = sorted(
            name
            for name in dirnames
            if name != ".git" and not (current / name).is_symlink()
        )
        for name in filenames:
            if name.endswith(".pth"):
                findings.append(
                    Finding(
                        (current / name).relative_to(root).as_posix(),
                        1,
                        "forbidden .pth file (would execute during interpreter "
                        "startup)",
                    )
                )
    requirements = root / "scripts" / "requirements-docs.txt"
    if not requirements.is_file():
        findings.append(
            Finding("scripts/requirements-docs.txt", 1, "missing dependency manifest")
        )
    else:
        try:
            actual = hashlib.sha256(requirements.read_bytes()).hexdigest()
            if actual != EXPECTED_REQUIREMENTS_SHA256:
                findings.append(
                    Finding(
                        "scripts/requirements-docs.txt",
                        1,
                        f"requirements bytes {actual} do not match pinned {EXPECTED_REQUIREMENTS_SHA256}",
                    )
                )
        except OSError as err:
            findings.append(
                Finding(
                    "scripts/requirements-docs.txt",
                    1,
                    f"cannot read dependency manifest: {err}",
                )
            )
    return sorted(findings, key=lambda finding: finding.path)


def check_workflow_hashes(workflows_dir: Path, files: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    for workflow in files:
        expected = EXPECTED_WORKFLOW_SHA256.get(workflow.name)
        if expected is None:
            continue
        try:
            actual = hashlib.sha256(workflow.read_bytes()).hexdigest()
        except OSError as error:
            findings.append(
                Finding(
                    workflow.relative_to(workflows_dir.parent.parent).as_posix(),
                    1,
                    f"cannot read workflow bytes for SHA-256 pin: {error}",
                )
            )
            continue
        if actual != expected:
            findings.append(
                Finding(
                    workflow.relative_to(workflows_dir.parent.parent).as_posix(),
                    1,
                    "workflow bytes do not match the reviewed SHA-256 pin "
                    f"(expected {expected}, got {actual}); update the pin only "
                    "in the same reviewed PR as the intentional workflow edit",
                )
            )
    return findings


def check_repo(root: Path, enforce_hashes: bool = True) -> list[Finding]:
    """Check workflows/actions; enforce raw-byte workflow pins by default.

    ``enforce_hashes=False`` is for synthetic semantic fixtures that mutate a
    workflow copy on purpose. Published workflows must keep the default. The
    hash pin is a review regression guard, not tamper-proof: updating
    ``EXPECTED_WORKFLOW_SHA256`` is an explicit reviewed change.
    """
    findings: list[Finding] = []
    findings.extend(check_python_shadow_modules(root))
    workflows_dir = root / ".github" / "workflows"
    files = sorted([*workflows_dir.glob("*.yml"), *workflows_dir.glob("*.yaml")])
    if not files:
        return findings + [
            Finding(".github/workflows", 1, "no workflow files found")
        ]
    if enforce_hashes:
        findings.extend(check_workflow_hashes(workflows_dir, files))

    visited_actions: set[Path] = set()
    for workflow in files:
        findings.extend(check_workflow(root, workflow, visited_actions))

    actions_dir = root / ".github" / "actions"
    if actions_dir.is_dir():
        action_files = sorted(
            [*actions_dir.rglob("action.yml"), *actions_dir.rglob("action.yaml")]
        )
        for action_file in action_files:
            check_action_file(root, action_file, visited_actions, findings)

    for required in sorted(ALLOWED_WORKFLOW_FILES):
        if not (workflows_dir / required).is_file():
            findings.append(
                Finding(
                    f".github/workflows/{required}",
                    1,
                    "required workflow is missing",
                )
            )
    return findings


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args and args[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if yaml is None:
        message = (
            "FAIL: PyYAML is required for structural workflow checks; install "
            "the pinned dependency with python3 -m pip install --require-hashes "
            "-r scripts/requirements-docs.txt"
        )
        print(message, file=sys.stderr)
        return 2
    root = Path(args[0] if args else ".").resolve()
    findings = check_repo(root)
    if findings:
        for finding in findings:
            print(finding.render(), file=sys.stderr)
        print(
            f"FAIL: {len(findings)} workflow policy finding(s)",
            file=sys.stderr,
        )
        return 1
    print("PASS: workflow policy checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
