package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor

/**
 * Root 提權執行器（S3，BACKLOG D09，矩陣 §3，僅 github 風味，極客可選）。
 *
 * 執行順序：[ShellPolicy.validateElevated] 先行 → 原因碼 → 配額 →
 * `su -c` argv 直達 → 超時殺 → 輸出截斷。策略拒絕/配額拒絕一律不建子進程；
 * su 不可用（二進位缺失/拒絕提權）回 [ShellResult.Failed] 並附降級話術，
 * 呼叫方回落免 Root 路徑。
 *
 * - 白名單外二進位僅允許走本提權通道（直接 exec 仍由
 *   [ShellPolicy.validate] 拒絕）；黑名單（`rm`/`dd`/`reboot`/
 *   `rm -rf /` 類）即使提權仍拒（[ShellPolicy.validateElevated] 同序先判）；
 * - 校驗對象是內層 argv（`su` 本體不在校驗內，`su -c` 只是傳送層）；
 *   內層參數已先過參數衛生（串接/重定向/子命令字元全拒），此處再對含
 *   非安全字元的參數做單引號包覆（縱深防禦，`su -c` 在裝置側必經一次
 *   字串解析，見 [run] 的傳送層註解）；
 * - 跨域路徑語義同 [ShizukuShellRunner]：需
 *   [dev.librepocket.files.FileScope.decide] 回 needsBridge 且橋接已授權
 *   （[bridgeGranted]，對應 `privilege_bridge` 開關）。
 */
class RootSuRunner(
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val quota: ShellQuota = ShellQuota(),
    private val bridgeGranted: Boolean = false,
    private val flavor: Flavor = Flavor.GITHUB,
    private val spawn: ((List<String>) -> Process)? = null,
) : ElevatedShellRunner {

    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
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
        // su -c 傳送層：內層 argv 已校驗，此處 joinToString 拼成單一
        // `-c` 字串（裝置側 `su` 必經一次字串解析，非 argv 直達；
        // 參數衛生已先拒絕串接/重定向字元，此處再經 quoteArg 包覆縱深防禦）。
        // P0 cwd 釘死：外層 ProcessBuilder 目錄釘到 privateRoot（見 pinnedSpawn），
        // 內層再 `cd <root> && exec ...` 雙保險，避免裝置側 su 重置 cwd 後
        // 相對路徑解析到不可控目錄。
        val inner = request.argv.joinToString(" ") { quoteArg(it) }
        val root = privateRoot
        val shellScript = if (root != null) {
            "cd ${quoteArg(root)} && exec $inner"
        } else {
            inner
        }
        val wrapped = listOf("su", "-c", shellScript)
        val process: Process = try {
            (spawn ?: ::pinnedSpawn)(wrapped)
        } catch (e: Exception) {
            return ShellResult.Failed("su spawn failed: ${e.message}")
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

    private fun ByteArray.toUtf8Lossy(): String = String(this, Charsets.UTF_8)

    /**
     * P0 cwd 釘死：真實建子進程時把工作目錄釘到 privateRoot。
     * privateRoot 為 null（未配置作用域）時維持預設 cwd，但政策層已對
     * 任何 path-like bare（含 `.`）fail-closed，殘餘僅非路徑子命令。
     */
    private fun pinnedSpawn(argv: List<String>): Process {
        val pb = ProcessBuilder(argv)
        val root = privateRoot
        if (root != null) {
            runCatching { pb.directory(java.io.File(root)) }
        }
        return pb.start()
    }

    companion object {
        /**
         * 單參數包覆：安全字元直行，其餘單引號包覆（`'` 內 `'\''` 轉義）。
         * 呼叫前參數衛生已拒絕串接/重定向字元，此處只處理白名單外字元
         * （傳送層縱深防禦；引號/括號等若出現在此即表示上游衛生有漏，
         * 包覆後仍可安全經一次 `su -c` 字串解析）。
         */
        fun quoteArg(arg: String): String {
            if (arg.isNotEmpty() && arg.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' || it == '/' || it == ':' || it == '=' || it == ',' }) {
                return arg
            }
            return "'" + arg.replace("'", "'\\''") + "'"
        }
    }
}
