# Jev AI 接入設計草案（JEV_INTEGRATION_DRAFT）

> 狀態：草案（Draft，待評審）。只新增本文件，不碰 `app/src/**`、`gradle/**`。
> 目標：解決 Eta 操作手機緩慢的痛點 —— 每步都走大模型全量推理 + 截圖 VLM。
> 思路：把「高頻、小決策」從大模型卸載到 Jev 三頭（Choice / Score / Noul），大模型只做低頻規劃與兜底。
> 適用階段：P2（FastRouter）+ P3（SlowRouter）+ 仲裁/風控；依賴 P1 的 `dev.librepocket.provider`、`dev.librepocket.redact`、`dev.librepocket.policy`。

---

## 1. 背景與問題

Eta 現狀（慢的原因）：

1. 意圖路由每輪都調大模型全量推理（含 system prompt + 全歷史），即使「設個鬧鐘」也要走一遍。
2. SlowRouter 每步都把截圖送 VLM 做 grounding（找控件 + 判動作），token 大、延遲高、費錢。
3. 仲裁/風控靠大模型自由發揮，無結構化風險分，不可測、不可審計。

Jev 定位：**小、快、可測的判別頭**，輸入小（結構化候選 + 脫敏文本 + 版面特徵），輸出小（選項 id / 分數 / 是否確認）。不做生成、不做規劃。

```
現狀：User → 大模型全量（含截圖）→ 動作 → 大模型全量（含截圖）→ …
目標：User → Jev-Choice（意圖）→ Fast 直達 / Slow 單步（Jev 重排+風控）→ 僅疑難回大模型
```

---

## 2. Jev 三頭語義（與本專案的綁定）

| 頭 | 數學語義 | 輸入 | 輸出 | 本專案用途 |
|----|----------|------|------|------------|
| Choice | N 選一分類（含弃權） | 任務文本特徵 + N 個候選描述 | `{ bestId, confidences[N], abstain: bool }` | 意圖路由、控件重排 |
| Score | 標量風險/品質打分 0..1 | 動作描述 + 上下文特徵 | `{ score, reasons[code] }` | 操作風險、危險度 |
| Noul | 二元守門（需確認？） | 動作 + Score + 上下文 | `{ needConfirm: bool, confidence }` | 是否轉人工確認 |

約定：

- 三頭皆為**純判別**，不回傳自然語言正文（理由只給枚舉碼，供轉錄審計）。
- `abstain=true` 或任一置信 `< 閾值` 一律視為「Jev 無把握」，走既有路徑（大模型或人工確認），禁止硬猜。
- 所有閾值集中在 `JevThresholds` 配置，可做 A/B，不散落各處。

---

## 3. 三處用法設計

### 3a) 意圖路由：Choice 選操作候選

候選集合（封閉，P2 對齊能力矩陣 §1，共 11 項 + GUI 兜底 = 12）：

```text
NAVIGATE / OPEN_APP / MAIL_DRAFT / ALARM / DIAL / SMS_PREFILL /
CALENDAR / MUSIC / VOLUME / NOTIFICATION_READ / SCREENSHOT / GUI_FALLBACK
```

流程：

```text
UserTurn（已脫敏文本 + 裝置投影快照）
  → JevChoice.route(candidates=12, context=projection)
  → if best != GUI_FALLBACK && confidence >= T_route (建議 0.75) → FastRouter 直達
  → else if best == GUI_FALLBACK 或 abstain 或低置信 → 大模型仲裁一次（沿用 ARCHITECTURE §9.3 規則引擎優先）
  → 若大模型亦低置信 → 澄清問句（模糊包名/地點/時間），不猜測
```

- 特權動作（DIAL / SMS_PREFILL / SCREENSHOT / NOTIFICATION全文）即使 Choice 高置信，仍走系統接管（`ACTION_DIAL`、系統編輯器、每次授權），Jev 無權跳過（見 §6 風控）。
- `ROUTE_DECISION` 事件新增欄位 `router:"jev-choice"|"llm"|"rule"` + `confidence` + `abstain`，寫入轉錄頭部。
- 快取：意圖模板快取（見 §5），「同文本+同投影」直接命中，不調任何模型。

### 3b) GUI grounding 重排：Choice + Score + Noul

現狀痛點：截圖 VLM 又找控件又判動作。拆為兩段：

```text
慢通道單步：
  1. 候選生成（輕量，不走大模型）：無障礙節點樹 → 過濾可見可點 N≤20 + 版面特徵（bounds/文字/類型）
  2. Jev-Choice.rerank(candidates=N) → 選 top1 + 置信
  3. Jev-Score.risk(action, target) → 風險分 0..1（支付/刪除/發送/授權類直接打高分，規則先行）
  4. Jev-Noul.needConfirm(score, action) → 是否彈確認
  5. 僅當 Choice abstain/低置信（<T_ground，建議 0.65）或 Noul needConfirm 且使用者選「讓 AI 決定」時，才升級大模型/VLM 看一次截圖
```

關鍵約束（隱私 + 速度）：

- 送 Jev 的**不是截圖像素**，而是：脫敏節點文本 + 控件類型 + 相對版面（歸一化 bounds）+ 任務目標句。截圖只在 Jev 無把握且使用者已授權自動化時，才送大模型（且走 P1 圖片降級策略）。
- 每步仍受 SlowRouter 雙預算（步數/時間）約束；Jev 超時即視為 abstain，不阻塞。
- 座標/節點動作仍寫獨立審計表（ARCHITECTURE §9.2），另加 `jevConfidence`、`riskScore` 兩欄。

### 3c) 仲裁 / 風控：Score + Noul

位置：`Tool Schema 校驗 → 能力投影 → Jev 風控 → 授權檢查 → 執行`（插在授權前，Jev 只有建議權，無放行權）。

```text
action（含副作用等級 READ/WRITE/PRIVILEGED）
  → Jev-Score.danger(action, context) → dangerScore + reasonCodes
  → Jev-Noul.canAutoExecute(score, policySnapshot) → { auto: bool }
  → if PRIVILEGED → 一律 CONFIRM/系統接管（Jev 建議忽略，規則勝出）
  → if WRITE && score >= T_danger (建議 0.6) → CONFIRM
  → else → 沿用 PolicyStore.evaluateFresh() 決策
```

- 規則 > Jev > 大模型自由發揮。Jev 永不放寬 `policy DENY`，只能收緊（`ALLOW→ASK`），方向單向。
- `CONFIRM_REQUEST` 事件攜帶 `dangerScore + reasonCodes`，供 UI 顯示「為何要你確認」。
- 紅隊用例（轉帳/刪對話/提權命令）必須被 Score 打高分 + Noul 攔截（見 §8 測試）。

---

## 4. 介面草案（只定義形狀，不實作）

包名：`dev.librepocket.jev`（新包，與 `provider` / `policy` / `redact` 並列，依賴方向 `chat, agent → jev → redact, policy`）。

```kotlin
package dev.librepocket.jev

// --- 通用 ---
data class JevThresholds(
  val routeMinConf: Float = 0.75f,   // 3a
  val groundMinConf: Float = 0.65f,  // 3b
  val dangerConfirm: Float = 0.60f,  // 3c WRITE 轉確認
  val autoExecMaxDanger: Float = 0.35f,
)

enum class JevStatus { OK, ABSTAIN, TIMEOUT, UNAVAILABLE, ERROR }

data class ChoiceResult(
  val bestId: String,                // 候選 id，如 "ALARM" / "node#7" / "GUI_FALLBACK"
  val confidences: Map<String, Float>,
  val abstain: Boolean,
  val status: JevStatus,
  val latencyMs: Long,
)

data class ScoreResult(
  val score: Float,                  // 0..1，越大越危險
  val reasonCodes: List<String>,      // 枚舉：PAYMENT / DELETE / SEND / PRIV_CMD / AUTH_STATE / ...
  val status: JevStatus,
  val latencyMs: Long,
)

data class NoulResult(
  val needConfirm: Boolean,
  val confidence: Float,
  val status: JevStatus,
  val latencyMs: Long,
)

// --- 三頭客戶端（傳輸無關，實作可為端側 TFLite / 本地服務 / 遠端小模型三選一，P2 先定介面） ---
interface JevChoice {
  suspend fun choose(
    taskText: String,                        // 已脫敏
    candidates: List<JevCandidate>,          // id + 描述（脫敏後短文本）
    topK: Int = 1,
    timeoutMs: Long = JevBudgets.CHOICE_TIMEOUT_MS,
  ): ChoiceResult
}

interface JevScore {
  suspend fun score(
    action: String,                          // 工具名 + 參數摘要（脫敏）
    context: JevContext,                     // 投影快照 + 副作用等級 + 會話摘要
    timeoutMs: Long = JevBudgets.SCORE_TIMEOUT_MS,
  ): ScoreResult
}

interface JevNoul {
  suspend fun gate(
    action: String,
    score: ScoreResult?,
    context: JevContext,
    timeoutMs: Long = JevBudgets.NOUL_TIMEOUT_MS,
  ): NoulResult
}

data class JevCandidate(val id: String, val label: String, val features: Map<String, String> = emptyMap())
data class JevContext(
  val副作用: String,                          // "READ" | "WRITE" | "PRIVILEGED"（實作用英文 enum，此處中文佔位，定稿前改名 sideEffect）
  val projection: String,                    // 能力投影摘要（可用工具 id 列表，非全量）
  val redactedGoal: String,                  // 本輪目標（脫敏後，≤500 字）
)

// --- 編排門面（Agent Runtime 調用，屏蔽三頭細節 + 降級） ---
interface JevRouter {
  suspend fun routeIntent(text: String, candidates: List<JevCandidate>): ChoiceResult
  suspend fun rerankNodes(goal: String, nodes: List<JevCandidate>): ChoiceResult
  suspend fun checkRisk(action: String, ctx: JevContext): Pair<ScoreResult, NoulResult>
  fun thresholds(): JevThresholds
}

// --- 延遲預算常量 ---
object JevBudgets {
  const val CHOICE_TIMEOUT_MS: Long = 300
  const val SCORE_TIMEOUT_MS: Long = 200
  const val NOUL_TIMEOUT_MS: Long = 150
  const val STEP_TOTAL_MS: Long = 800   // 3b+3c 單步合計（含候選生成），超限即 abstain/轉確認
}
```

> 註：`sideEffect` 欄位名定稿時用英文；草案先以中文標註防誤讀，實作 PR 必須更名（Code Review 檢查項）。

具象類規劃（P2/P3 再實作，此處只佔位）：

| 類 | 職責 |
|----|------|
| `JevRouterImpl : JevRouter` | 閾值判斷 + 超時轉 abstain + 降級計數 + 寫轉錄欄位 |
| `NoOpJevRouter` | Jev 不可用時的全降級（永遠 abstain / 高風險保守） |
| `JevCache` | 意圖模板 LRU + grounding 候選特徵快取（見 §5） |
| `JevMetrics` | 命中率/延遲/abstain 率/回退率統計（審計用，不含正文） |

---

## 5. 快取 / 超時 / 降級策略

### 快取（只快取脫敏後鍵，命中即零模型調用）

| 快取 | 鍵 | 值 | 容量/失效 |
|------|----|----|-----------|
| 意圖路由快取 | `hash(脫敏文本歸一化 + 投影工具id集合 + flavor)` | `ChoiceResult(bestId, conf)` | LRU 500 條；投影變化即 key 變化，天然失效；不持久化 |
| Grounding 重排快取 | `hash(goal + 頁面簽名(node id+文字+bounds 雜湊))` | `bestNodeId + conf` | LRU 200 條；頁面簽名變化即失效；記憶體 only |
| 風控快取 | `hash(action名 + 參數形狀(去值) + 副作用等級)` | `ScoreResult` | LRU 500 條；僅快取 `score<0.3` 的 READ 低風險（高風險永不快取，防誤放行） |

- 快取值寫轉錄時標 `cached=true`，便於統計真實加速比。
- 私有目錄外、跨會話、落盤一律禁止（快取只活在進程記憶體）。

### 超時

- Choice 300ms / Score 200ms / Noul 150ms；單步合計 800ms（見 `JevBudgets`）。
- 任一超時 = 該頭 `ABSTAIN/TIMEOUT`，不重試（重試留給 P1 大模型的 `TurnController`，Jev 層不放大延遲）。
- 超時計入 `JevMetrics`；連續超時 N=5 次 → 熔斷 60s（期間直接走 `NoOpJevRouter`，不調 Jev）。

### 降級（Jev 不可用時回退大模型，順序固定）

```text
Jev OK → Jev 快判
Jev TIMEOUT/ABSTAIN → 本步降級：路由回規則引擎+大模型；grounding 回 VLM 看一次；風控回 Policy 預設（保守 ASK）
Jev UNAVAILABLE/連續失敗熔斷 → 整輪降級：等價於「無 Jev 的 Eta」（現狀行為），功能不中斷
Play 風味 → Jev 的 GUI 重排分支整包不存在（與 SlowRouter 同進退，`src/full` only）
```

- `NoOpJevRouter` 語義：Choice 永遠 abstain；Score 對 WRITE 以上給 0.6（觸發確認）；Noul 對 PRIVILEGED 永遠 needConfirm。方向只收緊。
- 降級事件寫轉錄（`jevFallback:true + cause`），冒烟統計回退率（目標 <5%）。

---

## 6. 延遲預算（每步目標）

假設：Jev 為端側或同機房小模型；大模型走 P1 Provider（雲端 SSE）。

| 路徑 | 現狀（Eta 估算） | 引入 Jev 後目標（p50 / p95） | 說明 |
|------|------------------|-------------------------------|------|
| 意圖路由（簡單指令，如設鬧鐘/開 App） | 大模型全量 2–6s | Jev-Choice **80 / 150ms** + Intent 執行；快取命中 **<20ms** | 省掉整輪大模型 |
| 意圖路由（疑難/模糊） | 同上 | Jev 150ms abstain + 大模型 1 次（與現狀持平，不變慢） | 只多 150ms 上限 |
| Slow 單步（控件定位+風控） | 截圖 VLM 4–10s/步 | 候選生成 50ms + Choice **120 / 250ms** + Score+Noul **80 / 200ms**，合計 **<800ms** | 不送截圖像素 |
| Slow 單步（Jev 無把握升級 VLM） | 同上 | ≤800ms + VLM 1 次（與現狀持平） | 發生率目標 <20% |
| 仲裁/風控附加開銷 | 大模型內隱式（不可測） | **<150ms**（Score+Noul 並行調，取 max） | 結構化、可審計 |

端到端舉例：5 步跨 App 流程，現狀 5×6s≈30s → Jev 全命中約 5×0.8s + 1 次規劃 ≈ 5–6s。**預期加速比見 §9。**

測量方式：`JevMetrics` 記每步 `jevMs / llmMs / cached`，轉錄頭部帶 `routeLatencyMs`；冒烟用 AndroidWorld 腳本斷言 p95（見 §8）。

---

## 7. 隱私（不上傳截圖全文）

1. **截圖像素不出端**：Jev 輸入僅為脫敏節點文本 + 類型 + 歸一化 bounds + 任務目標句。若 Jev 為遠端部署，傳輸體同樣不含點陣圖；需 VLM 看圖時走既有 P1 通道（含使用者授權 + 圖片降級策略）。
2. **文本先脫敏後送 Jev**：複用 `dev.librepocket.redact.Redactor`（電話/郵件/帳密/座標/Token），Jev 日誌只記 `reasonCodes + 計數`，不記原文。
3. **通知/簡訊/日曆正文**：默認只送「標題/形狀」（如 `NOTIFICATION_TITLE_ONLY`），全文需二次確認且不進 Jev 快取。
4. **金鑰與鑑權**：若 Jev 為遠端，複用 P1 `KeyVault` 機制（BYOK），不新引入明文存放；端側模型檔放 App 私有域，不申請全存取。
5. **審計**：轉錄/審計表中的 Jev 欄位僅為 `bestId/conf/score/reasonCodes/latency`，可證明「沒傳什麼」；明文匯出需二次確認（沿用 P1 §8/§11 規則）。

---

## 8. 測試計劃（不寫業務碼，先凍斷言）

| 測試類（規劃） | 層 | 斷言要點 |
|---|---|---|
| `JevChoiceRouteTest` | JVM | 12 候選封閉集；高置信直達 Fast；低置信/abstain 轉大模型；模糊包名觸發澄清；`router` 欄位寫入轉錄 |
| `JevRerankTest` | JVM（錄製節點序列回放） | N≤20 截斷；top1 命中注釋真值；低置信升級 VLM（mock 計數=1）；輸入斷言不含點陣圖（傳輸體無 `bytes` 欄） |
| `JevRiskTest` | JVM | 支付/刪除/發送/提權一律高分+需確認；`DENY` 永不被 Jev 放寬；高風險不命中風控快取 |
| `JevFallbackTest` | JVM（fake 超時/不可用） | 超時→abstain 不重試；連續 5 超時熔斷 60s（fake clock）；熔斷期間功能等價無 Jev |
| `JevCacheTest` | JVM | 同鍵命中零調用；投影變化失效；高風險不快取；快取僅記憶體（進程重啟消失） |
| `JevLatencyTest` | Harness/真機 | AndroidWorld 腳本：簡單指令 p95 <300ms；Slow 單步 p95 <800ms；回退率 <5% |
| `JevPrivacyTest` | JVM | 送 Jev 前過 Redactor（含 R1–R12）；傳輸體/日誌/轉錄三處掃描無原文；截圖位元組永不進 Jev 請求 |
| Play 風味斷言 | Instrumented | play 包無 Slow/Jev-grounding 類（字串掃描 + `src/play` 無檔案） |

Fixture：`jev-route-cases.txt`（`input || expectedId || minConf`）、`jev-nodes-*.json`（錄製節點樹+真值 node）、`jev-risk-cases.txt`（越界指令集，紅隊）。

---

## 9. 預期效果與風險（給決策）

**預期加速比**（以簡單指令與典型 5 步流程估算，待 §8 實測校準）：

- 簡單 Intent 指令：2–6s → 0.1–0.3s（**約 10–20 倍**，快取命中更高）。
- Slow 單步：4–10s → <0.8s（**約 5–10 倍**）；整流程 30s → 5–6s（**約 5 倍**），其中約 80% 步數不碰 VLM。
-  token/費用：高頻判別零大模型 token；僅疑難步付 VLM 費用，預估省 60–80%（按回退率 20% 計）。

**風險**：

1. Jev 誤路由（高置信錯選）→ 執行錯動作。緩解：閾值保守 + 特權動作系統接管 + 低置信轉澄清；上線初期開 `shadow 模式`（Jev 只記錄不執行，對賬一周再放開）。
2. Grounding 特徵不足（只有文字+bounds，無視覺）→ 異形/圖標按鈕命中低。緩解：低置信升級 VLM；圖標類 App 列入「Jev 跳過名單」。
3. 風控誤放（Score 低估）→ 緩解：規則先行（支付/刪除等關鍵字直接高分，不依賴模型）、單向收緊、紅隊集必過。
4. 端側模型體積/功耗 → 緩解：Jev 實作三選一（端側/本地服務/遠端小模型），介面先行，體積大就走遠端小模型，Play 政策另審。
5. 閾值調參漂移 → 緩解：閾值集中配置 + 指標看板（abstain 率/回退率/誤攔率），閾值變更走規格修訂。

---

## 10. 未決問題（評審會定）

- [ ] Jev 部署形態：端側 TFLite / 本地常駐服務 / 遠端小模型？（建議：先遠端小模型驗證加速比，再端側化）
- [ ] 閾值初值（0.75/0.65/0.60）是否需按語種分別校準？
- [ ] `GUI_FALLBACK` 候選是否拆細（如 WEB_SEARCH_FALLBACK vs MANUAL_GUIDE）？
- [ ] Shadow 模式的對賬看板由誰做（沿用 AndroidWorld harness 還是另建）？

---

*約束重申：本草案只新增本文件；`app/src` 業務碼、gradle、風味劃分一律不動。介面簽名變更視為破壞性，需修訂本草案版本號。*
