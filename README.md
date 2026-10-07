# LibrePocket — Open Mobile AI Agent

> **Alpha — not production ready.** This repository does not promise stable APIs, durable database compatibility, or a supported upgrade path. Do not rely on it as the only copy of important data or credentials.

LibrePocket is an Android application for chatting with a user-configured model provider. Ordinary chat requests send message content to the configured provider without a global outbound redaction pass; selected hosted-search paths apply `Redactor`, but Room redaction does not protect the request already sent. “On device” describes the app, not necessarily model inference. See [Security](SECURITY.md) and the [threat model](docs/THREAT_MODEL.md) before using sensitive data.

## Current status

Checked against `b3d8a818f37f8455a254a0670b512286e4deb750` on 2026-10-07. This is a source snapshot, not a release or device certification. Scope: public product status. Owner role: project maintainers. Source of truth: checked-in app/build/CI configuration and the cited commit. Update trigger: changes to current runtime, flavor, privacy, test, or release claims.

- The current app has three Android product flavors: `play`, `foss`, and `github`, each with a distinct application ID. Their source sets, dependencies, and manifests are not capability-equivalent.
- The provider layer contains adapters for Chat Completions, Responses, and Anthropic Messages. Protocol support does not certify compatibility with every endpoint using those labels.
- Chat transcripts are currently persisted through Room. JSONL codecs and export/import logic exist as library code; the current app UI has no user-facing export/import route wired. The proposed append-only JSONL source-of-truth design is not the current storage contract.
- The built-in registry declares 41 tools, but none is marked `executionReady`; the current model-visible built-in tool list is therefore empty. A declaration, projection, or scaffold is not an executable feature.
- Android `allowBackup` is enabled. The configured Android backup rules exclude key-related files; they do not establish that transcripts, preferences, or audit data are excluded. Do not treat Android backup as transcript privacy protection.
- GitHub release automation exists, but there is no verified final-artifact gate that fails closed on signature/certificate, package, version, and debug state. Release readiness is not established.

## Build flavors

| Flavor | Application ID | Source / distribution boundary |
|---|---|---|
| `play` | `dev.librepocket.agent` | Play-oriented source set and manifest; no accessibility automation service. |
| `foss` | `dev.librepocket.agent.foss` | FOSS source set; includes the accessibility service and OSS vision dependencies. |
| `github` | `dev.librepocket.agent.github` | GitHub source set; includes the accessibility service and GitHub-only dependencies, including ML Kit, Azure Speech, and Shizuku. |

These are build boundaries, not a promise that all features work in each flavor. See [the capability matrix](docs/CAPABILITY_MATRIX.md) and [architecture](docs/ARCHITECTURE.md). Historical references to a `full` flavor are not proof that a user-facing migration path exists: read the warning in [the migration note](docs/MIGRATION_FULL_TO_GITHUB.md) before uninstalling anything.

## Build and verification

The source of truth for toolchain versions is `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, and `app/build.gradle.kts`. Setup is documented in [Build Environment](docs/ENV.md); current test scope and commands are in [Testing](docs/TESTING.md).

```sh
./gradlew :app:testPlayDebugUnitTest :app:testFossDebugUnitTest :app:testGithubDebugUnitTest --max-workers=1 --no-daemon
```

A command listed in documentation is not evidence it was run. CI currently runs flavor unit/Robolectric tests, lint, debug assembly, and Play/Foss policy checks; it does not run the documented instrumented-device matrix on every pull request.

## Project documents

- [Security reporting and handling](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Testing](docs/TESTING.md)
- [Release process and gaps](docs/RELEASE.md)
- [Architecture: current, target, and proposed](docs/ARCHITECTURE.md)
- [Capability status](docs/CAPABILITY_MATRIX.md)
- [Roadmap](docs/ROADMAP.md) and [backlog](docs/BACKLOG.md)
- [Historical full-to-GitHub migration note](docs/MIGRATION_FULL_TO_GITHUB.md)

## License

Apache License 2.0; see `LICENSE`. LibrePocket is independent and is not affiliated with device vendors, carriers, model providers, or app stores.
