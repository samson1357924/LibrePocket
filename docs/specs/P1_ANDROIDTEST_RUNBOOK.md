# P1 Android instrumented test notes

> **Status: Current inventory, not a CI merge gate.** Test source names and commands were checked against `b3d8a818f37f8455a254a0670b512286e4deb750` on 2026-10-07. This document does not claim a device run was performed for this revision.
>
> - **Scope:** Tests under `app/src/androidTest` only.
> - **Owner role:** CI/test maintainer; no individual assigned.
> - **Source of truth:** `app/src/androidTest`, `.github/workflows/pr-check.yml`, and run results tied to a commit/device.
> - **Update trigger:** Instrumented test, CI device matrix, Android API coverage, or required merge-gate changes.

## Current instrumented test sources

| Test | Source-level intent | Network |
|---|---|---|
| `EncryptedPrefsVaultInstrumentedTest` | Android Keystore-backed key vault behavior | None |
| `RoomSessionStoreConcurrencyInstrumentedTest` | In-memory Room concurrent append behavior | None |
| `PlayPermissionPolicyInstrumentedTest` | Policy/confirmation behavior and denial before a provider request | Local MockWebServer fixture |

Source presence is not evidence that a test passed on a physical device or emulator. Current pull-request CI runs unit/Robolectric, lint, debug assembly, and artifact policy scripts; it does not run this AndroidTest set.

## Example execution

Use JDK 17 for CI parity, set `ANDROID_HOME`, and confirm a device is connected. Run one Gradle/test process at a time.

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/platform-tools/adb" devices
./gradlew :app:connectedPlayDebugAndroidTest --max-workers=1 --no-daemon
```

The app has `play`, `foss`, and `github` variants, but test source/variant coverage must be confirmed from Gradle configuration before claiming a three-flavor instrumented matrix. Do not infer `connectedFossDebugAndroidTest` or `connectedGithubDebugAndroidTest` is a required or passing gate merely from a planned command.

## Evidence required for a device claim

Record the tested SHA, exact Gradle task, device/emulator model, Android API, JDK, result and report location. State which variants were not run. The historical API 33/34/36/37 matrix language elsewhere in the P1 design spec is a target proposal, not current CI configuration or proof of execution. See [Testing](../TESTING.md).
