# LibrePocket 能力與接線狀態

> **Current snapshot**：依 `6658e63086ea5b1af25ffe5bca4aaf28d6cee78e`（2026-10-15 提交，2026-10-15 核對）。本文件不把規格列出的能力當成已可用功能。
>
> - **範圍：** built-in tools、provider adapters、三個 flavor 的 source/manifest 邊界。
> - **Owner role：** app/runtime 維護者；未指派個人。
> - **Source of truth：** `ToolRegistry`/`ToolDef`, production composition, flavor source sets, dependencies, and manifests; CI artifact gates provide only the checks they explicitly run.
> - **更新觸發：** 工具實際接線/readiness、provider adapter、manifest、flavor dependency 或 artifact gate 改變時。

## 狀態詞彙

- **Declared**：有型別、schema、描述或規格列項。
- **Implemented**：有 production implementation；測試 fake 不算。
- **Wired**：從 production app entry point 接到實際 dispatcher/副作用。
- **Verified**：有對應正向與負向測試；若需 Android 行為，須有指定裝置/API 的實跑證據。

單一狀態不自動代表下一狀態。圖示、投影結果、權限列、dependency 或 service 宣告均不是 end-to-end 功能證明。

## Current：可確認的邊界

| 項目 | Current evidence | 不可推論為 |
|---|---|---|
| Chat provider adapters | `Chat Completions`, `Responses`, `Anthropic Messages` adapter 與 factory 存在 | 對每個相容宣稱的 endpoint 均已互通或認證 |
| Session transcript | production chat/session path 使用 Room；JSONL codec/import-export 有 library code | JSONL 是 canonical store；有可供使用者操作的匯出/遷移 UI |
| Ledger durability (`flushDurable`) | `TurnController.flushDurable` 八檢查合取（排空＋無 PENDING/CLAIMED receipt＋無未補償 SEALED＋admission／promotion／host／sticky 證明＋failure epoch 雙讀），永不 true-and-dangling；close 以 cancel-receipt barrier＋remaining-budget 線性化交接 | `true` 代表跨重啟耐久；fake session 的 `true` 可作耐久證據；SEALED 等同 settled |
| Cancel receipt / admission proofs | `hasUnsettledCancelReceipts`（PENDING/CLAIMED）、`hasSealedUncompensatedReceipts`（SEALED truth）、`hasUnsettledAdmission`（direct／promotion／UNKNOWN ghost／orphan；R22 由 direct-only 擴大）皆為 production 實作並合取進 `flushDurable` | 僅有 KDoc／介面預設即代表已接線；介面預設 `false` 可作耐久證據 |
| Built-in tools | registry 有 41 個 definitions；目前 `executionReady=false` 全部未啟用；model-visible built-in tool 集合為空 | 模型可以執行 schema 中描述的動作 |
| `play` | base application ID；Play overlay 移除高風險權限/service | 商店已上架或政策已被商店批准 |
| `foss` | `.foss` application ID；Foss manifest/source set 有 accessibility service 與 OSS vision dependencies | 與 `github` 有相同能力或已由 F-Droid 發布 |
| `github` | `.github` application ID；含 accessibility service 及 GitHub-only ML Kit/Azure Speech/Shizuku dependencies | 「完整版」意指所有設計能力均已接線 |
| Backup | manifest 設 `allowBackup=true`；XML rules 排除 key-specific paths | transcript、preferences、audit 都不會進入 Android backup |
| ScopedFileStore | 根鏈+段鏈 NOFOLLOW 全驗（declared/implemented，未 wired）；單線程毒根/段內連結確定性拒絕 | 已消除併發 symlink escapes；JSONL 是 canonical store；有裝置實證 |
| Direct shell executable | `ShellExecutables.resolve`＋`RestrictedShell` 有實作，正/負向 JVM 測試存在 | 已在指定裝置/API 實跑驗證（含 toybox/APEX、`isWritable` 真實語義）或可上線啟用 |
| Setup key.write／probe 門 | staged 寫入＋metadata CAS、ASK one-shot 確認（含 STAGED／KEEP_EXISTING 身份列）、probe 的 provider.call 新鮮閘、KEEP_EXISTING metadata-only（存在性 hasKey 前提，零 put/get/delete）；JVM 單測存在 | 裝置端到端外洩證明；policy 後端本身的可用性保證；自訂端點在政策層的細粒度區分（共用同一 resource，見威脅模型） |
| Chat key.read 門 | T0 新鮮評估＋綁定式 one-shot 確認、T1 capture 邊界重驗（ALLOW→ASK 翻轉拒絕、拒絕丟棄殘留槽位；T1→`vault()`→`getKey` 含 suspend／IO 的殘留窗口：`DENY` 翻轉後續無門捕捉，binding／generation 翻轉擋 attach 但擋不住已發生的 vault 讀，僅 close 抹除）；JVM 單測存在 | 重驗與 vault 讀之間的殘留窗口已消除；裝置端到端證明（已知殘留：含 suspend／IO，dispatcher hop＋Keystore/disk IO，未持有門控跨越；僅 JVM 單測） |
| 語音 UI 手勢直連 | `ChatScreen` 直連系統引擎：使用者逐次手勢 + DataStore 持久化同名開關（`voice_input`/`voice_output`，設定頁可切、關閉隱藏入口）；朗讀首次內聯確認（只問一次）；引擎失敗經分離的 `sttError`／`ttsError` 可觀察（`NO_ENGINE` 無引擎 fail-closed；`LANG_UNAVAILABLE` 缺語料僅降級提醒非失敗；`LISTENER_FAILED` 掛載失敗僅警告）；引擎資料處理已揭露 | 直連等同政策授權；模型可經工具鏈執行語音（模型側 `executionReady=false` 維持未啟用） |

`foss` 和 `github` 在某些 source/service 形狀上相似，不代表依賴、bridge 或能力相同。Flavor distribution/source-of-truth 見 [README](../README.md)、[Architecture](ARCHITECTURE.md) 與 [Threat Model](THREAT_MODEL.md)。

## Target：工具接線前的驗收

每個工具需單獨列明 flavor、使用的實體資源、權限要求、使用者確認、fresh policy evaluation、dispatcher/executor、取消與錯誤語義、持久化/審計路徑，以及正向與拒絕控制測試。未完成上述驗收時保持 `executionReady=false`。不要把 41 項一次改為 ready。

## Proposed：風味與能力對照表

未來若要發布面向使用者的逐功能矩陣，應由同一 SHA 的 production composition、source set/dependency、merged manifest、artifact checks 和裝置證據產生。當前沒有可支持完整功能表的端到端驗證；因此本文件刻意不列「完整/可用」打勾表。
