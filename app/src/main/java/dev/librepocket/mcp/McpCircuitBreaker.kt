package dev.librepocket.mcp

/**
 * D03 逐伺服器熔斷：連續失敗達閾值即開路一段時間，期間直接短路
 * （回 [McpStatus.CIRCUIT_OPEN]），不發 HTTP，不中斷會話。
 *
 * - 失敗 = 傳輸錯誤 / 超時 / 非 2xx / 協議解析失敗 / 工具回 error；
 *   成功（2xx 且解析通過，即使 `isError=true` 為工具業務錯）是否計成功
 *   由呼叫方決定：本類只記 [onSuccess]/[onFailure]，語義由 [McpServer] 統一
 *   ——傳輸/協議失敗計失敗，工具業務錯（HTTP 200 + isError）不計熔斷。
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
