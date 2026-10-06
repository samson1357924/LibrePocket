# LibrePocket – Open Mobile AI Agent

> **ALPHA — not production ready.** APIs, schemas, and DB formats may change
> without migration. Use debug builds for testing only; do not store
> irreplaceable data or keys you cannot re-enter. See `docs/ROADMAP.md`.

An international, open-source on-device AI agent for Android.
Licensed under the Apache License 2.0 (see `LICENSE`).

> **Not affiliated** with any device vendor, carrier, model provider, or
> app store. LibrePocket is an independent community project.

## Status — alpha (v0.1.0, debug only)

Backbone + early extensions are implemented behind debug builds
(P1 chat/BYOK, P2 fast-channel, P3 slow-channel foss/github-only default-off,
D01–D07 memory/MCP/Skills/shell/entry/hardening). No Play release, no stable
API: upgrades may require reinstall, and instrumented tests still need real
devices (API 33/37).

## Three distributions, distinct applicationIds (co-installable)

| Flavor | Channel | Permissions | Services | applicationId |
|--------|---------|-------------|----------|---------------|
| `play` | Google Play only | Store-safe only: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE. High-risk permissions are additionally stripped via `tools:node="remove"` (see `app/src/play/AndroidManifest.xml`). | No AccessibilityService, no VpnService | `dev.librepocket.agent` (base ID, no suffix) |
| `foss` | F-Droid (all-OSS; only a F-Droid-built APK counts as official — see `TRADEMARKS.md`) | Same as `play`: **never** requests `READ_SMS` / `RECEIVE_SMS` / `SEND_SMS`, `MANAGE_EXTERNAL_STORAGE`, or any VPN permission **in any flavor** (see `app/src/foss/AndroidManifest.xml`, `docs/CAPABILITY_MATRIX.md` §1–§2, `docs/ROADMAP.md` P1–P4, `docs/BACKLOG.md` B8/D15). SMS goes only via the system composer pre-fill; files go only via SAF + MediaStore + private storage. | AccessibilityService only (`FossAccessibilityService`, default off, two-step consent); no VpnService | `dev.librepocket.agent.foss` (`applicationIdSuffix = ".foss"`) |
| `github` | GitHub Releases only (direct download, never Play) | Same as `play` (see `app/src/github/AndroidManifest.xml`). | AccessibilityService only (`GithubAccessibilityService`, default off, two-step consent); no VpnService | `dev.librepocket.agent.github` (`applicationIdSuffix = ".github"`) |

Base `applicationId = dev.librepocket.agent`
(**reverse-DNS, permanent — it can never be changed after publication**;
Play owns the base ID and F-Droid indexes the foss build under the `.foss`
suffix). The `foss` / `github` flavors append `".foss"` / `".github"`, so all
three builds are co-installable side by side. They differ only in manifest
content (foss/github each add solely the accessibility automation service)
and in the vision stack (foss: ZXing/Tesseract/LiteRT pure OSS in
`src/foss`; github: ML Kit in `src/github` plus the OSS stack; play has
neither — see `docs/ARCHITECTURE.md` §9.4), never in base identity.

> History note: pre-1.0 docs used the name `full` for what is now the
> `github` flavor (`dev.librepocket.agent.github`). `foss` is new. See
> `docs/MIGRATION_FULL_TO_GITHUB.md`.

Build them with:

```sh
./gradlew :app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug
```

Only the `github` APK is published to GitHub Releases (Play AAB/APK are
policy self-proof, foss APKs are policy-checked but distributed via
F-Droid) — see `.github/workflows/release.yml`.

## Tech baseline

- compileSdk 37 (Android 17), targetSdk 36 (Play's 2026-08-31 requirement),
  minSdk 33 (Android 13)
- Kotlin + Android Gradle Plugin, version catalog in `gradle/libs.versions.toml`
- Planned building blocks (declared, not yet wired): okhttp-sse, Room,
  DataStore, androidx.security-crypto

Environment setup (JDK, `ANDROID_HOME`, SDK platforms) is documented in
`docs/ENV.md`. Brand rules live in `TRADEMARKS.md`.

## Acknowledgments

LibrePocket is an original clean-room implementation. It learns ideas only
from the projects below — no code is copied from non-compatible sources:

- [Eta](https://github.com/Mangi-11/Eta) — product/architecture inspiration
  (system-level agent, fast/slow paths, BYOK). Eta is PolyForm Noncommercial,
  so LibrePocket reimplements ideas independently and ships under Apache-2.0.
- [pi](https://github.com/earendil-works/pi) (MIT) — steering semantics,
  JSONL session-truth idea.
- [openclaw](https://github.com/openclaw/openclaw) (MIT) — gateway and
  auth-profile ideas.
- [opencode](https://github.com/sst/opencode) (MIT) — models.dev-driven
  provider catalog and permission-ruleset ideas.
- [hermes-agent](https://github.com/NousResearch/hermes-agent) (MIT) —
  on-device FTS recall idea.
- [mobilerun](https://github.com/droidrun/mobilerun) (MIT) — GUI harness and
  app-card ideas.
- [OmniBot](https://github.com/omnimind-ai/OmniBot) — closest on-device
  peer, used as black-box functional reference only (AGPL, not compatible,
  no code reuse).
- [AgentCPM-GUI](https://github.com/OpenBMB/AgentCPM-GUI) (Apache-2.0) —
  grounding action-space reference.
- [models.dev](https://models.dev) — open model directory snapshot idea.
- TypeSafe Jev (proprietary, via OpenRouter) — optional discrimination head
  (Choice/Score/Noul), off by default, key user-supplied.

## License

Apache-2.0 (`LICENSE`). Brand rules in `TRADEMARKS.md` (code is free,
brand is not). Third-party notices in `NOTICE`.
