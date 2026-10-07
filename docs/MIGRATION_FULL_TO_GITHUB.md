# 歷史 `full` → `github` 遷移說明 — **目前不可操作**

> **重要：不要依照舊版本文卸載 app、清除資料或假設可從 UI 匯出/匯入。** 本文原有的逐步遷移指南與現行程式不符，已撤回為警告說明。保留舊安裝與其資料，直到有經實機驗證、與來源版本匹配的遷移方案；本文件不提供卸載步驟。

- **狀態：** Current warning / Historical naming note。依 `b3d8a818f37f8455a254a0670b512286e4deb750` 核對（2026-10-07）。
- **Owner role：** app/release 維護者；未指派個人。
- **Source of truth：** `app/build.gradle.kts`, `app/src/*/AndroidManifest.xml`, current UI/session composition.
- **更新觸發：** 真的新增且實機驗證 user-facing migration/export/import 流程，或 flavors/application IDs 改變時。

## Current：名稱與包識別

此 commit 定義 `play` (`dev.librepocket.agent`)、`foss` (`dev.librepocket.agent.foss`) 與 `github` (`dev.librepocket.agent.github`) 三個 flavor；沒有 `full` flavor。不同 application ID 是不同 Android app。舊文件把歷史 `full` 映射到 `github` 的說法，不足以證明舊包能原地更新或資料能自動搬移；簽章、舊版實際 package ID、版本與裝置狀況都會影響結果。

## Current：資料匯出、匯入與備份的限制

- Room 是目前 session transcript 的 production persistence。JSONL codec、Room store import/export 方法及 backup/export helper 有 library-level code，但目前 app UI 沒有本文曾承諾的完整 SAF 選檔、使用者確認、跨 flavor 匯出再匯入流程。
- Android manifest 的 `allowBackup` 為 `true`。備份規則排除特定 key 檔案/目錄；它們不是 transcript、preferences 或 audit 的全面排除規則。Android/OEM/使用者備份行為不在本文保證範圍。
- App-level backup bundle 預設不納入 keys，但程式介面可顯式指定包含 keys。不要把 key-only Android backup exclusions 說成所有匯出格式都絕不含 key。
- Transcript 寫入 Room 時套用 redaction；這不是 provider request 的傳送前遮蔽保證。JSONL、備份檔與 provider request 均應視為可能含敏感內容，直至逐路徑驗證。

## Target：安全的遷移文件必須等待

只有在來源與目標版本均可取得、完整 UI 工作流可用，且已驗證檔案格式、大小限制、失敗原子性、key 排除、簽章/application ID 及實機恢復結果後，才可以發布操作步驟。測試必須使用 synthetic data，並在新指南清楚區分 Android system backup 與 app-level export。

在這之前：不要卸載來源 app、不要清除 app data，也不要把本地 JSONL helper 當成已提供的遷移介面。若已不慎移除舊 app，本文件不能承諾資料可恢復。

相關現況見 [Threat Model](THREAT_MODEL.md)、[Testing](TESTING.md) 與 [Release](RELEASE.md)。
