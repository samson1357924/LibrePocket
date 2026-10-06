package dev.librepocket.jev

/**
 * D01 熔斷（JEV_INTEGRATION_DRAFT §5）：連續 5 次失敗熔斷 60s，
 * 期間直接走 [NoOpJevRouter] 等價路徑，不調 Jev。
 *
 * - 失敗 = [JevStatus.ERROR] / [JevStatus.TIMEOUT] / [JevStatus.UNAVAILABLE]；
 *   [JevStatus.ABSTAIN] 是模型正常回應（無把握），不計失敗。
 * - 時鐘可注入，測試用 fake clock；執行緒安全。
 */
class JevCircuitBreaker(
    private val maxFailures: Int = 5,
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
        // 半開恢復：若已過期，canCall 已清零；此處不主動關閉未過期的熔斷。
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
    fun onResult(status: JevStatus) {
        when (status) {
            JevStatus.OK, JevStatus.ABSTAIN -> onSuccess()
            JevStatus.ERROR, JevStatus.TIMEOUT, JevStatus.UNAVAILABLE -> onFailure()
        }
    }

    @Synchronized
    fun failureCount(): Int = failures

    @Synchronized
    fun reset() {
        failures = 0
        openUntilMs = 0L
    }
}
