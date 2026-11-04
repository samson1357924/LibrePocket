package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellPolicy
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
 * B2 二進位修正（Issue #136 B2）：舊 `screencapViaElevated` 經
 * `ShellResult.Ok.stdout.toByteArray(UTF_8)` 往返，非 UTF-8 位元組（PNG）
 * 即損毀；現若 runner 實作 [BinaryElevatedRunner] 即走 [runBinary] 位元組
 * 直達（[screencapViaBinary]/[fb0ViaBinary]），否則回落舊 String 路徑
 * （僅相容 [dev.librepocket.shell.DenyingElevatedRunner] 等文字 runner）。
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
        /**
         * 二進位審計＋位元組抽取共用（P2-A）：兩路 `*ViaBinary` 皆經此記帳，
         * 避免雙寫 verdict 映射。行為錨定：`audit.record(argv/reason/verdict)`
         * 只記碼不記明文；僅 [ElevatedBinaryResult.Ok] 且 `truncated==false`
         * 回位元組拷貝
         * （`copyOf().takeIf { isNotEmpty() }`，防下游竄改 runner 緩衝），
         * 其餘（Denied/TimedOut/Failed，以及 Ok 但 truncated==true 的部分幀）
         * 一律回 null（呼叫方降級手動截圖）。截斷即 null，不摘要部分幀：
         * 截斷 PNG 仍被當成功幀摘要會虛構完整截圖，故視為不可用；
         * 審計仍記 verdict（Ok 截斷仍記 OK，只是不回位元組）。
         */
        private fun auditAndExtract(
            request: ElevatedRequest,
            result: ElevatedBinaryResult,
            audit: PrivilegeAuditLog,
        ): ByteArray? {
            audit.record(request.argv, request.reasonCode, result.verdict())
            val ok = result as? ElevatedBinaryResult.Ok ?: return null
            if (ok.truncated) return null
            return ok.stdout.copyOf().takeIf { it.isNotEmpty() }
        }

        /**
         * D09 門禁版 screencap 讀取：經提權通道執行 `screencap -p`
         *（[dev.librepocket.shell.ShellPolicy.validateElevated] 先行 +
         * 配額 + reasonCode 非空 + bridgeGranted，執行後
         * [PrivilegeAuditLog.recordResult] 留痕，只記雜湊計數）。
         * 通道拒絕/失敗回 null（呼叫方降級手動截圖）。
         *
         * B2：runner 若為 [BinaryElevatedRunner] 即走位元組直達
         * （[screencapViaBinary]），否則回落 String 路徑。
         *
         * P2-A 分發說明：此處 `as? BinaryElevatedRunner` 為相容分發——
         * 僅實作 [ElevatedShellRunner] 的裝飾器（例如測試替身、舊攔截器）
         * 即走 String 回落（lossy，僅相容文字 runner）；B3 起新 Runner
         * 應一律實作 [BinaryElevatedRunner]（多載優先：直接呼叫
         * [screencapViaBinary]/[fb0ViaBinary] 即走位元組直達，不經此分發）。
         * 舊路徑不刪（相容 [dev.librepocket.shell.DenyingElevatedRunner]
         * 等文字 runner）。
         */
        fun screencapViaElevated(
            runner: ElevatedShellRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        ): () -> ByteArray? {
            val binary = runner as? BinaryElevatedRunner
            if (binary != null) return screencapViaBinary(binary, audit, reasonCode, timeoutMs)
            return {
                val request = ElevatedRequest(listOf("screencap", "-p"), reasonCode)
                val result = runner.run(request, timeoutMs)
                audit.recordResult(request, result)
                val ok = result as? ShellResult.Ok
                if (ok == null || ok.truncated) null
                else ok.stdout.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
            }
        }

        /**
         * D09 門禁版 fb0 兜底讀取：經提權通道執行 `cat /dev/graphics/fb0`
         *（跨域路徑，需 [dev.librepocket.files.FileScope.decide] 回 needsBridge
         * 且橋接已授權；其餘門禁與審計同 [screencapViaElevated]）。
         * 通道拒絕/失敗回 null（呼叫方降級手動截圖）。
         *
         * B2：同 [screencapViaElevated]，二進位 runner 走位元組直達。
         */
        fun fb0ViaElevated(
            runner: ElevatedShellRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        ): () -> ByteArray? {
            val binary = runner as? BinaryElevatedRunner
            if (binary != null) return fb0ViaBinary(binary, audit, reasonCode, timeoutMs)
            return {
                val request = ElevatedRequest(listOf("cat", "/dev/graphics/fb0"), reasonCode)
                val result = runner.run(request, timeoutMs)
                audit.recordResult(request, result)
                val ok = result as? ShellResult.Ok
                if (ok == null || ok.truncated) null
                else ok.stdout.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
            }
        }

        /**
         * B2 位元組直達版 screencap：經 [BinaryElevatedRunner.runBinary]，
         * PNG 位元組不經 String，審計僅記 verdict（不記明文）。
         */
        fun screencapViaBinary(
            runner: BinaryElevatedRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        ): () -> ByteArray? = {
            val request = ElevatedRequest(listOf("screencap", "-p"), reasonCode)
            val result = runner.runBinary(request, timeoutMs)
            auditAndExtract(request, result, audit)
        }

        /**
         * B2 位元組直達版 fb0：同 [screencapViaBinary]，執行
         * `cat /dev/graphics/fb0`（跨域需 needsBridge＋橋授權）。
         */
        fun fb0ViaBinary(
            runner: BinaryElevatedRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        ): () -> ByteArray? = {
            val request = ElevatedRequest(listOf("cat", "/dev/graphics/fb0"), reasonCode)
            val result = runner.runBinary(request, timeoutMs)
            auditAndExtract(request, result, audit)
        }
    }
}
