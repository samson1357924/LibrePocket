# Testing and Verification

**Status:** Current test inventory and repeatable command guide.

- **Scope:** Repository CI, local JVM/Robolectric tests, static policy gates, and separately scheduled Android device tests.
- **Owner role:** CI/build maintainer; no individual is assigned here.
- **Source of truth:** `.github/workflows/pr-check.yml`, `.github/workflows/codeql.yml`, `.github/workflows/security-audit.yml`, `scripts/docs_claim_check.sh`, `scripts/apk_policy_inspect.py`, `scripts/summarize_junit.py`, `scripts/tests/test_apk_policy_check.py`, `scripts/tests/test_summarize_junit.py`, test source sets, Gradle wrapper/catalog, and the command results from the exact commit being assessed.
- **Update trigger:** CI, test source-set, Android SDK/JDK, policy-script, or merge-gate changes.

## Current CI scope

`pr-check.yml` is path-gated. A `changes` job classifies each pull request
with `dorny/paths-filter` into `code` (`app/src/**`,
`app/build.gradle.kts`, `app/lint.xml`, `gradle/**`,
`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
`.github/workflows/pr-check.yml`) and `policy`
(`scripts/play_policy_check.sh`, `scripts/apk_policy_inspect.py`,
`scripts/summarize_junit.py`,
`scripts/tests/test_apk_policy_check.py`,
`scripts/tests/test_summarize_junit.py`,
`.github/workflows/pr-check.yml`) outputs. `docs-guard` always runs the
static grep guard `scripts/docs_claim_check.sh` (no Gradle, no emulator);
`pr-gate` (`always()`, needs all prior jobs) resolves a skipped Gradle
stage as pass only when the corresponding `changes` output is explicitly
`false`, and fails closed otherwise.

When `code == true`, the workflow uses JDK 17 and runs these Gradle stages:

1. `:app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest` (including Robolectric tests available in those source sets). The unit-tests job fans out over a flavor matrix with fail-fast disabled, so each flavor runs on its own runner and wall time is the slowest shard rather than the sum. Test report artifacts now include both HTML reports and structured JUnit XML (`app/build/test-results/`), retained for 14 days; results are parsed in real time into GitHub Actions Step Summary (`$GITHUB_STEP_SUMMARY`) to surface failure counts, skipped tests, and assertion summaries directly without requiring artifact downloads. The parser (`scripts/summarize_junit.py`, covered by `scripts/tests/test_summarize_junit.py`) is reporting-only and never a gate: it emits an explicit `Status:` line that reads `PASS` only when every XML file is readable, `tests > 0`, `failures == 0`, `errors == 0`, and every suite's counters exactly equal its direct `<testcase>` detail (`tests` vs case count, `failures+errors` vs failed cases, `skipped` vs skipped cases, either direction) — otherwise `FAIL`, `PARTIAL` (unreadable files), `INCOMPLETE` (empty or contradictory totals, including any per-suite mismatch), or `NO_RESULTS` (no XML). Only outermost suites are counted (a `<testsuite>` root counts itself; a `<testsuites>` root counts its direct `<testsuite>` children; nested suites are ignored for counting with a warning and force `INCOMPLETE`, since outer counters alone cannot prove completeness; Gradle never emits nested suites, so flat real-world results are unaffected). Rendered output is capped at 900 KiB of UTF-8 bytes (idents, messages, every warning, and the flavor title each truncated to 300 characters); overflow drops tail entries and records `omitted N failures / M skips; see XML artifact` (plus `omitted K warning(s); see XML artifact` when warnings themselves overflow), never raising.
2. `:app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug`.
3. `:app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug`, then `scripts/play_policy_check.sh` for Play and `scripts/play_policy_check.sh --foss` for Foss. This stage also runs when only `policy == true`.

Robolectric SDK pin (Current): JVM unit tests run with `sdk=33` pinned in
`app/src/test/resources/robolectric.properties`, while `targetSdk` is 36
(see [Build Environment](ENV.md)). A green run on API 33 does **not** imply
API 34–36 behavior is verified; version-sensitive Android API behavior needs
a separate higher-API smoke pass (Proposed, not a current CI gate). The pin
itself and any `@Config` SDK matrix expansion are out of scope for the
current deflake work — do not change them here.

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

The workflows do **not** run an Android emulator/device matrix on every pull request. The Python APK policy and release-identity harnesses (standard library only) run in CI as the `policy-harness` job whenever `policy == true`, and `pr-gate` requires its success in that case. The command is `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_apk_policy_check.py scripts/tests/test_release_artifact_verifier.py scripts/tests/test_build_release_identity.py scripts/tests/test_summarize_junit.py`. These tests use synthetic APK-like files and fake Gradle/SDK/policy commands; they do not prove real APK or SDK behavior. A green unit/lint/policy workflow is not proof of runtime safety, provider compatibility, signing identity, or physical-device behavior.

## Local commands

Use one Gradle execution at a time on constrained machines. The repo's maintenance guidance requires `--max-workers=1 --no-daemon`; do not run a parallel flavor matrix after an OOM.

```sh
# Synthetic APK/DEX policy and final-artifact identity harnesses (Python stdlib only)
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  scripts/tests/test_apk_policy_check.py \
  scripts/tests/test_release_artifact_verifier.py \
  scripts/tests/test_build_release_identity.py \
  scripts/tests/test_summarize_junit.py

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

Current scheduler/matrix truth (`.github/workflows/nightly-instrumented.yml`):
the workflow runs on its nightly schedule and `workflow_dispatch`; on
pull requests (types `labeled`, `synchronize`, `opened`, `reopened`) the
`instrumented-test` job executes only when the PR carries the
`run-instrumented` label (the job `if:` requires that label for PR
events, so an unlabeled PR shows as Skipped/neutral — not a pass — and
does not enter `pr-gate` merge evaluation). The emulator matrix is API
levels 33 and 34 only, across the Play/Foss/Github flavors — while
`minSdk` is 33, `compileSdk` is 37 and `targetSdk` is 36
(`app/build.gradle.kts`), so there is currently no API 35, 36, or 37
emulator coverage in CI. This paragraph states Current coverage only;
any matrix expansion is Proposed until it is wired and run.

Instrumented tests are a separate device-dependent tier. The existing [P1 androidTest runbook](specs/P1_ANDROIDTEST_RUNBOOK.md) lists test classes and sample commands, but its device matrix and prior status notes are not current CI evidence. Before treating a device matrix as a merge/release gate, maintainers must name the required API/flavor/device coverage, run it, and retain results tied to the tested SHA. Do not report a device pass when no device was used.

## Security-sensitive test expectations

For changes involving credentials, policy, persistence, session lifecycle, cancellation, or tools:

- Add a regression test that fails before the fix and passes after it, plus a negative control when applicable.
- Use fake keys, temporary databases/files, synthetic transcript text, and local fake HTTP servers only. Do not use live credentials, production signing keys, paid endpoints, or private transcripts.
- Test fail-closed behavior and stale/late callbacks, not only the successful path.
- Keep tool states distinct: declared, implemented, wired, and verified. A test-only fake executor does not verify production wiring.
- Test execution contract: all asynchronous and coroutine waits must enforce a bounded timeout; teardown procedures, socket closures, and synthetic HTTP server (such as MockWebServer) requests must never block indefinitely. On a join timeout, run the collection in a detached scope, cancel it, release the peer latch/body/server (then client pools) with each close on a per-test daemon executor under its own wall-clock deadline via Future.get (on timeout the worker is cancelled and the remaining steps still run; Throwable diagnostics recorded, never silently swallowed) before the bounded observable re-join, so the parent scope cannot hang on a non-cooperative blocking read. Residual risk: a close that ignores interruption still pins its worker thread past its deadline; the backstop is the JUnit wall-clock timeout plus the CI job timeout (daemon threads only guarantee the stray worker never holds the JVM open).

Shell timeout boundary (Current): `ShellWhitelistTest.timeoutKillsSleep`
uses a hermetic `sleep` fixture (temp-dir `#!/bin/sh` infinite loop resolved
through `ShellExecutables` with a simulated device-unwritable search dir, so
the assertion does not depend on host `/bin/sleep` placement or host search-dir
writability) with `DefaultProcessRunner` and asserts `TimedOut` plus
`elapsedMs >= 400ms` (proves a real timeout wait, not an instant fake) and
`elapsedMs < 10s`. The 120s sleep argument is deliberately far above the kill
ceiling so a kill regression (falling through to natural exit) cannot pass
(the fixture loop ignores its argument); the 10s
cap is roughly 10x the normal ~0.5–1s kill path (`waitFor` 500ms +
`destroyForcibly` + per-stream gobbler `join` 5s×2), loose enough for loaded CI but tight
enough to catch a fully-degraded kill (~15.5s when every grace period fills:
0.5s wait + 5s destroy wait + 2×5s gobbler joins).
The passing-path cost stays ~1s: the long sleep only materializes as
extra wait when the kill itself regresses. The `writableFixture_*` negative
control proves the same fixture is denied with zero spawn when the search dir
is writable.

## Reporting evidence

Record the base SHA, exact commands, exit status, relevant test/report artifact, JDK/SDK/API/device where applicable, and checks not run. A planned command, an old runbook checkbox, or a successful build alone is not a security conclusion.

Flaky test resolution and debugging principles:

- Do not rely on silent retries to mask regressions or timing bugs.
- When test failures occur, investigate and capture forensic evidence via the Step Summary and console FULL stack trace (`--stacktrace`) captured in the workflow logs.
- Differential triage relies on the 14-day retention of test report artifacts (HTML reports and structured JUnit XML under `app/build/test-results/`) across attempts and runs.
