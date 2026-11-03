package dev.librepocket.privilege.github

import java.io.File

/**
 * Root su 探測（B1，BACKLOG D09，Issue #136 B1，僅 github 風味）。
 *
 * 補齊 Magisk／KernelSU／APatch 路徑（Issue #136 §Scope-1）：
 * 既有 [dev.librepocket.shell.PrivilegeProbe.defaultSuProbe] 只認四條
 * 傳統 su 路徑，本物件另認各方案管理器標記與 su 變體；main 源集不動，
 * 本物件為 github-only 延伸。
 *
 * - 只做存在性檢查，絕不執行任何二進位（含 su 本體）；
 * - [exists] 可注入：JVM 單測以假映射斷言，零真機、零 su；
 * - 單一路徑探測異常 runCatching 收斂為缺席（fail-closed：未知即無）。
 */
object RootSuProbe {
    /** 傳統 su 二進位（含 OEM/變體常見位）。 */
    val SU_BINARIES: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/system/xbin/daemonsu",
    )

    /** Magisk 標記：掛載點＋管理器資料 dir（只認存在性，不執行）。 */
    val MAGISK_MARKERS: List<String> = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
    )

    /** KernelSU 標記。 */
    val KERNELSU_MARKERS: List<String> = listOf(
        "/data/adb/ksu",
        "/data/adb/ksud",
    )

    /** APatch 標記。 */
    val APATCH_MARKERS: List<String> = listOf(
        "/data/adb/ap",
        "/data/adb/apd",
    )

    /** 分方案探測報告（typed，非裸 boolean）。 */
    data class Report(
        val suBinaries: List<String>,
        val magisk: Boolean,
        val kernelSu: Boolean,
        val aPatch: Boolean,
    ) {
        /** 任一通道可用。 */
        val anyAvailable: Boolean
            get() = suBinaries.isNotEmpty() || magisk || kernelSu || aPatch
    }

    fun probe(
        exists: (String) -> Boolean = { path -> File(path).exists() },
    ): Report {
        fun present(path: String): Boolean =
            runCatching { exists(path) }.getOrDefault(false)
        fun any(paths: List<String>): Boolean = paths.any(::present)
        return Report(
            suBinaries = SU_BINARIES.filter(::present),
            magisk = any(MAGISK_MARKERS),
            kernelSu = any(KERNELSU_MARKERS),
            aPatch = any(APATCH_MARKERS),
        )
    }
}
