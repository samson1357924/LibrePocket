# Release Process and Readiness

**Status:** Current workflow inventory plus an explicit release blocker, checked 2026-10-07 at `b3d8a818f37f8455a254a0670b512286e4deb750`.

- **Scope:** GitHub Actions release workflow and the minimum evidence required before calling an artifact verified.
- **Owner role:** Release maintainer; no individual is assigned here.
- **Source of truth:** `.github/workflows/release.yml`, `app/build.gradle.kts`, signing configuration, and verification output for the final artifact bytes.
- **Update trigger:** Workflow, signing, package/flavor, versioning, distribution channel, or artifact-verification changes.

## Current workflow (not a release assurance)

`.github/workflows/release.yml` runs on matching version tags or manual dispatch. It builds release variants, applies Play/Foss policy scripts, optionally invokes Android signing when the release keystore secret is present, creates a GitHub APK/SBOM/checksum bundle, and creates a GitHub Release. The intended Foss channel is F-Droid; this workflow does not publish an official F-Droid artifact. This description does not verify that any channel currently has a release.

The existence of this workflow does not prove a public release was completed, a required secret was configured, the chosen APK was signed, or an artifact matches the intended package/version. In particular, the current workflow may select an unsigned APK when no signed APK is found, and its policy checks are not a final-byte identity check.

## Release blocker: final-byte verification is not implemented

**Do not describe the current workflow as a verified secure-release gate.** Before enabling or relying on public release automation, add a fail-closed check against the exact bytes to be distributed. It must independently verify at minimum:

- cryptographic signature validity and expected signer certificate/fingerprint;
- package/application ID and flavor;
- version code and version name against the intended release;
- release/debug state (debuggable must be false);
- the checked artifact is exactly the one subsequently checksummed and uploaded.

Missing, ambiguous, unsigned, unexpected, or unverifiable output must fail the workflow before publication. A filename, checksum generated from an unverified file, or a policy scan that passes is not a substitute. Record the verifier's version and results with the release artifact. This is a **planned blocker**, not a claim that this implementation exists.

## Target release sequence

1. Review a pinned source commit and confirm docs, tests, policy results, and known limitations.
2. Build only intended flavor artifacts with controlled signing configuration.
3. Verify final artifact bytes and metadata using the fail-closed gate above.
4. Generate checksums/SBOM from the verified distribution set, publish only the intended channel artifacts, and retain verification evidence.
5. Confirm rollback/revocation instructions and user-facing privacy/security disclosures before enabling distribution.

Steps in this Target list are not all implemented or device/store verified. See [Testing](TESTING.md), [Security](../SECURITY.md), and [Build Environment](ENV.md).
