package dev.librepocket.privilege.github

import rikka.shizuku.Shizuku

/**
 * Shizuku 被動探測（S3，BACKLOG D09，矩陣 §3）。
 *
 * - 預設走真實 [Shizuku.pingBinder]，任何異常（未安裝/未授權/binder 死亡）
 *   經 runCatching 收斂為 false，呼叫方自動回落免 Root 路徑，不打擾使用者；
 * - 探測函數可注入，JVM 單測直接斷言「拋異常 → false」；
 * - su 側預設走 [RootSuProbe] 同源 6 路徑可執行判定
 *   （`RootSuProbe.probe().suExecutableAvailable`，只認 [RootSuProbe.SU_BINARIES]
 *   存在性，不執行二進位；管理器標記僅供診斷，不計入可執行）。
 *   與 main [dev.librepocket.shell.PrivilegeProbe.defaultSuProbe] 的 4 路徑差異：
 *   github 源集多認 `/vendor/bin/su`＋`/system/xbin/daemonsu` 兩條 OEM/變體位
 *   （見 [RootSuProbe.SU_BINARIES]）；markers（Magisk/KernelSU/APatch）存在
 *   僅置 [RootSuProbe.Report.anyAvailable]，不置可執行、不影響本預設。
 */
object ShizukuProbe {
    /** Shizuku binder 可達性：`pingBinder()` 的 runCatching 包裝，異常一律 false。 */
    fun ping(pingBinder: () -> Boolean = { Shizuku.pingBinder() }): Boolean =
        runCatching { pingBinder() }.getOrDefault(false)

    /** 雙通道探測（Shizuku binder + su 路徑），失敗 fail-closed，呼叫方回落。 */
    fun probe(
        shizukuProbe: () -> Boolean = { ping() },
        suProbe: () -> Boolean = { RootSuProbe.probe().suExecutableAvailable },
    ): List<dev.librepocket.shell.ProbeResult> =
        dev.librepocket.shell.PrivilegeProbe(
            shizukuProbe = shizukuProbe,
            suProbe = suProbe,
        ).probe()
}
