package dev.librepocket.linux

/**
 * S4 工具註冊元資料（ToolRegistry S4 錨點塊的單一來源）。
 *
 * - 五 WRITE + 一 PRIVILEGED（`decompile.repack` 獨立拆分，每次確認）；
 *   `linux.*` 共用開關 [LinuxBoot.SWITCH]，`compile.build` 用
 *   [CompileBuild.SWITCH]，`decompile.*` 用 [DecompileAnalyze.SWITCH]，
 *   三者預設全關。
 * - 三風味：play 不在支援集（投影 UNAVAILABLE + FLAVOR_BLOCKED，
 *   即「隱藏」）；foss/github NATIVE。
 * - 效能降級明示：[PERF_NOTICE] 必須出現在降級/不可用回覆與
 *   設定頁文案（PRoot 為 userspace 翻譯，無 KVM/root，CPU 密集任務
 *   明顯慢於桌機/CI；機內僅做輕量編譯與分析）。
 */
object LinuxTools {

    /** S4 六工具名（註冊順序即 ToolRegistry 列舉順序）。 */
    val ALL_NAMES: List<String> = listOf(
        LinuxBoot.NAME,
        ProotExec.NAME,
        LinuxPkg.NAME,
        CompileBuild.NAME,
        DecompileAnalyze.NAME,
        DecompileAnalyze.REPACK_NAME,
    )

    /**
     * 效能降級明示文案（呼叫方引用原文，不得改寫為效能承諾）。
     */
    const val PERF_NOTICE: String =
        "機內 Linux 跑在 PRoot（userspace，無 KVM、無 root），CPU 密集任務" +
            "（編譯大專案、jadx 全量反編譯）明顯慢於桌機/CI，僅適合輕量任務；" +
            "大型構建請用 CI，產物經 inbox 取回。"
}
