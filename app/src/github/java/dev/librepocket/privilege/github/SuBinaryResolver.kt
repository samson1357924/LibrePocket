package dev.librepocket.privilege.github

import java.io.File

private val SLASH_RUN = Regex("/+")

/**
 * 可信 su 解析（B2，Issue #136 B2，僅 github 風味）。
 *
 * - 舊 [RootSuRunner] 以 bare `"su"` spawn，落地目標由裝置 `PATH` 決定，
 *   可被劫持；此處只認 [RootSuProbe.SU_BINARIES]（絕對路徑存在性檢查，
 *   不執行任何二進位）；
 * - [resolve] 為純存在性檢查（[exists] 可注入，JVM 單測零真機、零 su）；
 * - 呼叫方（[RootSuRunner]）以解析後的絕對路徑 spawn，缺席即
 *   `Failed("su unavailable...")` 且零 spawn。
 * - 呼叫方雙注入保留（不合併，避免 API 破壞）：[RootSuRunner.suBinary]
 *   為直接覆寫的候選路徑（仍須過 [isTrusted]，否則 fail-closed 拒絕），
 *   [RootSuRunner.suExists] 為 [resolve] 用的存在性檢查；兩者正交，
 *   一個管「用哪條」，一個管「哪條存在」。
 * P2-A 可見性：僅 [RootSuRunner]＋同模組單測使用，收斂為 internal。
 */
internal object SuBinaryResolver {
    fun resolve(exists: (String) -> Boolean = { path -> File(path).exists() }): String? {
        for (candidate in RootSuProbe.SU_BINARIES) {
            val present = runCatching { exists(candidate) }.getOrDefault(false)
            if (present) return candidate
        }
        return null
    }

    /**
     * 可信判定：僅解析表內絕對路徑放行（含單測注入的同表路徑）。
     *
     * 輸入先做字串正規化（合併重複 `/`、剔除 `.` 段）再比對；
     * TOCTOU／symlink 揭露：此處只做字串層正規化，不解 symlink、不做
     * 存在時點綁定，解析到 spawn 之間的替換（TOCTOU）與表內路徑被鏈到
     * 不可信目標（symlink）仍不在本層收斂，依賴裝置側 root 方案自身的
     * 路徑完整性。
     */
    fun isTrusted(path: String): Boolean = normalizeSuPath(path) in RootSuProbe.SU_BINARIES

    /**
     * 字串層正規化：合併重複 `/`、剔除 `.` 段。
     *
     * 刻意不解 `..`、不跟隨 symlink（只做字串正規化，語義不變）：
     * `..` 保留原樣比對（非表內即 fail-closed），避免詞法消解改變指涉；
     * 大小寫不折疊（Linux 路徑大小寫敏感）。
     */
    internal fun normalizeSuPath(path: String): String {
        if (path.isEmpty()) return path
        val collapsed = path.replace(SLASH_RUN, "/")
        val trailing = collapsed.length > 1 && collapsed.endsWith("/")
        val parts = collapsed.split("/")
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part == ".") continue
            out.add(part)
        }
        var result = out.joinToString("/")
        if (result.isEmpty()) return "/"
        if (trailing && result.length > 1 && result.endsWith("/")) {
            result = result.dropLast(1)
        }
        return result
    }
}
