# Release Process and Readiness

**Status:** The public release workflow is intentionally frozen until Issue #12's release gates are implemented and reviewed. This document describes current behavior separately from target requirements.

- **Scope:** GitHub Actions release workflow and the evidence required before an artifact can be called verified.
- **Owner role:** Release maintainer; no individual is assigned here.
- **Source of truth:** `.github/workflows/release.yml`, `scripts/build_release.sh`, `scripts/play_policy_check.sh`, `scripts/apk_policy_inspect.py`, `app/build.gradle.kts`, signing configuration, and verification output tied to the final artifact bytes.
- **Update trigger:** Workflow, signing, package/flavor, versioning, distribution channel, or artifact-verification changes.

## Current state: public release disabled

`.github/workflows/release.yml` keeps the version-tag and manual triggers only to fail closed with `contents: read`. It does not check out source, build, sign, upload workflow artifacts, or create a public release. There is no alternate publishing branch in this workflow. Do not treat a successful workflow invocation as a release or artifact verification.

The local `scripts/build_release.sh` supports APK builds only when explicitly passed `--apk`; without it, it rejects the default AAB request before Bitwarden/credential lookup or Gradle. Its Play/Foss policy scan is a local policy gate, not authorization to publish. AAB is explicitly unsupported by the current scanner and must not be passed to it.

## Current APK policy scanner boundary

`scripts/play_policy_check.sh` accepts APK inputs only. It retains the source-manifest, cleartext, network-security, and backup-rule checks, requires Android `aapt` and `dexdump`, then delegates archive parsing and policy assertions to `scripts/apk_policy_inspect.py`.

The Python scanner rejects malformed/non-APK ZIPs, unsafe or duplicate member names, missing manifest or root `classes.dex`, unsupported DEX placements/versions, and parser/tool failures. It checks DEX `type_ids` references (including Foss proprietary-reference policy), Play class definitions/superclasses, aapt manifest output, and Play Linux-payload entry names. DEX support is a documented strict subset: little-endian versions 035, 037, 038, 039, and 040. Version 041/container and unknown/reverse-endian formats fail closed. `aapt` output has an 8 MiB cap and a 120-second timeout; each `dexdump` invocation has a 120-second timeout, with stdout/stderr discarded. `dexdump` is an independent parser sanity check, not the source of typed references. The Python scanner is not a complete DEX/ART semantic verifier.

Resource limits are deliberate scanner restrictions, not DEX format maxima. Each DEX allows at most 1,000,000 `string_ids` before allocating its offset table, 4,096 UTF-16 units per type descriptor, and cumulative descriptor totals of 4 Mi UTF-16 units / 12 MiB encoded bytes. Across one APK, retained descriptors are capped at 16 Mi UTF-16 units / 48 MiB encoded bytes. Encoded-byte accounting includes the length prefix and terminator. All descriptor ranges and cumulative totals are preflighted before allocating decoded strings; duplicate string-data offsets, overlapping referenced ranges, and duplicate type values are rejected. The measured debug-APK inventory used to choose headroom had maxima of 125,703 string IDs, 182 units per descriptor, 1,116,929 descriptor units per shard, and 2,720,128 per APK. This is resource-sizing evidence, not a fresh native-policy pass. Release/build maintainers must reassess these limits and rerun positive controls when artifact inventory changes; larger artifacts fail closed rather than relaxing caps through environment variables.

The stdlib tests use synthetic ZIP/DEX fixtures and fake native-tool commands. They are useful regression controls, not evidence that a production APK or Android SDK native parser passed. Before relying on this APK-only gate, the release/build maintainer should run the scanner and native tools against fresh debug APKs for all three flavors and retain results tied to the tested commit. That rehearsal does not establish release signing or public-release readiness.

## Release blocker: final-byte verification is not implemented

**Do not describe the current workflow or local policy scan as a verified secure-release gate.** Before enabling or relying on public release automation, add a fail-closed check against the exact bytes to be distributed. It must independently verify at minimum:

- cryptographic APK signature validity and expected signer certificate/fingerprint;
- package/application ID and flavor;
- version code and version name against the intended release;
- release/debug state (debuggable must be false);
- the checked artifact is exactly the one subsequently checksummed and uploaded.

Missing, ambiguous, unsigned, unexpected, or unverifiable output must fail before publication. A filename, checksum generated from an unverified file, or policy scan that passes is not a substitute. Record the verifier's version and results with the release artifact. None of these final-byte identity checks is claimed by the current APK scanner; this remains a separate release blocker and Issue #12 stays open.

## Target release sequence

1. Review a pinned source commit and confirm docs, tests, policy results, and known limitations.
2. Build only intended flavor artifacts with controlled, required signing configuration.
3. Verify final artifact bytes and metadata using the fail-closed gate above.
4. Generate checksums/SBOM from the exact verified distribution set, publish only intended channel artifacts, and retain verification evidence.
5. Confirm rollback/revocation instructions and user-facing privacy/security disclosures before enabling distribution.

These Target steps are not all implemented or device/store verified. See [Testing](TESTING.md), [Security](../SECURITY.md), and [Build Environment](ENV.md).
