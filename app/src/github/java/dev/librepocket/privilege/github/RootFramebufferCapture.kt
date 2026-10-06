package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellResult

data class FrameDigest(
    val digest: String,
    val source: FrameSource,
    val byteSize: Int,
    val atMs: Long,
)

enum class FrameSource {
    SCREENCAP,
    FB0,
}

/**
 * Root 截圖可選路徑（S3，BACKLOG D09，矩陣「截圖」行，僅 github 風味）。
 *
 * - 來源優先序：`screencap -p`（系統截圖直行，免提權）→
 *   `/dev/graphics/fb0` 兜底（需提權通道讀取）；
 * - 僅摘要進轉錄：本類只回 [FrameDigest]（SHA-256 摘要 + 來源 + 位元組數），
 *   像素位元組在摘要後即丟棄，絕不寫盤、不進審計表明文；
 * - 兩路皆無（皆 null/空）回 null，呼叫方降級「請用系統截圖/硬體按鍵」
 *   手動路徑（NO_PRIVILEGE），禁止虛構截圖成功。
 *
 * D09 門禁（合規要求）：本類不再內建任何 `ProcessBuilder`/`su` 直行路徑。
 * 兩個讀取函數均為必填注入 —— 產品接線必須使用 [screencapViaElevated] /
 * [fb0ViaElevated]（先 [dev.librepocket.shell.ShellPolicy.validateElevated]
 * + 配額 + reasonCode 非空 + bridgeGranted，後 [PrivilegeAuditLog.recordResult]，
 * 全路徑留痕），禁止傳入繞過提權通道的裸子進程 lambda。
 *
 * 讀取函數可注入，JVM 單測直接斷言優先序與摘要語義。
 */
class RootFramebufferCapture(
    private val readScreencap: () -> ByteArray?,
    private val readFb0: () -> ByteArray?,
) {
    /**
     * screencap 優先、fb0 兜底；只回摘要（像素不保留）。
     * @return 幀摘要，兩路皆無時為 null（呼叫方降級手動截圖）。
     */
    fun capture(atMs: Long = System.currentTimeMillis()): FrameDigest? {
        val screen = runCatching { readScreencap() }.getOrNull()
        if (screen != null && screen.isNotEmpty()) {
            return digestOf(screen, FrameSource.SCREENCAP, atMs)
        }
        val fb = runCatching { readFb0() }.getOrNull()
        if (fb != null && fb.isNotEmpty()) {
            return digestOf(fb, FrameSource.FB0, atMs)
        }
        return null
    }

    private fun digestOf(bytes: ByteArray, source: FrameSource, atMs: Long): FrameDigest =
        FrameDigest(
            digest = PrivilegeAuditLog.sha256HexBytes(bytes),
            source = source,
            byteSize = bytes.size,
            atMs = atMs,
        )

    companion object {
        private const val TIMEOUT_MS: Long = 10_000L

        /**
         * D09 門禁版 screencap 讀取：經提權通道執行 `screencap -p`
         *（[dev.librepocket.shell.ShellPolicy.validateElevated] 先行 +
         * 配額 + reasonCode 非空 + bridgeGranted，執行後
         * [PrivilegeAuditLog.recordResult] 留痕，只記雜湊計數）。
         * 通道拒絕/失敗回 null（呼叫方降級手動截圖）。
         */
        fun screencapViaElevated(
            runner: ElevatedShellRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = TIMEOUT_MS,
        ): () -> ByteArray? = {
            val request = ElevatedRequest(listOf("screencap", "-p"), reasonCode)
            val result = runner.run(request, timeoutMs)
            audit.recordResult(request, result)
            (result as? ShellResult.Ok)
                ?.stdout?.toByteArray(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        }

        /**
         * D09 門禁版 fb0 兜底讀取：經提權通道執行 `cat /dev/graphics/fb0`
         *（跨域路徑，需 [dev.librepocket.files.FileScope.decide] 回 needsBridge
         * 且橋接已授權；其餘門禁與審計同 [screencapViaElevated]）。
         * 通道拒絕/失敗回 null（呼叫方降級手動截圖）。
         */
        fun fb0ViaElevated(
            runner: ElevatedShellRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = TIMEOUT_MS,
        ): () -> ByteArray? = {
            val request = ElevatedRequest(listOf("cat", "/dev/graphics/fb0"), reasonCode)
            val result = runner.run(request, timeoutMs)
            audit.recordResult(request, result)
            (result as? ShellResult.Ok)
                ?.stdout?.toByteArray(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
        }
    }
}
