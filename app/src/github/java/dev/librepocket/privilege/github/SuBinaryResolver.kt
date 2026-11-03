package dev.librepocket.privilege.github

import java.io.File

/**
 * 可信 su 解析（B2，Issue #136 B2，僅 github 風味）。
 *
 * - 舊 [RootSuRunner] 以 bare `"su"` spawn，落地目標由裝置 `PATH` 決定，
 *   可被劫持；此處只認 [RootSuProbe.SU_BINARIES]（絕對路徑存在性檢查，
 *   不執行任何二進位）；
 * - [resolve] 為純存在性檢查（[exists] 可注入，JVM 單測零真機、零 su）；
 * - 呼叫方（[RootSuRunner]）以解析後的絕對路徑 spawn，缺席即
 *   `Failed("su unavailable...")` 且零 spawn。
 */
object SuBinaryResolver {
    fun resolve(exists: (String) -> Boolean = { path -> File(path).exists() }): String? {
        for (candidate in RootSuProbe.SU_BINARIES) {
            val present = runCatching { exists(candidate) }.getOrDefault(false)
            if (present) return candidate
        }
        return null
    }

    /** 可信判定：僅解析表內絕對路徑放行（含單測注入的同表路徑）。 */
    fun isTrusted(path: String): Boolean = path in RootSuProbe.SU_BINARIES
}
