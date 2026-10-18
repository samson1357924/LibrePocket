package dev.librepocket.mcp

/**
 * D03 逐伺服器熔斷：連續失敗達閾值即開路一段時間，期間直接短路
 * （回 [McpStatus.CIRCUIT_OPEN]），不發 HTTP，不中斷會話。
 *
 * - 失敗 = 傳輸錯誤 / 超時 / 非 2xx / 協議解析失敗（含 JSON-RPC 協議碼 error 包
 *   -32700/-32600/-32601/-32603、保留 server 範圍 −32099..−32000 的 error 包，
 *   以及 result 兩鍵皆無／content 必填欄位缺失等形狀不完整
 *   ——含 `isError=true` 但無 content/structuredContent 的變體，
 *   見 [McpWire.parseCall]）；
 *   成功（2xx 且解析通過且 `isError=false`）計成功；
 *   工具業務錯（HTTP 200 + `isError=true` 且具內容，或 code 為 `-32602`/範圍外
 *   未知碼的 error 包）為中性（[onNeutral]，不記成功也不記失敗，避免交錯 TOOL_ERROR
 *   壓住熔斷）；
 *   客戶端參數錯誤（未發送即回 PROTOCOL）同樣不碰熔斷，由呼叫方早退。
 *   本類只記 [onSuccess]/[onFailure]/[onNeutral]，語義由 [McpServer] 統一。
 * - 時鐘可注入，執行緒安全（原創小實現，與 Jev 熔斷同構但參數獨立）。
 *
 * 預設：連續 3 次失敗開路 60s（BACKLOG D03「連續失敗熔斷」的最小實現）。
 */
class McpCircuitBreaker(
    private val maxFailures: Int = 3,
    private val openMs: Long = 60_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var failures: Int = 0
    private var openUntilMs: Long = 0L

    @Synchronized
    fun canCall(): Boolean {
        if (openUntilMs == 0L) return true
        if (clockMs() >= openUntilMs) {
            failures = 0
            openUntilMs = 0L
            return true
        }
        return false
    }

    @Synchronized
    fun isOpen(): Boolean = !canCall()

    @Synchronized
    fun onSuccess() {
        failures = 0
        if (openUntilMs != 0L && clockMs() >= openUntilMs) openUntilMs = 0L
    }

    /**
     * 中性事件（工具業務錯）：不碰 `failures` 與開窗，既不重置也不累計，
     * 避免被入侵／不穩定 server 以交錯 TOOL_ERROR 壓住熔斷。
     */
    @Synchronized
    fun onNeutral() {
        // 刻意空操作（若已開路且到期，由下次 canCall() 重置）。
    }

    @Synchronized
    fun onFailure() {
        failures++
        if (failures >= maxFailures) {
            openUntilMs = clockMs() + openMs
        }
    }

    @Synchronized
    fun onStatus(ok: Boolean) {
        if (ok) onSuccess() else onFailure()
    }

    @Synchronized
    fun failureCount(): Int = failures

    @Synchronized
    fun reset() {
        failures = 0
        openUntilMs = 0L
    }
}
