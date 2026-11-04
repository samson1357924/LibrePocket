package dev.librepocket.privilege.github

import dev.librepocket.redact.Redactor
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor
import rikka.shizuku.Shizuku

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
) : BinaryElevatedRunner {

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
            return ElevatedRunnerCommon.missingReasonCode()
        }
        ShizukuStatusGate.denyFor(runCatching { status() }.getOrDefault(ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN)))
            ?.let { return ElevatedBinaryResult.Denied(it.reason, it.message) }
        if (!quota.tryAcquire()) {
            return ElevatedRunnerCommon.quotaExceeded()
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
         *
         * 禁止產品使用（已釘死 cwd：`dir=null` 落在 system_server 預設
         * cwd，不可控；產品一律走實例 [pinnedSpawn]）；死相容殼不刪除
         * （測試/舊碼引用不斷），B3 前刪除。
         */
        @Deprecated(
            "禁止產品使用（已釘死 cwd，dir=null 不可控）；死相容殼，B3 前刪除。產品一律走實例 pinnedSpawn。",
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
