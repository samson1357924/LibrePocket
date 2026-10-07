# LibrePocket 能力與接線狀態

> **Current snapshot**：依 `b3d8a818f37f8455a254a0670b512286e4deb750` 核對（2026-10-07）。本文件不把規格列出的能力當成已可用功能。
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
| Built-in tools | registry 有 41 個 definitions；目前 `executionReady=false` 全部未啟用；model-visible built-in tool 集合為空 | 模型可以執行 schema 中描述的動作 |
| `play` | base application ID；Play overlay 移除高風險權限/service | 商店已上架或政策已被商店批准 |
| `foss` | `.foss` application ID；Foss manifest/source set 有 accessibility service 與 OSS vision dependencies | 與 `github` 有相同能力或已由 F-Droid 發布 |
| `github` | `.github` application ID；含 accessibility service 及 GitHub-only ML Kit/Azure Speech/Shizuku dependencies | 「完整版」意指所有設計能力均已接線 |
| Backup | manifest 設 `allowBackup=true`；XML rules 排除 key-specific paths | transcript、preferences、audit 都不會進入 Android backup |

`foss` 和 `github` 在某些 source/service 形狀上相似，不代表依賴、bridge 或能力相同。Flavor distribution/source-of-truth 見 [README](../README.md)、[Architecture](ARCHITECTURE.md) 與 [Threat Model](THREAT_MODEL.md)。

## Target：工具接線前的驗收

每個工具需單獨列明 flavor、使用的實體資源、權限要求、使用者確認、fresh policy evaluation、dispatcher/executor、取消與錯誤語義、持久化/審計路徑，以及正向與拒絕控制測試。未完成上述驗收時保持 `executionReady=false`。不要把 41 項一次改為 ready。

## Proposed：風味與能力對照表

未來若要發布面向使用者的逐功能矩陣，應由同一 SHA 的 production composition、source set/dependency、merged manifest、artifact checks 和裝置證據產生。當前沒有可支持完整功能表的端到端驗證；因此本文件刻意不列「完整/可用」打勾表。
