package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * 子進程跑道抽象：預設走真實 [ProcessBuilder]，單測可注入假實現，
 * 讓「輸出截斷」等斷言不依賴宿主機二進位。
 */
interface ProcessRunner {
    fun run(argv: List<String>, timeoutMs: Long): RawOutput
}

/** 子進程原始回執：輸出為位元組（截斷前），[timedOut] 表示超時已被殺。 */
data class RawOutput(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int,
    val timedOut: Boolean,
)

/** 真實子進程實現：argv 直達 exec（無 shell），超時 [destroyForcibly]。 */
class DefaultProcessRunner : ProcessRunner {
    override fun run(argv: List<String>, timeoutMs: Long): RawOutput {
        val process = ProcessBuilder(argv)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
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
 * 執行順序：[ShellPolicy.validate] → 配額 → 建子進程 → 超時殺 → 輸出截斷。
 * 策略拒絕與配額拒絕一律不建子進程；提權執行不在此類（見 [ElevatedShellRunner]）。
 *
 * 檔案域：[privateRoot] 為 App 私有域根（例 `context.filesDir.absolutePath`），
 * 檔案參數凡絕對路徑一律先過 `FileScope.decide`（見 [ShellPolicy.validate]）。
 * 未配置（null，預設）時任何絕對路徑一律拒絕（fail-closed）；play 跨域拒絕，
 * full 跨域即使橋接已授權，直接 exec 仍拒絕（需改走 D09 橋）。
 */
class RestrictedShell(
    private val quota: ShellQuota = ShellQuota(),
    private val runner: ProcessRunner = DefaultProcessRunner(),
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val flavor: Flavor = Flavor.PLAY,
    private val bridgeGranted: Boolean = false,
) {
    fun execute(argv: List<String>, timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS): ShellResult {
        when (
            val v = ShellPolicy.validate(argv, privateRoot, safRoots, flavor, bridgeGranted)
        ) {
            is Validation.Denied -> return ShellResult.Denied(v.reason, v.message)
            is Validation.Allowed -> Unit
        }
        if (!quota.tryAcquire()) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "shell quota exceeded")
        }
        val raw: RawOutput = try {
            runner.run(argv, timeoutMs)
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
