package dev.librepocket.privilege.github

import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

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
 *
 * P2-B 抽檔（行為零變更）：原內嵌於 `ShizukuShellRunner.kt`，搬至本檔；
 * [ShizukuShellRunner]/[RootSuRunner] 同包直接引用，免 import。
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
 * 兩 runner 共用拒絕字串/ verdict（P2-B 收斂，僅 github 風味）。
 *
 * - `validate→reasonCode→quota→spawn→truncate→timeout→exit` 順序下，
 *   `reasonCode` 缺失與配額耗盡兩處拒絕字串原在 [ShizukuShellRunner] /
 *   [RootSuRunner] 各寫一次（改一漏一風險），收斂至此；
 * - verdict 映射單一來源：缺原因碼一律 `BAD_ARGUMENT`，配額耗盡一律
 *   `QUOTA_EXCEEDED`（與 [dev.librepocket.shell.ShellDeny] 對齊，不另發明碼）。
 */
internal object ElevatedRunnerCommon {
    /** 原因碼缺失拒絕訊息（矩陣 §3 審計要求原因碼非空）。 */
    const val REASON_CODE_REQUIRED_MESSAGE = "reasonCode required (matrix §3 audit)"

    /** 配額耗盡拒絕訊息。 */
    const val QUOTA_EXCEEDED_MESSAGE = "elevated shell quota exceeded"

    /** 原因碼為空（blank）時的 typed 拒絕：`BAD_ARGUMENT`，不建子進程。 */
    fun missingReasonCode(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.BAD_ARGUMENT, REASON_CODE_REQUIRED_MESSAGE)

    /** 配額耗盡時的 typed 拒絕：`QUOTA_EXCEEDED`，不建子進程。 */
    fun quotaExceeded(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, QUOTA_EXCEEDED_MESSAGE)
}
