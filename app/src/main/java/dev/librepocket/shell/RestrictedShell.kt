package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 子進程跑道抽象：預設走真實 [ProcessBuilder]，單測可注入假實現，
 * 讓「輸出截斷」等斷言不依賴宿主機二進位。
 *
 * @param env 子進程環境：真實 runner 一律 `clear()` 後全量替換，不繼承宿主 env
 * （阻斷 `LD_PRELOAD` / `PROOT_*` / 代理污染）；null 即最小乾淨環境
 * （[ShellExecutables.CLEAN_ENV]，固定 `PATH`；僅直接宿主通道的預設值，
 * PRoot guest 通道必須顯式傳 [dev.librepocket.linux.LinuxEnv.GUEST_ENV]，
 * 見 [dev.librepocket.linux.ProotExec.execute]）。
 */
interface ProcessRunner {
    fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>? = null): RawOutput
}

/** 子進程原始回執：輸出為位元組（截斷前），[timedOut] 表示超時已被殺。
 *
 * Phase O 完整性旗標（direct 通道誠實性，不新增 [ShellResult] verdict 常數）：
 * - [readFailed]：任一流 gobbler `read` 抛異常（真實 I/O 錯誤；只有真 EOF 才算完整）。
 *   `executeFrozen` 側 finished 下必映 `Failed`（部分輸出不當完整幀），超時路徑已先回
 *   `TimedOut` 不受影響（預期 close 收斂保留）。
 * - [outputIncomplete]：gobbler 有界 join（[DefaultProcessRunner.GOBBLER_JOIN_MS]）超界仍
 *   存活（父已 exit 但子孫繼承 pipe 致 EOF 延遲等），部分讀。would-be Ok（非超時且無
 *   [readFailed]）一律升級 `TimedOut`（攜 partial），不再回完整 Ok；`readFailed` 優先
 *   （兩旗同真即 `Failed`，數據錯誤優先於排空超時）。
 * - [overflowed]：任一流達 [ShellPolicy.HARD_READ_CAP_BYTES] 硬上限丟棄（drain-and-discard
 *   續讀至 EOF，不提前關流；見 P2-1）。下游仍經 [ShellPolicy.truncate]（64K）併入
 *   `truncated`（512K＞64K 故溢出必已截斷，此位僅為誠實冗餘）。
 */
data class RawOutput(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int,
    val timedOut: Boolean,
    val readFailed: Boolean = false,
    val outputIncomplete: Boolean = false,
    val overflowed: Boolean = false,
)

/** 真實子進程實現：argv 直達 exec（無 shell），超時 [destroyForcibly]。
 * [dirRoot] 非 null 時把子進程 cwd 釘到該目錄（縱深防禦：bare filename
 * 相對解析目標固定；PR#1 P0 direct-cwd 封堵）。null 時維持系統預設 cwd
 *（政策層仍 fail-closed）。
 * [run] 的 [env] 一律先 `clear()` 再全量替換，不繼承宿主 env；
 * null 即回落 [ShellExecutables.CLEAN_ENV]（固定 `PATH`、無危險變數），
 * guest 通道仍須顯式傳 [dev.librepocket.linux.LinuxEnv.GUEST_ENV]。
 *
 * Round-5 Phase O 生命週期（P1-1 owner try/finally＋P2-1 drain-and-discard＋P2-2 完整性）：
 * - owner 語義：[pb.start] 成功後立即進 try/finally。正常／Timeout／中斷／stdin 關閉抛／
 *   gobbler 啟動抛／`waitFor` 抛／`exitValue` 抛，全路徑關流（stdin＋雙讀端，best-effort
 *   吞）＋監督 kill+reap（`destroy`→有界 grace→`destroyForcibly`→有界 reap，見
 *   [DESTROY_GRACE_MS]/[REAP_JOIN_MS]），中斷旗標快照清除後恢復（不吞 caller 中斷）。
 * - 中斷：`waitFor` 抛 [InterruptedException] 即 kill 後原樣重抛（不偽造 Ok/TimedOut；
 *   上層 [RestrictedShell.executeFrozen] 映 `Failed("interrupted (process cleaned up)")`
 *   並恢復中斷；direct [ShellResult] 無 `cleanupIncomplete` 欄，以 Failed＋本 KDoc 說明，
 *   不新增 verdict 常數）。
 * - reap 失敗（有界 kill 後仍存活，實務僅不可殺 D-state）：try 內主動抛
 *   [IllegalStateException]（轉 `Failed("spawn failed")`），不回健康 Ok；finally 本身不抛
 *   （不遮蔽原異常），殘留風險見 KDoc 殘餘。
 * - P2-1：gobbler 到 cap 後 drain-and-discard 至 EOF（不提前 break/close，不給寫端
 *   EPIPE/SIGPIPE；與提權 [dev.librepocket.privilege.github.ElevatedProcessIo.copyCapped]
 *   同策），溢出經 [Gobbler.truncated] 單調標。
 * - P2-2：gobbler [Gobbler.finished]/[Gobbler.failed]/[Gobbler.truncated] 單調旗＋
 *   [Gobbler.read] join 超界 `joinIncomplete`；[RawOutput.readFailed]/[RawOutput.outputIncomplete]
 *   帶出，would-be Ok 不偽造完整（見 [RawOutput] 與 `executeFrozen` 映射）。
 * - Stage 3（P2-2 cap）：雙 gobbler 啟動前 [GobblerAdmission] reserve-before-start
 *   （飽和即零 thread、[RawOutput.readFailed]=true，`executeFrozen` 映 `Failed`，
 *   永不偽造 Ok）；permit 持至 thread 實際退出才釋放（[Gobbler.run] finally，
 *   殘留讀者續持、可觀測峰值見 [GobblerAdmission.peak]）；雙流 join 共享單一
 *   [GOBBLER_JOIN_MS] budget（非 2× 順序），縮短最壞 permit 持有。
 *
 * 殘餘：`destroyForcibly` 後仍不可殺（D-state／核心拒殺）即殘留活子進程（verdict 已為
 * Failed，非健康 Ok；本機無提權通道式 quarantine 閂，後續 spawn 不自動封鎖）。
 */
class DefaultProcessRunner(val dirRoot: String? = null) : ProcessRunner {
    companion object {
        /** gobbler 有界 join（每流；超界即 [RawOutput.outputIncomplete]，不無限等 pipe）。 */
        const val GOBBLER_JOIN_MS: Long = 5_000L

        /** `destroy` 優雅 grace（仍存活即升級 `destroyForcibly`）。 */
        const val DESTROY_GRACE_MS: Long = 200L

        /** `destroyForcibly` 後有界 reap（殭屍收割；早死即早回，非固定等待）。 */
        const val REAP_JOIN_MS: Long = 5_000L

        /** gobbler 線程唯一序號（`shell-gobbler-<seq>` 命名，診斷可區分 2N 殘留歸屬）。 */
        private val gobblerSeq = AtomicLong(0L)
    }

    /**
     * Phase O 測試縫（internal，同模組單測可達；產品碼永不設置）：
     * - [processObserver]：`pb.start()` 成功後同步回調（PID／[ProcessHandle] 可觀測；
     *   回調抛即吞，不影響生命週期）。
     * - [failStdinClose]：為 true 即不關 stdin、直接抛（模擬 stdin 關閉失敗路徑）。
     * - [failGobblerStart]：為 true 即不啟 gobbler、直接抛（模擬 gobbler 啟動失敗路徑）。
     */
    internal var processObserver: ((Process) -> Unit)? = null
    internal var failStdinClose: Boolean = false
    internal var failGobblerStart: Boolean = false

    override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
        val pb = ProcessBuilder(argv)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        // env 固定：null 不再繼承宿主，一律回落直接通道乾淨 env；
        // 非 null 同樣 clear() 後全量替換（呼叫方不得依賴繼承）。
        val effectiveEnv = env ?: ShellExecutables.CLEAN_ENV
        pb.environment().clear()
        pb.environment().putAll(effectiveEnv)
        if (dirRoot != null) {
            val dir = java.io.File(dirRoot)
            // 釘死失敗即 fail-closed：不繼承不可控 cwd，直接拋給上層轉 Failed。
            if (!dir.isDirectory) {
                throw IllegalStateException("pinned cwd not a directory: $dirRoot")
            }
            pb.directory(dir)
        }
        // start 抛（argv/cwd/env 無效）即無 process，無需清理，直接抛。
        val process = pb.start()
        // 測試縫回調抛即吞，不影響生命週期（生產永為 null）。
        processObserver?.let { cb ->
            runCatching { cb(process) }
        }
        var finished = false
        try {
            if (failStdinClose) {
                throw java.io.IOException("injected stdin-close failure (Phase O test seam)")
            }
            process.outputStream.close()
            if (failGobblerStart) {
                throw IllegalStateException("injected gobbler-start failure (Phase O test seam)")
            }
            // 任一啟動抛即進 finally（已啟的 gobbler 經關流＋kill 收斂，daemon 不殘留 verdict）。
            // Stage 3（P2-2 cap）：准入 reserve-before-start，fail-closed——飽和
            // （殘留讀者佔滿 [GobblerAdmission]，典型為 fork-but-no-exec 子孫握 pipe
            // 寫端致 EOF 延遲）即零 thread，直接回 [RawOutput.readFailed]=true
            // （上層映 `Failed`，永不偽造 Ok）。已啟的對端 gobbler（如有）經 finally
            // 關流＋kill 收斂（殘留與否皆有界，permit 持至其 thread 實際退出）。
            val outReader = try {
                streamGobbler(process.inputStream)
            } catch (_: GobblerSaturatedException) {
                return RawOutput(ByteArray(0), ByteArray(0), -1, false, readFailed = true)
            }
            val errReader = try {
                streamGobbler(process.errorStream)
            } catch (_: GobblerSaturatedException) {
                return RawOutput(ByteArray(0), ByteArray(0), -1, false, readFailed = true)
            }
            // Phase O：timeoutMs 負值經 coerceAtLeast(0L) 即時超時（waitFor(0) 未早死即 TimedOut；直調 runner 者同語義，不抛）。
            try {
                finished = process.waitFor(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ie
            }
            if (!finished) {
                killAndReap(process)
                if (isAlive(process)) {
                    throw IllegalStateException("process still alive after bounded kill+reap")
                }
            }
            // Stage 3 共享 tail budget：舊 2× [GOBBLER_JOIN_MS] 順序 join 最壞持有
            // permit 10s；現雙流共享單一 budget（次流取剩餘，可為 0 即即時快照），
            // 最壞持有 ≈5s＋調度，縮短殘留窗口（仍有界，超界即 joinIncomplete 誠實帶出）。
            val joinDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GOBBLER_JOIN_MS)
            val out = outReader.read(remainingJoinMs(joinDeadlineNanos))
            val err = errReader.read(remainingJoinMs(joinDeadlineNanos))
            val exit = if (finished) {
                try {
                    process.exitValue()
                } catch (_: Exception) {
                    throw IllegalStateException("exitValue unavailable after waitFor success")
                }
            } else {
                -1
            }
            return RawOutput(
                stdout = out.bytes,
                stderr = err.bytes,
                exitCode = exit,
                timedOut = !finished,
                readFailed = out.failed || err.failed,
                outputIncomplete = out.joinIncomplete || err.joinIncomplete,
                overflowed = out.truncated || err.truncated,
            )
        } finally {
            // owner 清理：全關流＋監督 kill+reap（best-effort 吞；中斷快照清除後恢復，
            // finally 內 waitFor 不因預置中斷即抛而跳過 destroyForcibly）。
            // 誠實注記：關讀端並不喚醒 Linux 上已阻塞於 FileInputStream.read() 的
            // gobbler（fd 在核心內保持開啟直至讀者返回，close 只對已 EOF／空閒者
            // 有效）；阻塞讀者只隨真 EOF 收斂（全部寫端關閉），子孫握寫端即殘留，
            // 由 [GobblerAdmission] 封頂（見該對象 KDoc 洩漏源推導）。
            val wasInterrupted = Thread.interrupted()
            try {
                runCatching { process.outputStream.close() }
                // 關雙讀端（best-effort：僅已 EOF／空閒 gobbler 有效；in-flight pipe
                // read 喚不醒，見上注記；正常 EOF 路徑已關，此處冪等）。
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                killAndReap(process)
            } finally {
                if (wasInterrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun isAlive(process: Process): Boolean =
        try {
            process.isAlive
        } catch (_: Exception) {
            false
        }

    /**
     * 共享 gobbler tail budget 剩餘（[Gobbler.read] 用；耗盡即 0，不負值）。
     */
    private fun remainingJoinMs(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

    /**
     * 監督 kill+reap（best-effort，不抛；中斷恢復旗標，呼叫方 finally 另做快照恢復）：
     * 已死即返；先 `destroy`＋[DESTROY_GRACE_MS] grace，仍存活即 `destroyForcibly`＋
     * [REAP_JOIN_MS] 有界 reap（早死早回）。
     *
     * Parent-only（direct 通道無 killTree——有意為之，非遺漏）：只信號直接子進程
     * handle；`fork`-but-no-`exec` 子孫繼承的 pipe 寫端不受影響而存活（direct  spawn
     * 為裸 argv、無 setsid 包裝，process-group kill 可能誤傷同組無關進程，故不用）。
     * 存活子孫握寫端即 EOF 延遲 → gobbler 殘留（關讀端喚不醒，已在 finally 注記），
     * 洩漏源由 [GobblerAdmission] 封頂，verdict 誠實性由 `outputIncomplete` 承載。
     */
    private fun killAndReap(process: Process) {
        if (!isAlive(process)) return
        try {
            process.destroy()
        } catch (_: Exception) {
        }
        try {
            if (process.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        }
        if (!isAlive(process)) return
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
        }
        try {
            process.waitFor(REAP_JOIN_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        }
    }

    /**
     * Stage 3 准入 gobbler（reserve-before-start，fail-closed；提權 verifier-cap 同式，
     * 見 [GobblerAdmission]）：先 [GobblerAdmission.tryEnter]，飽和即抛
     * [GobblerSaturatedException]（零 thread 啟動；`run` 側映 [RawOutput.readFailed]，
     * 永不偽造 Ok）。permit 持至 thread 實際退出（[Gobbler.run] finally 釋放，
     * [DirectAdmission.release] 同式）；起線程抛則 worker finally 永不執行，由此處
     * [Gobbler.dropAdmissionForStartFailure] 補釋（CAS 保 exactly-once）。
     * 線程唯一命名 `shell-gobbler-<seq>`（診斷可數、可歸因）。
     */
    private fun streamGobbler(stream: java.io.InputStream): Gobbler {
        if (!GobblerAdmission.tryEnter()) throw GobblerSaturatedException()
        val gobbler = Gobbler(stream)
        val thread = Thread(gobbler, "shell-gobbler-${gobblerSeq.incrementAndGet()}")
        thread.isDaemon = true
        gobbler.claimAdmission()
        try {
            thread.start()
        } catch (t: Throwable) {
            // 起線程失敗：worker finally 永不執行，由 caller 補釋（fail-closed）。
            gobbler.dropAdmissionForStartFailure()
            throw t
        }
        gobbler.thread = thread
        return gobbler
    }

    /**
     * Phase O gobbler（P2-1 drain-and-discard＋P2-2 單調旗；Stage 3 准入持有）：
     * - 到 [ShellPolicy.HARD_READ_CAP_BYTES] 後不 break／不關流，續讀丟棄至 EOF
     *   （[truncated] 單調標溢出；提前關讀端會致寫端 EPIPE/SIGPIPE，把 exit 0 改寫為
     *   141，見 P2-1）。
     * - [finished] 僅真 EOF（`read` 回 -1）置位；`read` 抛即 [failed] 單調置位後 break
     *   （killer 關流的預期中斷亦標 true，由呼叫方按 `finished`/超時區分語義）。
     * - [read] 有界 join（預設 [GOBBLER_JOIN_MS]；中斷恢復不吞，回 [GobblerRead] 快照）：
     *   join 超界仍存活即 [GobblerRead.joinIncomplete]=true（部分讀，呼叫方禁回完整 Ok）。
     *   中斷語義（誠實注記）：`join` 被中斷即提前抛、中斷旗恢復後直接按當刻存活快照
     *   返回（不重等剩餘 budget；任一返回路徑耗時恆 ≤ 傳入 budget＋調度，有界）。
     * - 洩漏語義（誠實注記）：父 exit0 但子孫握 pipe 寫端時，owner 關讀端喚不醒已
     *   阻塞於 `FileInputStream.read()` 的本 thread（Linux 語義，見 `run` finally）；
     *   本 thread 滯留至真 EOF（子孫死／寫端關）或 JVM 退出（daemon），期間續持
     *   [GobblerAdmission] permit（本 `run` finally 釋放，exactly-once），殘留總量由
     *   [GobblerAdmission.MAX_GOBBLERS] 封頂。
     * - 直接 `g.run()` 單測（未經 [streamGobbler]、未 [claimAdmission]）不持有
     *   permit，finally 不釋放（[releaseAdmissionOnce] CAS 保衛，不污染全域計數）。
     */
    internal class Gobbler(private val stream: java.io.InputStream) : Runnable {
        @Volatile var thread: Thread? = null
        private val sink = ByteArrayOutputStream()

        /** 准入持有（僅 [streamGobbler] 准入成功後置位；finally／start-failure 二路徑 CAS exactly-once 釋放）。 */
        private val admissionHeld = AtomicBoolean(false)

        /** 單調：真 EOF，已全量排空。 */
        @Volatile var finished: Boolean = false
            private set

        /** 單調：`read` 抛過（真實 I/O 錯誤或關流中斷）。 */
        @Volatile var failed: Boolean = false
            private set

        /** 單調：硬上限丟棄過（drain-and-discard 續讀，絕不靜默短讀不標）。 */
        @Volatile var truncated: Boolean = false
            private set

        /** owner 准入成功後調用（permit 與本 gobbler 生命週期綁定，持至 thread 實際退出）。 */
        internal fun claimAdmission() {
            admissionHeld.set(true)
        }

        /**
         * 起線程失敗回滾（owner 路徑；worker finally 永不執行）。
         * 與 finally 內 [releaseAdmissionOnce] 同一 CAS，exactly-once。
         */
        internal fun dropAdmissionForStartFailure() {
            releaseAdmissionOnce()
        }

        private fun releaseAdmissionOnce() {
            if (admissionHeld.compareAndSet(true, false)) GobblerAdmission.release()
        }

        override fun run() {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = try {
                        stream.read(buf)
                    } catch (_: Exception) {
                        failed = true
                        break
                    }
                    if (n < 0) {
                        finished = true
                        break
                    }
                    if (n == 0) continue
                    synchronized(sink) {
                        val room = ShellPolicy.HARD_READ_CAP_BYTES - sink.size()
                        if (room <= 0) {
                            // P2-1：已滿仍續讀丟棄，不 break／不關流。
                            truncated = true
                        } else if (n > room) {
                            truncated = true
                            sink.write(buf, 0, room)
                        } else {
                            sink.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                // Stage 3：permit 持至 thread 實際退出（殘留讀者續持，cap 封頂；見類 KDoc）。
                releaseAdmissionOnce()
            }
        }

        /**
         * 有界讀快照（預設完整 [GOBBLER_JOIN_MS] budget；`run` 側以共享剩餘傳入）。
         * 見 [read].
         */
        fun read(): GobblerRead = read(GOBBLER_JOIN_MS)

        /**
         * 有界讀快照（[timeoutMs] 上界；≤0 即不等待、直接快照當刻存活）。
         * 注意：`Thread.join(0)` 語義為無限等待，故耗盡 budget 時必須跳過 join
         * （不可透傳 0）。中斷即恢復旗標並按當刻存活返回（不重等，恆有界）；
         * 見類 KDoc 中斷語義。
         */
        fun read(timeoutMs: Long): GobblerRead {
            if (timeoutMs > 0L) {
                try {
                    thread?.join(timeoutMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val alive = thread?.isAlive == true
            synchronized(sink) {
                return GobblerRead(
                    bytes = sink.toByteArray(),
                    finished = finished,
                    failed = failed,
                    truncated = truncated,
                    joinIncomplete = alive,
                )
            }
        }
    }

    /** gobbler 有界讀快照（見 [Gobbler.read]）。 */
    internal data class GobblerRead(
        val bytes: ByteArray,
        val finished: Boolean,
        val failed: Boolean,
        val truncated: Boolean,
        val joinIncomplete: Boolean,
    )
}

/**
 * 滑動窗口配額：窗口內最多 [maxCalls] 次，超限呼叫方回
 * [ShellDeny.QUOTA_EXCEEDED]。時鐘可注入，執行緒安全。
 */
class ShellQuota(
    private val maxCalls: Int = ShellPolicy.DEFAULT_MAX_CALLS,
    private val windowMs: Long = ShellPolicy.DEFAULT_WINDOW_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val marks: ArrayDeque<Long> = ArrayDeque()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clockMs()
        while (marks.isNotEmpty() && now - marks.peekFirst() >= windowMs) {
            marks.removeFirst()
        }
        if (marks.size >= maxCalls) return false
        marks.addLast(now)
        return true
    }

    @Synchronized
    fun reset() {
        marks.clear()
    }
}

/**
 * Gobbler 准入（Round-5 Stage 3 P2-2，main 源集本地實現；[DirectAdmission] 姊妹對象）：
 *
 * 推導（固定小池之原子 reservation，提權 verifier-cap 同式）：
 * - 問題：每 `run` 起 2 條無名 `shell-gobbler` daemon、無准入無計數；父 exit0 但
 *   `fork`-but-no-`exec` 子孫握 pipe 寫端時，owner 關讀端喚不醒已阻塞於
 *   `FileInputStream.read()` 的讀者（Linux 語義）→ 讀者滯留至子孫死；N 次請求即
 *   2N daemon（各持 ≤512K sink＋棧），無峰值可觀測（Phase P 只採 `direct-*`）。
 * - 不用「關流＋kill 即收斂」假設：已實證關讀端不中斷 in-flight pipe read
 *   （見 `DefaultProcessRunner.run` finally 注記），收斂唯一依賴真 EOF，故必須
 *   以數量上界兜底，而非以清理語義兜底。
 * - 不用「池化复用」：gobbler 生命週期與單次 `run` 的雙流綁定（drain-and-discard
 *   狀態機不可跨 run 复用），池化省不掉 thread 數、徒增狀態污染面；reservation
 *   誠實（alive 即持 permit，idle 即零 thread），與 [DirectAdmission] 同哲學。
 * - 原子 reservation＋殘留持 permit 至 thread 實際退出：[permits] 同時是併發上限
 *   與 alive 上限（每活 gobbler 恰持一 permit，[Gobbler.run] `finally` 才釋放；
 *   caller 永不持 permit，entry 不預佔）；飽和即 [tryEnter] 失敗→`run` 側零
 *   thread、[RawOutput.readFailed]=true（fail-closed，不排隊：排隊即把 `run` 卡成
 *   隱性等待，與 gobbler join budget 疊加掩蓋飽和）。
 *
 * 常數 `MAX_GOBBLERS = 8` 推導：
 * - 下界：direct 側 [DirectAdmission.MAX_WORKERS]=3 允 3 併發 worker，每 worker 至多
 *   1 在飛 `run`（process 段監督內），每 `run` 2 gobbler → 穩態上限 6；取 8 留 2 槽
 *   餘量，容 teardown 交疊（舊 run 殘留未釋、新 run 已啟）下偶發飽和誤拒。
 * - 上界：每殘留持一 sink（≤512K，見 [ShellPolicy.HARD_READ_CAP_BYTES]）＋一 thread，
 *   8 份 ≈4M＋8 棧，有界小常數；fail-closed 方向（飽和拒讀不偽造 Ok），可用性損失
 *   只發生在殘留壓力下（洩漏源本身，非誤傷）。
 * - 與直接／提權正交：本池只計 gobbler thread（`shell-gobbler-*`），永不碰
 *   [DirectAdmission]／提權槽；gobbler 飽和只擋 pipe 排空（映 `Failed`），不影響
 *   監督 worker 准入（反之亦然）。
 *
 * 計數器（單測／診斷用，[DirectAdmission.availablePermits] 同式）：
 * - [current]：當前持 permit 數（== 活 gobbler 數，含殘留）；
 * - [peak]：歷峰（[resetPeak] 可回錨至當前值做測試隔離）；
 * - [availablePermits]：剩餘 permits（近似值）。
 */
internal object GobblerAdmission {
    /** 併發／殘留 gobbler 上界（見上推導；存活 `shell-gobbler-*` 總數恆 ≤ 本值）。 */
    const val MAX_GOBBLERS: Int = 8

    private val permits = Semaphore(MAX_GOBBLERS)
    private val inFlight = AtomicInteger(0)
    private val peakInFlight = AtomicInteger(0)

    /** 非阻塞准入：成功即持一 permit（由 [Gobbler.run] `finally` 釋放，caller 不持）。 */
    fun tryEnter(): Boolean {
        if (!permits.tryAcquire()) return false
        val c = inFlight.incrementAndGet()
        peakInFlight.updateAndGet { prev -> maxOf(prev, c) }
        return true
    }

    /** 退出釋放（僅 [Gobbler] finally／起線程失敗回滾調用；CAS 保 exactly-once）。 */
    fun release() {
        inFlight.decrementAndGet()
        permits.release()
    }

    /** 剩餘 permits（僅單測斷言有界用；近似值）。 */
    internal fun availablePermits(): Int = permits.availablePermits()

    /** 當前持 permit 數（== 活 gobbler 數，含殘留）。 */
    internal fun current(): Int = inFlight.get()

    /** 歷峰（單測斷言峰值有界用；[resetPeak] 回錨做隔離）。 */
    internal fun peak(): Int = peakInFlight.get()

    /** 峰值回錨至當前值（測試隔離用）。 */
    internal fun resetPeak() {
        peakInFlight.set(inFlight.get())
    }
}

/**
 * Gobbler 准入飽和（Stage 3 P2-2）：`DefaultProcessRunner.streamGobbler`
 * reserve-before-start 失敗即抛（零 thread 啟動）；`run` 側捕獲後回
 * [RawOutput.readFailed]=true（`executeFrozen` 映 `Failed("direct IO read failed…")`，
 * 與既有 P2-2 映射一致，永不回完整 Ok）。
 */
internal class GobblerSaturatedException :
    IllegalStateException("shell gobbler admission saturated (fail-closed, no thread started)")

/**
 * 直接通道跨呼叫准入（Round-5 Phase P P1-2，main 源集本地實現）：
 *
 * 推導（固定小池之原子 reservation，二選一之抉擇）：
 * - 不用「每呼叫各起一 thread」（Phase K 舊碼）：24／100 caller 各創一永久活
 *   daemon，`outstanding==0` 仍 thread 數隨呼叫數成長——呼叫數即殘留上界，
 *   不可接受（review 已驗證：並行 24/100 caller 留 24/100 活 daemon）。
 * - 不用「固定 ThreadPoolExecutor＋SynchronousQueue 零排隊」（提權快照池式）：
 *   池化雖同樣有界（全域恆 ≤N worker），但核心 threads 常駐 idle（`core==max`
 *   永不超時），idle 亦佔 `direct-*` 名，測試「釋 latch 後歸零」需等 keepAlive
 *   超時、非確定性；且池 thread 重用把「殘留引用」與「idle 線程」混為一談，
 *   不如 reservation 誠實（alive 即持 permit，idle 即零 thread）。
 * - 原子 reservation＋殘留持 permit 至 thread 實際退出：[permits] 同時是併發
 *   上限與 alive 上限（每活 worker 恰持一 permit，worker `finally` 才釋放；
 *   caller 永不持 permit，entry 不預佔）。飽和即 [tryEnter] 失敗→呼叫方零
 *   thread 即時 `Expired(hasLinger=true)`（fail-closed，不排隊：排隊即把 caller
 *   卡成隱性等待，與 deadline 門重複且掩蓋飽和）。
 *
 * 常數 `MAX_WORKERS = 3` 推導（與提權 7 體系協調的獨立小池）：
 * - 下界：正常 direct 預檢皆 µs～ms 級（拷貝＋詞法＋`lstat`），quota 30/min
 *   ≈0.5/s，Little 定律併發 ≈0.005，2 槽已不序列化正常；但 gate 含 FS／自訂
 *   resolver（FUSE 慢路徑 ms 級），取 3 留一槽餘量，避免正常抖動下偶發飽和
 *   誤拒（提權快照池取 2 因純拷貝無 FS，本池多一槽正為 FS 差異）。
 * - 上界：每殘留持一 argv 快照（≤64K＋12K，見 [ShellPolicy.copyArgvBounded]）
 *   ＋一 thread，3 份 ≈228K＋3 thread，有界小常數；全域 shell 子系統峰值
 *   ＝提權快照 2＋提權清理 7＋直接 3＝12 daemon，不擋 JVM 退出。
 * - 與提權正交：提權 `tryEnterReserved`／quarantine／slot（E/F/G/H/M/L/Q 已定）
 *   本池永不觸碰；直接飽和只擋直接，不影響提權（反之亦然）。
 *
 * 排序（quota 留位說明）：本准入在任何 worker 之前（freeze／gate 的
 * [DirectSupervisor.supervise] 內首步即 [tryEnter]，失敗即零 thread 回
 * `Expired`，故永久 stall 下後續請求立即 fail-closed，且發生在 quota 之前，
 * 飽和拒絕不耗 quota）；quota 本身留位 gate 之後（[RestrictedShell.executeFrozen]
 * 首步，見其 KDoc）：`deniedCallsDoNotConsumeQuota` 釘住「策略拒絕不耗配額」，
 * 提權雙 runner 亦 quota 置門後（門拒絕不耗配額），前移 quota 會使任意無效
 * argv 耗配額（無效探測即 DoS 配額），故配額仍為速率門、准入為負載門，
 * 分工見 [RestrictedShell.execute]。
 */
internal object DirectAdmission {
    /** 跨呼叫 alive worker 上界（見上推導；殘留引用全域上界同值）。 */
    const val MAX_WORKERS: Int = 3

    private val permits = Semaphore(MAX_WORKERS)

    /** 非阻塞准入：成功即持一 permit（由 worker `finally` 釋放，caller 不持）。 */
    fun tryEnter(): Boolean = permits.tryAcquire()

    /** worker 退出釋放（僅 worker `finally` 調用；起線程失敗由 caller 補釋）。 */
    fun release() {
        permits.release()
    }

    /** 剩餘 permits（僅單測斷言有界用；近似值）。 */
    internal fun availablePermits(): Int = permits.availablePermits()
}

/**
 * 直接通道有界監督（Round-4 Phase K P2-4＋Round-5 Phase P P1-2/P2-3，main 源集本地實現）：
 * 沿用 supervisor 模式（worker＋deadline＋bounded join），不依賴 github 源集
 * [dev.librepocket.privilege.github.ElevatedCallSupervisor]（main 源集不可見；
 * 語義同源：單一 request deadline、caller 只看中斷＋deadline、超界即
 * `interrupt`＋grace、仍存活即殘留 daemon）。
 * - 覆蓋：freeze（[ShellPolicy.copyArgvBounded]，惡意 `List` 可阻塞呼叫執行緒）→
 *   gate（[ShellPolicy.validate] 實體門 `lstat`/`toRealPath`＋resolve／自訂 resolver
 *   回呼，FUSE／壞 FS／自訂 resolver 可卡住）→ reverify（同 resolve 體）→
 *   process（[ProcessRunner.run] 全包：spawn＋wait＋kill＋gobbler joins 同一
 *   deadline 監督下，見 Round-5 Phase P P2-3）。
 * - Round-5 Phase P P1-2 跨呼叫有界：每 [supervise] 首步非阻塞取
 *   [DirectAdmission] permit（飽和即零 thread 直回 `Expired(true)`，fail-closed），
 *   worker `finally` 才釋放（殘留持 permit 至 thread 實際退出）；全域 alive
 *   `direct-*` 恆 ≤[DirectAdmission.MAX_WORKERS]，idle 零 thread（非池化常駐）。
 * - Round-5 Phase P P2-3 整體上限：process 段同 deadline 監督（caller wall
 *   ≤`timeoutMs`＋[GRACE_MS]＋[POLL_MS]＋調度，見 [RestrictedShell.execute]）；
 *   runner 內超額（`waitFor` 準時後的 kill-grace＋雙 gobbler join(5s)／吞中斷
 *   假 runner 永不返回／`pb.start()` 阻塞）只滯 worker（daemon 殘留，有界），
 *   不滯 caller；真 runner 清理（kill＋reap＋關流，Phase O owner 語義）仍在
 *   worker 內執行，caller `TimedOut` 後台收斂（見 Phase P 測試 kill＋reap 斷言）。
 * - 殘餘：超界殘留 worker 為 daemon（不擋 JVM 退出），阻塞於不可中斷操作即
 *   持 permit＋快照引用而殘留（全域 ≤3）；供給阻塞 list／resolver／runner 的
 *   本來就是呼叫方自身（假 runner 阻塞亦然），屬自限 DoS，不放大為跨呼叫無界。
 */
internal object DirectSupervisor {
    /** 監督輪詢切片（呼叫執行緒 join 粒度，兼中斷／deadline 響應）。 */
    const val POLL_MS = 20L

    /** 超界後 grace：`interrupt` 後再等本值，仍存活即殘留。 */
    const val GRACE_MS = 200L

    sealed interface Res<out T> {
        data class Ok<T>(val v: T) : Res<T>
        data class Err(val t: Throwable) : Res<Nothing>
        /** 超界：[hasLinger] 即 worker 在 grace 後仍存活（殘留 daemon）。 */
        data class Expired(val hasLinger: Boolean = false) : Res<Nothing>
    }

    /**
     * P1-1 內部超時標記（spawn 前預檢用）：worker 內 [isExpired]/中斷即抛，
     * 上層映 `Expired`→`TimedOut`（未 spawn，無需清理）。模組內可見
     * （internal，不進 public API；單測可用同一型別斷言映射）；[Thr]
     * 包裝後由呼叫方按型別映射（見 `executeFrozen`）。
     */
    internal class PreSpawnTimeoutException : RuntimeException("direct pre-spawn deadline exceeded")

    private class Val<T>(val v: T)
    private class Thr(val t: Throwable)

    /** 單一 request deadline（單調時鐘；`timeoutMs<=0` 即已逾期；溢出飽和）。 */
    fun deadlineNanos(timeoutMs: Long, nowNanos: Long = System.nanoTime()): Long {
        val safe = timeoutMs.coerceAtLeast(0L)
        val delta = try {
            Math.multiplyExact(safe, 1_000_000L)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE / 2
        }
        return try {
            Math.addExact(nowNanos, delta)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    fun isExpired(deadlineNanos: Long, nowNanos: Long = System.nanoTime()): Boolean =
        (deadlineNanos - nowNanos) <= 0L

    fun remainingMs(deadlineNanos: Long, nowNanos: Long = System.nanoTime()): Long =
        ((deadlineNanos - nowNanos) / 1_000_000L).coerceAtLeast(0L)

    /** 阻塞體有界：入口已逾期即不啟 worker，直回 `Expired(false)`（零殘留）。
     * Round-5 Phase P P1-2：次步非阻塞取 [DirectAdmission] permit（飽和即零
     * thread 直回 `Expired(true)`，fail-closed；permit 由 worker `finally` 釋放，
     * 殘留持至 thread 實際退出）。P1-1 fail-closed 收尾：join 迴圈以
     * `!worker.isAlive` 跳出後、讀 [holder] 之前必經 postcheck（呼叫方中斷旗標
     * 或 [isExpired] 即回 `Expired`，覆寫 `Val`——deadline+5ms 完工不誤判 Ok）。
     * 時鐘可注入（[nowNanos]，預設 [System.nanoTime]；單測傳 fake clock，
     * 產品行為不變）。
     */
    fun <T> supervise(
        deadlineNanos: Long,
        threadName: String,
        block: () -> T,
        nowNanos: () -> Long = System::nanoTime,
    ): Res<T> {
        if (isExpired(deadlineNanos, nowNanos())) return Res.Expired(false)
        if (!DirectAdmission.tryEnter()) return Res.Expired(true)
        val holder = arrayOfNulls<Any>(1)
        val worker = Thread({
            try {
                try {
                    holder[0] = Val(block())
                } catch (t: Throwable) {
                    holder[0] = Thr(t)
                }
            } finally {
                DirectAdmission.release()
            }
        }, threadName)
        worker.isDaemon = true
        try {
            worker.start()
        } catch (_: Throwable) {
            // 起線程失敗：worker finally 永不執行，由 caller 補釋（fail-closed）。
            DirectAdmission.release()
            return Res.Expired(false)
        }
        try {
            while (true) {
                worker.join(POLL_MS)
                if (!worker.isAlive) break
                if (Thread.currentThread().isInterrupted || isExpired(deadlineNanos, nowNanos())) {
                    runCatching { worker.interrupt() }
                    runCatching { worker.join(GRACE_MS) }
                    return Res.Expired(worker.isAlive)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { worker.interrupt() }
            runCatching { worker.join(GRACE_MS) }
            return Res.Expired(worker.isAlive)
        }
        // P1-1 fail-closed postcheck：worker 死於 deadline 之後（join 在最終切片
        // 才觀測到死亡，迴圈內未來得及看門）亦回 Expired，覆寫 holder 的 Val/Thr。
        if (Thread.currentThread().isInterrupted || isExpired(deadlineNanos, nowNanos())) {
            return Res.Expired(worker.isAlive)
        }
        return when (val h = holder[0]) {
            is Val<*> -> {
                @Suppress("UNCHECKED_CAST")
                Res.Ok((h as Val<T>).v)
            }
            is Thr -> Res.Err(h.t)
            else -> Res.Err(IllegalStateException("supervisor no result"))
        }
    }
}

/**
 * 受限 shell 入口（BACKLOG D05，矩陣「終端命令」行 play 可用部分）。
 *
 * 執行順序（Round-4 Phase K）：freeze-once（[ShellPolicy.copyArgvBounded] 有界
 * 不可變快照，同一 snapshot 驅動後續全鏈，見 [execute]）→
 * [ShellPolicy.validate]（含 argv[0] 詞法可信門 + 操作數實體門）＋
 * 可信 executable 映射（[ShellExecutables.resolveDetailed]：logical command →
 * [ShellExecutables.ResolvedExec]，拒 `PATH` 劫持；含 real containment +
 * app-writability 閘，逃逸/可寫即拒且零 spawn、不佔配額；兩段同一監督 worker
 * 同一 deadline，阻塞即 `TimedOut` 零 spawn，見 [DirectSupervisor]）→
 * 配額 → spawn 前重驗 executable（B1：同一解析再跑一次並比對實體路徑，
 * 縮小驗過→spawn 窗口；監督 worker，見 [executeFrozen] 殘餘揭露）→
 * 建子進程（frozen[0] 已改寫為固定絕對路徑 + [ShellExecutables.CLEAN_ENV]
 * 乾淨 env + cwd 釘死到 [privateRoot]，操作數取 frozen `drop(1)`）→ 超時殺 →
 * 輸出截斷。`timeoutMs` 為整體 request budget（見 [execute]），剩餘才交 runner。
 * spawn 固定形態（P2-7 parity）：非 multicall 別名形為 `[real, ...args]`；
 * 實體 basename 落 [ShellExecutables.MULTICALL_BINARIES]（toybox / toolbox /
 * busybox）「且」白名單放行值（`Allowed.binary`，即 `argv[0]` 的 basename）
 * 與實體 basename 不同（經 symlink／別名呼叫）時為 `[real, applet, ...args]`，
 * 其中 `applet` 取白名單放行值，還原被 link 吞掉的子命令名
 * （Android toybox 下 `ls -l` 不得丟成 `toybox -l`）；主程式自身
 * （`binary == realBase`，如 `["toybox", "id"]`）不插入（`args` 首位即 applet，
 * 否則形成 `[real, "toybox", "id"]` 錯誤形；陳舊映射攜非 null 亦不重插）。
 * 策略拒絕、映射拒絕與配額拒絕一律不建子進程；提權執行不在此類（見 [ElevatedShellRunner]）。
 * 操作數 schema（`--opt=value`、短旗標合併、取值旗標值槽、grep pattern 槽）
 * 沿 [ShellPolicy] 既有嚴格語義，本類不放寬、不重寫。
 *
 * 檔案域：[privateRoot] 為 App 私有域根（例 `context.filesDir.absolutePath`），
 * 檔案參數凡絕對路徑一律先過 `FileScope.decide` 詞法門 + 實體解析門
 * （見 [ShellPolicy.validate]）。未配置（null，預設）時任何絕對路徑一律拒絕，
 * 且隱式讀 cwd 的命令無明確路徑時亦拒絕（fail-closed）；play 跨域拒絕，
 * foss/github 跨域即使橋接已授權，直接 exec 仍拒絕（需改走 D09 橋）。
 */
class RestrictedShell(
    private val quota: ShellQuota = ShellQuota(),
    private val runner: ProcessRunner = DefaultProcessRunner(),
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val flavor: Flavor = Flavor.PLAY,
    private val bridgeGranted: Boolean = false,
    /**
     * 受控搜尋／可信目錄表（預設 [ShellExecutables.TRUSTED_BIN_DIRS]；
     * 產品碼一律用預設值）。同時餵給 [ShellPolicy.validate] 的 argv[0]
     * 詞法可信門與 [ShellExecutables.resolve] 的 `searchDirs`，兩者同表
     * 才保證「放行即能解析」；單測可注入暫存目錄以覆蓋絕對 `argv[0]` 全鏈。
     */
    private val execSearchDirs: List<String> = ShellExecutables.TRUSTED_BIN_DIRS,
    /**
     * containment 允許前綴（預設 null → 由 [execSearchDirs] real 化快照 +
     * 系統實體前綴；見 [ShellExecutables.snapshotRealRoots]）。單測可注入，
     * 產品碼一律用預設 null（現場快照，不長期重用）。
     */
    private val execAllowedRoots: List<String>? = null,
    /**
     * app-uid 可寫判定（預設真查 `Files.isWritable`，見
     * [ShellExecutables.defaultIsWritable]）。單測可注入以模擬系統自帶不可寫
     * （宿主暫存檔屬主可寫）；產品碼一律用預設。
     */
    private val execIsWritable: (java.nio.file.Path) -> Boolean = ShellExecutables::defaultIsWritable,
    /**
     * 可信 executable 解析（預設 null → 內建 [ShellExecutables.resolveDetailed] 真查 FS；
     * 單測可注入假映射，但生產必須用預設 null）。
     * 輸入為 frozen `argv[0]`（保留絕對/相對形態供驗證；P1-4 起不再讀原始 argv），輸出為
     * [ShellExecutables.ResolvedExec]（驗證後實體路徑 + multicall applet
     * 訊號）或 null（不可信/不存在即 null，呼叫方轉拒絕且零 spawn）。
     * 自訂映射回 multicall 時，其 `applet` 會被白名單放行值（`Allowed.binary`）
     * 覆寫後才 spawn，不採信映射自帶字串。
     * null（預設）路徑的拒絕訊息攜帶 [ShellExecutables.ResolveDeny] reason code
     * （不含任意 filesystem path）；自訂映射路徑維持通用訊息。
     */
    private val execResolver: ((String) -> ShellExecutables.ResolvedExec?)? = null,
) {
    /**
     * 直接通道執行（Round-4 Phase K P1-4＋P2-4，Round-5 Phase P P1-2/P2-3）。
     *
     * P1-4 freeze-once：入口只凍結一次有界 immutable argv（[ShellPolicy.copyArgvBounded]
     * 上限 argc≤64／單項≤4K／總≤64K＋lone surrogate 拒；超限／含 null／拷貝抛即
     * `Denied(BLACKLISTED)` 零 spawn），同一 frozen snapshot 驅動 validation、binary、
     * resolve／reverify、quota、最終 spawn；凍結後任何一步不再讀原始 `argv`
     * （含 `argv[0].trim()` 訊息、`drop(1)` 操作數、resolver 輸入——一律用 frozen）。
     *
     * P2-4 有界監督：freeze／gate（validate 實體門＋resolve／自訂 resolver）／reverify
     * 三段各跑 [DirectSupervisor] worker＋deadline＋bounded join；阻塞
     * `toRealPath`／resolver 即 typed `TimedOut`（空 partial）零 spawn。
     * Round-5 Phase P P1-2 跨呼叫准入：每段首步非阻塞取 [DirectAdmission]
     * permit（飽和即零 thread 即時 `TimedOut`，fail-closed；freeze／gate 在 quota
     * 之前故飽和不耗 quota；permits 由 worker `finally` 釋放，殘留持至 thread
     * 實際退出，全域 alive `direct-*` 恆 ≤[DirectAdmission.MAX_WORKERS]，idle
     * 零 thread，見 [DirectAdmission] 推導；quota 留位 gate 之後以保
     * `deniedCallsDoNotConsumeQuota`＋提權 parity，准入為負載門、配額為速率門）。
     *
     * Round-5 Phase P P2-3 受監督 process 段：`runner.run` 全包（spawn＋wait＋
     * kill＋gobbler joins）跑同一 deadline 監督 worker（`direct-process`，見
     * [executeFrozen]）：注入 runner 永不返回／`pb.start()` 阻塞／慢 gobbler
     * EOF 只滯 worker（daemon 殘留，有界），caller wall 恆有界（見下）。
     *
     * `timeoutMs` 語義（全鏈一致，整體 request budget，有測試背書見
     * `ShellDirectPhasePTest`）：自本函數入口起的單調時鐘總預算，
     * 覆 freeze＋validate＋resolve＋quota＋reverify＋process-run；剩餘 budget 才交
     * [ProcessRunner.run]（`remainingMs`，耗盡即 spawn 前 `TimedOut` 零 spawn）。
     * caller wall 上界＝`timeoutMs`＋[DirectSupervisor.GRACE_MS]＋
     * [DirectSupervisor.POLL_MS]＋調度（process 段吞中斷阻塞亦然；真 runner 的
     * kill-grace＋gobbler joins 超額只滯 worker，不滯 caller）。
     * 呼叫方（[ShellExecTool.execute]）直傳即同語義（Tool API 不變）。
     * `timeoutMs<=0` 即已逾期，直接 `TimedOut` 零 spawn（不耗配額）。
     * `TimedOut.timeoutMs` 回顯原始請求值（可觀測），runner 側實際等待為剩餘值。
     */
    fun execute(argv: List<String>, timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS): ShellResult {
        val entryNanos = System.nanoTime()
        val deadlineNanos = DirectSupervisor.deadlineNanos(timeoutMs, entryNanos)
        if (DirectSupervisor.isExpired(deadlineNanos)) {
            return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        // P1-4：只凍結一次。後續一律用 frozen，不再讀 argv。
        val frozenRaw: List<String> = when (
            val r = DirectSupervisor.supervise(deadlineNanos, "direct-freeze", block = {
                ShellPolicy.copyArgvBounded(argv)
            })
        ) {
            is DirectSupervisor.Res.Ok ->
                r.v ?: return ShellResult.Denied(
                    ShellDeny.BLACKLISTED,
                    "invalid argv (fail-closed, no spawn)",
                )
            is DirectSupervisor.Res.Err -> return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "invalid argv (fail-closed, no spawn)",
            )
            is DirectSupervisor.Res.Expired -> return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        val frozen: List<String> = Collections.unmodifiableList(frozenRaw)
        // P2-4：gate（validate＋resolve）同一 worker 同一 deadline 監督。
        // 可信 executable 映射失敗即拒，不建子進程、不佔配額（「拒絕零 spawn」不變量）。
        val custom = execResolver
        val gate: DirectGate = when (
            val r = DirectSupervisor.supervise(deadlineNanos, "direct-gate", block = {
                gateOnFrozen(frozen, custom)
            })
        ) {
            is DirectSupervisor.Res.Ok -> r.v
            is DirectSupervisor.Res.Err -> return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "validation failed (fail-closed, no spawn)",
            )
            is DirectSupervisor.Res.Expired -> return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        val binary: String
        val resolved: ShellExecutables.ResolvedExec
        when (gate) {
            is DirectGate.Denied -> return ShellResult.Denied(gate.reason, gate.message)
            is DirectGate.Ready -> {
                binary = gate.binary
                resolved = gate.resolved
            }
        }
        return executeFrozen(frozen, binary, resolved, timeoutMs, deadlineNanos)
    }

    /** gate worker 本體：只讀 frozen（純函數＋FS／resolver 阻塞體，監督內執行）。 */
    private fun gateOnFrozen(
        frozen: List<String>,
        custom: ((String) -> ShellExecutables.ResolvedExec?)?,
    ): DirectGate {
        val v = try {
            ShellPolicy.validate(
                frozen,
                privateRoot,
                safRoots,
                flavor,
                bridgeGranted,
                trustedBinDirs = execSearchDirs,
            )
        } catch (_: Exception) {
            return DirectGate.Denied(
                ShellDeny.BLACKLISTED,
                "validation failed (fail-closed, no spawn)",
            )
        }
        val binary = when (v) {
            is Validation.Denied -> return DirectGate.Denied(v.reason, v.message)
            is Validation.Allowed -> v.binary
        }
        val first = try {
            frozen[0].trim()
        } catch (_: Exception) {
            return DirectGate.Denied(
                ShellDeny.BLACKLISTED,
                "validation failed (fail-closed, no spawn)",
            )
        }
        if (custom != null) {
            val r = try {
                custom(first)
            } catch (_: Exception) {
                null
            } ?: return DirectGate.Denied(
                ShellDeny.BLACKLISTED,
                "untrusted executable: $first",
            )
            return DirectGate.Ready(binary, r)
        }
        val outcome = try {
            ShellExecutables.resolveDetailed(
                first,
                execSearchDirs,
                execAllowedRoots,
                execIsWritable,
            )
        } catch (_: Exception) {
            null
        }
        val r = (outcome as? ShellExecutables.ResolveOutcome.Ok)?.exec
            ?: run {
                val reason = (outcome as? ShellExecutables.ResolveOutcome.Denied)?.reason
                return DirectGate.Denied(
                    ShellDeny.BLACKLISTED,
                    if (reason != null) "untrusted executable: $first (reason=$reason)"
                    else "untrusted executable: $first",
                )
            }
        return DirectGate.Ready(binary, r)
    }

    private sealed interface DirectGate {
        data class Ready(val binary: String, val resolved: ShellExecutables.ResolvedExec) : DirectGate
        data class Denied(val reason: ShellDeny, val message: String) : DirectGate
    }

    /**
     * spawn 前重驗（B1）＋spawn：只讀 frozen snapshot（P1-4），重驗跑監督 worker（P2-4），
     * process 段跑同一 deadline 監督 worker（Round-5 Phase P P2-3）。
     *
     * 誠實揭露（殘餘 TOCTOU）：重驗與 `runner.run(spawnArgv)` 仍非原子；
     * Java 無 `O_NOFOLLOW`／`openat` 可持鎖，驗→spawn 間的二進位替換／link
     * 置換／同路徑內容覆寫仍可能發生，重驗只能縮窗、不能消除。直接通道沒有
     * 可持的容器鎖（不像 scoped-file 可持 fd 語義）；提權／guest 通道另見
     * 各自入口（`ProotExec` 持同容器鎖，仍只覆 spawn 臨界、不覆政策驗證）。
     * 重驗只覆 argv[0]
     * 實體（executable），操作數（frozen `drop(1)`）無二次驗、原樣傳遞；驗→spawn
     * 間操作數被換即穿，屬上游 ShellPolicy 門的 TOCTOU 殘餘類，僅有界——但
     * P1-4 已把「呼叫方經原始 mutable list 竄改操作數」的確定性繞道封死：
     * spawn 操作數取自凍結時 snapshot，驗證後竄改不影響本次 spawn。
     * 重驗失敗不洩路徑（通用訊息，僅攜 frozen `argv[0]`）；配額已在重驗前扣除
     * （攻擊性重驗失敗亦計次，兼作速率限制；監督超界同理，已扣除不退；
     * reverify／process 段飽和（[DirectAdmission] 滿）同理已扣除不退——freeze／
     * gate 飽和在 quota 之前故不耗，見 [execute]）。
     *
     * Round-5 Phase P P2-3 process 監督（不碰 [DefaultProcessRunner]/gobbler 內部
     * 語義，Phase O 已定，只包監督）：`effectiveRunner.run(spawnArgv,
     * remainingMsSnapshot, CLEAN_ENV)` 跑 `direct-process` 監督 worker，同一
     * `deadlineNanos` 為上界（caller wall ≤`timeoutMs`＋grace＋poll，見 [execute]）：
     * - 注入 runner 永不返回／`pb.start()`／`outputStream.close()` 阻塞／慢
     *   gobbler EOF（雙 join(5s)）只滯 worker（`Expired` 即 `TimedOut` 空 partial，
     *   worker 殘留 daemon 有界 ≤[DirectAdmission.MAX_WORKERS]，permit 至退出才釋）；
     * - 真 runner 清理仍在 worker 內（Phase O owner try/finally：關流＋監督
     *   kill+reap，中斷快照恢復），caller `TimedOut` 後台收斂（kill＋reap 斷言見
     *   Phase P 測試；direct 無 `cleanupIncomplete` 欄，`TimedOut` 即含「已殺」語義，
     *   殘留只可能是 worker 滯留而非未嘗試清理）；
     * - worker 抛 [InterruptedException]（runner 中斷已做 owner 清理）映
     *   `Failed("interrupted (process cleaned up)")`＋恢復 caller 中斷（P1-1 語義
     *   不變，只是發生地從 caller 線程移到 worker）；其餘抛映 `Failed("spawn
     *   failed")`（固定字串不內插異常訊息，防路徑外洩）。
     * - P1-1 雙門（fail-closed，不誤判 Ok）：成功路徑亦有 postcheck——worker 死於
     *   deadline 之後即 `Expired`→`TimedOut`（覆寫 `Val`）；spawn 前 worker 內預檢
     *   （[DirectSupervisor.PreSpawnTimeoutException]→`TimedOut`，零 spawn）。
     */
    private fun executeFrozen(
        frozen: List<String>,
        binary: String,
        resolved: ShellExecutables.ResolvedExec,
        timeoutMs: Long,
        deadlineNanos: Long,
    ): ShellResult {
        if (!quota.tryAcquire()) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "shell quota exceeded")
        }
        if (DirectSupervisor.isExpired(deadlineNanos)) {
            return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        // Direct 通道 cwd 縱深釘死：預設 runner 且 privateRoot 已知時，用釘死 cwd
        // 的 runner 執行（與 Root/Shizuku 雙層釘死對齊；政策層已 fail-closed，
        // 此處防 TOCTOU / cwd 預植）。privateRoot 非 dir 即拋（fail-closed，
        // 下方轉 Failed；見 DefaultProcessRunner）。
        val effectiveRunner: ProcessRunner =
            if (privateRoot != null && runner is DefaultProcessRunner) {
                DefaultProcessRunner(privateRoot)
            } else {
                runner
            }
        // spawn 前重驗：同一解析再跑一次，全字段不一致即拒（零 spawn；
        // ResolvedExec 為 data class，全相等比分段比對多覆未來新增欄位）。
        // P2-4：重驗跑監督 worker（自訂 resolver／FS 可阻塞），超界即 TimedOut。
        val safeFirst = try {
            frozen[0].trim()
        } catch (_: Exception) {
            return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "untrusted executable (fail-closed, no spawn)",
            )
        }
        val custom = execResolver
        val fresh: ShellExecutables.ResolvedExec? = when (
            val r = DirectSupervisor.supervise(deadlineNanos, "direct-reverify", block = {
                if (custom != null) {
                    try {
                        custom(safeFirst)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    try {
                        (ShellExecutables.resolveDetailed(
                            safeFirst,
                            execSearchDirs,
                            execAllowedRoots,
                            execIsWritable,
                        ) as? ShellExecutables.ResolveOutcome.Ok)?.exec
                    } catch (_: Exception) {
                        null
                    }
                }
            })
        ) {
            is DirectSupervisor.Res.Ok -> r.v
            is DirectSupervisor.Res.Err -> null
            is DirectSupervisor.Res.Expired -> return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        if (fresh != resolved) {
            return ShellResult.Denied(
                ShellDeny.BLACKLISTED,
                "untrusted executable: $safeFirst",
            )
        }
        if (DirectSupervisor.isExpired(deadlineNanos)) {
            return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        // spawn 固定形態：frozen[0] 已改寫為驗證後實體路徑（可執行目標不再由
        // OS 經 `PATH`/cwd 解析）+ 乾淨 env（固定 `PATH`、無危險變數，
        // 不繼承宿主）。操作數（frozen `drop(1)`）原樣傳遞，不重寫、不放寬。
        // multicall 別名形（resolved.applet 非 null「且」binary ≠ 實體 basename，
        // 即經 symlink／別名呼叫）時在 argv[1] 補回白名單放行值 `binary`
        // （即 frozen[0] 的 basename），還原被 link 吞掉的子命令名；此處用
        // validate 回傳值而非映射自帶字串，自訂映射不得注入未過白名單的
        // applet 名。主程式自身（binary == 實體 basename）與非 multicall
        // 不插入（P2-7 parity 縱深：陳舊映射攜非 null 亦不重插）。
        val realBase = resolved.path.substringAfterLast('/')
        val spawnArgv = if (resolved.applet != null &&
            realBase in ShellExecutables.MULTICALL_BINARIES && binary != realBase
        ) {
            listOf(resolved.path, binary) + frozen.drop(1)
        } else {
            listOf(resolved.path) + frozen.drop(1)
        }
        val remainingMs = DirectSupervisor.remainingMs(deadlineNanos)
        if (remainingMs <= 0L) {
            return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        // Round-5 Phase P P2-3：process 全包監督（spawn＋wait＋kill＋gobbler joins
        // 同一 deadline；runner 側實際等待為快照值，caller wall 另由監督封頂）。
        // 不碰 DefaultProcessRunner／Gobbler 內部語義（Phase O 已定），只包監督：
        // 阻塞 runner／慢 tail 只滯 worker（daemon 殘留，有界），不滯 caller。
        val remainingSnapshot = remainingMs
        val raw: RawOutput = when (
            val r = DirectSupervisor.supervise(deadlineNanos, "direct-process", block = {
                // P1-1 spawn 前預檢：supervise 入口→worker 實際執行間有線程啟動＋
                // 調度間隙，deadline 可在此間逾期；spawn 前再看門一次，逾期／中斷
                // 即抛 [DirectSupervisor.PreSpawnTimeoutException]（零 spawn，
                // 上層映 TimedOut，不走 Failed 清理語義）。
                if (DirectSupervisor.isExpired(deadlineNanos) || Thread.currentThread().isInterrupted) {
                    throw DirectSupervisor.PreSpawnTimeoutException()
                }
                effectiveRunner.run(spawnArgv, remainingSnapshot, ShellExecutables.CLEAN_ENV)
            })
        ) {
            is DirectSupervisor.Res.Ok -> r.v
            is DirectSupervisor.Res.Err -> {
                val t = r.t
                if (t is DirectSupervisor.PreSpawnTimeoutException) {
                    // 預檢期滿：spawn 未發生，無需清理，直回 TimedOut。
                    return ShellResult.TimedOut("", "", timeoutMs, false)
                }
                if (t is InterruptedException) {
                    // P1-1：runner 中斷已做 owner 清理（kill+reap＋關流，見 DefaultProcessRunner），
                    // 不偽造 Ok/TimedOut；direct 無 cleanupIncomplete 欄，以 Failed＋KDoc 說明。
                    Thread.currentThread().interrupt()
                    return ShellResult.Failed("interrupted (process cleaned up)")
                }
                // 診斷如需保留只可進 log；目前直接丟棄，回執固定字串不內插異常訊息（防路徑外洩）。
                return ShellResult.Failed("spawn failed")
            }
            is DirectSupervisor.Res.Expired ->
                // process 超界：空 partial（worker 滯留持有 partial，caller 不取；
                // 真 runner 清理在 worker 內後台收斂，見本函數 KDoc）。
                return ShellResult.TimedOut("", "", timeoutMs, false)
        }
        val out = ShellPolicy.truncate(raw.stdout)
        val err = ShellPolicy.truncate(raw.stderr)
        val truncated = out.truncated || err.truncated || raw.overflowed
        if (raw.timedOut) {
            return ShellResult.TimedOut(
                partialStdout = out.bytes.toUtf8Lossy(),
                partialStderr = err.bytes.toUtf8Lossy(),
                timeoutMs = timeoutMs,
                truncated = truncated,
            )
        }
        // P2-2：finished 下讀取異常必為真實 I/O 錯誤（只有真 EOF 才算完整流），
        // 部分輸出不當完整 Ok，回 Failed（超時路徑已先回 TimedOut，不受影響）。
        if (raw.readFailed) {
            return ShellResult.Failed("direct IO read failed (incomplete stream)")
        }
        // P2-2：finished 但 tail 未排空（join 超界仍存活，如子孫繼承 pipe 致 EOF 延遲），
        // would-be Ok 升級 TimedOut（攜 partial＋truncated），不再回完整 Ok；
        // exitCode 不攜（request 未完成，非命令退出語義；避免以 exit 0 宣告工具完成）。
        if (raw.outputIncomplete) {
            return ShellResult.TimedOut(
                partialStdout = out.bytes.toUtf8Lossy(),
                partialStderr = err.bytes.toUtf8Lossy(),
                timeoutMs = timeoutMs,
                truncated = truncated,
            )
        }
        return ShellResult.Ok(
            stdout = out.bytes.toUtf8Lossy(),
            stderr = err.bytes.toUtf8Lossy(),
            exitCode = raw.exitCode,
            truncated = truncated,
        )
    }

    private fun ByteArray.toUtf8Lossy(): String =
        String(this, Charsets.UTF_8)
}
