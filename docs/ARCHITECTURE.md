# LibrePocket 架構：Current、Target 與 Proposed

> **Current snapshot**：依 `82f2a0a8` 核對（2026-10-22，S1–S7＋R27＋R28-1）；#96 後 `lsp.symbols` 已 executionReady（翻轉點 `b804ce89e99588cd5ca7c9cc3556e4dc37df6911`，見 CAPABILITY_MATRIX），但那是 registry readiness 旗標：production composition 以 `toolSlice=null` 建 session（records-only），`SafeToolSlice` 迴圈僅測試附掛，本快照沒有 production 內建工具執行路徑。#96 P2 Phase 3 硬化（`82f2a0a8`）：`pathPrefix` 拒絕全部 Unicode FORMAT（Cf）碼點（含 supplementary plane）；空白 call id 在預算／dedup 之前以 SCHEMA_INVALID 拒絕整輪且零 backend 呼叫，該輪仍可續聊（slice 專屬決定性合成 wire id `call_blank_slice_<n>`，碰撞時重綁 `_2`／`_3`），續聊 call／result 以 index 精確配對；重複 raw id 與 redaction 碰撞 id 在 backend 之前即整輪拒絕，但拒絕仍可續聊（決定性消歧 wire id `_2`／`_3`）；args 根非 object 時 fallback 為固定物件 `{"echo":"args redacted/capped"}`，provider adapter 不會再以 `TOOL_ARGUMENTS_MUST_BE_OBJECT` 失敗；backend 欄位省略 receipt 固定為 `[backend field omitted]`（不洩漏 model 長度）；call-id 後綴界定為 128-bit（32 hex）；timeout 分流——backend 自身的 `TimeoutCancellationException` 映射 EXEC_ERROR，slice 自身計時器映射 EXEC_DENIED；secret 家族的 number／bool／null 值收斂為 redaction receipt；history 壓縮改為 marker-aware 掃描（不再依賴原始「最後一個 `]`」啟發式）：切點落在 marker 內（含引號內的 `]`）時乾淨裁切、畸形 opener 不再吃掉後續有效 marker 與其後答案、truncation 後綴僅在 cap 實際切掉該列時附加；Responses provider 的 canonical 數字比較改用精確十進位值（BigDecimal，非 Double）：`1`／`1.0`／`1e2`／`100` 視為同一呼叫，超過 2^53 的整數維持相異，矛盾重放 fail-closed；MCP 畸形／空白 `baseUrl` 在 vault 讀取與 HTTP 之前以 UNAUTHORIZED 拒絕且不計熔斷失敗（DISABLED／已開路 CIRCUIT_OPEN 先行；callTool 畸形 args 仍優先 PROTOCOL）；Stage 1 & 2 硬化修復：ChatViewModel restoreOpForFailedSend 確立 notice-last 順序不變量消除跨分支發送競態、SafeToolSlice 預算超限（budget-exceeded）正規化收據 wire ID。#93 已合併（`c3ae01fb`），本分支已完成 rebase 並更新 branch-only `.gitleaksignore` 指紋。本文分開記錄目前接線與未完成設計，不把規劃圖視為產品能力。
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

Built-in ToolRegistry: 41 declared definitions; executionReady=true for exactly 1 (`lsp.symbols`, the P2 READ-only slice); false for the other 40
  ├─ registry eligibility (`ToolRegistry.visibleTools`): ["lsp.symbols"] when the lsp switch is on; empty otherwise (default off)
  └─ production: ChatRequest.tools empty and no execution — ChatSessionFactory defaults toolSlice=null (records-only); the slice loop is test-attached (SafeToolSlice budget-exceeded normalized wire IDs; ChatViewModel restoreOpForFailedSend notice-last invariant)
```

- `play`, `foss`, and `github` are distinct product flavors with distinct application IDs. `foss` and `github` each package an accessibility service; `github` has additional dependencies including ML Kit, Azure Speech, and Shizuku. These source sets are not equivalent implementations of every planned capability.
- The provider adapters implement three wire protocols, not universal compatibility with all providers that advertise a similar API.
- Setup model refresh merges the provider's live listing with the provider-scoped models.dev snapshot. The directory GET is keyless and bounded to 4 MiB of encoded response-body entity bytes before transparent gzip plus an independent 4 MiB decoded UTF-8 limit, 10 s connect/read-idle limits, and a 20 s whole-call timeout; redirects and retries are disabled. Here “wire bytes” means the encoded HTTP response-body representation observed before transparent gzip—not TCP/TLS bytes, headers, HTTP chunk framing, or an OS socket-buffer hard cap. OkHttp/Okio may prefetch bounded internal buffers, so the cap+1 rule limits bytes delivered through the counting source, not packet-level reads. Setup status describes only the directory source; live provider listing remains independent. Bundled fallback reasons are typed and rendered as fixed text. The snapshot freshness helper is not an active cache authority on this path.
- Room backs the current session transcript path. JSONL codec and import/export code exist at library level; a user-facing migration workflow is not present in the current app flow. Import rejects a crafted non-partial non-final `assistant` row fail-closed (`reasoning` flag-only markers exempt); pre-F lines without `isFinal` still read back final.
- Ledger durability (Current): `TurnController` guards never-true-and-dangling with a cancel-receipt close barrier (host settle → waiter handoff → flush barrier → seal), the SEALED-truth contract (SEALED alone is not settled), remaining-budget linearization (the settle shares one `drainTimeoutMs` fence; waiters get only the remainder), and an eight-check `flushDurable` (drain + receipt + sealed + admission/promotion/host/sticky proofs + failure-epoch triple read fail-last). `hasUnsettledAdmission` covers direct, promotion, UNKNOWN-ghost, and orphan windows. Interface defaults report no obligations, so a `true` from a fake proves nothing; only production with a real sink is evidence. The same-origin hydrate guarantee holds only for R4-1+ new/fork ledgers (single-recipient by construction); pre-R4-1 mixed ids need manual isolation — see [Threat Model](THREAT_MODEL.md). Prune (R27-1/R27-2 Current): the raw keep-newest cut and the family fixup run in one Room transaction per session (a kill between them rolls back to the pre-prune shape); the fixup is unconditional and unbounded-convergent with shape-aware suspect clearing, so new prunes leave no head orphan across restart — interior/pre-R26 orphans excepted, see [Threat Model](THREAT_MODEL.md).
- Permission projection, tool definitions, routing/helper classes, and flavor services do not by themselves mean that an end-to-end tool can be invoked. Tool execution is not wired for the built-in registry in this snapshot. No built-in tool has production execution wiring in this snapshot: production sessions are constructed without a tool slice (`toolSlice=null`, records-only), so the P2 `SafeToolSlice` loop — the only loop that could execute `lsp.symbols` — is attached by tests only. The other 40 tools also lack dispatcher wiring. A process kill can lose best-effort tool markers; nothing re-executes on reopen (see `SafeToolSlice` durability KDoc). MCP 第三方工具同未接線（見 CAPABILITY_MATRIX MCP 行與「MCP 啟用／接線 gate 清單」；`toToolDef` 沿用 `executionReady=false` 雙閘；`project()`／`check()`／`canExecute()`／`toToolDef()` 皆不收 `sideEffect` 參數——MCP 子集無 sideEffect 宣告，一律按 WRITE 對待、未確認即需確認）；零 production import 由 `scripts/docs_claim_check.sh` 的 zero-wiring 檢查在 CI `docs-guard` job 內 fail-closed 執行（違反即該 job 紅）；`main` 已配置 branch protection 並將 `PR Gate` 列為 required status check，`pr-gate` 在 `docs-guard` 非 success 時以 exit 1 失敗，故 zero-wiring 閘紅燈會阻擋 merge（`enforce_admins` 未啟用、repository rulesets 為 0、required approving reviews 為 0、未配置 CODEOWNERS；此狀態可用 `gh api repos/<owner>/<repo>/branches/main/protection` 複核，見 TESTING.md）。接線 PR 須刻意放寬該閘。
- Android backup is enabled and excludes key-specific paths, not a general transcript exclusion. See [Threat Model](THREAT_MODEL.md).
- 語音 UI 手勢直連（有意決策 Stage2）：`ChatScreen` 的系統 STT 委託（`RecognizerIntent`）與系統 TTS（`VoiceSpeaker`）是使用者逐次手勢的直接呼叫（朗讀需逐則點按；`voice_input`/`voice_output` 開關經 `VoiceSwitchStore` DataStore 持久化，在設定頁語音卡可切換，由 `MainScreen` 讀快照傳入 `ChatScreen(userSwitches=...)`，關閉即隱藏對應入口，與投影層同 key、同預設），不經模型工具鏈；`executionReady=false` 僅封鎖模型可見與可執行（語音工具一律不進模型名單；`lsp` 開關開啟時 `visibleTools` 含 `lsp.symbols`），不約束使用者手勢。
- 朗讀首次點按顯示內聯確認（`VoiceTts.ttsConfirmMessage`，確認狀態持久化、只問一次；朗讀鈕無障礙文案與設定頁語音卡維持揭露）。
- 引擎失敗經分離的 `sttError`／`ttsError` 可觀察（TTS 成功／新 speak 發起／關閉輸出清除 TTS 錯誤；STT 成功不清 TTS 錯誤）；TTS 細分：無引擎 `NO_ENGINE` fail-closed（空表；查詢未知維持原流程）、缺中文語料走降級提醒（`LANG_UNAVAILABLE` 警告非失敗、仍朗讀）、listener 掛載失敗僅警告（`LISTENER_FAILED`，引擎仍可用）；系統引擎的資料處理取決於已安裝引擎。
- 未來雲端語音分派（Azure fallback）落地時走 dispatcher + 新鮮政策評估 + 送雲前 redact（見 `VoiceTools.redactForCloud`）。
- Setup 保存管線（S6 Current）：entry 快照→V1 新鮮 `key.write` 評估→form fence→ASK 時 mint one-shot 同意書＋確認對話框（STAGED／KEEP_EXISTING 皆帶身份列）→confirm 攜 consent 第二 entry→staged 路徑（per-op staged alias put＋V2＋metadata CAS＋舊 alias 退役）或 KEEP_EXISTING 路徑（存在性 `hasKey` 前提＋metadata-only CAS，零 put/get/delete）；探測（測試連線／模型列表）走獨立 `provider.call` 新鮮閘，僅 `ALLOW` 放行。拒絕收斂至單一 `POLICY_DENIED_KEY_WRITE`（防 oracle，見威脅模型），teardown 競爭另立可重試碼。
- Chat 讀 key 管線（S6 Current）：T0 新鮮 `key.read` 評估＋綁定式 one-shot 確認（P2 token：拒絕時與 emission 同產 `PendingKeyReadConfirm(binding＋generation＋confirmSequence＋opId/openSessionId)`，sequence 單調遞增永不重置；`confirmKeyRead(expected, token)` 驗「仍為原請求、generation/seq 未變、binding 全欄相等」三者全等才 mint，否則 false 且零 vault 讀；mint 成功同鎖耗 token（單次有效，同 token 二次必 false）；`invalidateLifecycle(clearChat)`／`invalidateForEndpointChange(null)`／`openSession` 新輪／logout 皆 burn token＋consent（同一 `keyReadConsentLock` 下 bump＋burn），`dismiss` 同鎖丟棄兩者；`ChatScreen` capture seq＋binding＋token，mint 前驗新鮮、mint 後 stale completion 丟棄）→T1 capture 邊界重驗→vault 讀→會話建構；`ALLOW`→`ASK` 翻轉拒絕；T1→`vault()`→`getKey` 含 suspend／IO（dispatcher hop＋Keystore/disk IO）的殘留窗口未持有門控跨越（屬已知殘留）；窗口內 `DENY` 翻轉後續無門捕捉，binding／generation 翻轉擋 attach 但擋不住已發生的 vault 讀（僅 close 抹除）。

- 端點身份與授權分流（Stage1 Current）：custom 端點傳輸身份為 per-URL 穩定 entity id（`custom:<sha256-32hex(baseUrl)>`，內建 preset 維持 `preset:<id>`），授權 scope 仍凍結 preset 粒度（`preset:<presetId>`，見 Target）；legacy custom 列（`preset:custom`）四層 fail-closed（store readiness→NO_KEY、Setup gate→NoEndpoint、chat binding 缺席、factory→ENDPOINT_ID_LEGACY，零 vault 讀）；URL respelling（除首尾空白外，派生前 trim）即新 entity，須重貼 key（fail-closed）；store 寫入與 factory 讀取另驗 providerId／baseUrl／apiKeyRef triple 命名空間一致（legacy 列寫入豁免以保留遷移播種，讀側仍 fail-closed：store readiness→NO_KEY、Setup gate→NoEndpoint、chat binding 缺席、factory→ENDPOINT_ID_LEGACY；遷移時僅回填位址／模型，金鑰恆需重貼）。
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
