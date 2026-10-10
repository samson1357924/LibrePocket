# Testing and Verification

**Status:** Current test inventory and repeatable command guide.

- **Scope:** Repository CI, local JVM/Robolectric tests, static policy gates, and separately scheduled Android device tests.
- **Owner role:** CI/build maintainer; no individual is assigned here.
- **Source of truth:** `.github/workflows/pr-check.yml`, `.github/workflows/codeql.yml`, `.github/workflows/security-audit.yml`, `scripts/docs_claim_check.sh`, `scripts/apk_policy_inspect.py`, `scripts/tests/test_apk_policy_check.py`, test source sets, Gradle wrapper/catalog, and the command results from the exact commit being assessed.
- **Update trigger:** CI, test source-set, Android SDK/JDK, policy-script, or merge-gate changes.

## Current CI scope

`pr-check.yml` is path-gated. A `changes` job classifies each pull request
with `dorny/paths-filter` into `code` (`app/src/**`,
`app/build.gradle.kts`, `app/lint.xml`, `gradle/**`,
`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
`.github/workflows/pr-check.yml`) and `policy`
(`scripts/play_policy_check.sh`, `scripts/apk_policy_inspect.py`,
`scripts/tests/test_apk_policy_check.py`,
`.github/workflows/pr-check.yml`) outputs. `docs-guard` always runs the
static grep guard `scripts/docs_claim_check.sh` (no Gradle, no emulator);
`pr-gate` (`always()`, needs all prior jobs) resolves a skipped Gradle
stage as pass only when the corresponding `changes` output is explicitly
`false`, and fails closed otherwise.

When `code == true`, the workflow uses JDK 17 and runs these Gradle stages:

1. `:app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest` (including Robolectric tests available in those source sets). The unit-tests job fans out over a flavor matrix with fail-fast disabled, so each flavor runs on its own runner and wall time is the slowest shard rather than the sum. Test report artifacts now include both HTML reports and structured JUnit XML (`app/build/test-results/`), retained for 14 days; results are parsed in real time into GitHub Actions Step Summary (`$GITHUB_STEP_SUMMARY`) to surface failure counts, skipped tests, and assertion summaries directly without requiring artifact downloads.
2. `:app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug`.
3. `:app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug`, then `scripts/play_policy_check.sh` for Play and `scripts/play_policy_check.sh --foss` for Foss. This stage also runs when only `policy == true`.

What runs by edit type:

- Docs-only (neither `code` nor `policy`, e.g. `docs/**` or `*.md` edits): unit tests, lint, and build/policy are skipped; `docs-guard` and `pr-gate` still run.
- Policy-script-only (any `policy` path edit, `code == false`): build/policy and the stdlib Python policy harness run; unit tests and lint are skipped and resolved as pass by `pr-gate`. A scanner-only diff (`scripts/apk_policy_inspect.py` or `scripts/tests/test_apk_policy_check.py`) therefore cannot pass with all policy validation skipped.
- Workflow-only edit to `pr-check.yml`: counts as both `code` and `policy`, so all Gradle stages plus the Python policy harness run. The harness pin tests therefore execute exactly when the wiring they validate changes.
- Mixed docs + code/policy edits: full Gradle stages plus `docs-guard` run.

Static analysis lives outside `pr-check.yml`: CodeQL runs in
`.github/workflows/codeql.yml`, which skips docs-only push/PR events via
`paths-ignore` (`docs/**`, `**/*.md`, `LICENSE*`, `TRADEMARKS.md`,
`NOTICE*`) while the weekly cron and `workflow_dispatch` runs stay
unfiltered. Secret scanning (`gitleaks`) and `dependency-review` in
`.github/workflows/security-audit.yml` still run on every pull request,
including docs-only ones.

No branch-protection required checks are configured on `main`; `pr-gate`
and the checks above are informational until protection is configured.

The workflows do **not** run an Android emulator/device matrix on every pull request. The Python APK policy and release-identity harnesses (standard library only) run in CI as the `policy-harness` job whenever `policy == true`, and `pr-gate` requires its success in that case. The command is `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_apk_policy_check.py scripts/tests/test_release_artifact_verifier.py scripts/tests/test_build_release_identity.py`. These tests use synthetic APK-like files and fake Gradle/SDK/policy commands; they do not prove real APK or SDK behavior. A green unit/lint/policy workflow is not proof of runtime safety, provider compatibility, signing identity, or physical-device behavior.

## Local commands

Use one Gradle execution at a time on constrained machines. The repo's maintenance guidance requires `--max-workers=1 --no-daemon`; do not run a parallel flavor matrix after an OOM.

```sh
# Synthetic APK/DEX policy and final-artifact identity harnesses (Python stdlib only)
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  scripts/tests/test_apk_policy_check.py \
  scripts/tests/test_release_artifact_verifier.py \
  scripts/tests/test_build_release_identity.py

# Flavor unit/Robolectric tests
./gradlew :app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest --max-workers=1 --no-daemon

# Flavor lint
./gradlew :app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug --max-workers=1 --no-daemon

# Debug assembly
./gradlew :app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug --max-workers=1 --no-daemon

# APK-only artifact policy checks after successful assembly; AAB is unsupported
# and rejected before credentials/build by the local release builder.
scripts/play_policy_check.sh app/build/outputs/apk/play/debug/app-play-debug.apk
scripts/play_policy_check.sh --foss app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

The local APK release integration is `scripts/build_release.sh`; it requires `--apk`, explicit per-flavor trusted identity values, explicit SDK parser paths, and an existing output parent before it reaches credential lookup or build. It remains a local staging/verification path only, not a publishing workflow or release-readiness claim.

The synthetic Python fixtures and fake `aapt`/`dexdump`/`apksigner`/Gradle/policy commands test bounded parser, policy, identity, transaction, failure-cleanup, and process-cancellation behavior; they do not represent a real APK or prove Android SDK native-tool compatibility. A release/build maintainer should run the APK policy/native-parser gates against fresh debug APKs for all three flavors and record their paths, SHA-256 values, SDK build-tools version, command output, and tested commit. The separate signed-release verifier requires fresh controlled test fixtures/native-tool evidence and explicit trusted identity values; its synthetic harness alone does not establish that validation. Do not infer a pass from historical APK inventory. These are instructions, not a claim that they were run for this documentation change. Version sources are listed in [Build Environment](ENV.md).

The shared setup tests include local synthetic MockWebServer coverage for the model-directory transport and outcome mapping: keyless requests, declared-length and chunked wire-body limits, UTF-8 decoded-byte limits, gzip expansion, truncation, non-2xx/redirect handling, unsupported encoding, empty bodies, total timeout, and prompt caller cancellation. They also cover remote-versus-bundled setup status and stale refresh isolation. These cases do not contact models.dev; test source presence alone is not evidence that a test task passed.

## Android instrumented tests

Instrumented tests are a separate device-dependent tier. The existing [P1 androidTest runbook](specs/P1_ANDROIDTEST_RUNBOOK.md) lists test classes and sample commands, but its device matrix and prior status notes are not current CI evidence. Before treating a device matrix as a merge/release gate, maintainers must name the required API/flavor/device coverage, run it, and retain results tied to the tested SHA. Do not report a device pass when no device was used.

## Security-sensitive test expectations

For changes involving credentials, policy, persistence, session lifecycle, cancellation, or tools:

- Add a regression test that fails before the fix and passes after it, plus a negative control when applicable.
- Use fake keys, temporary databases/files, synthetic transcript text, and local fake HTTP servers only. Do not use live credentials, production signing keys, paid endpoints, or private transcripts.
- Test fail-closed behavior and stale/late callbacks, not only the successful path.
- Keep tool states distinct: declared, implemented, wired, and verified. A test-only fake executor does not verify production wiring.
- Test execution contract: all asynchronous and coroutine waits must enforce a bounded timeout; teardown procedures, socket closures, and synthetic HTTP server (such as MockWebServer) requests must never block indefinitely.

## Reporting evidence

Record the base SHA, exact commands, exit status, relevant test/report artifact, JDK/SDK/API/device where applicable, and checks not run. A planned command, an old runbook checkbox, or a successful build alone is not a security conclusion.

Flaky test resolution and debugging principles:

- Do not rely on silent retries to mask regressions or timing bugs.
- When test failures occur, investigate and capture forensic evidence via the Step Summary and console FULL stack trace (`--stacktrace`) captured in the workflow logs.
- Differential triage relies on the 14-day retention of test report artifacts (HTML reports and structured JUnit XML under `app/build/test-results/`) across attempts and runs.
