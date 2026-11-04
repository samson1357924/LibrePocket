package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
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
 *
 * P2-A 防禦性拷貝：[Ok]/[TimedOut] 為普通類（非 data class，保留手寫
 * equals/hashCode/copy/componentN 以相容解構與拷貝），構造時對傳入
 * [ByteArray] 做 `copyOf()` 存入私有 backing，取值經 `get() = copyOf()`。
 * 呼叫方不得保留傳入引用（內部已拷貝），取用方拿到的每次皆為新拷貝，
 * 可自由竄改不影響內部。單測以 `contentEquals` 斷言，故構造拷貝安全。
 */
sealed interface ElevatedBinaryResult {
    class Ok(
        stdout: ByteArray,
        stderr: ByteArray,
        val exitCode: Int,
        val truncated: Boolean,
    ) : ElevatedBinaryResult {
        private val _stdout: ByteArray = stdout.copyOf()
        private val _stderr: ByteArray = stderr.copyOf()

        val stdout: ByteArray get() = _stdout.copyOf()
        val stderr: ByteArray get() = _stderr.copyOf()

        fun copy(
            stdout: ByteArray = this.stdout,
            stderr: ByteArray = this.stderr,
            exitCode: Int = this.exitCode,
            truncated: Boolean = this.truncated,
        ): Ok = Ok(stdout, stderr, exitCode, truncated)

        operator fun component1(): ByteArray = stdout
        operator fun component2(): ByteArray = stderr
        operator fun component3(): Int = exitCode
        operator fun component4(): Boolean = truncated

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ok) return false
            return _stdout.contentEquals(other._stdout) &&
                _stderr.contentEquals(other._stderr) &&
                exitCode == other.exitCode &&
                truncated == other.truncated
        }

        override fun hashCode(): Int {
            var r = _stdout.contentHashCode()
            r = 31 * r + _stderr.contentHashCode()
            r = 31 * r + exitCode
            r = 31 * r + truncated.hashCode()
            return r
        }

        override fun toString(): String =
            "Ok(stdout=$_stdout, stderr=$_stderr, exitCode=$exitCode, truncated=$truncated)"
    }

    data class Denied(
        val reason: ShellDeny,
        val message: String,
    ) : ElevatedBinaryResult

    class TimedOut(
        partialStdout: ByteArray,
        partialStderr: ByteArray,
        val timeoutMs: Long,
        val truncated: Boolean,
    ) : ElevatedBinaryResult {
        private val _partialStdout: ByteArray = partialStdout.copyOf()
        private val _partialStderr: ByteArray = partialStderr.copyOf()

        val partialStdout: ByteArray get() = _partialStdout.copyOf()
        val partialStderr: ByteArray get() = _partialStderr.copyOf()

        fun copy(
            partialStdout: ByteArray = this.partialStdout,
            partialStderr: ByteArray = this.partialStderr,
            timeoutMs: Long = this.timeoutMs,
            truncated: Boolean = this.truncated,
        ): TimedOut = TimedOut(partialStdout, partialStderr, timeoutMs, truncated)

        operator fun component1(): ByteArray = partialStdout
        operator fun component2(): ByteArray = partialStderr
        operator fun component3(): Long = timeoutMs
        operator fun component4(): Boolean = truncated

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TimedOut) return false
            return _partialStdout.contentEquals(other._partialStdout) &&
                _partialStderr.contentEquals(other._partialStderr) &&
                timeoutMs == other.timeoutMs &&
                truncated == other.truncated
        }

        override fun hashCode(): Int {
            var r = _partialStdout.contentHashCode()
            r = 31 * r + _partialStderr.contentHashCode()
            r = 31 * r + timeoutMs.hashCode()
            r = 31 * r + truncated.hashCode()
            return r
        }

        override fun toString(): String =
            "TimedOut(partialStdout=$_partialStdout, partialStderr=$_partialStderr, " +
                "timeoutMs=$timeoutMs, truncated=$truncated)"
    }

    data class Failed(val message: String) : ElevatedBinaryResult
}

/**
 * 二進位提權通道：位元組直達，不經 String。
 *
 * P2-A：本介面為 [ElevatedShellRunner] 的子介面（`run` 為文字相容殼，
 * [runBinary] 為主通道）。各 Runner 直接 `: BinaryElevatedRunner` 即可，
 * 不必重複列出父介面。僅實作 [ElevatedShellRunner] 的裝飾器仍合法，
 * 經 [RootFramebufferCapture] 回落 String 路徑（見其 KDoc）。
 *
 * P2-A 可見性：保持 public（public Runner 的 supertype，
 * 收斂為 internal 會暴露內部超型別，故不收斂）。
 */
interface BinaryElevatedRunner : ElevatedShellRunner {
    fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult
}

/**
 * 二進位 verdict（審計只記此碼，不記明文）。
 *
 * 與 [dev.librepocket.shell.PrivilegeAuditLog.verdictName] 對齊：
 * 兩者皆為 `OK / DENY / TIMEOUT / FAILED` 四碼，僅輸入型別不同
 * （本函數吃 [ElevatedBinaryResult]，對方吃 [ShellResult]）。
 * 新增分支時兩處須同步；此處刻意不經 [toShellResult] 轉一道
 * （lossy 解碼只為 verdict 不值得），故以獨立 `when` 保持映射。
 */
fun ElevatedBinaryResult.verdict(): String = when (this) {
    is ElevatedBinaryResult.Ok -> "OK"
    is ElevatedBinaryResult.Denied -> "DENY"
    is ElevatedBinaryResult.TimedOut -> "TIMEOUT"
    is ElevatedBinaryResult.Failed -> "FAILED"
}

/**
 * 文字相容轉換。
 *
 * **強警告（lossy，不可逆）**：本轉換以 UTF-8 解碼位元組，非法序列以
 * Replacement 字元收斂，原位元組即丟失。僅文字命令（`id`/`getprop` 等
 * 已知純文字輸出）的呼叫方可用；二進位（截圖 PNG、`cat fb0` 等任意位元組）
 * 必須直接用 [ElevatedBinaryResult]（[runBinary] 直達），禁止經此函數中轉
 * 再 `toByteArray(UTF_8)`（見 [RootFramebufferCapture] B2 修正）。
 * 本函數不加 `Deprecated`：文字相容殼仍為 [ElevatedShellRunner.run]
 * 的實現手段，保留合法呼叫。
 */
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
