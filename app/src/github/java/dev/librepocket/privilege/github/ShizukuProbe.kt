package dev.librepocket.privilege.github

import rikka.shizuku.Shizuku

/**
 * Shizuku 被動探測（S3，BACKLOG D09，矩陣 §3）。
 *
 * - 預設走真實 [Shizuku.pingBinder]，任何異常（未安裝/未授權/binder 死亡）
 *   經 runCatching 收斂為 false，呼叫方自動回落免 Root 路徑，不打擾使用者；
 * - 探測函數可注入，JVM 單測直接斷言「拋異常 → false」；
 * - su 側沿用 main 的 [dev.librepocket.shell.PrivilegeProbe] 預設探測
 *   （常見路徑存在性檢查，不執行二進位）。
 */
object ShizukuProbe {
    /** Shizuku binder 可達性：`pingBinder()` 的 runCatching 包裝，異常一律 false。 */
    fun ping(pingBinder: () -> Boolean = { Shizuku.pingBinder() }): Boolean =
        runCatching { pingBinder() }.getOrDefault(false)

    /** 雙通道探測（Shizuku binder + su 路徑），失敗 fail-closed，呼叫方回落。 */
    fun probe(
        shizukuProbe: () -> Boolean = { ping() },
        suProbe: () -> Boolean = dev.librepocket.shell.PrivilegeProbe.defaultSuProbe,
    ): List<dev.librepocket.shell.ProbeResult> =
        dev.librepocket.shell.PrivilegeProbe(
            shizukuProbe = shizukuProbe,
            suProbe = suProbe,
        ).probe()
}
