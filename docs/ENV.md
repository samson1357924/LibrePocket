# Build Environment — LibrePocket

**Status:** Current checked-in build baseline, checked 2026-10-29 against `4cf8216b` (committed 2026-10-24).

- **Scope:** Local build prerequisites and checked-in Android toolchain values.
- **Owner role:** Build/CI maintainer; no individual assigned.
- **Source of truth:** `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts`, `github_bot/.nvmrc`, `github_bot/package.json`, `scripts/verify_jdk_floor.sh`, `.github/workflows/pr-check.yml`, and `.github/dependabot.yml`.
- **Update trigger:** Any SDK, AGP, Kotlin, Gradle, JDK, Node.js, flavor, CI image, or dependency update policy change.

## JDK and Gradle

CI uses JDK 25, requesting the latest patch via `check-latest: true` and enforcing
a minimum security floor of Temurin 25.0.5+8 via
`scripts/verify_jdk_floor.sh --require-vendor Temurin` for two October 2026
security advisories:

- CVE-2026-91403 (eMudhra intermediate CA compromise mitigation; see the Oracle
  CVE-2026-91403 advisory:
  https://www.oracle.com/security-alerts/alert-cve-2026-91403.html).
- CVE-2026-114820 (Oracle eMudhra root-CA advisory:
  https://www.oracle.com/security-alerts/alert-cve-2026-114820.html). Adoptium
  issue #317 updates certdata to NSS 3.131.1, removing the compromised eMudhra
  roots.

The script's `jdk.security.caDistrustPolicies` inspection is informational only;
the primary gate is the verified Temurin build floor, not the presence of a
distrust entry. Use JDK 25 (>= 25.0.5+8) for closest CI parity; older local JDKs
(such as 21) may still build locally, but they are not the CI baseline. The
repository pins the Gradle wrapper; a system Gradle installation is not needed,
and `gradle/wrapper/gradle-wrapper.properties` enforces `distributionSha256Sum`
verification to protect against distribution tampering.

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

Requires Node.js `^24.22.1` (or `>= 26.12.1` if running Node 26) to enforce the
October 2026 security release addressing CVE-2026-105482, CVE-2026-105483,
CVE-2026-106069, CVE-2026-111497 (Brotli RCE), and Undici vulnerabilities
(see Node.js security release: https://nodejs.org/en/blog/release/v24.22.1/).
`github_bot/.nvmrc` pins 24.22.1 as the baseline for CI workflows (via
`node-version-file: 'github_bot/.nvmrc'`) and local development.
`github_bot/.npmrc` sets `engine-strict=true` to reject installs on
incompatible Node engines.

```sh
cd github_bot && nvm install && nvm use && node -v
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

## Supply Chain and Dependency Update Policy

Automated dependency monitoring, pinning rules, and review protocols protect build integrity and supply-chain authenticity across ecosystems (see issue #20):

1. **Automated Dependency Monitoring:** Dependabot is configured in `.github/dependabot.yml` to monitor dependencies weekly across three package ecosystems:
   - `github-actions` (workflow root `/`, commit prefix `chore(ci)`),
   - `gradle` (Android build root `/`, commit prefix `chore(deps)`),
   - `npm` (PocketGuard bot `/github_bot`, commit prefix `chore(bot)`).

2. **Commit-SHA Pinning for GitHub Actions:** All GitHub Actions workflows (`.github/workflows/*.yml`) enforce supply-chain integrity (#20) by requiring every action step `uses:` to resolve to a full 40-character commit SHA rather than a mutable tag or branch. Dependabot PRs updating Actions will propose upstream version/tag bumps or SHAs; maintainers must ensure the 40-hex SHA pin is maintained (annotated with a trailing version comment, e.g., `# v4.4.0`) before merging.

3. **Verification Protocol for Dependency PRs:**
   - **GitHub Actions:** Audit the upstream commit SHA on the action repository, verify commit authenticity and author provenance, and check against workflow policy and least-privilege permissions.
   - **Gradle Dependencies and Toolchain:** Updates must be reflected in the version catalog (`gradle/libs.versions.toml`) or Gradle wrapper (`gradle/wrapper/gradle-wrapper.properties` with an authentic `distributionSha256Sum`). Maintainers must execute the full 3-flavor unit test suite, lint, debug assembly, and artifact policy checks (`:app:testPlayDebugUnitTest`, `:app:testFossDebugUnitTest`, `:app:testGithubDebugUnitTest`, `:app:lintPlayDebug`, `:app:lintFossDebug`, `:app:lintGithubDebug`, `:app:assemblePlayDebug`, `:app:assembleFossDebug`, `:app:assembleGithubDebug`, and `scripts/play_policy_check.sh` on Play and Foss APKs) as documented in [Testing](TESTING.md).
   - **Node.js (`github_bot`):** Validate lockfile integrity and test suites by executing `npm ci --ignore-scripts && npm test && npm run build` within `github_bot/`.

4. **Residual Note on Offline Dependency Locking:** Full offline dependency locking (`gradle/verification-metadata.xml`) remains an open follow-up milestone for strict transitive byte-level verification against upstream repository tampering.
