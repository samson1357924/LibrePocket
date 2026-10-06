# LibrePocket – Open Mobile AI Agent

An international, open-source on-device AI agent for Android.
Licensed under the Apache License 2.0 (see `LICENSE`).

> **Not affiliated** with any device vendor, carrier, model provider, or
> app store. LibrePocket is an independent community project.

## Status

Infrastructure skeleton only (v0.1.0): build system, product flavors,
permissions model, and project documents. There is **no agent loop and no
tool implementations** yet — see "Next steps" below.

## Two distributions, one applicationId

| Flavor | Channel | Permissions | Services |
|--------|---------|-------------|----------|
| `play` | Google Play | Store-safe only: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE. High-risk permissions are additionally stripped via `tools:node="remove"` (see `app/src/play/AndroidManifest.xml`). | No AccessibilityService, no VpnService |
| `full` | F-Droid / direct download | Everything in `play`, plus READ_SMS, RECEIVE_SMS, MANAGE_EXTERNAL_STORAGE (see `app/src/full/AndroidManifest.xml`). | Placeholder AccessibilityService + VpnService declarations |

Both flavors share `applicationId = dev.librepocket.agent`
(**reverse-DNS, permanent — it can never be changed after publication**,
and F-Droid indexes the app under it). They differ only in manifest
content, never in identity.

Build them with:

```sh
./gradlew :app:assemblePlayDebug :app:assembleFullDebug
```

## Tech baseline

- compileSdk 37 (Android 17), targetSdk 36 (Play's 2026-08-31 requirement),
  minSdk 33 (Android 13)
- Kotlin + Android Gradle Plugin, version catalog in `gradle/libs.versions.toml`
- Planned building blocks (declared, not yet wired): okhttp-sse, Room,
  DataStore, androidx.security-crypto

Environment setup (JDK, `ANDROID_HOME`, SDK platforms) is documented in
`docs/ENV.md`. Brand rules live in `TRADEMARKS.md`.

## Next steps (not yet implemented)

1. Agent loop + tool registry design doc.
2. Network transport wrapper on okhttp-sse.
3. Room schema v1 + DataStore settings keys.
4. Encrypted credential storage via security-crypto.
5. Release signing, versionCode policy, Play Data Safety form draft.
