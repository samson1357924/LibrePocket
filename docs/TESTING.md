# Testing and Verification

**Status:** Current test inventory and repeatable command guide, checked 2026-10-07 at `d7449d8ccf8c38884f514f5c30db157ca89641e1`.

- **Scope:** Repository CI, local JVM/Robolectric tests, static policy gates, and separately scheduled Android device tests.
- **Owner role:** CI/build maintainer; no individual is assigned here.
- **Source of truth:** `.github/workflows/pr-check.yml`, `.github/workflows/codeql.yml`, `.github/workflows/security-audit.yml`, `scripts/docs_claim_check.sh`, test source sets, Gradle wrapper/catalog, and the command results from the exact commit being assessed.
- **Update trigger:** CI, test source-set, Android SDK/JDK, policy-script, or merge-gate changes.

## Current CI scope

`pr-check.yml` is path-gated. A `changes` job classifies each pull request
with `dorny/paths-filter` into `code` (`app/src/**`,
`app/build.gradle.kts`, `app/lint.xml`, `gradle/**`,
`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
`.github/workflows/pr-check.yml`) and `policy`
(`scripts/play_policy_check.sh`) outputs. `docs-guard` always runs the
static grep guard `scripts/docs_claim_check.sh` (no Gradle, no emulator);
`pr-gate` (`always()`, needs all prior jobs) resolves a skipped Gradle
stage as pass only when the corresponding `changes` output is explicitly
`false`, and fails closed otherwise.

When `code == true`, the workflow uses JDK 17 and runs these Gradle stages:

1. `:app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest` (including Robolectric tests available in those source sets).
2. `:app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug`.
3. `:app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug`, then `scripts/play_policy_check.sh` for Play and `scripts/play_policy_check.sh --foss` for Foss. This stage also runs when only `policy == true`.

What runs by edit type:

- Docs-only (neither `code` nor `policy`, e.g. `docs/**` or `*.md` edits): unit tests, lint, and build/policy are skipped; `docs-guard` and `pr-gate` still run.
- Policy-script-only (`scripts/play_policy_check.sh` edit, `code == false`): build/policy runs; unit tests and lint are skipped and resolved as pass by `pr-gate`.
- Workflow-only edit to `pr-check.yml`: counts as `code`, so all Gradle stages run.
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

The workflows do **not** run an Android emulator/device matrix on every pull request. A green unit/lint/policy workflow is not proof of runtime safety, provider compatibility, signing identity, or physical-device behavior.

## Local commands

Use one Gradle execution at a time on constrained machines. The repo's maintenance guidance requires `--max-workers=1 --no-daemon`; do not run a parallel flavor matrix after an OOM.

```sh
# Flavor unit/Robolectric tests
./gradlew :app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest --max-workers=1 --no-daemon

# Flavor lint
./gradlew :app:lintPlayDebug :app:lintFossDebug :app:lintGithubDebug --max-workers=1 --no-daemon

# Debug assembly
./gradlew :app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug --max-workers=1 --no-daemon

# Artifact policy checks after successful assembly
scripts/play_policy_check.sh app/build/outputs/apk/play/debug/app-play-debug.apk
scripts/play_policy_check.sh --foss app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

These are instructions, not a claim that they were run for this documentation change. Version sources are listed in [Build Environment](ENV.md).

## Android instrumented tests

Instrumented tests are a separate device-dependent tier. The existing [P1 androidTest runbook](specs/P1_ANDROIDTEST_RUNBOOK.md) lists test classes and sample commands, but its device matrix and prior status notes are not current CI evidence. Before treating a device matrix as a merge/release gate, maintainers must name the required API/flavor/device coverage, run it, and retain results tied to the tested SHA. Do not report a device pass when no device was used.

## Security-sensitive test expectations

For changes involving credentials, policy, persistence, session lifecycle, cancellation, or tools:

- Add a regression test that fails before the fix and passes after it, plus a negative control when applicable.
- Use fake keys, temporary databases/files, synthetic transcript text, and local fake HTTP servers only. Do not use live credentials, production signing keys, paid endpoints, or private transcripts.
- Test fail-closed behavior and stale/late callbacks, not only the successful path.
- Keep tool states distinct: declared, implemented, wired, and verified. A test-only fake executor does not verify production wiring.

## Reporting evidence

Record the base SHA, exact commands, exit status, relevant test/report artifact, JDK/SDK/API/device where applicable, and checks not run. A planned command, an old runbook checkbox, or a successful build alone is not a security conclusion.
