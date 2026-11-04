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
import java.io.File

/**
 * Root 提權執行器（S3，BACKLOG D09，矩陣 §3，僅 github 風味，極客可選）。
 *
 * 執行順序：[ShellPolicy.validateElevated] 先行 → 原因碼 → 可信 su 解析
 * （[suBinary] 非空直接用（仍須 [SuBinaryResolver.isTrusted]）；為空才走
 * [SuBinaryResolver.resolve] 以 [suExists] 做存在性檢查）→ 配額 →
 * `su -c` argv 直達 → 超時殺（盡力整組；Android 多數退化為關流+單殺，
 * 見 [ElevatedProcessKiller]） → 輸出截斷。
 * 策略拒絕/配額拒絕/su 不可用一律不建子進程；缺 su 不耗配額。
 * su 不可用（二進位缺席/拒絕提權）回
 * typed Denied/Failed 並附降級話術，呼叫方回落免 Root 路徑。
 *
 * B2 修正（Issue #136 B2）：
 * - su exit 語義：拒絕（[isSuDenied]）回 `Denied(NOT_WHITELISTED)`，
 *   一般非零回 `Failed`，皆不回 `Ok`（舊實現誤回 Ok 已修正）；
 *   訊息內 stderr 經 `Redactor.redactError` 收斂（保留 redacted，不洩密）；
 * - 超時/cancel 走 [ElevatedProcessKiller.killTree]（盡力整組殺，舊實現只殺 `su`
 *   本體；Android 多數退化為關流+單殺，見 [ElevatedProcessKiller]）＋ [isCancelled] 輪詢鉤，解除 blocking I/O；
 * - su 本體經 [SuBinaryResolver] 解析可信絕對路徑（舊碼 bare `"su"` 走 PATH，
 *   可劫持），缺席即 Failed 且零 spawn；
 * - 二進位通道：[runBinary] 以 [ByteArray] 直達，[run] 僅為文字相容殼。
 *
 * - 白名單外二進位僅允許走本提權通道（直接 exec 仍由
 *   [ShellPolicy.validate] 拒絕）；黑名單（`rm`/`dd`/`reboot`/
 *   `rm -rf /` 類）即使提權仍拒（[ShellPolicy.validateElevated] 同序先判）；
 * - 校驗對象是內層 argv（`su` 本體不在校驗內，`su -c` 只是傳送層）；
 *   內層參數已先過參數衛生（串接/重定向/子命令字元全拒），此處再對含
 *   非安全字元（見 [SAFE_ARG_CHARS]）的參數做單引號包覆（縱深防禦，
 *   `su -c` 在裝置側必經一次字串解析，見 [runBinary] 的傳送層註解）；
 * - 跨域路徑語義同 [ShizukuShellRunner]：需
 *   [dev.librepocket.files.FileScope.decide] 回 needsBridge 且橋接已授權
 *   （[bridgeGranted]，對應 `privilege_bridge` 開關）。
 *
 * 注入說明（雙注入保留，不合併，避免 API 破壞）：[suBinary] 為直接覆寫的
 * 候選 su 路徑（測試／呼叫方可釘死，仍須過可信判定），[suExists] 為解析時
 * 的存在性檢查（預設走檔案系統，單測可注入假映射）；前者管「用哪條」，
 * 後者管「哪條存在」，正交不合併。
 */
class RootSuRunner(
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val quota: ShellQuota = ShellQuota(),
    private val bridgeGranted: Boolean = false,
    private val flavor: Flavor = Flavor.GITHUB,
    private val spawn: ((List<String>) -> Process)? = null,
    private val suBinary: String? = null,
    private val suExists: (String) -> Boolean = { path -> File(path).exists() },
    private val killer: (Process) -> Unit = { ElevatedProcessKiller.killTree(it) },
    private val isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
) : ElevatedShellRunner, BinaryElevatedRunner {

    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        runBinary(request, timeoutMs).toShellResult()

    override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult {
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
        val rawSu = suBinary ?: SuBinaryResolver.resolve(suExists)
        if (rawSu == null) {
            return ElevatedBinaryResult.Failed("su unavailable (no trusted su binary; fallback to unprivileged path)")
        }
        // 正規化後再判可信、再 spawn：`/system/bin/su/` 等 trailing "/" 字串層
        // 可判 trusted，但直接 exec 會 ENOTDIR；此處統一以正規化後路徑執行。
        val su = SuBinaryResolver.normalizeSuPath(rawSu)
        if (!SuBinaryResolver.isTrusted(su)) {
            return ElevatedBinaryResult.Failed("su untrusted path (fail-closed, no spawn)")
        }
        if (!quota.tryAcquire()) {
            return ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, "elevated shell quota exceeded")
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
        val wrapped = listOf(su, "-c", shellScript)
        val process: Process = try {
            (spawn ?: ::pinnedSpawn)(wrapped)
        } catch (e: Exception) {
            val redacted = Redactor.redactError(e.message ?: e.javaClass.simpleName)
            return ElevatedBinaryResult.Failed("su spawn failed: $redacted")
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
            return if (isSuDenied(err.bytes, raw.exitCode)) {
                ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "su denied exit=${raw.exitCode} (NO_PRIVILEGE; fallback to unprivileged path): $redacted",
                )
            } else {
                ElevatedBinaryResult.Failed("su failed exit=${raw.exitCode}: $redacted")
            }
        }
        return ElevatedBinaryResult.Ok(
            stdout = out.bytes,
            stderr = err.bytes,
            exitCode = raw.exitCode,
            truncated = truncated,
        )
    }

    /**
     * P0 cwd 釘死：真實建子進程時把工作目錄釘到 privateRoot。
     * privateRoot 為 null（未配置作用域）時維持預設 cwd，但政策層已對
     * 任何 path-like bare（含 `.`）fail-closed，殘餘僅非路徑子命令。
     *
     * 非目錄即抛（由 [runBinary] 的 spawn catch 轉 Failed），
     * 不靜默續行：靜默回落到預設 cwd 會讓相對路徑解析到不可控目錄。
     */
    private fun pinnedSpawn(argv: List<String>): Process {
        val pb = ProcessBuilder(argv)
        val root = privateRoot
        if (root != null) {
            val dir = java.io.File(root)
            if (!dir.isDirectory) {
                throw IllegalStateException("pinned cwd unavailable: $root")
            }
            pb.directory(dir)
        }
        return pb.start()
    }

    companion object {
        /**
         * [quoteArg] 安全字元集：此集合內字元直行，之外一律單引號包覆。
         * 行為錨定既有單測（字母數字＋`_ - . / : = ,` 直行），只做抽取不放寬。
         */
        internal val SAFE_ARG_CHARS: Set<Char> =
            buildSet {
                for (c in 'a'..'z') add(c)
                for (c in 'A'..'Z') add(c)
                for (c in '0'..'9') add(c)
                add('_')
                add('-')
                add('.')
                add('/')
                add(':')
                add('=')
                add(',')
            }

        /**
         * 單參數包覆：安全字元直行，其餘單引號包覆（`'` 內 `'\''` 轉義）。
         * 呼叫前參數衛生已拒絕串接/重定向字元，此處只處理白名單外字元
         * （傳送層縱深防禦；引號/括號等若出現在此即表示上游衛生有漏，
         * 包覆後仍可安全經一次 `su -c` 字串解析）。
         */
        fun quoteArg(arg: String): String {
            if (arg.isNotEmpty() && arg.all { it in SAFE_ARG_CHARS }) {
                return arg
            }
            return "'" + arg.replace("'", "'\\''") + "'"
        }
    }
}
