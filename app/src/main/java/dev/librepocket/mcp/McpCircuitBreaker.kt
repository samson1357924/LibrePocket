package dev.librepocket.mcp

/**
 * D03 逐伺服器熔斷：連續失敗達閾值即開路一段時間，期間直接短路
 * （回 [McpStatus.CIRCUIT_OPEN]），不發 HTTP，不中斷會話。
 *
 * - 失敗 = 傳輸錯誤 / 超時 / 非 2xx（**401/403 認證錯例外**——見 [McpServer]
 *   C-06：UNAUTHORIZED 不計熔斷、換 key／修權限後立即恢復，不等開窗）/
 *   協議解析失敗（含 JSON-RPC 協議碼 error 包
 *   -32700/-32600/-32601/-32603、保留 server 範圍 −32099..−32000 的 error 包，
 *   以及 result 兩鍵皆無／content 必填欄位缺失等形狀不完整
 *   ——含 `isError=true` 但無 content/structuredContent 的變體，
 *   見 [McpWire.parseCall]）；
 *   成功（2xx 且解析通過且 `isError=false`）計成功；
 *   工具業務錯（HTTP 200 + `isError=true` 且具內容，或 code 為 `-32602`/範圍外
 *   未知碼的 error 包）為中性（[onNeutral]，不記成功也不記失敗，避免交錯 TOOL_ERROR
 *   壓住熔斷）；
 *   客戶端參數錯誤（未發送即回 PROTOCOL）同樣不碰熔斷，由呼叫方早退。
 *   本類只記 [onSuccess]/[onFailure]/[onNeutral]，語義由 [McpServer] 統一
 *   （契約面僅此三事件＋[canCall]/[isOpen]/[failureCount] 查詢——D-04 曾存在的
 *   `onStatus`/`reset` 為零呼叫死碼，已移除，見 McpCircuitBreakerConcurrencyTest 釘面）。
 * - 時鐘可注入（預設**單調** [monotonicClockMs]，C-08：牆鐘回撥/前跳不影響視窗；
 *   注入假時鐘由呼叫方保證單調性；測試除預設時鐘 pin（如
 *   `defaultMonotonicClockIsUptimeBasedNotEpoch`）外一律用注入時鐘），執行緒安全
 *   （原創小實現，與 Jev 熔斷同構但參數獨立）。
 *
 * **狀態與查詢語義（C-07 收斂，逐點文件化＋測試釘死）**：
 * 1. **查詢面純唯讀**：[isOpen]／[failureCount] 一律不改狀態。舊實作 [isOpen]
 *    內部到期順手重置（唯讀查詢有副作用，先呼叫者改變後呼叫者的結果）——已移除；
 *    重置只在**狀態入口** [canCall]／[onFailure]／[onSuccess] 執行。
 * 2. **到期語義（同點一致、與呼叫順序無關）**：一旦 `now >= openUntilMs`，
 *    [isOpen] 恆回 `false`、[failureCount] 恆回 `0`（「視窗已過＝視同清窗」的
 *    讀取視圖）——不論兩者以何順序呼叫、是否已有人走過 [canCall]，結果相同；
 *    內部計數的實際清零在下一個狀態入口發生（見第 4 點對失效後失敗的處理）。
 * 3. **無半開態（最小實現取捨，刻意不引入 half-open 狀態機）**：到期後第一個
 *    [canCall] 全閉放行＋清零；若伺服器仍壞，需**再累積滿 [maxFailures] 次失敗**
 *    才重新開路，期間呼叫全量發送（無單發探測窗口）。記錄在案的代價：恢復期
 *    對仍壞的伺服器是全量試探而非限流探測；收益是零額外狀態、行為可預測。
 * 4. **開窗中 in-flight 失敗延窗**：視窗**未到期**前抵達的 [onFailure] 把
 *    `openUntilMs` 重切為 `now + openMs`（已達閾值時每敗重切）——開窗可被持續
 *    失敗一路延長；**到期後**才抵達的失敗先清窗（第 2 點視圖）再計數，視為
 *    恢復期第一敗、不沿用舊計數。兩半皆已測。
 *
 * 預設：連續 3 次失敗開路 60s（BACKLOG D03「連續失敗熔斷」的最小實現）。
 */
class McpCircuitBreaker(
    private val maxFailures: Int = 3,
    private val openMs: Long = 60_000L,
    private val clockMs: () -> Long = ::monotonicClockMs,
) {
    private var failures: Int = 0
    private var openUntilMs: Long = 0L

    /** 到期判定（純計算、唯讀；`openUntilMs == 0` 代表從未開路——見類 KDoc 第 1、2 點）。 */
    private fun windowExpired(): Boolean = openUntilMs != 0L && clockMs() >= openUntilMs

    /** 到期重置（**僅狀態入口**呼叫：[canCall]／[onFailure]／[onSuccess]；查詢面永不呼叫）。 */
    private fun resetWindowIfExpired() {
        if (windowExpired()) {
            failures = 0
            openUntilMs = 0L
        }
    }

    @Synchronized
    fun canCall(): Boolean {
        if (openUntilMs == 0L) return true
        resetWindowIfExpired()
        return openUntilMs == 0L
    }

    /** 純查詢（C-07a）：開路中且未到期 → true；到期 → false（不重置狀態，見類 KDoc 第 1 點）。 */
    @Synchronized
    fun isOpen(): Boolean = openUntilMs != 0L && !windowExpired()

    @Synchronized
    fun onSuccess() {
        failures = 0
        resetWindowIfExpired()
    }

    /**
     * 中性事件（工具業務錯）：不碰 `failures` 與開窗，既不重置也不累計，
     * 避免被入侵／不穩定 server 以交錯 TOOL_ERROR 壓住熔斷。
     */
    @Synchronized
    fun onNeutral() {
        // 刻意空操作：到期後的**讀取面**已回 false/0（類 KDoc 第 2 點），
        // 內部清窗由下一個 canCall()/onFailure()/onSuccess() 執行。
    }

    @Synchronized
    fun onFailure() {
        // 到期先清窗（第 4 點下半）：失效後的失敗視為恢復期第一敗，不沿用舊計數。
        resetWindowIfExpired()
        failures++
        if (failures >= maxFailures) {
            // 開窗中（含未到期前抵達的 in-flight 失敗）重切窗口至 now+openMs（第 4 點上半）。
            openUntilMs = clockMs() + openMs
        }
    }

    /** 純查詢（C-07b）：到期即回 0（「視窗已過」讀取視圖，與 [isOpen] 同點一致、無副作用）。 */
    @Synchronized
    fun failureCount(): Int = if (windowExpired()) 0 else failures
}
