package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

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

/** 子進程原始回執：輸出為位元組（截斷前），[timedOut] 表示超時已被殺。 */
data class RawOutput(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int,
    val timedOut: Boolean,
)

/** 真實子進程實現：argv 直達 exec（無 shell），超時 [destroyForcibly]。
 * [dirRoot] 非 null 時把子進程 cwd 釘到該目錄（縱深防禦：bare filename
 * 相對解析目標固定；PR#1 P0 direct-cwd 封堵）。null 時維持系統預設 cwd
 *（政策層仍 fail-closed）。
 * [run] 的 [env] 一律先 `clear()` 再全量替換，不繼承宿主 env；
 * null 即回落 [ShellExecutables.CLEAN_ENV]（固定 `PATH`、無危險變數），
 * guest 通道仍須顯式傳 [dev.librepocket.linux.LinuxEnv.GUEST_ENV]。
 */
class DefaultProcessRunner(val dirRoot: String? = null) : ProcessRunner {
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
        val process = pb.start()
        process.outputStream.close()
        val outReader = streamGobbler(process.inputStream)
        val errReader = streamGobbler(process.errorStream)
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        return RawOutput(
            stdout = outReader.read(),
            stderr = errReader.read(),
            exitCode = if (finished) process.exitValue() else -1,
            timedOut = !finished,
        )
    }

    private fun streamGobbler(stream: java.io.InputStream): Gobbler {
        val gobbler = Gobbler(stream)
        val thread = Thread(gobbler, "shell-gobbler")
        thread.isDaemon = true
        thread.start()
        gobbler.thread = thread
        return gobbler
    }

    private class Gobbler(private val stream: java.io.InputStream) : Runnable {
        @Volatile var thread: Thread? = null
        private val sink = ByteArrayOutputStream()

        override fun run() {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    synchronized(sink) {
                        val room = ShellPolicy.HARD_READ_CAP_BYTES - sink.size()
                        if (room <= 0) break
                        sink.write(buf, 0, minOf(n, room))
                    }
                }
            } catch (_: Exception) {
                // 子進程被殺時流中斷屬正常，直接收尾。
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
            }
        }

        fun read(): ByteArray {
            try {
                thread?.join(5_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            synchronized(sink) {
                return sink.toByteArray()
            }
        }
    }
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
 * 受限 shell 入口（BACKLOG D05，矩陣「終端命令」行 play 可用部分）。
 *
 * 執行順序：[ShellPolicy.validate]（含 argv[0] 詞法可信門）→
 * 可信 executable 映射（[ShellExecutables.resolve]：logical command →
 * [ShellExecutables.ResolvedExec]，拒 `PATH` 劫持；含 real containment +
 * app-writability 閘，逃逸/可寫即拒且零 spawn、不佔配額）→
 * 配額 → 建子進程（argv[0] 已改寫為固定絕對路徑 + [ShellExecutables.CLEAN_ENV]
 * 乾淨 env + cwd 釘死到 [privateRoot]）→ 超時殺 → 輸出截斷。
 * spawn 固定形態：非 multicall 為 `[real, ...args]`；實體 basename 落
 * [ShellExecutables.MULTICALL_BINARIES]（toybox / toolbox / busybox）時為
 * `[real, applet, ...args]`，其中 `applet` 取白名單放行值（`Allowed.binary`，
 * 即 `argv[0]` 的 basename），還原被 link 吞掉的子命令名
 * （Android toybox 下 `ls -l` 不得丟成 `toybox -l`）。
 * 策略拒絕、映射拒絕與配額拒絕一律不建子進程；提權執行不在此類（見 [ElevatedShellRunner]）。
 * 操作數 schema（`--opt=value`、短旗標合併、取值旗標值槽、grep pattern 槽）
 * 沿 [ShellPolicy] 既有嚴格語義，本類不放寬、不重寫。
 *
 * 檔案域：[privateRoot] 為 App 私有域根（例 `context.filesDir.absolutePath`），
 * 檔案參數凡絕對路徑一律先過 `FileScope.decide`（見 [ShellPolicy.validate]）。
 * 未配置（null，預設）時任何絕對路徑一律拒絕，且隱式讀 cwd 的命令無明確
 * 路徑時亦拒絕（fail-closed）；play 跨域拒絕，
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
     * 可信 executable 解析（預設 [ShellExecutables.resolve] 真查 FS；
     * 單測可注入假映射，但生產必須用預設）。
     * 輸入為原始 `argv[0]`（保留絕對/相對形態供驗證），輸出為
     * [ShellExecutables.ResolvedExec]（驗證後實體路徑 + multicall applet
     * 訊號）或 null（不可信/不存在即 null，呼叫方轉拒絕且零 spawn）。
     * 自訂映射回 multicall 時，其 `applet` 會被白名單放行值（`Allowed.binary`）
     * 覆寫後才 spawn，不採信映射自帶字串。
     */
    private val execResolver: (String) -> ShellExecutables.ResolvedExec? =
        { ShellExecutables.resolve(it, execSearchDirs, execAllowedRoots, execIsWritable) },
) {
    fun execute(argv: List<String>, timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS): ShellResult {
        val binary = when (
            val v = ShellPolicy.validate(argv, privateRoot, safRoots, flavor, bridgeGranted, trustedBinDirs = execSearchDirs)
        ) {
            is Validation.Denied -> return ShellResult.Denied(v.reason, v.message)
            is Validation.Allowed -> v.binary
        }
        // 可信 executable 映射（建程序前）：logical command → 已驗證實體。
        // 失敗（同名不同路徑假二進位、`PATH` 劫持、檔案缺失/不可執行、
        // symlink 逃逸集外、可寫目標）即拒，
        // 不建子進程、不佔配額（沿用「拒絕零 spawn」不變量）。
        val resolved = try {
            execResolver(argv[0].trim())
        } catch (_: Exception) {
            null
        } ?: return ShellResult.Denied(
            ShellDeny.BLACKLISTED,
            "untrusted executable: ${argv[0].trim()}",
        )
        if (!quota.tryAcquire()) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "shell quota exceeded")
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
        // spawn 固定形態：argv[0] 已改寫為驗證後實體路徑（可執行目標不再由
        // OS 經 `PATH`/cwd 解析）+ 乾淨 env（固定 `PATH`、無危險變數，
        // 不繼承宿主）。操作數（drop(1)）原樣傳遞，不重寫、不放寬。
        // multicall（resolved.applet 非 null，即實體為 toybox/toolbox/busybox）
        // 時在 argv[1] 補回白名單放行值 `binary`（即 argv[0] 的 basename），
        // 還原被 link 吞掉的子命令名；此處用 validate 回傳值而非映射自帶字串，
        // 自訂映射不得注入未過白名單的 applet 名。非 multicall 不插入。
        val spawnArgv = if (resolved.applet != null) {
            listOf(resolved.path, binary) + argv.drop(1)
        } else {
            listOf(resolved.path) + argv.drop(1)
        }
        val raw: RawOutput = try {
            effectiveRunner.run(spawnArgv, timeoutMs, ShellExecutables.CLEAN_ENV)
        } catch (e: Exception) {
            return ShellResult.Failed("spawn failed: ${e.message}")
        }
        val out = ShellPolicy.truncate(raw.stdout)
        val err = ShellPolicy.truncate(raw.stderr)
        val truncated = out.truncated || err.truncated
        if (raw.timedOut) {
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
