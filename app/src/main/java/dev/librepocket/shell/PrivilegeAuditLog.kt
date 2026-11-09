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
 *
 * Phase 5 B6 審計欄位（僅 KDoc＋TODO，不加欄位）：
 * 排障需要明文枚舉欄位（`truncated`／`cleanupIncomplete`／`exitCode`／
 * `elapsedMs`／`capTrack`，`reason` 仍只存 hash）或 `ElevatedDebugEvents`
 * 環形緩衝（仅内存、不落盤、不進轉錄），但 [PrivilegeAuditRecord] 為 data class——
 * 加欄位即改 `equals/hashCode/componentN/copy/toString`，既有
 * `snapshot().single()`＋欄位斷言、`toString()` 禁明文斷言皆須重審，
 * 且轉錄側 `countByVerdict` 形狀亦受影響。本階段不加欄位，留 #136-B3。
 * TODO(#136-B3): 審計可辨識性 —— `record` 加可選 `details`（默認 null，
 * 明文枚舉＋耗時＋cap 軌，reason 仍只存 hash）或開 `ElevatedDebugEvents`
 * 環形緩衝；加欄位時須同步更新 data-class 相等斷言與禁明文測試。
 *
 * Phase 5 B5 verdict 坍縮（詳見二進位側 `verdict()` KDoc「verdict 坍縮表」）：
 * `QUOTA_EXCEEDED` 四因同碼（未驗證 quarantine 另以獨立 `QUARANTINE` 碼拒絕，
 * 同記 `DENY`，見二進位側坍縮表）、`TimedOut` 含取消與超時、tail 取消／超時
 * （R2-PhaseC P2-2：finished 但 `tailIncomplete` 即 `TimedOut`，不再回 Ok）→
 * `TIMEOUT`、`Res.Err` 在 su 路徑
 * 與政策門路徑不對稱（`Failed` vs `Denied`）。本表只記四碼 verdict，
 * 坍縮原因須看拒絕訊息／`cleanupIncomplete` 上下文，不得僅看本表 verdict。
 *
 * Phase D（PR #140 Round-2）不變量：
 * - P2-3 argv 雜湊為單射結構化編碼 `argvHash`（`u32BE(argc)`＋每項
 *   `u32BE(len)+UTF-8`），分隔符注入無法跨向量碰撞；`record`／`recordSnapshot`
 *   簽名不變，內部編碼已換，`PrivilegeAuditRecord` 不加欄位故 data-class
 *   相等／`hashCode`／`copy` 語義不變。
 * - P2-4 單鎖：`seq` 分配＋`records+=row`＋修剪同一 `synchronized(lock)`，
 *   雜湊計算在鎖外；snapshot 內 seq 恆單調遞增且與 append 順序一致。
 */
data class PrivilegeAuditRecord(
    val seqId: String,
    val verdict: String,
    /** argv 向量的 SHA-256（hex，非原文；Phase D 起為長度前綴單射編碼之雜湊，見 `argvHash`）。 */
    val argvHash: String,
    /** 跨權限邊界原因碼的 SHA-256（hex），非原文。 */
    val reasonHash: String,
    val atMs: Long,
)

class PrivilegeAuditLog {
    private val records = mutableListOf<PrivilegeAuditRecord>()
    private val lock = Any()
    private var seq: Long = 0L

    /**
     * 記一筆審計列（Phase D 單鎖不變量）：雜湊在鎖外先算好，
     * 鎖內一次完成 `seq+=1`＋列建立＋`records+=row`＋超限修剪。
     *
     * 舊版曾用兩次 `synchronized(lock)`（先配序號、離鎖後再 append）：
     * A 配 `priv-1` 後被搶佔、B 配 `priv-2` 先 append，會使 snapshot 內
     * 出現先 `priv-2` 後 `priv-1` 的倒序，`MAX_RECORDS` 修剪亦可能刪錯列。
     * 本版 seq 分配與 append 同一臨界區，故 snapshot 內 seq 恆單調遞增、
     * append 順序即 seq 順序、修剪恆刪最舊 seq、無空洞／倒序。
     */
    fun record(
        argv: List<String>,
        reasonCode: String,
        verdict: String,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeAuditRecord {
        require(verdict.isNotBlank()) { "verdict must not be blank" }
        // Round-4 Phase N（P2-2）：reasonCode 超限 fail-closed，不哈希无界输入。
        // 入口（Dispatch／Tool）已先行检查并映 `Denied`（见各调用方 KDoc），本处为纵深：
        // 直调 record 传超限 reason 即抛（无行写入），不做截断哈希（截断即把相异长码
        // 坍缩为同哈希，审计不可辨；且 `toByteArray` 物化本身即无界成本）。
        require(!isReasonCodeTooLong(reasonCode)) {
            "reasonCode too long (max $MAX_REASON_CODE_BYTES UTF-8 bytes, fail-closed)"
        }
        // 純計算（SHA-256）放鎖外，避免持鎖做雜湊；結果綁定本次呼叫的 argv／reason。
        val argvH = argvHash(argv)
        val reasonH = sha256Hex(reasonCode)
        return synchronized(lock) {
            seq += 1
            val row = PrivilegeAuditRecord(
                seqId = "priv-$seq",
                verdict = verdict,
                argvHash = argvH,
                reasonHash = reasonH,
                atMs = atMs,
            )
            records += row
            // 有界審計表：超過上限丟棄最舊（seqId 單調遞增保證轉錄側可察覺空洞，
            // 見 MAX_RECORDS；與 seq 分配同鎖，故修剪恆刪最舊 seq）。
            while (records.size > MAX_RECORDS) records.removeAt(0)
            row
        }
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

    /**
     * 快照版記帳（Phase 4，P2-1，PR #140）：呼叫方傳入入口已凍結的
     * argv／reason（見 [ShellExecTool.executeElevated]），本函數不再觸碰
     * 原始 `request`，故 runner 執行期間的竄改不污染審計雜湊。
     * 與 [recordResult] 同為雜湊計數、不記明文；舊過載保留相容。
     */
    fun recordSnapshot(
        argvSnapshot: List<String>,
        reasonSnapshot: String,
        result: ShellResult,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeAuditRecord = record(
        argv = argvSnapshot,
        reasonCode = reasonSnapshot,
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

        /**
         * Round-4 Phase N（P2-2）：reasonCode 入站上限（短码语义）。
         *
         * - 取值 512 UTF-8 位元組：原因码为人工短码（`D09-bridge`／矩阵 §3 量级，
         *   通常 <64B），512 已留 ~8× 余量；与 argv 通道 64K 上限同属“有限输入→
         *   有限哈希成本”体系（512B 哈希为常数成本，不可成为放大的 DoS 面）。
         * - 计数口径为 UTF-8 位元组（非 `String.length`）：多字节码点（CJK 3B／
         *   emoji 4B）按实际哈希物化成本计，`length` 口径可被非 ASCII 绕行。
         * - 失败语义 fail-closed（见 [record] 与各入口）：超限即 `Denied`，
         *   不截断（截断即哈希坍缩，不可辨）、不流式哈希（短码无流式必要）。
         */
        const val MAX_REASON_CODE_BYTES: Int = 512

        /**
         * reasonCode 的 UTF-8 有界长度（P2-2 门用，不物化字节数组）：
         * 逐字符累加（ASCII 1／<0x800 2／非代理 3／代理对 4／孤立代理按替换
         * `?` 计 1，与 `toByteArray(UTF_8)` 一致），超 [MAX_REASON_CODE_BYTES]
         * 即 early-exit（长输入不断言全扫，无 2× 物化）。
         * 返回值在超限时只保证 `> MAX`（饱和，不承诺精确长度）。
         */
        fun reasonUtf8Length(reason: String): Int {
            var total = 0
            var i = 0
            val n = reason.length
            while (i < n) {
                val c = reason[i]
                val w = when {
                    c < '\u0080' -> 1
                    c < '\u0800' -> 2
                    c.isHighSurrogate() -> {
                        if (i + 1 < n && reason[i + 1].isLowSurrogate()) {
                            i++
                            4
                        } else {
                            1
                        }
                    }
                    c.isLowSurrogate() -> 1
                    else -> 3
                }
                total += w
                if (total > MAX_REASON_CODE_BYTES) return total
                i++
            }
            return total
        }

        /** reasonCode 是否超限（gate／audit／hash 前的统一门，见 P2-2）。 */
        fun isReasonCodeTooLong(reason: String): Boolean =
            reasonUtf8Length(reason) > MAX_REASON_CODE_BYTES

        /**
         * argv 向量的單射編碼（Phase D P2-3，PR #140 Round-2）：
         * 舊版 `argv.joinToString("\u0001")` 再雜湊具編碼歧義——`ShellPolicy`
         * 未禁 U+0001，故 `listOf("id","foo\u0001bar")` 與
         * `listOf("id","foo","bar")` 序列化同為 `"id\u0001foo\u0001bar"` 而同 hash
         *（非 SHA 碰撞，是分隔符歧義）。
         *
         * 新編碼（長度前綴，單射）：
         * `u32BE(argc) || (u32BE(utf8Len(arg_i)) || utf8Bytes(arg_i))*`，
         * 其中整數皆為 4-byte big-endian 無符號（`argc` 與每項 UTF-8 位元組長），
         * 位元組序固定、與平台預設編碼無關。解碼時先讀 `argc`，再依次讀
         * `(len, bytes)` 對，邊界由長度唯一確定，故相異向量必得相異位元組串，
         * 再經 SHA-256 即得相異雜湊（仍以 SHA 抗碰撞為前提，但已消除分隔符歧義）。
         * 邊界：空 argv 編為單一 `0` 計數；空字串項編為 `len=0` 零載荷；
         * 內含 `\u0000`／`\u0001` 皆為 UTF-8 載荷位元組，不具分隔意義，編碼層不抛；
         * 上游對 `\u0000` 的拒絕由 `ShellPolicy` 負責，本層只保證雜湊可辨。
         *
         * Round-3 Phase H（P3 註記，選項 a）：上式「相異向量必得相異位元組串」
         * 只對良構字串成立——未配對 UTF-16 代理（lone surrogate）經
         * `toByteArray(UTF_8)` 替換為單位元組 `[63]`（即 `?`；已在 JDK 25
         * 實證：`"\uD800".getBytes(UTF_8)==byteArrayOf(63)`，與 `"?"` 同串），
         * 故 `listOf("\uD800")` 與 `listOf("?")` 編碼同串、同雜湊，強單射對
         * 一切 JVM String 不成立。
         * 入站層已 fail-closed 拒孤立代理（見 [ShellPolicy.copyArgvBounded]，
         * 產品路徑不可達替換分支），本函數為純編碼保持原樣（不抛、不改簽名，
         * 審計欄位不變約束）；直接呼叫本函數傳孤立代理即得替換後雜湊
         * （行為保留，不承諾單射）。
         */
        fun encodeArgv(argv: List<String>): ByteArray {
            // 先逐項轉 UTF-8（避免重複編碼），總長 = 4 + Σ(4 + len_i)。
            val parts = Array(argv.size) { i -> argv[i].toByteArray(Charsets.UTF_8) }
            val total = 4 + parts.sumOf { 4 + it.size }
            val out = java.nio.ByteBuffer.allocate(total)
            out.order(java.nio.ByteOrder.BIG_ENDIAN)
            out.putInt(parts.size)
            for (p in parts) {
                out.putInt(p.size)
                out.put(p)
            }
            return out.array()
        }

        /**
         * argv 向量的雜湊：`SHA-256(encodeArgv(argv))` 的 hex。
         * 與單字串版 [sha256Hex] 不同：本函數先經 [encodeArgv] 單射編碼，
         * 故分隔符注入（U+0001）無法造成跨向量碰撞。
         */
        fun argvHash(argv: List<String>): String = sha256HexBytes(encodeArgv(argv))

        fun verdictName(result: ShellResult): String = when (result) {
            is ShellResult.Ok -> "OK"
            is ShellResult.Denied -> "DENY"
            is ShellResult.TimedOut -> "TIMEOUT"
            is ShellResult.Failed -> "FAILED"
        }
        // Phase 5 B2：本映射與二進位側 `verdict()` 手動同步（皆四碼，不合併實作；
        // 新增分支兩處須同步，一致性由 `ElevatedVerdictMappingTest` 鎖定）。

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
