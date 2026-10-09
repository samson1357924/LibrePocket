# Release Process and Readiness

**Status:** The public release workflow is intentionally frozen until Issue #12's release gates are implemented and reviewed. This document describes current behavior separately from target requirements.

- **Scope:** GitHub Actions release workflow and the evidence required before an artifact can be called verified.
- **Owner role:** Release maintainer; no individual is assigned here.
- **Source of truth:** `.github/workflows/release.yml`, `scripts/build_release.sh`, `scripts/play_policy_check.sh`, `scripts/apk_policy_inspect.py`, `app/build.gradle.kts`, signing configuration, and verification output tied to the final artifact bytes.
- **Update trigger:** Workflow, signing, package/flavor, versioning, distribution channel, or artifact-verification changes.

## Current state: public release disabled

`.github/workflows/release.yml` keeps the version-tag and manual triggers only to fail closed with `contents: read`. It does not check out source, build, sign, upload workflow artifacts, or create a public release. There is no alternate publishing branch in this workflow. Do not treat a successful workflow invocation as a release or artifact verification.

The local `scripts/build_release.sh` supports APK builds only when explicitly passed `--apk`; without it, it rejects the default AAB request before Bitwarden/credential lookup or Gradle. Its Play/Foss policy scan is a local policy gate, not authorization to publish. AAB is explicitly unsupported and is not a fallback.

The local `scripts/build_release.sh` provides an APK identity verification and staging path: callers must supply each selected flavor's expected package, version code, version name, and signer-certificate SHA-256, plus explicit SDK tool paths. The builder retains its existing environment-first/Bitwarden keystore-password resolver before Gradle; this change does not alter that flow. After Gradle completes, it requires exactly one APK candidate per selected flavor, snapshots the candidate into a unique private transaction, verifies signature/metadata/debug state on those staged snapshot bytes, and runs the existing Play/Foss policy checks against them. It rechecks each staged checksum after policy and before reporting the complete set. The post-build verifier/stager does not perform credential lookup or choose/approve production signing policy. Output files/directories are assigned and checked for POSIX read-only/private mode bits as an accidental-overwrite guard; filesystems that do not report the required modes fail closed before a ready result. This is not an OS sandbox or protection from a same-user process that deliberately changes permissions. On failure it attempts to remove only the transaction it created; cleanup is best-effort, not an absolute guarantee under filesystem errors or abrupt termination. The helper's SDK/policy process output and runtime are bounded, and normal signal handling stops its active process group, but this is not a general process sandbox. This local path does not publish, upload, support AAB, or establish release readiness; the public workflow below remains disabled.

## Current APK policy scanner boundary

`scripts/play_policy_check.sh` accepts APK inputs only. It retains the source-manifest, cleartext, network-security, and backup-rule checks, requires Android `aapt` and `dexdump`, then delegates archive parsing and policy assertions to `scripts/apk_policy_inspect.py`.

The Python scanner rejects malformed/non-APK ZIPs, unsafe or duplicate member names, missing manifest or root `classes.dex`, unsupported DEX placements/versions, and parser/tool failures. It checks DEX `type_ids` references (including Foss proprietary-reference policy), Play class definitions/superclasses, aapt manifest output, and Play Linux-payload entry names. DEX support is a documented strict subset: little-endian versions 035, 037, 038, 039, and 040. Version 041/container and unknown/reverse-endian formats fail closed. `aapt` output has an 8 MiB cap and a 120-second timeout; each `dexdump` invocation has a 120-second timeout, with stdout/stderr discarded. `dexdump` is an independent parser sanity check, not the source of typed references. The Python scanner is not a complete DEX/ART semantic verifier.

Resource limits are deliberate scanner restrictions, not DEX format maxima. Each DEX allows at most 1,000,000 `string_ids` before allocating its offset table, 4,096 UTF-16 units per type descriptor, and cumulative descriptor totals of 4 Mi UTF-16 units / 12 MiB encoded bytes. Across one APK, retained descriptors are capped at 16 Mi UTF-16 units / 48 MiB encoded bytes. Encoded-byte accounting includes the length prefix and terminator. All descriptor ranges and cumulative totals are preflighted before allocating decoded strings; duplicate string-data offsets, overlapping referenced ranges, and duplicate type values are rejected. The measured debug-APK inventory used to choose headroom had maxima of 125,703 string IDs, 182 units per descriptor, 1,116,929 descriptor units per shard, and 2,720,128 per APK. This is resource-sizing evidence, not a fresh native-policy pass. Release/build maintainers must reassess these limits and rerun positive controls when artifact inventory changes; larger artifacts fail closed rather than relaxing caps through environment variables.

The stdlib tests use synthetic ZIP/DEX fixtures and fake native-tool commands. They are useful regression controls, not evidence that a production APK or Android SDK native parser passed. Before relying on this APK-only gate, the release/build maintainer should run the scanner and native tools against fresh debug APKs for all three flavors and retain results tied to the tested commit. That rehearsal does not establish release signing or public-release readiness.

## Local release identity and staging gate

The local builder implements the identity checks above for staged APK bytes via `scripts/verify_release_apk.py` and `scripts/release_artifact_stage.py`. The standard-library synthetic tests are unit and regression controls, not evidence that a production release APK passed on physical devices or across every SDK toolchain. The verifier uses `apksigner verify --verbose --print-certs -Werr` and `aapt dump badging`, compares against trusted caller-supplied per-flavor identity values (never inferred from the candidate), and rejects missing/ambiguous candidates, identity mismatch, invalid signature, debug signer, or debuggable APK. A unique transaction contains staged APKs, per-artifact identity/checksum records, and an aggregate checksum list; Play/Foss policy checks run on the staged bytes and checksums are rechecked before the one complete transaction is reported.

The local builder's existing environment-first/Bitwarden keystore-password resolution is unchanged. The verifier/stager performs no credential lookup and does not select or approve production signing policy; this work does not establish production-key custody/provenance or a trusted signer-pin registry, wire a public release workflow, prove correctness of every SDK/toolchain combination, publish, or establish release readiness. The existing Play/Foss policy gate remains in addition to signature/identity verification, not replaced by it. A filename, checksum alone, or policy scan is not a substitute for final-byte verification. Issue #12 remains open until all scoped requirements, native verification, and required release evidence are complete; see [Testing](TESTING.md).

## Target release sequence

1. Review a pinned source commit and confirm docs, tests, policy results, and known limitations.
2. Build only intended flavor artifacts with controlled, required signing configuration.
3. Verify final artifact bytes and metadata using the fail-closed gate above.
4. Generate checksums/SBOM from the exact verified distribution set, publish only intended channel artifacts, and retain verification evidence.
5. Confirm rollback/revocation instructions and user-facing privacy/security disclosures before enabling distribution.

These Target steps are not all implemented or device/store verified. See [Testing](TESTING.md), [Security](../SECURITY.md), and [Build Environment](ENV.md).
