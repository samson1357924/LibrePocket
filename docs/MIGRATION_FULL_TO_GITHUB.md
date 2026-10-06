# `full` → `github` 遷移公告與跨包遷移指南（MIGRATION_FULL_TO_GITHUB）

> 適用對象：裝過 pre-1.0 `full` 風味調試包（`dev.librepocket.agent.full`）的測試者。
> 結論先行：`full` 已截尾，不再構建/簽名/發布；接替者是 `github`（`dev.librepocket.agent.github`）。
> applicationId 不同 = Android 視為不同應用，**必須卸載重裝**；會話經匯出/匯入搬運，**Key 一律不遷移**（見 §3–§4）。

## 1. 截尾公告（§C）

1. `full` 風味在此文檔落地後正式截尾：`src/full` 不再存在（已遷至 `src/github`，另有 `src/foss` 鏡像），`applicationIdSuffix ".full"` 不再被任何構建引用，CI 三包矩陣僅為 `play` / `foss` / `github`。
2. 發布渠道同步截尾：GitHub Releases 只發 `github` APK（+ SBOM + SHA256SUMS）；Play AAB/APK 僅作政策自證；foss 經 F-Droid 構建發布。任何自稱 `full` 的後續產物皆為非官方。
3. 為何不斷 `full` 而是改名：pre-1.0 尚無任何商店發布（v0.1.0 alpha，debug only），`.full` ID 從未進入 Play / F-Droid 索引；`github` 之名誠實表達「直裝完整版（含專有服務 ML Kit）」，與 `foss`（F-Droid 純開源版）對稱。早斷尾比晚斷尾便宜。
4. 歷史注記：本倉 pre-1.0 文檔（ROADMAP / BACKLOG / CAPABILITY_MATRIX / ARCHITECTURE / specs）中出現的 `full`，一律理解為現 `github`；`foss` 為截尾同時新增的第三風味。

## 2. 先選對包：`github` 還是 `foss`

| 你是 | 裝哪個 | applicationId |
|---|---|---|
| 要最新直裝完整版（含 ML Kit OCR/條碼），接受專有服務 | `github`（GitHub Releases） | `dev.librepocket.agent.github` |
| 要純開源版（ZXing / Tesseract / LiteRT），經 F-Droid 更新 | `foss`（F-Droid；只有 F-Droid 構建的才算官方） | `dev.librepocket.agent.foss` |
| 只要商店合規版 | `play`（Google Play，尚未發布） | `dev.librepocket.agent` |

三包能力對照見 `docs/CAPABILITY_MATRIX.md` §1–§2；品牌規則見 `TRADEMARKS.md`。

## 3. 跨包遷移（卸載重裝 + 匯出匯入）

原理：`dev.librepocket.agent.full` → `dev.librepocket.agent.github` 是**不同應用**，Android 不允許覆蓋安裝（簽名與 ID 雙雙對不上），系統備份也不會跨包還原。本指南用應用內匯出/匯入搬運資料。

步驟（在舊 `full` 包內操作）：

```text
1. 舊包 → 設定 → 匯出：會話 JSONL（`librepocket-<sessionId8>-<日期>.jsonl`，SAF 存到自選位置；
   單 session 上限 20 MiB，超限先 prune）＋ 偏好/審計匯出（脫敏版）。
   匯出預設為脫敏版；明文匯出需顯式開關並記審計事件。
2. 核對匯出檔可用 `jq` 解析（`jq empty <file>.jsonl`），再備份到電腦。
3. 卸載舊 `full` 包（系統設定 → 應用 → LibrePocket full → 解除安裝）。
4. 安裝新包（GitHub Releases 的 `LibrePocket-<版本>-github.apk`，或 F-Droid 的 foss）。
5. 新包 → 設定 → 匯入：逐個匯入 JSONL（壞行整批回滾，只報行號不貼內容；`sessionId` 重寫為新 ID）。
6. 重新授權：日曆/通知監聽/錄屏、自動化開關（foss/github 預設關，需系統授權 + App 內二次確認）。
```

## 4. Key 不遷移（必須重輸）

- BYOK 金鑰（各 Provider Key、MCP `keyId`）**預設永不進入任何備份**：`BackupPolicy.KEY_FILES_EXCLUDED` + `backup_rules.xml` / `data_extraction_rules.xml` 明確排除（`librepocket_keys.xml`、vault、Tink keyset）；`BackupBundle.DEFAULT_INCLUDE_KEYS = false`。
- 因此第 3 步搬運的只是會話/偏好/審計，**不含金鑰**。換包（乃至換機還原）後請逐個重輸 Key；輪換即本機 `KeyVault.deleteKey + putKey`，無服務端可轉移（無帳號體系，P7 非目標）。
- 切勿為省事開明文匯出傳 Key：明文匯出需二次確認且記審計，仍建議只在受控環境使用。

## 5. 驗證你遷移成功了

```sh
# 新包三查：ID 對了、舊包沒了、會話回來了
adb shell pm list packages | grep librepocket   # 應見 .github（或 .foss），不應再見 .full
# 新包內：會話列表與匯出前一致；轉錄頭部含路由決策 + 投影快照；Key 頁顯示未填（預期）
```

## 6. 常見問題

- **能不卸載直接裝嗎？** 不能。不同 applicationId，包管理器拒絕覆蓋安裝（`INSTALL_FAILED_CONFLICTING_PROVIDER` 類錯誤都算正常）。
- **舊包已卸載但忘記匯出？** 資料隨舊包刪除，無法找回（alpha 階段無雲同步，P7 明確不做服務端託管）。
- **`full` 包還能從哪下載？** 不能。release 工作流不再產生它；殘留的本地 `app-full-*.apk` 請刪除。
- **foss 和 github 能共存嗎？** 能。三包 ID 兩兩不同，可並存；但同一會話不要雙開編輯後互導，會出現 `sessionId` 分叉（以最後一次匯入為準）。

## 7. Obtainium 訂閱（只追 `github`）

- URL：在 Obtainium 新增 App → 來源選 `GitHub Releases` → 指向本倉 Releases 頁（`https://github.com/<owner>/<repo>/releases`，`owner/repo` 換成本倉實際路徑）。
  - 不要填 F-Droid / Play 地址：`foss` 走 F-Droid 構建、`play` 尚未發布，Obtainium 只應追 `github`。
- APK 過濾正則（按正則篩選 APK）：
  ```text
  LibrePocket-.*-github\.apk
  ```
  - 作用：只命中 `LibrePocket-<版本>-github.apk`（見 `.github/workflows/release.yml`「Prepare Release Artifacts」；同目錄的 `SHA256SUMS.txt` / `SBOM-*.json` 不會被誤裝）。
- 版本擷取：保持預設「從 Release tag 擷取」。tag 形如 `vX.Y.Z`，檔名中 `<版本>` 即該 tag（如 `LibrePocket-v0.2.0-github.apk`）；不要改成從檔名/APK `versionName` 擷取，避免附屬檔干擾。
- 三包 ID 對照提醒：此條目裝到的永遠是 `dev.librepocket.agent.github`；它與 `dev.librepocket.agent`（`play`）/`dev.librepocket.agent.foss`（`foss`）是不同應用，可並存但不互通更新——`foss` 請走 F-Droid 更新，勿用此 Obtainium 條目去裝 `foss` / `play`。
