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
 * - 超時/cancel 走 [ElevatedProcessKiller.killTree]（盡力整組，舊碼只殺本體）；
 * - 殺後關流以解除 gobbler 的 blocking I/O（舊碼流殘留可卡住 join）；
 * - 輪詢式等待（50ms 切片）＋ [isCancelled] 鉤：協程取消（Thread 中斷或
 *   外部旗標）即盡力殺整組並帶 partial 回，不 мок 在 `waitFor(timeout)` 內；
 * - [killer]/[isCancelled] 可注入，JVM 單測以假進程斷言「超時/cancel 必殺」。
 *
 * P1-B review（成功路徑有界等待，超界即 truncated）：
 * - 成功路徑亦有界等待（上界 `SUCCESS_JOIN_MS` 10s，見 [drain]）：
 *   `finished==true` 後若 gobbler 流永不 EOF（遠端流未關），有界 join 超界即停
 *   並經 [Drained.truncated] 標截斷（絕不靜默短讀不標）；
 * - 任何路徑 drain 總耗時有上界（timeoutMs + 常數），不無限卡。
 */
internal object ElevatedProcessIo {
    /** P1-B magic number 抽常數（行為不變）：輪詢切片 / gobbler 有界 join / 成功路徑放寬 join / 殺後等待 / 複製緩衝。 */
    private const val POLL_SLICE_MS = 50L
    private const val GOBBLER_JOIN_MS = 5_000L
    private const val SUCCESS_JOIN_MS = 10_000L
    private const val KILL_WAIT_SEC = 5L
    private const val COPY_BUF_SIZE = 8192

    data class Drained(
        val stdout: ByteArray,
        val stderr: ByteArray,
        val exitCode: Int,
        val timedOut: Boolean,
        /** gobbler 有界 join 未完成（部分讀）：呼叫方須併入 truncated，絕不靜默短讀不標。 */
        val truncated: Boolean = false,
    )

    fun drain(process: Process, timeoutMs: Long): Drained =
        drain(process, timeoutMs, { ElevatedProcessKiller.killTree(it) }, { Thread.currentThread().isInterrupted })

    fun drain(
        process: Process,
        timeoutMs: Long,
        killer: (Process) -> Unit,
        isCancelled: () -> Boolean,
    ): Drained {
        // P1-B：spawn 成功後立即關子進程 stdin（子進程 stdin EOF）：等 stdin EOF 的
        // 子進程（如 cat）不再假性 TimedOut。killer 內已關流，此處縱深再關一次。
        // 約束：本 drain 為無 stdin 模式（起手即關），未來需寫 stdin 必須另開
        // API，不得復用本函數（復用會與起手關流競態，並改變 EOF 語義）。
        runCatching { process.outputStream.close() }
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
                val slice = minOf(remaining, POLL_SLICE_MS)
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
            // 輪詢異常收斂為未完成，走下方清理（fail-closed：timedOut，不偽造成功）。
        }
        // gobbler 有界 join 未完成標記：任一 join 超界/中斷後執行緒仍存活即部分讀，
        // 經 Drained.truncated 帶出（絕不靜默短讀不標）。
        var joinIncomplete = false
        if (!finished) {
            runCatching { killer(process) }
            // 關流以解除 gobbler 阻塞（killer 內已關，此處縱深再關一次）。
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            // 殺後等待：中斷必須 restore（不吞 InterruptedException）；
            // 非中斷異常收斂（清理路徑不升級為崩潰，仍回 TimedOut）。
            try {
                process.waitFor(KILL_WAIT_SEC, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // 清理異常收斂，見上。
            }
            // 超時/cancel 路徑保留有界 join：gobbler 仍可能卡住，避免無限等。
            // join 中斷必須 restore 中斷旗標（不吞 InterruptedException）。
            try {
                tOut.join(GOBBLER_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (tOut.isAlive) joinIncomplete = true
            }
            try {
                tErr.join(GOBBLER_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (tErr.isAlive) joinIncomplete = true
            }
        } else {
            // 成功路徑亦有界等待（P1-B review）：上界 SUCCESS_JOIN_MS（10s），
            // 超界即停並標 truncated（絕不靜默短讀不標）；drain 總耗時有上界
            //（timeoutMs + 常數），不無限卡。
            // 無界 join 在遠端流永不 EOF 時可卡死超 timeoutMs，故不用。
            try {
                tOut.join(SUCCESS_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (tOut.isAlive) joinIncomplete = true
            }
            try {
                tErr.join(SUCCESS_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (tErr.isAlive) joinIncomplete = true
            }
        }
        return Drained(
            stdout = out.bytes(),
            stderr = err.bytes(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !finished,
            truncated = joinIncomplete,
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
        /** 是否已達硬上限（P1-B review：僅觀測/斷言用；cap 強制由 write 內丟棄承擔）。 */
        fun isFull(): Boolean = synchronized(sink) { sink.size() >= ShellPolicy.HARD_READ_CAP_BYTES }
        fun bytes(): ByteArray = synchronized(sink) { sink.toByteArray() }
    }

    private fun copyCapped(stream: java.io.InputStream, pipe: CappedPipe) {
        val buf = ByteArray(COPY_BUF_SIZE)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                pipe.write(buf, n)
                if (pipe.isFull()) {
                    // P1-B review：超標後仍繼續讀丟棄，絕不關流（省 CPU 的優化撤回，
                    // 正確性優先）。提前關讀端會致寫端 EPIPE/SIGPIPE，使本可
                    // exit 0 + truncated 的成功改寫為 141 轉 Failed。
                    // OOM 防護由 write() 內 HARD_READ_CAP 丟棄超額承擔，語義不變。
                    // （此分支無動作，僅留檢查點明語義；讀丟棄經由下輪 read 繼續。）
                }
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
 * 執行順序：[ShellPolicy.validateElevated] 先行 → 原因碼 →
 * [ShizukuStatusGate]（B2：binder/授權/版本 typed 門，拒絕即 Denied 且零 spawn；
 * P1-B：validate 先於 binder IPC，避免黑名單 argv 先打 3 次 binder）→ 配額
 * （P1-B：quota 置於門後，與 RootSuRunner 側一致，門拒絕不耗配額）→ 遠端建子進程
 * → 超時殺（盡力整組，見 [ElevatedProcessKiller]） → 輸出截斷。
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
        // P1-B 門順序：validate → reasonCode → status 門 → quota → spawn。
        // validate 先於 binder IPC（status 內 ping 最多 3 次 binder），黑名單 argv
        // 零 IPC 即拒；quota 置於門後（與 RootSuRunner 側一致），門拒絕不耗配額。
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
        ShizukuStatusGate.denyFor(runCatching { status() }.getOrDefault(ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN)))
            ?.let { return ElevatedBinaryResult.Denied(it.reason, it.message) }
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
        val truncated = out.truncated || err.truncated || raw.truncated
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
