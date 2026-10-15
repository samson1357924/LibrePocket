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
`scripts/summarize_junit.py`, `scripts/check_test_budgets.py`,
`scripts/check_junit_durations.py`,
`scripts/tests/test_apk_policy_check.py`,
`scripts/tests/test_summarize_junit.py`,
`scripts/tests/test_check_test_budgets.py`,
`scripts/tests/test_check_junit_durations.py`,
`.github/workflows/pr-check.yml`) outputs. `docs-guard` always runs the
static grep guard `scripts/docs_claim_check.sh` (no Gradle, no emulator);
`pr-gate` (`always()`, needs all prior jobs) resolves a skipped Gradle
stage as pass only when the corresponding `changes` output is explicitly
`false`, and fails closed otherwise.

When `code == true`, the workflow uses JDK 25 and runs these Gradle stages:

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

Issue #86 byte-cap PR/nightly split (Current, Phase B stop-gap, zero prod change):
PR runs slimmed byte-cap versions (11-turn / 22-row / 2.2M-char over-budget
prefix plus the 12-turn cleanFence; same 20-fit / 21st-overflow arithmetic,
same all-or-nothing + marker + clean-reopen assertions); the original 100-turn
/ 200-row / 20M-char full stress runs only in the nightly `nightly-unit-stress`
job (`NIGHTLY_STRESS=1`, Foss flavor only — shaping is flavor-independent,
independent 35-minute timeout, JUnit XML retained 14 days, red requires filing
an issue manually and never blocks merge — not in `pr-check.yml` `pr-gate`).
Slim tests use `FakeSessionStore.appendPreRedactedForBudgetTest`
(pre-redacted `"x"` fixture, bytes identical, skips the O(n²) EMAIL scan;
`appendEvent` 本體不動，F8 parity unaffected); nightly stress uses the true
`appendEvent` shaping.

| PR slim (fast, gate) | Nightly full (`NIGHTLY_STRESS=1`, non-gate) |
|---|---|
| `ChatSnapshotByteCapTest.overBudgetPrefix_loadBoundedAndSnapshotAbandonsWholeWithMarker` (11 turns, 20 fit / 2 dropped) | `ChatSnapshotByteCapFullStressTest.overBudgetPrefix_full200Rows_abandonsWholeWithMarker` (100 turns, 20 fit / 180 dropped) |
| `SnapshotFiveDimPhase4Test.byteCapOverBudget_fiveDim` (11 turns) | `ChatSnapshotByteCapFullStressTest.byteCapOverBudget_fiveDim_full200Rows` (100 turns) |
| `ChatSnapshotByteCapTest.cleanFenceOverBudgetFork_surfacesIncompleteNoticeImmediately` (12 turns, pre-redacted fast path) | (same ledger shape covered by the two full tests above; no separate nightly) |

Issue #86 Phase A root fix (Current, production change — `Redactor` only):
single-scan per rule (`replaceAndCount`, replacing the
`findAll().count()`+`replace()` double scan; `$1` semantics unchanged),
fail-closed literal prechecks per rule (miss-then-skip only on necessary
conditions: `@`+`.tld`-dot, `bearer`, `://`+`@`, `?`/`&`+`=`, `09`, `+`,
`1`/`2`, key words, path prefixes, digit presence), a linear `@`-anchored
EMAIL scan (equivalent to the regex `replaceAll` — maximal local run +
anchored domain per `@`, disjoint runs keep it O(n); possessive local part
kept as defense in depth), and a digit precheck in `applyCardRule`. Output
text and per-rule hit counts are bit-identical (pinned by `RedactorTest` 71
tests + corpus + `SecretValueRedactionRegressionTest` 15 tests + new
`RedactorPerfRegressionTest` 6 tests: homogeneous 100k passthrough with zero
hits, single 100k <500ms, `@`-bearing probes <500ms with dotless passthrough,
21-shape EMAIL differential vs the legacy greedy regex, 200-row equivalent
<60s). Measured (Foss, post-fix, `testFossDebugUnitTest` JUnit XML):
`RedactorPerfRegressionTest` 6/6 in ~1.2s (incl. 200-row equivalent ~1.0s,
`@`-probes ~0.01s); PR-slim `ChatSnapshotByteCapTest` 4/4 in ~0.5s;
nightly-only `ChatSnapshotByteCapFullStressTest` 2/2 in ~1.4s with
`NIGHTLY_STRESS=1` (true shaping). The Phase B slim/nightly split and Phase C
gates are retained (defense in depth, still reject wasteful huge loops).

What runs by edit type:

- Docs-only (neither `code` nor `policy`, e.g. `docs/**` or `*.md` edits): unit tests, lint, and build/policy are skipped; `docs-guard` and `pr-gate` still run.
- Policy-script-only (any `policy` path edit, `code == false`): build/policy and the stdlib Python policy harness run; unit tests and lint are skipped and resolved as pass by `pr-gate`. The harness runs both the Python unit tests AND the repo-wide `python3 scripts/check_test_budgets.py` scan (PR #87 P2-4: script-only diffs skip the `unit-tests` job that otherwise hosts that gate, so the scan is wired into `policy-harness` too — pinned by `PolicyCiGateControl.test_policy_harness_runs_repo_budget_guard`). A scanner-only diff therefore cannot pass with all policy validation skipped.
- Workflow-only edit to `pr-check.yml`: counts as both `code` and `policy`, so all Gradle stages plus the Python policy harness run. The harness pin tests therefore execute exactly when the wiring they validate changes.
- Mixed docs + code/policy edits: full Gradle stages plus `docs-guard` run.
- Stacked PRs (base is not `main`, e.g. a fix branch targeting a feature branch): `pr-check.yml` only triggers on `main`, so no PR Check runs attach to the stacked PR itself — verification rests on local runs (base SHA, exact commands, exit status recorded) plus `workflow_dispatch` evidence; merging the stack into the feature branch re-enters that branch's own `main`-targeted gate, which is where the three-flavor green must be observed before closing the related issue.

Static analysis lives outside `pr-check.yml`: CodeQL runs in
`.github/workflows/codeql.yml`, which skips docs-only push/PR events via
`paths-ignore` (`docs/**`, `**/*.md`, `LICENSE*`, `TRADEMARKS.md`,
`NOTICE*`) while the weekly cron and `workflow_dispatch` runs stay
unfiltered. Secret scanning (`gitleaks`) and `dependency-review` in
`.github/workflows/security-audit.yml` still run on every pull request,
including docs-only ones.

No branch-protection required checks are configured on `main`; `pr-gate`
and the checks above are informational until protection is configured.

The workflows do **not** run an Android emulator/device matrix on every pull request. The Python APK policy and release-identity harnesses (standard library only) run in CI as the `policy-harness` job whenever `policy == true`, and `pr-gate` requires its success in that case. The command is `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/tests/test_apk_policy_check.py scripts/tests/test_release_artifact_verifier.py scripts/tests/test_build_release_identity.py scripts/tests/test_summarize_junit.py scripts/tests/test_check_test_budgets.py scripts/tests/test_check_junit_durations.py`. These tests use synthetic APK-like files and fake Gradle/SDK/policy commands; they do not prove real APK or SDK behavior. A green unit/lint/policy workflow is not proof of runtime safety, provider compatibility, signing identity, or physical-device behavior.

Issue #86 Phase C guardrails (Current, gates — only reject/alert, never
silently downgrade redaction or writes):
- `scripts/check_test_budgets.py` (covered by
  `scripts/tests/test_check_test_budgets.py`) runs BEFORE the unit-test suite in
  each flavor job over all test source sets (`app/src/test`, `app/src/testFoss`,
  `app/src/testGithub`, `app/src/androidTest`): H1 rejects `repeat(N>=10)` loops writing huge fixtures
  (`100_000`/`100_001`/`huge`/`big`) through the true `.appendEvent(` shaping
  (use `appendPreRedactedForBudgetTest` or move to nightly; only
  `ChatSnapshotByteCapFullStressTest.kt` is allowlisted); H2 rejects
  `repeat(N>=2000)` with `.appendEvent(`. Local: `python3
  scripts/check_test_budgets.py`.
- `scripts/check_junit_durations.py` (covered by
  `scripts/tests/test_check_junit_durations.py`) runs AFTER the suite in each
  flavor job and fails when any single test class exceeds 120s (same JUnit XML
  glob as `summarize_junit.py`, which stays reporting-only). No XML skips green
  (Gradle governs that case). Local: `python3 scripts/check_junit_durations.py`
  (needs `app/build/test-results/**/TEST-*.xml` from a prior Gradle run).
- Known slow classes (budgets, Current): PR-slim byte-cap
  (`ChatSnapshotByteCapTest`, 11-turn + 12-turn + 2000 small rows) and
  `SnapshotFiveDimPhase4Test.byteCapOverBudget_fiveDim` must stay well under
  120s per class; nightly-only `ChatSnapshotByteCapFullStressTest` (200 rows,
  true shaping) is exempt from the PR gate by construction (skips without
  `NIGHTLY_STRESS=1`) and has its own 35-minute nightly timeout.
- Per-class Gradle sharding with independent per-class timeouts is Proposed
  (not wired): today the per-flavor job timeout (`timeout-minutes: 20`) plus
  the two gates above are the enforcement. Do not claim sharded execution until
  it is wired and run.

## Local commands

Use one Gradle execution at a time on constrained machines. The repo's maintenance guidance requires `--max-workers=1 --no-daemon`; do not run a parallel flavor matrix after an OOM.

```sh
# Synthetic APK/DEX policy and final-artifact identity harnesses (Python stdlib only)
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  scripts/tests/test_apk_policy_check.py \
  scripts/tests/test_release_artifact_verifier.py \
  scripts/tests/test_build_release_identity.py \
  scripts/tests/test_summarize_junit.py \
  scripts/tests/test_check_test_budgets.py \
  scripts/tests/test_check_junit_durations.py

# Issue #86 Phase C guardrails (static, no Gradle)
python3 scripts/check_test_budgets.py
python3 scripts/check_junit_durations.py

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
does not enter `pr-gate` merge evaluation). The `nightly-unit-stress` job
(Issue #86 Phase B, Foss `ChatSnapshotByteCapFullStressTest` with
`NIGHTLY_STRESS=1`) runs on schedule / `workflow_dispatch` only
(`if: github.event_name != 'pull_request'`), never on PR; JUnit XML retained
14 days, non-gating (not in `pr-check.yml` `pr-gate`). The emulator matrix is API
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
