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
 * R3-B 二進位-only 合約（owner P2-6 後半＋PocketGuard WARN capture fallback）：
 * 新增 [screencapViaElevated]/[fb0ViaElevated] 的 [BinaryElevatedRunner] 多載，
 * 直接走位元組直達（無 `as?`，本體即委派 [screencapViaBinary]/[fb0ViaBinary]）；
 * 舊 `ElevatedShellRunner` 通用版標 Deprecated（行為不變，僅相容文字 runner，
 * 會損毀 PNG）。審計（R3-B）：grep 產品源集（`app/src/main`＋`app/src/github`）
 * 零呼叫，僅測試引用；產品 #136-B3 接線應使用 Binary 多載。
 *
 * B4 PNG 輕量門（owner P2-6，前者落地；Phase 4 P2-4 收緊，後者為準）：
 * - `screencap -p` 產物必須為 PNG：[isPlausiblePng] 驗 8 位元組 signature
 *  （`89 50 4E 47 0D 0A 1A 0A`）＋第一塊 IHDR（length==13、`"IHDR"`，
 *  width／height 皆 >0）＋尾 12 位元組 IEND chunk
 *  （`00 00 00 00 49 45 4E 44 AE 42 60 82`）＋最小長度 45；不做全 chunk walk／
 *   不驗 CRC／IDAT，不解碼（輕量，JVM 零依賴；非解碼保證，僅擋截斷／拼接）；
 * - `capture()` 側：screencap 位元組未過門即視為缺失，回落 fb0（fb0 為原始
 *   framebuffer，不做 PNG 門）；兩路皆無／皆無效即 null（降級手動截圖）；
 * - `screencapViaBinary`／`screencapViaElevated`（String 回落同）側：Ok 非截斷
 *   但 PNG 無效即 null，審計記 `FRAME_REJECTED`（非 `OK`，機器可區分）；
 *   truncated 即 null 仍記既有 `OK`（B1-B3 語義 pin 保留，不改，見
 *   `RootCaptureTest.truncatedOkYieldsNullWithoutSummarizingPartialFrame`）；
 * - fb0 通道（原始位元組）不做 PNG 門，語義不變。
 *
 * S2-B 容量矩陣（owner P2-4，僅 KDoc 文件化，行為零變更）：
 * - 典型 PNG 截圖 1-3MB：完整通過（<8M，Ok 非 truncated，capture 可摘要）；
 * - 600KB fb0：完整通過；
 * - 未壓縮 RGBA 1080x2400x4 ≈10.37MB：明確不支援（>8M 即 truncated→null，
 *   呼叫方降級手動截圖；截斷即 null，不摘要部分幀，見 `auditAndExtract`）；
 * - 高熵 PNG 超限同理：>8M 即 truncated→null，不分內容熵；
 * - 容量常數見 [ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES]（stdout 8M 截斷＋硬讀取）；
 *   pipe 保留 8M＋512K≈8.5M，非進程峰值；閾值恰等於硬讀上限（8M==8M）靠溢出位區分
 *   恰上限通過與超限丟棄（見容量測試 8M exact／8M+1）；
 *   裝置容量驗收歸 #136-B3 E2E（見 [PrivilegeActivation]）。
 *
 * U2-B stderr 獨立小 cap（owner P2）：截圖 stderr 維持通用軌（硬讀取 512K／
 * 截斷 64K；截圖錯誤訊息短，降 OOM 峰值）——stderr 超限同樣經 truncated→null
 * （即使 stdout 完整；見 `auditAndExtract`：Ok.truncated 即 null，不分哪路截斷）。
 *
 * 讀取函數可注入，JVM 單測直接斷言優先序與摘要語義。
 */
class RootFramebufferCapture(
    private val readScreencap: () -> ByteArray?,
    private val readFb0: () -> ByteArray?,
) {
    /**
     * screencap 優先、fb0 兜底；只回摘要（像素不保留）。
     * B4：screencap 位元組須過 [isPlausiblePng]（輕量 PNG 門），未過即視為
     * 缺失回落 fb0（fb0 原始位元組不做 PNG 門）；兩路皆無／皆無效為 null。
     * @return 幀摘要，兩路皆無時為 null（呼叫方降級手動截圖）。
     */
    fun capture(atMs: Long = System.currentTimeMillis()): FrameDigest? {
        val screen = runCatching { readScreencap() }.getOrNull()
        if (screen != null && screen.isNotEmpty() && isPlausiblePng(screen)) {
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
         * B4 PNG 輕量門（owner P2-6；Phase 4 P2-4 收緊，PR #140）：
         * 只驗 signature＋第一塊 IHDR＋尾 IEND＋最小長度，不做全 chunk
         * walk／不解碼、不驗 CRC／IDAT（輕量，JVM 零依賴；**非解碼保證**，
         * 僅擋截斷／拼接／非 PNG 誤摘要）。fb0 原始位元組不經此門。
         *
         * - 最小長度 45：sig(8)＋IHDR 塊(25：len4＋"IHDR"＋data13＋crc4)＋
         *   IEND(12)；舊 20-byte 拼接（sig＋IEND 直貼）在本門即拒；
         * - 第一塊必須是 IHDR：length==13、`"IHDR"`、width／height 皆 >0
         *   （big-endian uint32；零寬高即拒）；
         * - 尾 12 位元組須為 IEND chunk。
         *
         * truncated-Ok 審計語義（B1-B3 pin 保留，Phase 4 不改）：
         * `auditAndExtractScreenshot` 對 truncated Ok 仍記 `OK`（只是不回
         * 位元組、不摘要部分幀）；`OK` 含 truncated-Ok，轉錄側不得把 `OK`
         * 直接解讀為「完整幀已摘要」。若 Phase 5 要新增 `OK_TRUNCATED`
         * verdict，須同步雙映射（[PrivilegeAuditLog.verdictName]＋
         * [verdict]）並加測試鎖定，見 Phase 5 事項。
         */
        internal val PNG_SIGNATURE: ByteArray =
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A,
            )
        private val PNG_IEND: ByteArray =
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0x49, 0x45, 0x4E, 0x44,
                0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
            )

        /** IHDR 塊固定長度（不含 len＋type 頭的 data 段 13 位元組）。 */
        private const val PNG_IHDR_DATA_LEN = 13

        /** 最小 PNG 長度：sig(8)＋IHDR 塊(4＋4＋13＋4)＋IEND(12)＝45。 */
        internal const val PNG_MIN_BYTES: Int = 8 + (4 + 4 + PNG_IHDR_DATA_LEN + 4) + 12

        internal fun isPlausiblePng(bytes: ByteArray): Boolean {
            if (bytes.size < PNG_MIN_BYTES) return false
            if (!bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return false
            // 第一塊須為 IHDR：length==13（4 位元組 big-endian）＋"IHDR"。
            if (bytes[8] != 0x00.toByte() || bytes[9] != 0x00.toByte() ||
                bytes[10] != 0x00.toByte() || bytes[11] != 0x0D.toByte()
            ) {
                return false
            }
            if (bytes[12] != 0x49.toByte() || bytes[13] != 0x48.toByte() ||
                bytes[14] != 0x44.toByte() || bytes[15] != 0x52.toByte()
            ) {
                return false
            }
            // IHDR data：width／height（各 4 位元組 big-endian uint32）皆須 >0。
            if (u32Be(bytes, 16) == 0L || u32Be(bytes, 20) == 0L) return false
            val tail = bytes.copyOfRange(bytes.size - PNG_IEND.size, bytes.size)
            if (!tail.contentEquals(PNG_IEND)) return false
            return true
        }

        private fun u32Be(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                (bytes[offset + 3].toLong() and 0xFF)

        /**
         * 二進位審計＋位元組抽取共用（P2-A）：兩路 `*ViaBinary` 皆經此記帳，
         * 避免雙寫 verdict 映射。行為錨定：`audit.record(argv/reason/verdict)`
         * 只記碼不記明文；僅 [ElevatedBinaryResult.Ok] 且 `truncated==false`
         * 回位元組拷貝
         * （`copyOf().takeIf { isNotEmpty() }`，防下游竄改 runner 緩衝），
         * 其餘（Denied/TimedOut/Failed，以及 Ok 但 truncated==true 的部分幀）
         * 一律回 null（呼叫方降級手動截圖）。截斷即 null，不摘要部分幀：
         * 截斷 PNG 仍被當成功幀摘要會虛構完整截圖，故視為不可用；
         * 審計仍記 verdict（Ok 截斷仍記 OK，只是不回位元組；B1-B3 pin 保留）。
         */
        private fun auditAndExtract(
            request: ElevatedRequest,
            result: ElevatedBinaryResult,
            audit: PrivilegeAuditLog,
        ): ByteArray? {
            audit.record(request.argv, request.reasonCode, result.verdict())
            when (result) {
                is ElevatedBinaryResult.Ok -> {
                    if (result.truncated) return null
                    return result.stdout.copyOf().takeIf { it.isNotEmpty() }
                }
                is ElevatedBinaryResult.Denied,
                is ElevatedBinaryResult.Failed,
                is ElevatedBinaryResult.TimedOut,
                -> return null
            }
        }

        /**
         * B4 screencap 專用抽取：先過 PNG 輕量門，未過即 `FRAME_REJECTED`＋null
         *（機器可區分 malformed 與 truncated；truncated 仍走 [auditAndExtract]
         * 記既有 `OK`，語義不改）。fb0 不經此函數（原始位元組無 PNG 門）。
         */
        private fun auditAndExtractScreenshot(
            request: ElevatedRequest,
            result: ElevatedBinaryResult,
            audit: PrivilegeAuditLog,
        ): ByteArray? {
            if (result is ElevatedBinaryResult.Ok &&
                !result.truncated &&
                result.stdout.isNotEmpty() &&
                !isPlausiblePng(result.stdout)
            ) {
                audit.record(request.argv, request.reasonCode, "FRAME_REJECTED")
                return null
            }
            return auditAndExtract(request, result, audit)
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
         * 即走 String 回落（lossy，僅相容文字 runner）；#136-B3 起新 Runner
         * 應一律實作 [BinaryElevatedRunner]（多載優先：直接呼叫
         * [screencapViaBinary]/[fb0ViaBinary] 或 Binary 多載
         * [screencapViaElevated]/[fb0ViaElevated] 即走位元組直達，不經此分發）。
         * 舊路徑不刪（相容 [dev.librepocket.shell.DenyingElevatedRunner]
         * 等文字 runner）。
         *
         * R3-B：本通用版已 Deprecated（訊息「二進位優先：傳 BinaryElevatedRunner
         * 走直達；文字 runner 僅相容，會損毀 PNG」），行為不變；新碼一律使用
         * Binary 多載。審計（R3-B）：產品源集零呼叫，僅測試引用。#136-B3 前相容保留，不刪除；#136-B3 前重審移除。
         */
        @Deprecated("二進位優先：傳 BinaryElevatedRunner 走直達；文字 runner 僅相容，會損毀 PNG")
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
                val ok = result as? ShellResult.Ok
                if (ok == null || ok.truncated) {
                    audit.recordResult(request, result)
                    null
                } else {
                    // B4：String 回落同過 PNG 輕量門；lossy 損毀位元組未過門即
                    // FRAME_REJECTED＋null（不摘要損毀幀）；空位元組維持既有
                    // OK＋null（B1-B3 pin，不改）。
                    val bytes = ok.stdout.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
                    if (bytes == null) {
                        audit.recordResult(request, result)
                        null
                    } else if (!isPlausiblePng(bytes)) {
                        audit.record(request.argv, request.reasonCode, "FRAME_REJECTED")
                        null
                    } else {
                        audit.recordResult(request, result)
                        bytes
                    }
                }
            }
        }

        /**
         * D09 門禁版 fb0 兜底讀取：經提權通道執行 `cat /dev/graphics/fb0`
         *（跨域路徑，需 [dev.librepocket.files.FileScope.decide] 回 needsBridge
         * 且橋接已授權；其餘門禁與審計同 [screencapViaElevated]）。
         * 通道拒絕/失敗回 null（呼叫方降級手動截圖）。
         *
         * B2：同 [screencapViaElevated]，二進位 runner 走位元組直達。
         *
         * R3-B：本通用版已 Deprecated（訊息同 [screencapViaElevated]），行為不變；
         * 新碼一律使用 Binary 多載 [fb0ViaElevated]。審計（R3-B）：產品源集零呼叫，
         * 僅測試引用。#136-B3 前相容保留，不刪除；#136-B3 前重審移除。
         */
        @Deprecated("二進位優先：傳 BinaryElevatedRunner 走直達；文字 runner 僅相容，會損毀 PNG")
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
         * R3-B 二進位-only 多載版 screencap：首參為 [BinaryElevatedRunner] 即
         * 直接走位元組直達（本體即委派 [screencapViaBinary]，無 `as?` 分發）。
         * 產品 #136-B3 接線應使用本多載；文字 runner 僅走上方 Deprecated 通用版
         * （相容，會損毀 PNG）。
         */
        fun screencapViaElevated(
            runner: BinaryElevatedRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        ): () -> ByteArray? = screencapViaBinary(runner, audit, reasonCode, timeoutMs)

        /**
         * R3-B 二進位-only 多載版 fb0：同 [screencapViaElevated]（Binary 多載），
         * 直接走位元組直達（本體即委派 [fb0ViaBinary]，無 `as?` 分發）。
         */
        fun fb0ViaElevated(
            runner: BinaryElevatedRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        ): () -> ByteArray? = fb0ViaBinary(runner, audit, reasonCode, timeoutMs)

        /**
         * B2 位元組直達版 screencap：經 [BinaryElevatedRunner.runBinary]，
         * PNG 位元組不經 String，審計僅記 verdict（不記明文）。
         * B4：經 [auditAndExtractScreenshot]（PNG 輕量門＋FRAME_REJECTED）。
         */
        fun screencapViaBinary(
            runner: BinaryElevatedRunner,
            audit: PrivilegeAuditLog,
            reasonCode: String,
            timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
        ): () -> ByteArray? = {
            val request = ElevatedRequest(listOf("screencap", "-p"), reasonCode)
            val result = runner.runBinary(request, timeoutMs)
            auditAndExtractScreenshot(request, result, audit)
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
