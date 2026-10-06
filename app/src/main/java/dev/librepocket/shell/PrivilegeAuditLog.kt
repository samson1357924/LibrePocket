package dev.librepocket.shell

import java.security.MessageDigest

/**
 * 提權審計表（S3，BACKLOG D09，矩陣 §3/§4）。
 *
 * 仿 [dev.librepocket.guard.SlowAuditLog] 的形狀，但只記雜湊與計數，
 * 絕不記明文：
 * - 寫入簽名本身就不接收可回溯的明文：argv 與 reasonCode 只以
 *   SHA-256 摘要（[argvHash]/[reasonHash]）落表；
 * - 轉錄/匯出只能拿到 verdict / 計數（[countByVerdict]），拿不到原文；
 * - 使用者在設定頁一鍵收回即 [revokeAll]（清表，回傳清除筆數，
 *   呼叫方同時把 `privilege_bridge` 開關與橋接授權一起關掉）。
 *
 * 零 Android 依賴，JVM 單測可直接斷言。
 */
data class PrivilegeAuditRecord(
    val seqId: String,
    val verdict: String,
    /** argv 向量的 SHA-256（hex），非原文。 */
    val argvHash: String,
    /** 跨權限邊界原因碼的 SHA-256（hex），非原文。 */
    val reasonHash: String,
    val atMs: Long,
)

class PrivilegeAuditLog {
    private val records = mutableListOf<PrivilegeAuditRecord>()
    private val lock = Any()
    private var seq: Long = 0L

    fun record(
        argv: List<String>,
        reasonCode: String,
        verdict: String,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeAuditRecord {
        require(verdict.isNotBlank()) { "verdict must not be blank" }
        val row = synchronized(lock) {
            seq += 1
            PrivilegeAuditRecord(
                seqId = "priv-$seq",
                verdict = verdict,
                argvHash = sha256Hex(argv.joinToString("\u0001")),
                reasonHash = sha256Hex(reasonCode),
                atMs = atMs,
            )
        }
        synchronized(lock) {
            records += row
            // 有界審計表：超過上限丟棄最舊（seqId 單調遞增保證轉錄側可察覺空洞，
            // 見 MAX_RECORDS）。
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
        return row
    }

    /** 從提權執行結果直接記帳：只提取結果種類名，輸出明文不進表。 */
    fun recordResult(
        request: ElevatedRequest,
        result: ShellResult,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeAuditRecord = record(
        argv = request.argv,
        reasonCode = request.reasonCode,
        verdict = verdictName(result),
        atMs = atMs,
    )

    fun snapshot(): List<PrivilegeAuditRecord> = synchronized(lock) { records.toList() }

    fun size(): Int = synchronized(lock) { records.size }

    fun countByVerdict(): Map<String, Int> = synchronized(lock) {
        records.groupingBy { it.verdict }.eachCount()
    }

    /**
     * 一鍵收回：清表並回傳清除筆數。呼叫方（設定頁）必須同時關閉
     * `privilege_bridge` 開關與橋接授權，保證收回後不再有跨域執行。
     */
    fun revokeAll(): Int = synchronized(lock) {
        val n = records.size
        records.clear()
        n
    }

    companion object {
        /**
         * 審計表上限：超過即丟棄最舊列（有界記憶體，防長會話無限膨脹；
         * seqId 單調遞增，轉錄側以序號空洞察覺丟棄）。
         */
        const val MAX_RECORDS: Int = 1_000

        fun verdictName(result: ShellResult): String = when (result) {
            is ShellResult.Ok -> "OK"
            is ShellResult.Denied -> "DENY"
            is ShellResult.TimedOut -> "TIMEOUT"
            is ShellResult.Failed -> "FAILED"
        }

        fun sha256Hex(input: String): String =
            sha256HexBytes(input.toByteArray(Charsets.UTF_8))

        fun sha256HexBytes(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return buildString(digest.size * 2) {
                for (b in digest) append("%02x".format(b))
            }
        }
    }
}
