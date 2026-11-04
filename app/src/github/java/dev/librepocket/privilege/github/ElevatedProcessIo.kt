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
 * - 輪詢式等待（50ms 切片）＋ [isCancelled] 鉤：外部取消（Thread 中斷或
 *   外部旗標）即盡力殺整組並帶 partial 回，不卡在 `waitFor(timeout)` 內；
 * - [killer]/[isCancelled] 可注入，JVM 單測以假進程斷言「超時/cancel 必殺」。
 *
 * R3-A 取消限制（owner P2-5）：預設 [isCancelled] 只看 Thread 中斷
 * （`Thread.currentThread().isInterrupted`），coroutine `Job` 取消不保證中斷
 * blocking `waitFor`；coroutine 呼叫方應顯式傳
 * `isCancelled = { !job.isActive }`（或 `!coroutineContext.isActive`），
 * 否則取消可能要等到 timeout 才收斂。抛異常的鉤一律折為未取消
 * （fail-closed：不斷流、不誤殺，見 drain 內 `getOrDefault(false)`）。
 *
 * P1-B review（成功路徑有界等待，超界即 truncated）：
 * - 成功路徑亦有界等待（上界 `SUCCESS_JOIN_MS` 10s，見 [drain]）：
 *   `finished==true` 後若 gobbler 流永不 EOF（遠端流未關），有界 join 超界即停
 *   並經 [Drained.truncated] 標截斷（絕不靜默短讀不標）；
 * - 任何路徑 drain 總耗時有上界（timeoutMs + 常數），不無限卡。
 *
 * P2-B 抽檔（行為零變更）：原內嵌於 `ShizukuShellRunner.kt`，搬至本檔；
 * [ShizukuShellRunner]/[RootSuRunner] 同包直接引用，免 import。
 *
 * R1-A 雙軌容量（Issue #136 R1-A，owner P1-2）：
 * - 通用命令：截斷標記沿用 [ShellPolicy.MAX_OUTPUT_BYTES]（64K），硬讀取上限
 *   沿用 [ShellPolicy.HARD_READ_CAP_BYTES]（512K），OOM 有界（每流 512K）；
 * - 截圖二進位（`screencap -p` / `cat /dev/graphics/fb0`）：截斷上限
 *   [BINARY_SCREENSHOT_MAX_BYTES]（8M），硬讀取上限
 *   [BINARY_SCREENSHOT_HARD_CAP_BYTES]（8M），OOM 有界（每流 8M）；
 * - 雙軌由各 Runner 以 argv 精確匹配判定（見兩 Runner 的 `isScreenshot`），
 *   本檔只提供可選參數 `hardCapBytes`（預設 512K，截圖傳 8M）與截斷常數，
 *   不做 argv 判定；[Drained] 結構不變（仍為 stdout/stderr/exitCode/
 *   timedOut/truncated，`truncated` 另含硬上限溢出標記，見 [CappedPipe]）。
 *
 * R3-B（PocketGuard SUGGESTION，ExecutorService）：維持現狀 —— 固定 2 daemon
 * threads＋有界 join 已防洩漏/hang，ExecutorService 為未來選項（B3）。
 *
 * S2-B 容量矩陣（owner P2-4，僅 KDoc 文件化，行為零變更）：
 * - 典型 PNG 截圖 1-3MB：完整通過（<8M，Ok 非 truncated，capture 可摘要）；
 * - 600KB fb0：完整通過（見容量測試 600KB 雙 runner 通過）；
 * - 未壓縮 RGBA 1080x2400x4 ≈10.37MB：明確不支援（>8M 即 truncated→null，
 *   呼叫方降級手動截圖；截斷幀摘要會虛構完整截圖，故不摘要部分幀）；
 * - 高熵 PNG 超限同理：>8M 即 truncated→null，不分內容熵；
 * - 裝置容量驗收歸 B3 E2E（見 [PrivilegeActivation]：`ELEVATED_RUNNERS_READY`
 *   翻 true 前需裝置驗收；JVM 單測僅以假位元組斷言容量邊界，不代表真機幀大小）。
 */
internal object ElevatedProcessIo {
    /** R1-A 截圖二進位容量：`screencap -p` / `cat /dev/graphics/fb0` 的截斷上限（8M）。 */
    const val BINARY_SCREENSHOT_MAX_BYTES: Int = 8 * 1024 * 1024

    /** R1-A 截圖二進位硬讀取上限（8M，每流 OOM 上界；溢出經 [Drained.truncated] 帶出）。 */
    const val BINARY_SCREENSHOT_HARD_CAP_BYTES: Int = 8 * 1024 * 1024

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
        /**
         * gobbler 有界 join 未完成（部分讀）：呼叫方須併入 truncated，絕不靜默短讀不標。
         * R1-A：另含硬上限溢出（任一流丟棄即 true，見 [CappedPipe.didOverflow]），
         * 截圖 8M==8M 時靠此位區分恰上限通過與超限丟棄。
         */
        val truncated: Boolean = false,
    )

    fun drain(
        process: Process,
        timeoutMs: Long,
        hardCapBytes: Int = ShellPolicy.HARD_READ_CAP_BYTES,
    ): Drained =
        drain(
            process,
            timeoutMs,
            { ElevatedProcessKiller.killTree(it) },
            { Thread.currentThread().isInterrupted },
            hardCapBytes,
        )

    /**
     * R1-A 雙軌：`hardCapBytes` 預設 [ShellPolicy.HARD_READ_CAP_BYTES]（512K，通用命令）；
     * 截圖二進位傳 [BINARY_SCREENSHOT_HARD_CAP_BYTES]（8M）。[Drained] 結構不變，
     * `truncated` 含 join 未完成或硬上限溢出（任一流丟棄即 true，絕不靜默短讀不標）。
     *
     * R3-A 取消限制（owner P2-5）：[isCancelled] 預設只看 Thread 中斷，
     * coroutine 呼叫方應傳 `isCancelled = { !job.isActive }`
     * （或 `!coroutineContext.isActive`）；鉤抛異常折為未取消（fail-closed）。
     */
    fun drain(
        process: Process,
        timeoutMs: Long,
        killer: (Process) -> Unit,
        isCancelled: () -> Boolean,
        hardCapBytes: Int = ShellPolicy.HARD_READ_CAP_BYTES,
    ): Drained {
        // P1-B：spawn 成功後立即關子進程 stdin（子進程 stdin EOF）：等 stdin EOF 的
        // 子進程（如 cat）不再假性 TimedOut。killer 內已關流，此處縱深再關一次。
        // 約束：本 drain 為無 stdin 模式（起手即關），未來需寫 stdin 必須另開
        // API，不得復用本函數（復用會與起手關流競態，並改變 EOF 語義）。
        runCatching { process.outputStream.close() }
        val out = CappedPipe(hardCapBytes)
        val err = CappedPipe(hardCapBytes)
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
            // R1-A：join 未完成或任一流硬上限溢出（>hardCapBytes 丟棄）即 truncated；
            // 通用 512K 溢出本就經 64K truncate 標記，此處 OR 不改其 verdict；
            // 截圖 8M==8M 時需此位區分「恰 8M 通過」與「>8M 丟棄」（後者 truncate(8M,8M)
            // 無法檢出，故必須由溢出位帶出，否則 >8M 會誤判為完整幀）。
            truncated = joinIncomplete || out.didOverflow() || err.didOverflow(),
        )
    }

    private class CappedPipe(private val hardCapBytes: Int) {
        private val sink = ByteArrayOutputStream()
        @Volatile private var overflowed = false
        fun write(buf: ByteArray, n: Int) {
            // S3 競爭收斂（PocketGuard SUGGESTION，語義不變）：鎖外先讀 size
            // 快照做快速判斷（room<=0 即標溢出直接返回；n>room 預標溢出），
            // 鎖內只做最終判定＋寫入。快照僅啟發式（stale 亦無害：溢出位單調，
            // 早標不錯；最終是否寫／寫多少一律以鎖內 room 為準，故絕不溢寫）。
            val roomSnap = hardCapBytes - runCatching { sink.size() }.getOrDefault(0)
            if (roomSnap <= 0) {
                overflowed = true
                return
            }
            if (n > roomSnap) overflowed = true
            synchronized(sink) {
                val room = hardCapBytes - sink.size()
                if (room <= 0) {
                    overflowed = true
                    return
                }
                if (n > room) overflowed = true
                sink.write(buf, 0, minOf(n, room))
            }
        }
        /** 是否已達硬上限（P1-B review：僅觀測/斷言用；cap 強制由 write 內丟棄承擔）。 */
        fun isFull(): Boolean = synchronized(sink) { sink.size() >= hardCapBytes }
        /** 硬上限溢出（丟棄過任一位元組）：drain 經 [Drained.truncated] 帶出，不靜默短讀。 */
        fun didOverflow(): Boolean = overflowed
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
                    // OOM 防護由 write() 內 hardCapBytes 丟棄超額承擔，語義不變
                    //（通用 512K／截圖 8M，見 drain 的 hardCapBytes）。
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
