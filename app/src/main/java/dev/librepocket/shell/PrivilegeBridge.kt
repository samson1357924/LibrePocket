package dev.librepocket.shell

import java.io.File

/**
 * 提權子進程預留介面（BACKLOG D05/D09，矩陣 §3）。
 *
 * - 本層只做「探測」，不實作任何提權執行：[ElevatedShellRunner] 沒有預設
 *   真實現，呼叫一律被 [DenyingElevatedRunner] 拒絕。
 * - Shizuku/Root 的真正橋接屬於 foss/github 風味後續工作（D09），屆時在風味源集
 *   另行提供實現；play 風味永遠不可見提權路徑。
 */
enum class Privilege {
    SHIZUKU,
    ROOT,
}

/** 被動探測結果：失敗不打擾使用者，呼叫方自動回落免 Root 路徑。 */
data class ProbeResult(
    val privilege: Privilege,
    val available: Boolean,
)

/** 提權執行請求：每次跨權限邊界呼叫必須攜帶原因碼（矩陣 §3 審計要求）。 */
data class ElevatedRequest(
    val argv: List<String>,
    val reasonCode: String,
)

/**
 * 提權執行器介面（僅介面）。D05 階段不提供真實現；
 * D09 在 foss/github 源集落地前，呼叫方應使用 [DenyingElevatedRunner]。
 */
interface ElevatedShellRunner {
    fun run(request: ElevatedRequest, timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS): ShellResult
}

/** 佔位實現：任何提權執行一律拒絕且不建子進程（證明「僅介面」）。 */
class DenyingElevatedRunner(
    private val message: String = "elevated execution not implemented (D09)",
) : ElevatedShellRunner {
    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        ShellResult.Denied(ShellDeny.NOT_WHITELISTED, message)
}

/**
 * 被動探測器：Shizuku binder 可達性 / su 可用性。
 *
 * - 預設 su 探測只做常見路徑存在性檢查，不執行任何二進位。
 * - Shizuku 預設探測固定回 false（真實 binder 檢查需 Android/Shizuku 依賴，
 *   由 foss/github 風味的 D09 實現注入）；兩探測函數均可注入，便於單測。
 */
class PrivilegeProbe(
    private val shizukuProbe: () -> Boolean = { false },
    private val suProbe: () -> Boolean = defaultSuProbe,
) {
    fun probe(): List<ProbeResult> = listOf(
        ProbeResult(Privilege.SHIZUKU, runCatching { shizukuProbe() }.getOrDefault(false)),
        ProbeResult(Privilege.ROOT, runCatching { suProbe() }.getOrDefault(false)),
    )

    fun anyAvailable(): Boolean = probe().any { it.available }

    companion object {
        private val SU_PATHS: List<String> = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )

        /** 預設 su 探測：僅檢查檔案存在性，不執行。 */
        val defaultSuProbe: () -> Boolean = {
            SU_PATHS.any { File(it).exists() }
        }
    }
}
