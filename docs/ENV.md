# Build Environment — LibrePocket

**Status:** Current checked-in build baseline, checked 2026-10-24 against `d5a98bd35e919083b0475027c9b2e74b703e4e58` (committed 2026-10-13).

- **Scope:** Local build prerequisites and checked-in Android toolchain values.
- **Owner role:** Build/CI maintainer; no individual assigned.
- **Source of truth:** `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, and `.github/workflows/pr-check.yml`.
- **Update trigger:** Any SDK, AGP, Kotlin, Gradle, JDK, flavor, or CI image change.

## JDK and Gradle

CI uses JDK 25, requesting the latest patch via `check-latest: true` and enforcing a minimum security floor of 25.0.5 via `scripts/verify_jdk_floor.sh` for CVE-2026-91403 (eMudhra intermediate CA compromise mitigation; see Oracle CVE-2026-91403 advisory: https://www.oracle.com/security-alerts/alert-cve-2026-91403.html). Use JDK 25 (>= 25.0.5) for closest CI parity; older local JDKs (such as 21) may still build locally, but they are not the CI baseline. The repository pins the Gradle wrapper; a system Gradle installation is not needed, and `gradle/wrapper/gradle-wrapper.properties` enforces `distributionSha256Sum` verification to protect against distribution tampering.

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

Install the matching Android platforms and build tools required by the wrapper/build. The Gradle wrapper pin in `gradle/wrapper/gradle-wrapper.properties` enforces `distributionSha256Sum` verification to protect against distribution tampering. Do not infer installation paths or current Google Play policy deadlines from this table; the values above describe this source revision, not a store approval.

## Node.js (PocketGuard Bot)

Requires Node.js `>= 24.22.1` (enforcing the October 2026 security release; see Node.js security release: https://nodejs.org/en/blog/release/v24.22.1/). `github_bot/.nvmrc` is the single source of truth for Node.js 24.22.1 across workflows (via `node-version-file: 'github_bot/.nvmrc'`) and local development, and npm enforces `engine-strict=true`.

```sh
cd github_bot && nvm use && node -v
```

## Flavors and IDs

| Flavor | Application ID | Source boundary |
|---|---|---|
| `play` | `dev.librepocket.agent` | base ID; Play overlay removes high-risk permissions/services |
| `foss` | `dev.librepocket.agent.foss` | Foss source set and OSS-only dependency policy |
| `github` | `dev.librepocket.agent.github` | GitHub source set and GitHub-only dependencies |

Do not treat a locally built APK as an official channel artifact. Distribution/signing status is described in [Release](RELEASE.md) and [TRADEMARKS](../TRADEMARKS.md).

## Build commands

Use the commands and evidence guidance in [Testing](TESTING.md). On constrained systems, serialize Gradle runs with `--max-workers=1 --no-daemon`; do not run multiple Gradle/test JVMs after an OOM.
