package dev.librepocket.privilege.github

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
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 提權子進程 IO 共用（github 源集內部）：超時讀取 + 硬上限防 OOM。
 * 截斷標記沿用 [ShellPolicy.MAX_OUTPUT_BYTES]，由各 Runner 呼叫
 * [ShellPolicy.truncate] 後判定（與 [dev.librepocket.shell.RestrictedShell]
 * 同語義）。
 */
internal object ElevatedProcessIo {
    data class Drained(
        val stdout: ByteArray,
        val stderr: ByteArray,
        val exitCode: Int,
        val timedOut: Boolean,
    )

    fun drain(process: Process, timeoutMs: Long): Drained {
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
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            runCatching { process.destroyForcibly() }
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
 * 執行順序：ShizukuBinder 可達性 → [ShellPolicy.validateElevated] 先行 →
 * 原因碼 → 配額 → 遠端建子進程 → 超時殺 → 輸出截斷。策略拒絕/配額拒絕/
 * binder 不可達一律不建遠端進程；Shizuku 不可用時回 [ShellResult.Failed]
 * 並附降級話術，呼叫方回落免 Root 路徑（卸載 Shizuku 冒烟仍過）。
 *
 * - 遠端進程經 Shizuku 13.x `newProcess(argv, env, dir)`（private，
 *   反射呼叫；binder 死亡/版本差異經 runCatching 收斂為 [ShellResult.Failed]）；
 * - 白名單外二進位僅允許走本通道（[ShellPolicy.validateElevated] 語義），
 *   黑名單（`rm -rf /` 類）即使提權仍拒；
 * - 跨域路徑需 [dev.librepocket.files.FileScope.decide] 回 needsBridge
 *   且橋接已授權（[bridgeGranted]，對應 `privilege_bridge` 開關），
 *   否則拒絕 —— 私有域/SAF 直行優先，呼叫方應優先走 SAF。
 */
class ShizukuShellRunner(
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val quota: ShellQuota = ShellQuota(),
    private val bridgeGranted: Boolean = false,
    private val flavor: Flavor = Flavor.GITHUB,
    private val pingBinder: () -> Boolean = { ShizukuProbe.ping() },
    private val spawn: (List<String>) -> Process = defaultSpawn,
) : ElevatedShellRunner {

    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
        if (!isBinderLive()) {
            return ShellResult.Failed(
                "shizuku binder unreachable; fallback to unprivileged path (NO_PRIVILEGE)",
            )
        }
        when (
            val v = ShellPolicy.validateElevated(
                request.argv,
                privateRoot,
                safRoots,
                flavor,
                bridgeGranted,
            )
        ) {
            is Validation.Denied -> return ShellResult.Denied(v.reason, v.message)
            is Validation.Allowed -> Unit
        }
        if (request.reasonCode.isBlank()) {
            return ShellResult.Denied(ShellDeny.BAD_ARGUMENT, "reasonCode required (matrix §3 audit)")
        }
        if (!quota.tryAcquire()) {
            return ShellResult.Denied(ShellDeny.QUOTA_EXCEEDED, "elevated shell quota exceeded")
        }
        val process: Process = try {
            spawn(request.argv)
        } catch (e: Exception) {
            return ShellResult.Failed("shizuku spawn failed: ${e.message}")
        }
        val raw = ElevatedProcessIo.drain(process, timeoutMs)
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

    /**
     * ShizukuBinder 存活檢查：先經 [ShizukuBinderWrapper] 包裝實體 binder
     * ping（binder 層），再經 [Shizuku.pingBinder]（服務層），任一活即視為
     * 可達；任何異常 runCatching 收斂為 false。
     */
    private fun isBinderLive(): Boolean {
        val wrapperAlive = runCatching {
            val binder = Shizuku.getBinder() ?: return@runCatching false
            ShizukuBinderWrapper(binder).pingBinder()
        }.getOrDefault(false)
        if (wrapperAlive) return true
        return runCatching { pingBinder() }.getOrDefault(false)
    }

    private fun ByteArray.toUtf8Lossy(): String = String(this, Charsets.UTF_8)

    companion object {
        /**
         * 預設遠端建子進程：Shizuku 13.x 的 `newProcess` 為 private，
         * 此處反射呼叫（argv 直達，不經 `sh -c`，不拼字串）。
         */
        val defaultSpawn: (List<String>) -> Process = { argv ->
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (method.invoke(null, argv.toTypedArray(), null, null) as Process)
        }
    }
}
