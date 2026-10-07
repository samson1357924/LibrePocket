# LibrePocket 架構：Current、Target 與 Proposed

> **Current snapshot**：依 `b3d8a818f37f8455a254a0670b512286e4deb750` 核對（2026-10-07）。本文分開記錄目前接線與未完成設計，不把規劃圖視為產品能力。
>
> - **範圍：** Android app composition、provider、Room transcript、flavor 與工具執行狀態。
> - **Owner role：** app/runtime 維護者；未指派個人。
> - **Source of truth：** app composition、provider/session/tool source、Gradle flavor/dependency、manifest；功能可用狀態另見 [能力矩陣](CAPABILITY_MATRIX.md)。
> - **更新觸發：** production composition、資料儲存、provider protocol、tool readiness 或 flavor/manifest 改變時。

## Current：現行組成

```text
Compose Android UI
  ├─ endpoint setup ── KeyVault / ProviderConfig
  ├─ chat session ──── DefaultProviderFactory
  │                     ├─ Chat Completions adapter
  │                     ├─ Responses adapter
  │                     └─ Anthropic Messages adapter
  └─ session list ──── RoomSessionStore ── Room database

Built-in ToolRegistry: 41 declared definitions; executionReady=false for all
  └─ model-visible built-in tool list: empty
```

- `play`, `foss`, and `github` are distinct product flavors with distinct application IDs. `foss` and `github` each package an accessibility service; `github` has additional dependencies including ML Kit, Azure Speech, and Shizuku. These source sets are not equivalent implementations of every planned capability.
- The provider adapters implement three wire protocols, not universal compatibility with all providers that advertise a similar API.
- Room backs the current session transcript path. JSONL codec and import/export code exist at library level; a user-facing migration workflow is not present in the current app flow.
- Permission projection, tool definitions, routing/helper classes, and flavor services do not by themselves mean that an end-to-end tool can be invoked. Tool execution is not wired for the built-in registry in this snapshot.
- Android backup is enabled and excludes key-specific paths, not a general transcript exclusion. See [Threat Model](THREAT_MODEL.md).

## Target：現有設計文件描述的方向

The design documents describe a provider-neutral chat loop, explicit session and transcript contracts, capability projection, policy checks, and optional flavor-specific adapters. Treat a design section as a target contract only when the relevant code is not wired and verified in the current production composition.

Before enabling any external side effect, preserve these boundaries:

- capability state must distinguish declared, implemented, wired, and verified;
- permission and flavor checks must fail closed, and user confirmation must bind to the exact operation snapshot;
- credential selection must remain bound to endpoint identity/configuration;
- cancellation and session-generation changes must prevent stale network work or callbacks from attaching to a later session;
- filesystem and process boundaries must use canonical paths/trusted executables, not Root/Shizuku/PRoot as a claim of OS sandboxing.

These are engineering requirements, not claims that the whole Target is complete. See [Threat Model](THREAT_MODEL.md) and [Testing](TESTING.md).

## Proposed：未採納決策

- **Transcript authority:** an append-only JSONL journal is a possible future canonical source of truth. It is **not current**; Room is the current persisted session store. Writer ordering, crash recovery, schema evolution, and migration/rollback need an explicit decision and implementation before changing authority.
- **Capability execution:** wire and verify tools individually with production dispatchers, fresh policy evaluation, cancellation, and positive/negative controls. Do not bulk-enable `executionReady`.
- **Flavor parity:** decide capability parity based on source sets, dependencies, manifests, and artifact evidence; do not mirror a service or label a flavor “same capability” based only on a shared interface.

No proposal in this section is an Accepted ADR. Decision records should be added only when maintainers actually accept a cross-layer decision.

## Related documents

- [Capability matrix](CAPABILITY_MATRIX.md) — current status versus declared/scaffolded capability.
- [P1 specification](specs/P1_SPEC.md) — historical design specification with current divergence notice.
- [Build environment](ENV.md), [Testing](TESTING.md), and [Release](RELEASE.md).
