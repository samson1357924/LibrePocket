# LibrePocket 骨幹優先計劃（BACKLOG）

> 定位：骨幹先行，其餘全部進池排隊。本文只寫文檔建議，不涉及 `app/src` 實作。
> 輸入：`ROADMAP.md`（P0–P7）、`CAPABILITY_MATRIX.md`、`ARCHITECTURE.md`、`ENV.md`。
> 缺失輸入：`P1_SPEC.md`、`JEV_INTEGRATION_DRAFT.md` 在 `docs/` 下不存在（`glob docs/*` 僅命中 ROADMAP/ENV/CAPABILITY_MATRIX/ARCHITECTURE），故 Jev 相關需求僅按 ROADMAP/ARCHITECTURE 提及範圍推定，待補檔後再細化。
> 約定：驗收四件套 `UT / AAPT / SMOKE / PLAY` 沿用 ROADMAP 定義。

---

## 1. 骨幹定義：什麼叫「骨幹可用」

骨幹 = P1 全量 + P2 全量 + P3 最小閉環 + 橫切（脫敏/權限/雙 flavor）全綠。P0 視為外部依賴（另一組交付），只消費介面。

### 1.1 骨幹清單（Must，缺一不可）

| # | 骨幹項 | 對應 ROADMAP | 完成標準（可執行） |
|---|---|---|---|
| B1 | BYOK 聊天閉環 | P1 | Provider A/B/C 三轉接器映射全斷言；Keystore-backed 金鑰存儲；模型切換不丟歷史；Runtime 事件串流 `TEXT_DELTA/DONE/STATE_CHANGE`；單會話串流渲染 + 重試/停止/重生 |
| B2 | 會話存儲 | P1/ARCH §8 | JSONL 追加寫為真相來源 + DB 只建索引；`RUNNING → INTERRUPTED` 需手動恢復、不自動重放副作用 |
| B3 | 快通道 11 操作 | P2 + 矩陣 §1 | 導航 / 開 App / 郵件草稿 / 鬧鐘 / 電話預填(DIAL) / 簡訊預填 / 日曆 / 音樂 / 音量 / 通知標題 / 截圖系統路徑。每個有 Schema + Intent 映射 + 超時 + 降級分支；電話僅 `ACTION_DIAL`、簡訊僅系統編輯器預填、不申請 SMS 權限 |
| B4 | 快通道路由與授權 | P2 + ARCH §5/§6/§9 | FastRouter 決策理由碼寫轉錄頭部；Tool Schema 註冊表；副作用三級 READ/WRITE/PRIVILEGED；`PrivilegeGateTest`（PRIVILEGED 未確認不執行）；降級話術模板含原因碼 |
| B5 | 慢通道 GUI 最小閉環（full 限定、預設關） | P3 + ARCH §9.2 | SlowRouter 迴圈：截圖→語義壓縮→單步提議→仲裁→執行→觀測；步數/時間雙預算、超限轉手動指引；仲裁攔截支付/刪除/發送類（強制 CONFIRM）；steering/cancel/pause 語義完整；審計表座標/節點獨立統計；程式碼僅存 `src/full` |
| B6 | 脫敏 v1 | P1/ARCH §8.2 | 電話/郵件/座標/金鑰樣式（`sk-`等）進轉錄前遮蔽；`TranscriptRedactorTest` 給定語料斷言輸出不含原文；匯出預設脫敏版，明文需二次確認 + 審計事件 |
| B7 | 能力投影 v1 | P1/ARCH §6 | 純函數投影：風味 + 使用者開關（本階段不含提權探測）；play 下慢通道工具不可見；投影快照寫轉錄頭部；`CapabilityProjectionTest` 綠 |
| B8 | 雙 flavor 綠 | P1–P3/P0 介面 | `./gradlew assemblePlayDebug assembleFullDebug` 一次通過；`AAPT` play 包零匹配 `SEND_SMS/RECEIVE_SMS/READ_SMS/MANAGE_EXTERNAL_STORAGE`，且 play 無 `BIND_ACCESSIBILITY_SERVICE` 自動化子類、無 `VPNService` 子類（字串掃描零匹配）；full 包列 a11y 但預設關（開關初始 `false` 可 dump 證明） |
| B9 | 骨幹測試門禁 | P1–P3 測試策略 | `ProviderAdapterTest / TranscriptRedactorTest / CapabilityProjectionTest / ChatBudgetTest / FastRouterDecisionTest / SystemIntentMappingTest / PrivilegeGateTest / SlowBudgetTest / ArbitrationTest / SteeringSemanticsTest / PauseResumeTest` 全綠；AndroidWorld `smoke_chat_stop.py` + `slow_checkout_demo.py`（2 基準 App，含暫停恢復 + steering + 超限轉指引）通過 |

### 1.2 骨幹非目標（明確不做）

- 不做終端/檔案跨域（P4）、MCP/Skills/記憶檢索（P5）、全量系統入口（P6，骨幹僅需 Launcher Activity 單入口可聊）、加固備份上架（P7，僅 debug 包門禁）。
- 不做提權實作（Shizuku/Root 僅預留介面）、不做雲同步/帳號體系、不承諾慢通道任意 App 成功率。

### 1.3 骨幹 Done 檢查表（一票否決）

1. 無效金鑰 → 認證類錯誤碼且可重試；A→B 供應商切換歷史不丟；殺進程重啟 → `INTERRUPTED` 且不自動重放。
2. 快通道 11 項逐項 1 分鐘冒烟過（含無地圖 App 走網頁降級、電話/簡訊只驗跳系統 App）。
3. 慢通道在 2 基準 App 跑通 + 越界指令（轉帳/刪對話）一律被仲裁攔截。
4. `AAPT` + 風味源碼集差異（`src/play` 無慢通道類）雙證 play 合規。

---

## 2. 代辦池（MoSCoW）

> 依賴列寫「前置骨幹/前置代辦」；驗收方向只寫增量斷言，不重複骨幹門禁。

| ID | 代辦項 | MoSCoW | 依賴 | 驗收方向 |
|---|---|---|---|---|
| D01 | Jev 最小可用整合（待 `JEV_INTEGRATION_DRAFT.md` 補檔後定界） | Must(骨幹後首位) | B1/B2/B7 | 待補：先定 Jev 是 Provider D 型 / MCP 伺服器 / Skill 包三選一；`UT` 歸一化錯誤碼 + 熔斷不中斷會話 |
| D02 | 記憶三層：工作記憶 N 輪 + 情節 FTS + 語義偏好 KV | Should | B2/B6 | `MemoryFtsTest`（50 條合成語料召回率基線；刪單條索引同步消失）；`MemoryRedactTest`（寫盤前不含敏感原文）；讀寫授權面 + 級聯刪除 |
| D03 | MCP 客戶端（多伺服器、熔斷、再投影，預設 WRITE） | Should | B4/B7 | `McpFuseTest`（連續失敗熔斷）；第三方工具同走投影+確認；僅網路權限，地址使用者配置 |
| D04 | Skills 包（子集收斂 + 雜湊校驗 + 權限清單展示） | Should | D03/B4 | `SkillScopeTest`（啟用後工具被收斂）；匯入校驗失敗拒裝 |
| D05 | 受限 shell（白名單/超時/配額/截斷）+ SAF/私有域文件 | Should | B4/B6 | `ShellWhitelistTest`（黑名單拒絕、超時殺、輸出截斷）；`FileScopeTest`（play 拒跨域）；檔名 fuzz（中文/空格/長路徑） |
| D06 | 系統入口擴充 + 預設助手（Shortcut/Tile/Share/通知回覆/Widget/語音喚起/Assist） | Should | B1/B2 | `EntryNormalizeTest`（各入口→同一 UserTurn）；全入口走查表（新建/續接×前台/後台；旋轉/切後台不斷線） |
| D07 | 加固/備份/上架包（R8/輪換/審計匯出/雙風味 AAB/`play_policy_check.sh`） | Should | B8 全量 | `BackupRoundTripTest`（金鑰預設排除）；`AuditExportTest`（無明文）；`play_policy_check.sh` 入 CI 門禁 |
| D08 | Linux PRoot 下載式插件（使用者態、體積合規） | Could | D05 | SMOKE：PRoot hello-world；體積/動態下載走 Play 資產合規；效能降級明示 |
| D09 | Shizuku/Root 可選增強（檔案跨域橋/提權子進程/截圖可選路徑） | Could | D05/B7 | `PrivilegeProbeTest`（探測失敗回落免 Root 不崩）；卸載 Shizuku 冒烟仍過；跨邊界呼叫攜原因碼寫審計；一鍵收回 |
| D10 | 語音喚起 + 系統語音輸入鏈（不自研 ASR，用系統/供應商） | Could | D06 | SMOKE：喚起→續同一會話不斷線；後台喚起需使用者手勢（PLAY 無濫用） |
| D11 | 定時任務 / 提醒排程（Doze/精準鬧鐘合規） | Could | D06/B4 | 逐權限用途說明；連續拒絕兩次不再騷擾；超限/殺進程後任務狀態可審計 |
| D12 | IM 通道（Share/通知回覆先行；獨立 IM App 對接後排） | Could | D06/D02 | 同 D06 入口範式；敏感正文脫敏後才寫盤 |
| D13 | MCP/Skills 市集（發現/評分/簽名審查） | Won't-now | D03/D04/D07 | 政策：第三方工具不繞投影+確認；市集包簽名/雜湊強制校驗（本期只留介面） |
| D14 | Xposed/LSPosed 注入他 App | Won't-now | — | 雙風味永不內建；僅預留外部模組協議（矩陣 §1/§2 合規紅線） |
| D15 | VPN/流量攔截 | Won't-ever | — | 雙風味皆不提供（矩陣 §1 明確 `—`） |
| D16 | 向量重型記憶庫 / 服務端同步/雲備份/帳號體系 | Won't-now | D02 | P5 非目標：先用 FTS + 輕量排序；不同步聊天內容到雲 |

**代辦 Top 10（執行序）：** D01 Jev 最小可用 → D02 記憶 FTS → D03 MCP 客戶端 → D04 Skills → D05 受限 shell+文件 → D06 系統入口/預設助手 → D07 加固備份上架 → D08 PRoot 插件 → D09 Shizuku/Root 增強 → D10 語音鏈。（D11 定時、D12 IM 緊隨；D13–D16 明確凍結。）

---

## 3. 插件化建議（只建議，不寫碼）

1. **能力一律插件面掛載**：D03 MCP 伺服器、D04 Skill 包、D08 PRoot 發行版、D09 提權橋（Shizuku/Root）、D10 語音引擎、D13 市集包，全部走「註冊表（Schema + 副作用等級 + 投影謂詞）→ 投影過濾 → 授權」同一鏈路；骨幹 11 快工具與慢通道亦視為內建插件，便於市集化時零分叉。
2. **風味隔離優先於運行時開關**：慢通道、提權橋、a11y 自動化類只放 `src/full`，play 構建物理缺失（靠 `AAPT` + 源碼集 diff 自證），不要用 `if (flavor==play)` 殘留程式碼。
3. **下載式插件控體積**：PRoot 發行版、語音模型、MCP 重型伺服器走動態下載（Play 資產/外鏈合規），控制 APK 體積；參考 `models.dev` 思想解耦模型列表與發版。
4. **投影謂詞隨插件聲明**：每個插件自帶 `availability()` 純函數（風味/探測/開關→ NATIVE/DEGRADED/UNAVAILABLE + 原因碼），投影只做合取，便於單測與審計。
5. **審計與脫敏不可插拔**：轉錄頭部（路由決策+投影快照）、脫敏器、審計表為核心常駐，不允許插件繞過或替換。

---

## 4. 建議執行序

1. 骨幹 B1→B9（單入口可聊即可，不等 D06）。
2. D01 Jev 最小可用（補規格後立即定界，避免阻塞 D02–D04）。
3. D02→D04（記憶→MCP→Skills：先可回想，再可擴充，再收斂規劃）。
4. D05→D06→D07（執行力→入口→上架：達到可發版狀態）。
5. D08→D12 按需取用；D13–D16 凍結。

## 5. 未決事項（需上游確認）

- `ENV.md §3` 稱 full 包 `MUST list READ_SMS and MANAGE_EXTERNAL_STORAGE`，與 `ROADMAP P1–P4` + `CAPABILITY_MATRIX §2`（雙風味永不申請 SMS/全存取；簡訊只走系統預填、文件只用 SAF+MediaStore+自有域）直接矛盾。**本計劃以 ROADMAP/矩陣合規口徑為準**，建議修正 ENV 或另立 full-root 變體說明。
- `P1_SPEC.md`、`JEV_INTEGRATION_DRAFT.md` 缺失，D01 無法定界；需補檔後重估 D01/D03 邊界。
