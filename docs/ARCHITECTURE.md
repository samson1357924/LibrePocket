# LibrePocket 架構：Current、Target 與 Proposed

> **Current snapshot**：依 `78fc509` 核對（2026-10-15，S1–S7）。本文分開記錄目前接線與未完成設計，不把規劃圖視為產品能力。
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
- Setup model refresh merges the provider's live listing with the provider-scoped models.dev snapshot. The directory GET is keyless and bounded to 4 MiB of encoded response-body entity bytes before transparent gzip plus an independent 4 MiB decoded UTF-8 limit, 10 s connect/read-idle limits, and a 20 s whole-call timeout; redirects and retries are disabled. Here “wire bytes” means the encoded HTTP response-body representation observed before transparent gzip—not TCP/TLS bytes, headers, HTTP chunk framing, or an OS socket-buffer hard cap. OkHttp/Okio may prefetch bounded internal buffers, so the cap+1 rule limits bytes delivered through the counting source, not packet-level reads. Setup status describes only the directory source; live provider listing remains independent. Bundled fallback reasons are typed and rendered as fixed text. The snapshot freshness helper is not an active cache authority on this path.
- Room backs the current session transcript path. JSONL codec and import/export code exist at library level; a user-facing migration workflow is not present in the current app flow. Import rejects a crafted non-partial non-final `assistant` row fail-closed (`reasoning` flag-only markers exempt); pre-F lines without `isFinal` still read back final.
- Ledger durability (Current): `TurnController` guards never-true-and-dangling with a cancel-receipt close barrier (host settle → waiter handoff → flush barrier → seal), the SEALED-truth contract (SEALED alone is not settled), remaining-budget linearization (the settle shares one `drainTimeoutMs` fence; waiters get only the remainder), and an eight-check `flushDurable` (drain + receipt + sealed + admission/promotion/host/sticky proofs + failure-epoch triple read fail-last). `hasUnsettledAdmission` covers direct, promotion, UNKNOWN-ghost, and orphan windows. Interface defaults report no obligations, so a `true` from a fake proves nothing; only production with a real sink is evidence. The same-origin hydrate guarantee holds only for R4-1+ new/fork ledgers (single-recipient by construction); pre-R4-1 mixed ids need manual isolation — see [Threat Model](THREAT_MODEL.md).
- Permission projection, tool definitions, routing/helper classes, and flavor services do not by themselves mean that an end-to-end tool can be invoked. Tool execution is not wired for the built-in registry in this snapshot.
- Android backup is enabled and excludes key-specific paths, not a general transcript exclusion. See [Threat Model](THREAT_MODEL.md).
- 語音 UI 手勢直連（有意決策 Stage2）：`ChatScreen` 的系統 STT 委託（`RecognizerIntent`）與系統 TTS（`VoiceSpeaker`）是使用者逐次手勢的直接呼叫（朗讀需逐則點按；`voice_input`/`voice_output` 開關經 `VoiceSwitchStore` DataStore 持久化，在設定頁語音卡可切換，由 `MainScreen` 讀快照傳入 `ChatScreen(userSwitches=...)`，關閉即隱藏對應入口，與投影層同 key、同預設），不經模型工具鏈；`executionReady=false` 僅封鎖模型可見與可執行（`visibleTools` 為空），不約束使用者手勢。
- 朗讀首次點按顯示內聯確認（`VoiceTts.ttsConfirmMessage`，確認狀態持久化、只問一次；朗讀鈕無障礙文案與設定頁語音卡維持揭露）。
- 引擎失敗經分離的 `sttError`／`ttsError` 可觀察（TTS 成功／新 speak 發起／關閉輸出清除 TTS 錯誤；STT 成功不清 TTS 錯誤）；TTS 細分：無引擎 `NO_ENGINE` fail-closed（空表；查詢未知維持原流程）、缺中文語料走降級提醒（`LANG_UNAVAILABLE` 警告非失敗、仍朗讀）、listener 掛載失敗僅警告（`LISTENER_FAILED`，引擎仍可用）；系統引擎的資料處理取決於已安裝引擎。
- 未來雲端語音分派（Azure fallback）落地時走 dispatcher + 新鮮政策評估 + 送雲前 redact（見 `VoiceTools.redactForCloud`）。
- Setup 保存管線（S6 Current）：entry 快照→V1 新鮮 `key.write` 評估→form fence→ASK 時 mint one-shot 同意書＋確認對話框（STAGED／KEEP_EXISTING 皆帶身份列）→confirm 攜 consent 第二 entry→staged 路徑（per-op staged alias put＋V2＋metadata CAS＋舊 alias 退役）或 KEEP_EXISTING 路徑（存在性 `hasKey` 前提＋metadata-only CAS，零 put/get/delete）；探測（測試連線／模型列表）走獨立 `provider.call` 新鮮閘，僅 `ALLOW` 放行。拒絕收斂至單一 `POLICY_DENIED_KEY_WRITE`（防 oracle，見威脅模型），teardown 競爭另立可重試碼。
- Chat 讀 key 管線（S6 Current）：T0 新鮮 `key.read` 評估＋綁定式 one-shot 確認（P2 token：拒絕時與 emission 同產 `PendingKeyReadConfirm(binding＋generation＋confirmSequence＋opId/openSessionId)`，sequence 單調遞增永不重置；`confirmKeyRead(expected, token)` 驗「仍為原請求、generation/seq 未變、binding 全欄相等」三者全等才 mint，否則 false 且零 vault 讀；mint 成功同鎖耗 token（單次有效，同 token 二次必 false）；`invalidateLifecycle(clearChat)`／`invalidateForEndpointChange(null)`／`openSession` 新輪／logout 皆 burn token＋consent（同一 `keyReadConsentLock` 下 bump＋burn），`dismiss` 同鎖丟棄兩者；`ChatScreen` capture seq＋binding＋token，mint 前驗新鮮、mint 後 stale completion 丟棄）→T1 capture 邊界重驗→vault 讀→會話建構；`ALLOW`→`ASK` 翻轉拒絕；T1→`vault()`→`getKey` 含 suspend／IO（dispatcher hop＋Keystore/disk IO）的殘留窗口未持有門控跨越（屬已知殘留）；窗口內 `DENY` 翻轉後續無門捕捉，binding／generation 翻轉擋 attach 但擋不住已發生的 vault 讀（僅 close 抹除）。

## Target：現有設計文件描述的方向

The design documents describe a provider-neutral chat loop, explicit session and transcript contracts, capability projection, policy checks, and optional flavor-specific adapters. Treat a design section as a target contract only when the relevant code is not wired and verified in the current production composition.

Before enabling any external side effect, preserve these boundaries:

- capability state must distinguish declared, implemented, wired, and verified;
- permission and flavor checks must fail closed, and user confirmation must bind to the exact operation snapshot;
- credential selection must remain bound to endpoint identity/configuration;
- setup/chat policy gates keep their documented shape: custom endpoints share one policy resource (coarse granularity, known limitation); save denials converge to a single code (oracle avoidance) with teardown-blocked kept distinct; existence-only `hasKey` checks stay ahead of the verdict (deliberate, see Threat Model);
- cancellation and session-generation changes must prevent stale network work or callbacks from attaching to a later session;
- filesystem and process boundaries must use canonical paths/trusted executables, not Root/Shizuku/PRoot as a claim of OS sandboxing.

These are engineering requirements, not claims that the whole Target is complete. See [Threat Model](THREAT_MODEL.md) and [Testing](TESTING.md).

## Proposed：未採納決策

- **Transcript authority:** an append-only JSONL journal is a possible future canonical source of truth. It is **not current**; Room is the current persisted session store. Writer ordering, crash recovery, schema evolution, and migration/rollback need an explicit decision and implementation before changing authority.
- **Capability execution:** wire and verify tools individually with production dispatchers, fresh policy evaluation, cancellation, and positive/negative controls. Do not bulk-enable `executionReady`.
- **Flavor parity:** decide capability parity based on source sets, dependencies, manifests, and artifact evidence; do not mirror a service or label a flavor “same capability” based only on a shared interface.
- **Chat key.read 殘留窗口收斂（follow-up，未實作）：**T1→`vault()`→`getKey` 含 suspend／IO（dispatcher hop＋Keystore/disk IO）的殘留窗口仍在（未持有門控跨越）；窗口內 `DENY` 翻轉後續無門捕捉，binding／generation 翻轉擋 attach 但擋不住已發生的 vault 讀（僅 close 抹除）。收斂（如 vault 預熱＋緊貼 `getKey` 前重驗；加法式 second recheck：`vault()` 後、`getKey` 前再驗一次，只增拒絕不增放行，無優先序回歸，為選項）需先做語義 review（vault 錯誤與政策拒絕的優先序、`getKey` 本身 suspend 的不可消除部分），並補 barrier 測試釘住目前行為；本快照未實作，不宣稱消除。

No proposal in this section is an Accepted ADR. Decision records should be added only when maintainers actually accept a cross-layer decision.

## Related documents

- [Capability matrix](CAPABILITY_MATRIX.md) — current status versus declared/scaffolded capability.
- [P1 specification](specs/P1_SPEC.md) — historical design specification with current divergence notice.
- [Build environment](ENV.md), [Testing](TESTING.md), and [Release](RELEASE.md).
