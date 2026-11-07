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
 *
 * AA1 清理旗標穿透（owner 新 P1＋P2；僅監測，不改 verdict）：[Ok.cleanupIncomplete]／
 * [TimedOut.cleanupIncomplete]／[Denied.cleanupIncomplete]／[Failed.cleanupIncomplete]
 * 由 drain 經 [ElevatedProcessIo.Drained.cleanupIncomplete]
 * 穿透（預設 false，舊 4 參／2 參構造仍可編譯；copy/getter/equals/hashCode/componentN
 * 一併更新，手寫風格與 P2-A 一致）。auditAndExtract 不變（truncated 語義不混入）。
 * P2 防禦拷貝說明：[Ok]/[TimedOut] 構造拷貝＋取值拷貝各一次；[copy] 預設參數
 * 直接取內部 backing 引用（不經 getter），僅構造時拷貝一次，避免雙重拷貝
 * （getter 仍每次拷貝，呼叫方拿到的皆為新拷貝，可自由竄改）。
 */
sealed interface ElevatedBinaryResult {
    class Ok(
        stdout: ByteArray,
        stderr: ByteArray,
        val exitCode: Int,
        val truncated: Boolean,
        val cleanupIncomplete: Boolean = false,
    ) : ElevatedBinaryResult {
        private val _stdout: ByteArray = stdout.copyOf()
        private val _stderr: ByteArray = stderr.copyOf()

        val stdout: ByteArray get() = _stdout.copyOf()
        val stderr: ByteArray get() = _stderr.copyOf()

        fun copy(
            stdout: ByteArray = this._stdout,
            stderr: ByteArray = this._stderr,
            exitCode: Int = this.exitCode,
            truncated: Boolean = this.truncated,
            cleanupIncomplete: Boolean = this.cleanupIncomplete,
        ): Ok = Ok(stdout, stderr, exitCode, truncated, cleanupIncomplete)

        operator fun component1(): ByteArray = stdout
        operator fun component2(): ByteArray = stderr
        operator fun component3(): Int = exitCode
        operator fun component4(): Boolean = truncated
        operator fun component5(): Boolean = cleanupIncomplete

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ok) return false
            return _stdout.contentEquals(other._stdout) &&
                _stderr.contentEquals(other._stderr) &&
                exitCode == other.exitCode &&
                truncated == other.truncated &&
                cleanupIncomplete == other.cleanupIncomplete
        }

        override fun hashCode(): Int {
            var r = _stdout.contentHashCode()
            r = 31 * r + _stderr.contentHashCode()
            r = 31 * r + exitCode
            r = 31 * r + truncated.hashCode()
            r = 31 * r + cleanupIncomplete.hashCode()
            return r
        }

        override fun toString(): String =
            "Ok(stdout.size=${_stdout.size}, stderr.size=${_stderr.size}, exitCode=$exitCode, truncated=$truncated, " +
                "cleanupIncomplete=$cleanupIncomplete)"
    }

    data class Denied(
        val reason: ShellDeny,
        val message: String,
        val cleanupIncomplete: Boolean = false,
    ) : ElevatedBinaryResult

    class TimedOut(
        partialStdout: ByteArray,
        partialStderr: ByteArray,
        val timeoutMs: Long,
        val truncated: Boolean,
        val cleanupIncomplete: Boolean = false,
    ) : ElevatedBinaryResult {
        private val _partialStdout: ByteArray = partialStdout.copyOf()
        private val _partialStderr: ByteArray = partialStderr.copyOf()

        val partialStdout: ByteArray get() = _partialStdout.copyOf()
        val partialStderr: ByteArray get() = _partialStderr.copyOf()

        fun copy(
            partialStdout: ByteArray = this._partialStdout,
            partialStderr: ByteArray = this._partialStderr,
            timeoutMs: Long = this.timeoutMs,
            truncated: Boolean = this.truncated,
            cleanupIncomplete: Boolean = this.cleanupIncomplete,
        ): TimedOut = TimedOut(partialStdout, partialStderr, timeoutMs, truncated, cleanupIncomplete)

        operator fun component1(): ByteArray = partialStdout
        operator fun component2(): ByteArray = partialStderr
        operator fun component3(): Long = timeoutMs
        operator fun component4(): Boolean = truncated
        operator fun component5(): Boolean = cleanupIncomplete

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TimedOut) return false
            return _partialStdout.contentEquals(other._partialStdout) &&
                _partialStderr.contentEquals(other._partialStderr) &&
                timeoutMs == other.timeoutMs &&
                truncated == other.truncated &&
                cleanupIncomplete == other.cleanupIncomplete
        }

        override fun hashCode(): Int {
            var r = _partialStdout.contentHashCode()
            r = 31 * r + _partialStderr.contentHashCode()
            r = 31 * r + timeoutMs.hashCode()
            r = 31 * r + truncated.hashCode()
            r = 31 * r + cleanupIncomplete.hashCode()
            return r
        }

        override fun toString(): String =
            "TimedOut(partialStdout.size=${_partialStdout.size}, partialStderr.size=${_partialStderr.size}, " +
                "timeoutMs=$timeoutMs, truncated=$truncated, cleanupIncomplete=$cleanupIncomplete)"
    }

    data class Failed(val message: String, val cleanupIncomplete: Boolean = false) : ElevatedBinaryResult
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
 *
 * 清理旗標監測契約：[run] 為文字相容殼，經 [toShellResult] 丟棄
 * cleanupIncomplete（[ShellResult] 無此欄位）；監測須用 runBinary
 * （二進位 [ElevatedBinaryResult] 為準，攜 Ok/TimedOut/Denied/Failed 全分支旗標）。
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
 * 雙映射一致性由 `ElevatedVerdictMappingTest` 鎖定（遍歷全變體斷言
 * `verdict() == verdictName(toShellResult())`），不合併實作。
 *
 * Phase 5 verdict 坍縮表（B5，僅 KDoc，#136-B3 排障用；不新增 verdict 常數，
 * `OK_TRUNCATED` 不引入，維持 Phase 4 決定 —— truncated-Ok 仍記 `OK`，
 * 只是不回位元組、不摘要部分幀，見 `RootFramebufferCapture`）：
 * - `QUOTA_EXCEEDED` 四因同碼（僅訊息區分，皆零 spawn、不耗配額）：
 *   配額耗盡（`ElevatedRunnerCommon.quotaExceeded`）／清理 backlog 飽和
 *   （`cleanupBacklogFull`）／連續不清創熔斷（`cleanupBreakerOpen`）／
 *   併發上限（`concurrencyFull`）。審計 `DENY` 下四因不可區分，
 *   排障須看拒絕訊息（`quota exceeded`／`backlog full`／`breaker open`／
 *   `concurrency full`），不得僅看 verdict；
 * - 取消／超時同 `TimedOut`：spawn 前取消（`isPreSpawnCancelled`）、deadline
 *   耗盡（`preSpawnExpired`）、drain 超時／取消（kill 路徑）一律
 *   `TimedOut`（空 partial、`cleanupIncomplete=false` 若未建進程；
 *   spawn 逾期／kill 殘留則 `cleanupIncomplete=true`）。取消與超時在
 *   verdict 層不可區分，排障須看 `cleanupIncomplete`＋上下文；
 * - `Res.Err` 不對稱（刻意）：su 探測抛（`suExists`／`suBinary` 阻塞體 Err）
 *   → `Failed("su unavailable...")`（可用性語義，回落免 Root 路徑）；
 *   grant／activation／bridge／status 阻塞體 Err → `Denied(NOT_WHITELISTED)`
 *  （政策門 fail-closed）。Shizuku `status()` Err 另折
 *   `BinderUnreachable` 再經 `ShizukuStatusGate.denyFor` 映射為 `Denied`
 *  （不斷言 `Failed`）。不對稱即契約，#136-B3 不得「統一」為同碼。
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
 *
 * AA1 誠實聲明：legacy String 路徑不攜清理旗標（[ShellResult] 無此欄位，
 * 此處不映射 cleanupIncomplete；二進位 [ElevatedBinaryResult] 為準，
 * 監測須用 runBinary）。
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
