# Build Environment — LibrePocket

**Status:** Current checked-in build baseline, checked 2026-10-29 against `4cf8216b` (committed 2026-10-24).

- **Scope:** Local build prerequisites and checked-in Android toolchain values.
- **Owner role:** Build/CI maintainer; no individual assigned.
- **Source of truth:** `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `scripts/verify_gradle_wrapper.py`, `app/build.gradle.kts`, `github_bot/.nvmrc`, `github_bot/package.json`, `scripts/verify_jdk_floor.sh`, `scripts/build_release.sh`, `.github/workflows/pr-check.yml`, and `.github/dependabot.yml`.
- **Update trigger:** Any SDK, AGP, Kotlin, Gradle, JDK, Node.js, Python, flavor, CI image, or dependency update policy change.

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

## Python

Requires Python `>= 3.11` (standard library only, no external dependencies required for release builders and policy checkers). Python 3.11+ is strictly required for `python3 -P` (safe `sys.path` isolation) across release builders and policy checkers to prevent local import-shadowing attacks by disallowing prepending the current working directory or script directory to `sys.path`.

```sh
python3 --version
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

1. **Automated Dependency Monitoring:** `.github/dependabot.yml` configures weekly Dependabot version updates for four package ecosystems (actual operation depends on GitHub repository settings and service availability):
   - `github-actions` (workflow root `/`, commit prefix `chore(ci)`),
   - `gradle` (Android build root `/`, commit prefix `chore(deps)`),
   - `npm` (PocketGuard bot `/github_bot`, commit prefix `chore(bot)`),
   - `pip` (docs guard requirements in `/scripts`, commit prefix `chore(deps)`).
   This configuration alone does not establish that Dependabot alerts or security updates are enabled; verify those settings separately.

2. **Commit-SHA Pinning for GitHub Actions:** The review policy (#20) requires external action `uses:` references in `.github/workflows/*.yml` to use full 40-character commit SHAs rather than mutable tags or branches. This is a maintainer review requirement, not an automatically enforced CI SHA-pin check. Maintainers must verify the SHA and retain a trailing version comment (e.g., `# v4.4.0`) before merging, including on Dependabot PRs.

3. **Verification Protocol for Dependency PRs:**
   - **GitHub Actions:** Audit the upstream commit SHA on the action repository, verify commit authenticity and author provenance, and check against workflow policy and least-privilege permissions. `pr-check.yml` triggers only for PRs targeting `main` and pushes to `main`; it has no dispatch trigger. Within a triggered run, unit/lint use `code` paths, build/policy uses `code` or `policy` paths, the policy harness uses `policy` paths, and bot tests use `bot` paths; docs-guard has no path filter. `.github/workflows/**` selects policy; `pr-check.yml` changes select all three, and `pocketguard.yml` selects both policy and bot. Scheduled, dispatch, release and `pull_request_target` workflows have their own triggers and may not exercise the proposed PR version. See [Testing](TESTING.md) and each workflow; a green PR gate is not evidence that every affected action executed.
   - **Gradle Dependencies and Toolchain:** Update the actual source of truth for the change: the catalog (`gradle/libs.versions.toml`), build/settings files (including `app/build.gradle.kts`), wrapper properties, or CI/JDK configuration as applicable, rather than assuming every version lives in the catalog or wrapper. Follow [Testing](TESTING.md) for the complete three-flavor debug unit tests and lint, assembly of all three debug and unsigned release APKs, and artifact policy checks for both build types: Play and Foss policy checks and GitHub's mode-independent artifact binding and common manifest checks. Record the commands and results; do not claim checks that did not run.
      For a wrapper upgrade, independently verify the official distribution and wrapper JAR checksums against trustworthy upstream sources and review both launchers and the complete properties file, including the intended official `distributionUrl` and distribution checksum. Update the wrapper files, all four SHA-256 pins in `scripts/verify_gradle_wrapper.py`, version-specific constants, test names and descriptions in `scripts/tests/test_gradle_wrapper_checksum.py`, and affected documentation (including the table above) in the same reviewed PR. Run `python3 scripts/verify_gradle_wrapper.py` and the checksum harness in [Testing](TESTING.md#local-commands). The checker compares exact bytes of both launchers, the JAR, and properties with its pins; the harness also checks distribution checksum and properties settings. Neither independently authenticates new pins or downloads the distribution for review.

      Each Gradle-invoking job in PR Check, CodeQL, and nightly CI runs the checker before its wrapper invocation. This detects mismatches against the checked-in pins, not malicious changes: checker, pins, workflow, and wrapper come from the same PR checkout and can be changed together. Workflow snapshots detect changes to workflow files, not changes to scripts indirectly invoked by them before the guard; manually review those scripts and their execution order as well as workflow changes. It is not Gradle dependency verification, and `scripts/build_release.sh` is not covered by this CI pre-execution check. Review changes independently; local users can run the checker before invoking the wrapper.
   - **Node.js (`github_bot`):** Validate lockfile integrity and test suites by executing `npm ci --ignore-scripts && npm test && npm run build` within `github_bot/`.
   - **Python docs guard:** Review Dependabot's `scripts/requirements-docs.txt` update and independently verify every accepted hash against its upstream artifact, including all listed wheels and the sdist, and verify coverage for supported CI Python versions. Keep exact pins and `--require-hashes`; do not merge an update that omits required artifacts. Use a fresh virtual environment for local installation because pip does not reverify already-installed packages against requirements hashes. Dependabot does not update the independent PyYAML version assertion in `.github/workflows/pr-check.yml`: update it and the version-specific comments and documentation in the same PR. Run `scripts/tests/test_dependabot_config.py`, the docs guard and its self-test (see [local commands](TESTING.md#local-commands)); the configuration test checks hash syntax and specific install/assertion command lines, not artifact authenticity, coverage, or workflow execution conditions. A Dependabot PR may therefore need maintainer edits before CI passes.

4. **Residual Gradle Dependency Controls:** Gradle dependency verification metadata (`gradle/verification-metadata.xml`) is not currently checked in, leaving a gap in verification of resolved dependency artifacts. It can verify resolved artifact checksums or signatures when configured, but it is neither dependency version locking nor an offline dependency cache. Dependency locking, verification metadata, and offline availability are separate controls; none should be inferred from the wrapper checksum.
