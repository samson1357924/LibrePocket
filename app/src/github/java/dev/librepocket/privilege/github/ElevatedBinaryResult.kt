package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellResult

/**
 * 提權二進位結果（B2，Issue #136 B2，僅 github 風味）。
 *
 * - [ShellResult.Ok] 的 stdout/stderr 為 [String]，二進位經 UTF-8 往返即損毀
 *  （截圖 PNG 等非文字位元組不可走 String；見 [RootFramebufferCapture]）；
 *   本型別以 [ByteArray] 承載 stdout/stderr，截斷語義同
 *   [dev.librepocket.shell.ShellPolicy.truncate]；
 * - Denied/Failed 只攜 redacted 訊息（見各 Runner 的
 *   `Redactor.redactError` 收斂），絕不攜原始位元組；
 * - [runBinary] 為主通道，[dev.librepocket.shell.ElevatedShellRunner.run]
 *   僅為文字相容殼（lossy 解碼），exit 語義單一來源。
 */
sealed interface ElevatedBinaryResult {
    data class Ok(
        val stdout: ByteArray,
        val stderr: ByteArray,
        val exitCode: Int,
        val truncated: Boolean,
    ) : ElevatedBinaryResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ok) return false
            return stdout.contentEquals(other.stdout) &&
                stderr.contentEquals(other.stderr) &&
                exitCode == other.exitCode &&
                truncated == other.truncated
        }

        override fun hashCode(): Int {
            var r = stdout.contentHashCode()
            r = 31 * r + stderr.contentHashCode()
            r = 31 * r + exitCode
            r = 31 * r + truncated.hashCode()
            return r
        }
    }

    data class Denied(
        val reason: ShellDeny,
        val message: String,
    ) : ElevatedBinaryResult

    data class TimedOut(
        val partialStdout: ByteArray,
        val partialStderr: ByteArray,
        val timeoutMs: Long,
        val truncated: Boolean,
    ) : ElevatedBinaryResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TimedOut) return false
            return partialStdout.contentEquals(other.partialStdout) &&
                partialStderr.contentEquals(other.partialStderr) &&
                timeoutMs == other.timeoutMs &&
                truncated == other.truncated
        }

        override fun hashCode(): Int {
            var r = partialStdout.contentHashCode()
            r = 31 * r + partialStderr.contentHashCode()
            r = 31 * r + timeoutMs.hashCode()
            r = 31 * r + truncated.hashCode()
            return r
        }
    }

    data class Failed(val message: String) : ElevatedBinaryResult
}

/** 二進位提權通道：位元組直達，不經 String。 */
interface BinaryElevatedRunner {
    fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult
}

/** 二進位 verdict（審計只記此碼，不記明文；與 [dev.librepocket.shell.PrivilegeAuditLog.verdictName] 對齊）。 */
fun ElevatedBinaryResult.verdict(): String = when (this) {
    is ElevatedBinaryResult.Ok -> "OK"
    is ElevatedBinaryResult.Denied -> "DENY"
    is ElevatedBinaryResult.TimedOut -> "TIMEOUT"
    is ElevatedBinaryResult.Failed -> "FAILED"
}

/** 文字相容轉換：僅文字命令呼叫方使用，二進位請直接用 [ElevatedBinaryResult]。 */
fun ElevatedBinaryResult.toShellResult(): ShellResult = when (this) {
    is ElevatedBinaryResult.Ok ->
        ShellResult.Ok(
            stdout = String(stdout, Charsets.UTF_8),
            stderr = String(stderr, Charsets.UTF_8),
            exitCode = exitCode,
            truncated = truncated,
        )
    is ElevatedBinaryResult.Denied -> ShellResult.Denied(reason, message)
    is ElevatedBinaryResult.TimedOut ->
        ShellResult.TimedOut(
            partialStdout = String(partialStdout, Charsets.UTF_8),
            partialStderr = String(partialStderr, Charsets.UTF_8),
            timeoutMs = timeoutMs,
            truncated = truncated,
        )
    is ElevatedBinaryResult.Failed -> ShellResult.Failed(message)
}
