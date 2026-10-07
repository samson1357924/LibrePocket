# Build Environment — LibrePocket

**Status:** Current checked-in build baseline, checked 2026-10-07 at `b3d8a818f37f8455a254a0670b512286e4deb750`.

- **Scope:** Local build prerequisites and checked-in Android toolchain values.
- **Owner role:** Build/CI maintainer; no individual assigned.
- **Source of truth:** `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, and `.github/workflows/pr-check.yml`.
- **Update trigger:** Any SDK, AGP, Kotlin, Gradle, JDK, flavor, or CI image change.

## JDK and Gradle

CI uses JDK 17. Use JDK 17 for closest CI parity; JDK 21 may be used locally, but it is not the CI baseline. The repository pins the Gradle wrapper; a system Gradle installation is not needed.

```sh
java -version
./gradlew --version
```

## Android SDK

Set `ANDROID_HOME` to the SDK installation on your machine (the value below is an example):

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

The checked-in app configuration currently uses:

| Setting | Value |
|---|---:|
| `minSdk` | 33 |
| `targetSdk` | 36 |
| `compileSdk` | 37 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.20 |
| Gradle wrapper | 9.7.1 |

Install the matching Android platforms and build tools required by the wrapper/build. Do not infer installation paths or current Google Play policy deadlines from this table; the values above describe this source revision, not a store approval.

## Flavors and IDs

| Flavor | Application ID | Source boundary |
|---|---|---|
| `play` | `dev.librepocket.agent` | base ID; Play overlay removes high-risk permissions/services |
| `foss` | `dev.librepocket.agent.foss` | Foss source set and OSS-only dependency policy |
| `github` | `dev.librepocket.agent.github` | GitHub source set and GitHub-only dependencies |

Do not treat a locally built APK as an official channel artifact. Distribution/signing status is described in [Release](RELEASE.md) and [TRADEMARKS](../TRADEMARKS.md).

## Build commands

Use the commands and evidence guidance in [Testing](TESTING.md). On constrained systems, serialize Gradle runs with `--max-workers=1 --no-daemon`; do not run multiple Gradle/test JVMs after an OOM.
