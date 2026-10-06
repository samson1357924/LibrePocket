package dev.librepocket.shell

/**
 * 受限 shell 執行結果（BACKLOG D05）。
 *
 * - [Ok]：子進程正常結束；[truncated] 表示輸出被 [ShellPolicy.MAX_OUTPUT_BYTES] 截斷。
 * - [Denied]：未建子進程即拒絕（白名單/黑名單/配額/參數）。
 * - [TimedOut]：超時已殺，攜帶殺前已採集到的部分輸出（同樣可能被截斷）。
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

    data class TimedOut(
        val partialStdout: String,
        val partialStderr: String,
        val timeoutMs: Long,
        val truncated: Boolean,
    ) : ShellResult

    data class Failed(
        val message: String,
    ) : ShellResult
}
