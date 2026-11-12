package dev.librepocket.shell

/**
 * 受限 shell 執行結果（BACKLOG D05）。
 *
 * - [Ok]：子進程正常結束；[truncated] 表示輸出被 [ShellPolicy.MAX_OUTPUT_BYTES] 截斷。
 * - [Denied]：未建子進程即拒絕（白名單/黑名單/配額/參數）。
 * - [TimedOut]：deadline exceeded＋部分輸出（同樣可能被截斷），清理狀態 UNKNOWN——
 *   background worker 可能仍持有活子進程／gobbler（飽和零 thread、linger 殘留、
 *   entry 期滿三態坍縮前由 [cleanupIncomplete] 區分；絕非「已殺」保證，見該欄 KDoc）。
 * - [Failed]：子進程無法啟動（IO/安全異常），非策略拒絕。
 */
sealed interface ShellResult {
    data class Ok(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val truncated: Boolean,
    ) : ShellResult

    data class Denied(
        val reason: ShellDeny,
        val message: String,
    ) : ShellResult

    /**
     * 超時（P2-3 Stage 4 誠實化，#136-B3 前置）：
     * `deadline exceeded ＋ partial`，清理狀態 UNKNOWN——background worker 可能仍
     * 持有活子進程／gobbler（kill 可能在 worker 內尚未執行、已嘗試但未驗證收斂、
     * 或從未啟動）；絕非「已殺」保證。
     *
     * - [cleanupIncomplete]：殘留信號（預設 false，舊 4 參構造仍可編譯，
     *   source-compatible；data class 的 `equals/hashCode/componentN/copy/toString`
     *   由編譯器隨新欄自動更新——與提權側 `ByteArray` 承載不同，本型別全為
     *   `String`／ primitives，data class 語義即正確，無需手寫）：
     *   true＝飽和零 thread（全域 backlog 存在）／process 段 worker 在 grace 後仍
     *   存活（linger daemon）／tail 未排空（would-be Ok 升級）；false＝entry 期滿
     *   零 worker／spawn 前預檢期滿（未 spawn，無需清理）／runner 內 kill 已執行
     *   且已收斂（殘留只剩有界 gobbler tail，見 `outputIncomplete` 升級路徑）。
     * - 審計：[dev.librepocket.shell.PrivilegeAuditLog.verdictName] 仍映 `TIMEOUT`
     *  （verdict 不變；linger 細節不在審計表，見該表 B3 TODO）。
     */
    data class TimedOut(
        val partialStdout: String,
        val partialStderr: String,
        val timeoutMs: Long,
        val truncated: Boolean,
        val cleanupIncomplete: Boolean = false,
    ) : ShellResult

    data class Failed(
        val message: String,
    ) : ShellResult
}
