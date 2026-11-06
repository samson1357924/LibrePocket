package dev.librepocket.privilege.github

import java.io.File

/**
 * Root su 探測（#136-B1，BACKLOG D09，Issue #136-B1，僅 github 風味）。
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
    /**
     * 傳統 su 二進位（含 OEM/變體常見位）。
     *
     * 非窮舉：例如 `/data/adb/ksu/bin/su`（KernelSU su 變體）等未列入；
     * 缺席不代表無 root，呼叫方一律 fail-closed 回落到免 Root 路徑。
     */
    val SU_BINARIES: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/system/xbin/daemonsu",
    )

    /**
     * Magisk 標記：掛載點＋管理器資料 dir（只認存在性，不執行）。
     *
     * 路徑存在性僅供參考，版本未 pin（管理器版本升級可能遷移路徑）。
     */
    val MAGISK_MARKERS: List<String> = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
    )

    /**
     * KernelSU 標記。
     *
     * 路徑存在性僅供參考，版本未 pin（管理器版本升級可能遷移路徑）。
     */
    val KERNELSU_MARKERS: List<String> = listOf(
        "/data/adb/ksu",
        "/data/adb/ksud",
    )

    /**
     * APatch 標記。
     *
     * 路徑存在性僅供參考，版本未 pin（管理器版本升級可能遷移路徑）。
     */
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
        /**
         * 可直接 spawn 的 su 二進位是否存在（runner 唯一執行依據）。
         *
         * probe 稱可用≠可執行：即使本值為 true，提權仍可能被拒
         * （參見 [dev.librepocket.privilege.github.isSuDenied]）。
         */
        val suExecutableAvailable: Boolean
            get() = suBinaries.isNotEmpty()

        /**
         * 任一通道可用（含各方案管理器標記）。
         *
         * 注意：管理器標記存在≠ su 可執行；[RootSuRunner] 實際以
         * [SuBinaryResolver.resolve]（同源 [SU_BINARIES]）為準，
         * 本值只供診斷／UI 展示。
         */
        val anyAvailable: Boolean
            get() = suExecutableAvailable || magisk || kernelSu || aPatch
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
