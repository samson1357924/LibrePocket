# P1 Android instrumented test notes

> **Status: Current source/workflow inventory, not proof of a device pass.** The starting commit for this change is `aff1dc6763f33adeae521a9d02a7786a3b253c30`; the inventory below reflects the branch/worktree sources. No physical-device or emulator execution is claimed here.
>
> - **Scope:** Tests under `app/src/androidTest` only.
> - **Owner role:** CI/test maintainer; no individual assigned.
> - **Source of truth:** `app/src/androidTest`, `.github/workflows/pr-check.yml`, `.github/workflows/nightly-instrumented.yml`, and run results tied to a commit/device.
> - **Update trigger:** Instrumented test, CI device matrix, Android API coverage, or required merge-gate changes.

## Current instrumented test sources

| Test | Source-level intent | Network |
|---|---|---|
| `EncryptedPrefsVaultInstrumentedTest` | Android Keystore-backed key vault behavior | None |
| `RoomSessionStoreConcurrencyInstrumentedTest` | In-memory Room concurrent append behavior | None |
| `PlayPermissionPolicyInstrumentedTest` | Policy decision matching; a test-created dialog fixture; test-only ASK/DENY transport blocking and an actual ALLOW `GET /v1/models` request | Local MockWebServer |

The dialog and policy-to-provider gate in `PlayPermissionPolicyInstrumentedTest` are test fixtures, not production UI or a production consent gate. The test does not exercise setup/dispatch ASK consent behavior (issue #13); this change does not fix or claim that behavior. Source presence is not evidence that a test passed on a physical device or emulator.

For relevant code/policy changes, the regular pull-request workflow gates unit/Robolectric, lint, debug assembly, and artifact policy checks; docs-only changes can skip those jobs under its path filter. That workflow does not run this AndroidTest set. The separate nightly/instrumented workflow is configured to run API 33 and API 34 across Play, Foss, and Github variants. Pull-request runs remain gated by the `run-instrumented` label and are triggered when the label is applied, a commit is synchronized, or a PR is opened/reopened. This is configured coverage only, not evidence of a passing device run.

## Example execution

Use JDK 17 for CI parity, set `ANDROID_HOME`, and confirm a device is connected. Run one Gradle/test process at a time.

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/platform-tools/adb" devices
./gradlew :app:connectedPlayDebugAndroidTest --max-workers=1 --no-daemon
```

This command runs only the Play variant on the attached device. The nightly workflow configuration expands the test task across all three flavors and API 33/34; only attached CI results tied to a source SHA establish which combinations actually ran and passed.

## Evidence required for a device claim

Record the tested SHA, exact Gradle task, device/emulator model, Android API, JDK, result and report location. State which variants were not run. The historical API 33/34/36/37 matrix language elsewhere in the P1 design spec is a target proposal, not current CI configuration or proof of execution. See [Testing](../TESTING.md).
