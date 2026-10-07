# LibrePocket 分階段路線圖（ROADMAP）

> **狀態：Target roadmap / historical planning.** 本文件列出方向與原始驗收構想，不是 Current 功能表、團隊分工或已執行測試證據。Current 狀態請看 [README](../README.md)、[能力矩陣](CAPABILITY_MATRIX.md) 與各測試/發布文件。
> - 範圍：未來階段的目標、非目標及候選驗收。
> - Owner role：roadmap maintainer；未指派個人。
> - Source of truth：實際程式與 CI；roadmap 只記錄目標。
> - 更新觸發：階段目標、接受決策或驗收方式改變時。
> 驗收四件套縮寫：`UT` = 單元測試類名；`AAPT` = 權限斷言命令；`SMOKE` = 冒烟步驟（含 AndroidWorld 或手動）；`PLAY` = Play 政策檢查項。
> 歷史注記：pre-1.0 文档中的 `full` 風味即現 `github` 風味（GitHub 直裝完整版，`dev.librepocket.agent.github`）；`foss` 為新增的 F-Droid 純開源風味。見 `docs/MIGRATION_FULL_TO_GITHUB.md`。

## P0 — 基建（歷史規劃項；非現行分組或完成狀態）

- 目標：提供可構建、可測、可發版的底座。
- 原規劃範圍：Gradle 風味（`play` / `foss` / `github`）、CI、簽名、版本號、基礎設計系統空殼；不表示有獨立 P0 團隊。
- 本專案依賴介面：`BuildConfig.FLAVOR`、風味原始碼集目錄劃分（`src/play` / `src/foss` / `src/github`）、CI 產物（APK/AAB）。
- 原規劃驗收目標：`./gradlew assemblePlayDebug assembleFossDebug assembleGithubDebug` 三風味一次通過；非現行測試證據。

---

## P1 — 核心：Provider BYOK + 聊天 + 會話存儲 + 權限模型

### 目標

跑通「問一句、答一句」的最小閉環：自帶金鑰可切換供應商、串流聊天不斷線、會話可重開、可證明敏感字已遮蔽。

### 範圍

- Provider 三協議轉接器（A/B/C 型）+ BYOK 金鑰存儲（Keystore-backed）+ 模型切換不丟歷史。
- 聊天 UI（單會話串流渲染、重試/停止/重生）+ Runtime 事件串流（`TEXT_DELTA/DONE/STATE_CHANGE`）。
- 會話存儲：JSONL 追加寫 + 資料庫索引 + 中斷恢復標記（`INTERRUPTED` 需手動恢復）。
- Tool Schema 註冊表雛形 + 副作用三級（READ/WRITE/PRIVILEGED）+ 脫敏器 v1（電話/郵件/座標/金鑰樣式）。
- 能力投影 v1（僅風味 + 使用者開關，不含提權探測）。

### 非目標

- 不做任何系統操作工具（P2）、不做 GUI 自動化（P3）、不做終端/檔案跨域（P4）、不做 MCP/Skills/記憶檢索（P5）。

### 驗收標準（可執行）

1. `UT`：`ProviderAdapterTest`（三協議映射：system/tools/stream/stopReason 全斷言）、`TranscriptRedactorTest`（给定語料含電話+郵件+經緯度+`sk-`樣式，斷言輸出不含原文）、`CapabilityProjectionTest`（play 風味下慢通道工具不可見）、`ChatBudgetTest`（超步數/超時即停）。
2. `AAPT`：`aapt dump permissions app-play-debug.apk | grep -Ev 'SEND_SMS|RECEIVE_SMS|READ_SMS|MANAGE_EXTERNAL_STORAGE'` 必須零匹配；foss / github 包亦在本階段不含 SMS/全存取權限。
3. `SMOKE`（手動，3 台階）：① 填入無效金鑰 → 錯誤碼為認證類且可重試；② 切換 A→B 型供應商 → 歷史不丟；③ 殺進程重啟 → 上次 RUNNING 會話顯示 INTERRUPTED 且不自動重放。另用 AndroidWorld 錄製腳本跑「啟動→輸入→等待首字→停止」時序斷言（見測試策略）。
4. `PLAY`：資料安全表單 v1（聲明網路+Keystore 用途）、無 SMS/全存取/VPN 權限、匯出轉錄預設為脫敏版。

### 測試策略

- 單元：JUnit + Robolectric（轉接器用錄製的供應商 JSON 回放，不打真網；重試只測 READ 路徑）。
- 整合：MockWebServer 回放限流/認證/過濾三種錯誤，斷言歸一化錯誤碼。
- E2E：手動冒烟 + AndroidWorld 腳本（`smoke_chat_stop.py`：斷言首字延遲與停止後狀態為 CANCELLED）。

---

## P2 — 快通道系統操作（Intent 優先）

### 目標

覆蓋矩陣中全部快通道操作：導航/開 App/郵件草稿/鬧鐘/電話預填/簡訊預填/日曆/音樂/音量/通知標題/截圖系統路徑，且每個都有降級路徑。

### 範圍

- FastRouter + 11~13 個確定性工具（每個：Schema + Intent 映射 + 超時 + 降級）。
- 高風險接管：電話僅 `ACTION_DIAL`、簡訊僅系統編輯器預填；不申請 SMS 權限。
- 路由決策理由碼寫入轉錄頭部；降級話術模板（含原因碼）。
- 權限申請鏈（日曆/通知監聽/錄屏每次授權）+ 設定頁可收回。

### 非目標

- 不做螢幕點擊/讀屏自動化（P3）；不做提權路徑（Shizuku/Root 僅預留介面，不實作）；不做後台直發郵件/直撥電話。

### 驗收標準（可執行）

1. `UT`：`FastRouterDecisionTest`（有 Intent 模板→Fast；投影不可用→拒絕；模糊包名→澄清）、`SystemIntentMappingTest`（逐工具斷言 Intent action/extra/降級分支）、`PrivilegeGateTest`（PRIVILEGED 未確認不執行）。
2. `AAPT`：同 P1 權限斷言 + `grep -c ACCESS_FINE_LOCATION` 僅在導航聲明時出現且與商店描述一致；斷言無 `BIND_ACCESSIBILITY_SERVICE` 自動化子類（play 風味原始碼集無該檔案）。
3. `SMOKE`：AndroidWorld 或手動逐項（每項 1 分鐘）：導航 Intent 拉起地圖、開 App（模糊名觸發澄清）、郵件草稿預填、鬧鐘建立、日曆建事件、音樂播放/音量、通知標題列表、系統截圖（每次授權）。電話/簡訊只驗「跳系統 App 且本 App 未發出」。無地圖 App 的裝置驗網頁降級分支。
4. `PLAY`：逐權限用途說明（日曆/位置/通知/錄屏）、連續拒絕兩次後不再騷擾申請、錄屏每次授權。

### 測試策略

- 單元：Intent 映射表驅動測試（參數化每工具 × 正常/缺參/越界）。
- 裝置：AndroidWorld harness 錄製「指令→Intent→系統 App 出現」序列；無障礙/提權缺席時斷言降級話術含理由碼。
- 手動矩陣：Android 13/14/15 × 有/無地圖/郵件 App。

---

## P3 — 慢通道 GUI（foss / github 版限定，可選）

### 目標

為無公開 API 的流程提供「看得懂螢幕、走得穩、停得下」的慢通道，且預設關閉、超限即轉手動指引。

### 範圍

- SlowRouter：截圖→語義壓縮→單步提議→仲裁→執行→觀測迴圈；步數/時間雙預算；超限轉手動指引。
- 仲裁器：越界檢測（支付/刪除/發送類需確認）、單步可觀測性檢查。
- 審計表（座標/節點動作獨立統計）+ 風味開關（程式碼僅存於 `src/github`，foss 鏡像見 `src/foss`）。
- steering/cancel/pause 完整語義（步驟邊界暫停、取消不回滾已副作用但如實記錄）。

### 非目標

- Play 版不包含本階段任何程式碼；不做跨裝置雲控；不承諾任意 App 100% 成功率。

### 驗收標準（可執行）

1. `UT`：`SlowBudgetTest`（超步數→指引）、`ArbitrationTest`（支付/發送類強制 CONFIRM）、`SteeringSemanticsTest`（插入新指示不搶占原子步驟）、`PauseResumeTest`（不斷點重放寫動作，冪等鍵去重）。
2. `AAPT`：play 包斷言無 `BIND_ACCESSIBILITY_SERVICE` 且 `src/play` 無慢通道類；foss / github 包列出該權限但功能預設關（設定頁開關初始值 `false` 的截圖/偏好 dump）。
3. `SMOKE`：AndroidWorld 腳本 `slow_checkout_demo.py` 在 2 個基準 App 上跑通（含 1 次暫停恢復 + 1 次中途 steering）；超限場景驗證輸出為手動步驟指引而非無限重試。手動驗取消後轉錄含 `CANCELLED_AFTER_SIDE_EFFECT`（若有）或乾淨 `CANCELLED`。
4. `PLAY`：play 上架包不含本階段能力（政策檢查：無障礙用途聲明不出現於 play 描述；若被問詢可出示風味源碼集差異）。

### 測試策略

- Harness 回放：錄製基準 App 的節點/截圖序列，CI 跑仲裁與預算邏輯（不依賴真機渲染）。
- 真機抽查：每週對 2~3 個常用 App 重跑冒烟，失敗用例轉為「指引品質」評分而非阻塞發版。
- 紅隊：構造越界指令（轉帳/刪對話），斷言一律被仲裁攔截。

---

## P4 — 終端 / 文件 / Linux PRoot

### 目標

給進階使用者受控的本機執行力：受限 shell、自有域+SAF 文件、免 Root 的 PRoot 發行版，提權路徑（Shizuku/Root）作為可選增強且可一鍵收回。

### 範圍

- 受限 shell（白名單命令、超時、配額、輸出截斷）+ 提權子進程適配器介面（Shizuku/Root，foss / github 版才實作）。
- 文件：私有域 + SAF + MediaStore；跨域讀寫經 Shizuku/Root 橋（需雙重確認）。
- PRoot 發行版：下載式插件（控制 APK 體積）、使用者態運行、效能降級明示。
- 審計：每次跨權限邊界呼叫記原因碼。

### 非目標

- 不做 VPN/流量攔截（三風味皆不提供；見 CAPABILITY_MATRIX §1-§2）；不做全存取權限申請；不把提權作為主流程。

### 驗收標準（可執行）

1. `UT`：`ShellWhitelistTest`（黑名單命令拒絕、超時殺進程、輸出截斷）、`FileScopeTest`（play 風味拒絕跨域路徑）、`PrivilegeProbeTest`（探測失敗回落免 Root，不拋崩潰）。
2. `AAPT`：`aapt dump permissions` 斷言三風味皆無 `MANAGE_EXTERNAL_STORAGE`；foss / github 包 Shizuku 權限僅為可選聲明且缺失時功能降級（卸載 Shizuku 後冒烟仍過）。
3. `SMOKE`：手動 5 步：受限命令成功、危險命令被拒、SAF 選檔讀寫、PRoot 啟動 hello-world、關閉提權開關後跨域入口消失。AndroidWorld 跑檔案命名參數化（中文/空格/長路徑）。
4. `PLAY`：體積與下載政策（PRoot 為動態下載需走 Play 資產/外鏈合規）、無全存取、無 VPNService 子類（字串掃描零匹配）。

### 測試策略

- 單元 + Robolectric（shell 用假進程，檔案用記憶體 FS）。
- 真機：免 Root / Shizuku / Root 三種環境各跑一次降級矩陣。
- 模糊：檔名/路徑/超大輸出三組 fuzz 語料。

---

## P5 — MCP / Skills / 記憶三層

### 目標

讓能力可擴充、可收斂、可回想：MCP 擴工具、Skills 收斂規劃、記憶三層支撐跨會話連續性，且全部經過脫敏與授權。

### 範圍

- MCP 客戶端（多伺服器配置、熔斷、工具再投影，預設副作用 WRITE）。
- Skills 包（提示片段+工具子集+前置檢查；匯入校驗雜湊+權限清單展示）。
- 記憶：工作記憶（N 輪）/ 情節記憶（轉錄摘要 + FTS 全文檢索）/ 語義記憶（偏好 KV，可檢視可刪）。
- 記憶讀寫授權面 + 級聯刪除 + 脫敏後才寫盤。

### 非目標

- 不做服務端同步/雲備份（P7）；不做向量重型庫（先用 FTS + 輕量排序）。

### 驗收標準（可執行）

1. `UT`：`McpFuseTest`（連續失敗熔斷）、`SkillScopeTest`（啟用後可用工具被收斂）、`MemoryFtsTest`（FTS 召回上次餐廳/偏好；刪除單條後索引同步消失）、`MemoryRedactTest`（記憶寫盤前不含敏感原文）。
2. `AAPT`：權限無新增（與 P4 基線 diff 為空）；MCP 僅用網路權限且伺服器地址由使用者配置。
3. `SMOKE`：手動 4 步：接 1 個本地 MCP 伺服器並調用、啟用 1 個 Skill 完成排會議、跨會話追問「上次的餐廳」、刪除一條偏好後不再被召回。AndroidWorld 跑轉錄崩潰恢復（kill→重啟→逐行重放完整）。
4. `PLAY`：MCP/Skills 不繞過權限模型（第三方工具同樣走投影+確認）；記憶資料Included in 資料安全表「收集/用途」聲明。

### 測試策略

- FTS 品質集：50 條合成轉錄，測召回率/精確率基線（存為測試資源）。
- MCP 相容：對 2~3 個公開 MCP 伺服器做連通性矩陣（失敗僅告警不阻塞）。
- 隱私：脫敏語料回歸集每次必跑。

---

## P6 — 系統入口 / 預設助手

### 目標

同一個 Runtime，能從多個系統入口進入，且使用者可把本 App 設為預設助手而不改變核心行為。

### 範圍

- 入口：Launcher Activity、Shortcut、Tile、Share 接收、通知回覆、Widget、語音喚起、預設助手（Assist API）。
- 入口→`UserTurn` 正規化 + 會話歸屬（新建/續接）+ 事件渲染（共用同一訂閱器）。
- 生命週期：旋轉/切後台不中斷；回收後 INTERRUPTED 需手動恢復。

### 非目標

- 不做鎖屏繞過、不做常駐前台保活對抗系統、不做語音辨識自研（用系統/已選供應商）。

### 驗收標準（可執行）

1. `UT`：`EntryNormalizeTest`（各入口輸入→同一 UserTurn 結構）、`SessionResumeTest`（回收後狀態機轉換正確）。
2. `AAPT`：新增 `ASSIST` 相關 intent-filter 但無新增危險權限；diff 斷言列出新增項且每項有政策理由。
3. `SMOKE`：手動全入口走查表（每入口：發起→續同一會話→旋轉→回前台不斷線）；設為預設助手後長按電源/手勢可喚起。AndroidWorld 跑 share→續會話腳本。
4. `PLAY`：預設助手/語音入口描述與實際行為一致；無背景啟動濫用（後台喚起需使用者手勢）。

### 測試策略

- 入口參數化測試（每個入口 × 新建/續接 × 前台/後台）。
- 真機走查：Android 13/14/15 各一台，記錄喚起成功率。

---

## P7 — 加固 / 備份 / 上架

### 目標

達到可上架、可維運、可問責：加固、備份、金鑰輪換、崩潰可恢復、政策包一次過。

### 範圍

- 加固：混淆/R8、Keystore 金鑰輪換、審計事件匯出（脫敏版）、明文匯出二次確認。
- 備份：會話/偏好本地備份（金鑰預設不含），恢復時完整性校驗。
- 上架：三風味 AAB/APK、資料安全表單終版、商店描述（含權限用途）、政策自查腳本（`scripts/play_policy_check.sh`，play 門 + `--foss` 門）。
- 可觀測：崩潰上報（無敏感欄位）、ANR/卡頓基線。

### 非目標

- 不規劃 app-managed 服務端託管、帳號體系或聊天同步。Android manifest 目前 `allowBackup=true`，僅排除 key-specific paths；不得據此宣稱聊天內容不會進入 Android backup。

### 驗收標準（可執行）

1. `UT`：`BackupRoundTripTest`（備份→恢復一致；含金鑰預設排除）、`AuditExportTest`（匯出不含明文敏感）。
2. `AAPT` + 腳本：`scripts/play_policy_check.sh app-play-release.aab` 與 `scripts/play_policy_check.sh --foss <foss-apk/aab>` 各一次通過，內容 = 權限黑名單斷言 + VPNService/Accessibility 自動化類掃描（play 門）+ 專有字串掃描（foss 門）+ 資料安全表單一致性。兩門均納入 CI 必跑門禁。
3. `SMOKE`：release 包手動全迴歸（P1→P6 冒烟子集 30 分鐘版）+ AndroidWorld 全量腳本綠；崩潰注入（kill -9）後轉錄可逐行恢復。
4. `PLAY`：政策清單逐項勾選：目標 API 等級、64 位、資料安全表單、權限用途影片/說明（如需）、分級問卷、地區分發（簡訊/電話相關描述合規）。

### 測試策略

- 發版門禁：UT 全綠 + `play_policy_check.sh` + release 冒烟三件套缺一不可。
- 灰度：github 版先內測通道，play 版走封閉測試 → 公開；foss 版隨 F-Droid 構建發布。
- 回滾：版本號/資料庫遷移腳本需雙向驗證（升→降不丟會話索引）。
