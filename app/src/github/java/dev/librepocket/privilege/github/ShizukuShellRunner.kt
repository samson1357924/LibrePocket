package dev.librepocket.privilege.github

import dev.librepocket.redact.Redactor
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor
import rikka.shizuku.Shizuku

/**
 * Shizuku 提權執行器（S3，BACKLOG D09，矩陣 §3，僅 github 風味）。
 *
 * 執行順序：[ShellPolicy.validateElevated] 先行（[bridgeGranted] 當次求值） →
 * 原因碼 → grant 門（[grantCheck] 每次呼叫新鮮求值，見 R1-B 段）→ activation 門
 *（[activationCheck] 新鮮求值，未就緒即拒，見 U1 段）→ 橋門（[bridgeGranted]
 * 快照，未授權即拒，對一切命令生效，見 U1 段）→ [ShizukuStatusGate]
 *（B2：binder/授權/版本 typed 門，拒絕即 Denied 且零 spawn；
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
 *
 * R1-B grant 接線（Issue #136 R1-B，owner P1-3＋PocketGuard WARN grant）：
 * 每次 [runBinary] 呼叫新鮮求值 [grantCheck]（構造時只存 supplier，不快取結果；
 * 同一實例跨 call 翻轉即拒，見 runner grant 測試的 flipsAcrossCalls）。
 * [grantCheck] 為必填參數（mandatory dependency，無預設值；缺接線編譯失敗，
 * 一律零 spawn）：呼叫方必須每次供給新鮮 [GrantCheck]，產品 B3 供給
 * `{ store.check(freshProviderId, freshRevision) }`
 *（blocking 包裝由 B3 決定，runner 非 suspend 故不能直調 suspend DataStore）；
 * [GrantCheck.Invalid] 一律 `Denied(NOT_WHITELISTED, reason)` 且零 spawn，
 * [grantCheck] 抛異常同樣 fail-closed `Denied(NOT_WHITELISTED)` 且零 spawn。
 *
 * U1 執行門（owner 新 P1-1＋P1-2）：
 * 每次 [runBinary] 呼叫新鮮求值 [bridgeGranted] 與 [activationCheck]
 *（構造時只存 supplier，不快取結果；當次 call 快照一次，供
 * [ShellPolicy.validateElevated] 路徑 scope 與橋門共用，避免同 call 內
 * 翻轉造成政策與門禁不一致；同一實例跨 call 翻轉即拒，
 * 見 runner grant 測試的 bridge 翻轉用例）。
 * - [activationCheck] 預設讀 [PrivilegeActivation.ELEVATED_RUNNERS_READY]
 *   （現 `false` 即 default-deny；B3 產品傳明示 allow＋每次重驗，不得快取）；
 *   未就緒即 `Denied(NOT_WHITELISTED, "activation not ready")` 且零 spawn，
 *   抛異常同樣 fail-closed `Denied(NOT_WHITELISTED)` 且零 spawn；
 * - [bridgeGranted] 預設 `{ false }`（default-deny）；`false` 即
 *   `Denied(NOT_WHITELISTED, "privilege bridge off")` 且零 spawn，
 *   對一切命令生效（含無路徑的 `id`/`getprop`：validate 只把 bridge 傳給
 *   路徑 scope，無路徑即 Allowed，故此處顯式再擋），抛異常同樣 fail-closed
 *   且零 spawn。舊「構造固定值」限制已消除：撤銷後 fresh switch
 *   同一實例次 call 即拒。
 *
 * 測試用明示 allow fixture（見 `GrantFixtures.allowAlways`／`bridgeOn`／`activationOn`）。
 *
 * 產品接線門：[PrivilegeActivation.ELEVATED_RUNNERS_READY] 已被兩 runner 經
 * `activationCheck` 預設消費（default-deny，未就緒一律零 spawn）；
 * B3 接線時傳明示 allow＋每次重驗。
 *
 * R1-A 雙軌容量（Issue #136 R1-A，owner P1-2；U2-B 細化 stderr，owner P2）：
 * 截圖二進位（`screencap -p` / `cat /dev/graphics/fb0`，argv 精確匹配）走
 * stdout 8M 軌（[ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES] 截斷＋
 * [ElevatedProcessIo.BINARY_SCREENSHOT_HARD_CAP_BYTES] 硬讀取，OOM 上界 stdout 8M），
 * stderr 維持通用軌（[ShellPolicy.MAX_OUTPUT_BYTES] 64K 截斷＋
 * [ShellPolicy.HARD_READ_CAP_BYTES] 512K 硬讀取；截圖錯誤訊息短，降 OOM 峰值）；
 * 其餘通用命令走舊 64K/512K 軌（[ShellPolicy.MAX_OUTPUT_BYTES]／
 * [ShellPolicy.HARD_READ_CAP_BYTES]）。[BinaryElevatedRunner] 介面簽名不變。
 *
 * R3-A 取消限制（owner P2-5）：[isCancelled] 預設只看 Thread 中斷
 * （`Thread.currentThread().isInterrupted`），coroutine `Job` 取消不保證中斷
 * blocking `waitFor`；coroutine 呼叫方應顯式傳
 * `isCancelled = { !job.isActive }`（或 `!coroutineContext.isActive`）。
 */
class ShizukuShellRunner(
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val quota: ShellQuota = ShellQuota(),
    private val bridgeGranted: () -> Boolean = { false },
    private val activationCheck: () -> Boolean = { PrivilegeActivation.ELEVATED_RUNNERS_READY },
    private val flavor: Flavor = Flavor.GITHUB,
    private val pingBinder: () -> Boolean = { ShizukuProbe.ping() },
    private val spawn: ((List<String>) -> Process)? = null,
    private val status: () -> ShizukuStatus = { ShizukuStatusProbe.status(pingBinder = pingBinder) },
    private val killer: (Process) -> Unit = { ElevatedProcessKiller.killTree(it) },
    private val isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    private val grantCheck: () -> GrantCheck,
) : BinaryElevatedRunner {

    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        runBinary(request, timeoutMs).toShellResult()

    override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult {
        // P1-B 門順序＋R1-B grant＋U1 執行門：
        // validate → reasonCode → grantCheck → activation → bridge → status 門 → quota → spawn。
        // validate 先於 binder IPC（status 內 ping 最多 3 次 binder），黑名單 argv
        // 零 IPC 即拒；quota 置於門後（與 RootSuRunner 側一致），門拒絕不耗配額。
        // U1：bridge 當次 call 新鮮求值快照一次，供 validate 與橋門共用
        //（同 call 內一致；抛異常即 fail-closed 零 spawn）。
        val bridge = try {
            bridgeGranted()
        } catch (_: Exception) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "privilege bridge check failed (fail-closed, no spawn)",
            )
        }
        when (
            val v = ShellPolicy.validateElevated(
                request.argv,
                privateRoot,
                safRoots,
                flavor,
                bridge,
            )
        ) {
            is Validation.Denied -> return ElevatedBinaryResult.Denied(v.reason, v.message)
            is Validation.Allowed -> Unit
        }
        if (request.reasonCode.isBlank()) {
            return ElevatedRunnerCommon.missingReasonCode()
        }
        // R1-B：grant 新鮮求值
        //（validate→reasonCode→grantCheck→activation→bridge→status 門→quota→spawn）。
        // Invalid 即 Denied(NOT_WHITELISTED, reason) 且零 spawn；抛異常同樣
        // fail-closed Denied 且零 spawn（不耗 quota，不打 binder IPC，不 spawn）。
        val grant = try {
            grantCheck()
        } catch (_: Exception) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "grant check failed (fail-closed, no spawn)",
            )
        }
        when (grant) {
            is GrantCheck.Valid -> Unit
            is GrantCheck.Invalid -> return ElevatedBinaryResult.Denied(ShellDeny.NOT_WHITELISTED, grant.reason)
        }
        // U1 執行門：activation 未就緒即 Denied 且零 spawn；抛異常同樣
        // fail-closed Denied 且零 spawn（不打 binder IPC，不耗 quota，不 spawn）。
        val activated = try {
            activationCheck()
        } catch (_: Exception) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "activation check failed (fail-closed, no spawn)",
            )
        }
        if (!activated) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "activation not ready (fail-closed, no spawn)",
            )
        }
        // U1 橋門：bridge off 對一切命令生效（含無路徑的 id/getprop：
        // validate 只把 bridge 傳給路徑 scope，無路徑即 Allowed，故此處顯式再擋）。
        if (!bridge) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "privilege bridge off (fail-closed, no spawn)",
            )
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
        // R1-A 雙軌＋U2-B stderr 獨立小 cap：截圖 stdout 即 8M 軌，stderr 維持
        // 通用軌（硬讀取 512K／截斷 64K；截圖錯誤訊息短，降 OOM 峰值）；
        // 其餘通用命令走舊 64K/512K。
        val isScreenshot = request.argv == listOf("screencap", "-p") ||
            request.argv == listOf("cat", "/dev/graphics/fb0")
        val hardCapBytes = if (isScreenshot) {
            ElevatedProcessIo.BINARY_SCREENSHOT_HARD_CAP_BYTES
        } else {
            ShellPolicy.HARD_READ_CAP_BYTES
        }
        // U2-B：截圖 stderr 獨立小 cap（通用軌 512K）；通用命令傳 null 即同 stdout cap。
        val hardCapErrBytes: Int? = if (isScreenshot) ShellPolicy.HARD_READ_CAP_BYTES else null
        val maxBytes = if (isScreenshot) {
            ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES
        } else {
            ShellPolicy.MAX_OUTPUT_BYTES
        }
        // U2-B：stderr 截斷一律通用軌 64K（截圖亦同）。
        val errMaxBytes = ShellPolicy.MAX_OUTPUT_BYTES
        val raw = ElevatedProcessIo.drain(process, timeoutMs, killer, isCancelled, hardCapBytes, hardCapErrBytes)
        val out = ShellPolicy.truncate(raw.stdout, maxBytes)
        val err = ShellPolicy.truncate(raw.stderr, errMaxBytes)
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
         * R3-B：grep 確認測試零引用，收斂為 private（僅 [pinnedSpawn]
         * 內部使用）；B3 前重審（刪除或保留內部實現）。
         */
        private fun defaultSpawnWithDir(argv: List<String>, dir: String?): Process {
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
