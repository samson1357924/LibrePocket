package dev.librepocket.privilege.github

import dev.librepocket.redact.Redactor
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * 提權子進程 IO 共用（github 源集內部）：超時讀取 + 硬上限防 OOM。
 * 截斷標記沿用 [ShellPolicy.MAX_OUTPUT_BYTES]，由各 Runner 呼叫
 * [ShellPolicy.truncate] 後判定（與 [dev.librepocket.shell.RestrictedShell]
 * 同語義）。
 *
 * B2 修正（Issue #136 B2）：
 * - 超時/cancel 走 [ElevatedProcessKiller.killTree]（整組殺，舊碼只殺本體）；
 * - 殺後關流以解除 gobbler 的 blocking I/O（舊碼流殘留可卡住 join）；
 * - 輪詢式等待（50ms 切片）＋ [isCancelled] 鉤：協程取消（Thread 中斷或
 *   外部旗標）即殺整組並帶 partial 回，不 мок 在 `waitFor(timeout)` 內；
 * - [killer]/[isCancelled] 可注入，JVM 單測以假進程斷言「超時/cancel 必殺」。
 */
internal object ElevatedProcessIo {
    data class Drained(
        val stdout: ByteArray,
        val stderr: ByteArray,
        val exitCode: Int,
        val timedOut: Boolean,
    )

    fun drain(process: Process, timeoutMs: Long): Drained =
        drain(process, timeoutMs, { ElevatedProcessKiller.killTree(it) }, { Thread.currentThread().isInterrupted })

    fun drain(
        process: Process,
        timeoutMs: Long,
        killer: (Process) -> Unit,
        isCancelled: () -> Boolean,
    ): Drained {
        val out = CappedPipe()
        val err = CappedPipe()
        val tOut = Thread({ copyCapped(process.inputStream, out) }, "elevated-stdout").also {
            it.isDaemon = true
            it.start()
        }
        val tErr = Thread({ copyCapped(process.errorStream, err) }, "elevated-stderr").also {
            it.isDaemon = true
            it.start()
        }
        var finished = false
        try {
            // 單調時鐘：nanoTime 不受 wall-clock 回撥/校時影響，避免超時拉長或提前。
            val deadlineNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
            while (true) {
                if (runCatching { isCancelled() }.getOrDefault(false)) break
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remaining <= 0) break
                val slice = minOf(remaining, 50L)
                val done = try {
                    process.waitFor(slice, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                if (done) {
                    finished = true
                    break
                }
            }
        } catch (_: Exception) {
            // 輪詢異常一律視為未完成，走下方整組清理。
        }
        if (!finished) {
            runCatching { killer(process) }
            // 關流以解除 gobbler 阻塞（killer 內已關，此處縱深再關一次）。
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        }
        runCatching { tOut.join(5_000L) }
        runCatching { tErr.join(5_000L) }
        return Drained(
            stdout = out.bytes(),
            stderr = err.bytes(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !finished,
        )
    }

    private class CappedPipe {
        private val sink = ByteArrayOutputStream()
        fun write(buf: ByteArray, n: Int) {
            synchronized(sink) {
                val room = ShellPolicy.HARD_READ_CAP_BYTES - sink.size()
                if (room <= 0) return
                sink.write(buf, 0, minOf(n, room))
            }
        }
        fun bytes(): ByteArray = synchronized(sink) { sink.toByteArray() }
    }

    private fun copyCapped(stream: java.io.InputStream, pipe: CappedPipe) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                pipe.write(buf, n)
            }
        } catch (_: Exception) {
            // 被殺時流中斷屬正常，直接收尾。
        } finally {
            runCatching { stream.close() }
        }
    }
}

/**
 * Shizuku 提權執行器（S3，BACKLOG D09，矩陣 §3，僅 github 風味）。
 *
 * 執行順序：[ShizukuStatusGate]（B2：binder/授權/版本 typed 門，拒絕即
 * Denied 且零 spawn）→ [ShellPolicy.validateElevated] 先行 →
 * 原因碼 → 配額 → 遠端建子進程 → 超時殺（整組） → 輸出截斷。
 * 策略拒絕/配額拒絕/門拒絕一律不建遠端進程；Shizuku 不可用時回 typed
 * Denied（B2：舊碼回 Failed，現對齊 B1 門語義），spawn 異常回
 * [ShellResult.Failed]，呼叫方回落免 Root 路徑。
 *
 * - 遠端進程經 Shizuku 13.x `newProcess(argv, env, dir)`（private，
 *   反射呼叫；回傳物經 [ShizukuRemoteProcess.wrap] 包裝，永不直 cast
 *   `Process`，binder 死亡/版本差異經 runCatching 收斂為 [ShellResult.Failed]）；
 * - 白名單外二進位僅允許走本通道（[ShellPolicy.validateElevated] 語義），
 *   黑名單（`rm -rf /` 類）即使提權仍拒；
 * - 跨域路徑需 [dev.librepocket.files.FileScope.decide] 回 needsBridge
 *   且橋接已授權（[bridgeGranted]，對應 `privilege_bridge` 開關），
 *   否則拒絕 —— 私有域/SAF 直行優先，呼叫方應優先走 SAF。
 * - 二進位通道：[runBinary] 以 [ByteArray] 直達（B2；截圖 PNG 等不可經
 *   String），[run] 僅為文字相容殼；非零 exit 回 Failed（redacted），不回 Ok。
 */
class ShizukuShellRunner(
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val quota: ShellQuota = ShellQuota(),
    private val bridgeGranted: Boolean = false,
    private val flavor: Flavor = Flavor.GITHUB,
    private val pingBinder: () -> Boolean = { ShizukuProbe.ping() },
    private val spawn: ((List<String>) -> Process)? = null,
    private val status: () -> ShizukuStatus = { ShizukuStatusProbe.status(pingBinder = pingBinder) },
    private val killer: (Process) -> Unit = { ElevatedProcessKiller.killTree(it) },
    private val isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
) : ElevatedShellRunner, BinaryElevatedRunner {

    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        runBinary(request, timeoutMs).toShellResult()

    override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult {
        ShizukuStatusGate.denyFor(runCatching { status() }.getOrDefault(ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN)))
            ?.let { return ElevatedBinaryResult.Denied(it.reason, it.message) }
        when (
            val v = ShellPolicy.validateElevated(
                request.argv,
                privateRoot,
                safRoots,
                flavor,
                bridgeGranted,
            )
        ) {
            is Validation.Denied -> return ElevatedBinaryResult.Denied(v.reason, v.message)
            is Validation.Allowed -> Unit
        }
        if (request.reasonCode.isBlank()) {
            return ElevatedBinaryResult.Denied(ShellDeny.BAD_ARGUMENT, "reasonCode required (matrix §3 audit)")
        }
        if (!quota.tryAcquire()) {
            return ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, "elevated shell quota exceeded")
        }
        val process: Process = try {
            (spawn ?: ::pinnedSpawn)(request.argv)
        } catch (e: Exception) {
            val redacted = Redactor.redactError(e.message ?: e.javaClass.simpleName)
            return ElevatedBinaryResult.Failed("shizuku spawn failed: $redacted")
        }
        val raw = ElevatedProcessIo.drain(process, timeoutMs, killer, isCancelled)
        val out = ShellPolicy.truncate(raw.stdout)
        val err = ShellPolicy.truncate(raw.stderr)
        val truncated = out.truncated || err.truncated
        if (raw.timedOut) {
            return ElevatedBinaryResult.TimedOut(
                partialStdout = out.bytes,
                partialStderr = err.bytes,
                timeoutMs = timeoutMs,
                truncated = truncated,
            )
        }
        if (raw.exitCode != 0) {
            val redacted = Redactor.redactError(String(err.bytes, Charsets.UTF_8))
            return ElevatedBinaryResult.Failed("shizuku command failed exit=${raw.exitCode}: $redacted")
        }
        return ElevatedBinaryResult.Ok(
            stdout = out.bytes,
            stderr = err.bytes,
            exitCode = raw.exitCode,
            truncated = truncated,
        )
    }

    /**
     * P0 cwd 釘死：遠端建子進程時把 `dir` 釘到 privateRoot（原 `null` 會落在
     * system_server 預設 cwd，不可控）。privateRoot 為 null 時維持 null，
     * 但政策層已 fail-closed，殘餘僅非路徑子命令。
     */
    private fun pinnedSpawn(argv: List<String>): Process = defaultSpawnWithDir(argv, privateRoot)

    companion object {
        /**
         * 預設遠端建子進程：Shizuku 13.x 的 `newProcess` 為 private，
         * 此處反射呼叫（argv 直達，不經 `sh -c`，不拼字串）。
         * P0 修正：`dir` 傳 privateRoot（非 null），不再傳 null。
         * B2 修正：回傳物經 [ShizukuRemoteProcess.wrap] 包裝，永不直 cast。
         */
        @Deprecated(
            "僅測試/舊碼相容，產品碼禁用：未釘死 cwd（dir=null）。產品一律走實例 pinnedSpawn。",
        )
        val defaultSpawn: (List<String>) -> Process = { argv ->
            defaultSpawnWithDir(argv, null)
        }

        fun defaultSpawnWithDir(argv: List<String>, dir: String?): Process {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val raw = method.invoke(null, argv.toTypedArray(), null, dir)
            return ShizukuRemoteProcess.wrap(raw)
        }
    }
}
