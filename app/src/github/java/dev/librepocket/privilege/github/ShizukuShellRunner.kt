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
 * Denied（B2：舊碼回 Failed，現對齊 #136-B1 門語義），spawn 異常回
 * [ShellResult.Failed]，呼叫方回落免 Root 路徑。
 *
 * B2 失敗訊息收斂 pin（Phase3 P3 低風險，行為不變）：
 * 非零 exit 回 `Failed("shizuku command failed exit=...: $redacted")`，
 * `$redacted` 經 `Redactor.redactError` 收斂。路徑覆蓋邊界
 * （見 `Redactor.ANDROID_PATH`）：覆蓋 `/data/data|user|app|media|misc`、
 * `/storage/emulated`、`/sdcard`；不覆蓋 `/system|/vendor|/data/adb`——
 * su／Shizuku 可信路徑常量屬公開 allowlist，非秘密，原文保留可接受。
 * 保守做法：保留 redact 不改審計格式（B2 紅線），僅以本 KDoc＋
 * `ElevatedRunnerPhase3Test.redactorPathBoundaryPinsBehavior` 負向測試鎖定邊界。
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
 * 一律零 spawn）：呼叫方必須每次供給新鮮 [GrantCheck]，產品 #136-B3 供給
 * `{ store.check(freshProviderId, freshRevision) }`
 *（blocking 包裝由 #136-B3 決定，runner 非 suspend 故不能直調 suspend DataStore；
 * blocking 包裝契約——IO dispatcher 單發射讀 grant DataStore＋
 * `snapshotWithRevisionStrict` 同 emission 取 endpoint 身分／revision＋永不快取＋
 * 撕裂讀禁用——見 [ShizukuGrantStore.check] KDoc，本 runner 側只消費 supplier，
 * 不自建快取）；
 * [GrantCheck.Invalid] 一律 `Denied(NOT_WHITELISTED, reason)` 且零 spawn，
 * [grantCheck] 抛異常同樣 fail-closed `Denied(NOT_WHITELISTED)` 且零 spawn。
 * 快照語義：spawn 成功後 drain 不再重估 grant；mid-drain 撤銷不影響當次
 * （當次按進入時快照完成，次 call 才拒，見 grant 測試 mid-drain 翻轉用例）。
 *
 * Phase3 spawn 前重求值（TOCTOU 收斂，行為見 `ElevatedRunnerPhase3Test`）：
 * 准入（[ElevatedCleanupTracker.tryEnterReserved]）已占席後、配額
 * （[ShellQuota.tryAcquire]）之前、spawn 前一刻重求 `grantCheck`＋
 * `activationCheck`＋`bridgeGranted` 新鮮快照＋重估 `status()`
 *（首次 `status()` 在准入前、重估在准入後、配額在重估後——慢 IPC 阻塞不占配額，
 * 早段阻塞尚未占席，重驗阻塞已占席但配額尚未 acquire），
 * 任一翻轉即按早拒同錯誤碼 Denied 且零 spawn；
 * 拒絕經 `finally { leaveCall() }` 釋席，且配額尚未 acquire 故不耗配額。
 *
 * U1 執行門（owner 新 P1-1＋P1-2）：
 * 每次 [runBinary] 呼叫新鮮求值 [bridgeGranted] 與 [activationCheck]
 *（構造時只存 supplier，不快取結果；當次 call 快照一次，供
 * [ShellPolicy.validateElevated] 路徑 scope 與橋門共用，避免同 call 內
 * 翻轉造成政策與門禁不一致；同一實例跨 call 翻轉即拒，
 * 見 runner grant 測試的 bridge 翻轉用例）。
 * - [activationCheck] 預設讀 [PrivilegeActivation.ELEVATED_RUNNERS_READY]
 *   （現 `false` 即 default-deny；#136-B3 產品傳明示 allow＋每次重驗，不得快取）；
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
 * #136-B3 接線時傳明示 allow＋每次重驗。
 *
 * R1-A 雙軌容量（Issue #136 R1-A，owner P1-2；U2-B 細化 stderr，owner P2）：
 * 截圖二進位（`screencap -p` / `cat /dev/graphics/fb0`，argv 精確匹配）走
 * stdout 8M 軌（[ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES] 截斷＋
 * [ElevatedProcessIo.BINARY_SCREENSHOT_HARD_CAP_BYTES] 硬讀取，OOM 上界 stdout 8M），
 * stderr 維持通用軌（[ShellPolicy.MAX_OUTPUT_BYTES] 64K 截斷＋
 * [ShellPolicy.HARD_READ_CAP_BYTES] 512K 硬讀取；截圖錯誤訊息短，降 OOM 峰值）；
 * pipe 保留 8M＋512K≈8.5M，非進程峰值（另含拷貝＋防禦複製，見 [ElevatedProcessIo]
 * V2 OOM 峰值說明）；閾值恰等於硬讀上限（8M==8M）靠溢出位區分恰上限通過與超限丟棄
 * （溢出即 truncated；恰 8M 通過不截斷，見容量測試 8M exact／8M+1）；
 * 其餘通用命令走舊 64K/512K 軌（[ShellPolicy.MAX_OUTPUT_BYTES]／
 * [ShellPolicy.HARD_READ_CAP_BYTES]）。[BinaryElevatedRunner] 介面簽名不變。
 *
 * R3-A 取消限制（owner P2-5）：[isCancelled] 預設只看 Thread 中斷
 * （`Thread.currentThread().isInterrupted`），coroutine `Job` 取消不保證中斷
 * blocking `waitFor`；coroutine 呼叫方應顯式傳
 * `isCancelled = { !job.isActive }`（或 `!coroutineContext.isActive`）。
 *
 * V1 全域 flavor 門（owner 新 P1-1）：本 runner 僅 github 風味可用（github-only
 * 設計）。[runBinary] 首位即判 `flavor != GITHUB → Denied(NOT_WHITELISTED,
 * "unsupported flavor (github-only)")` 且零 spawn，位於 grantCheck／activation／
 * bridge／status 門／spawn 之前（flavor 為構造屬性，fail-closed 早拒）。
 * [ShellPolicy.validateElevated] 只把 flavor 傳給路徑 scope（FileScope），無路徑
 * 命令（`id`／`getprop`）在 PLAY／FOSS 亦 Allowed，故不能只靠 validate，須此全域門兜底。
 *
 * AA1 資源門（owner 新 P1＋P2）：[ElevatedCleanupTracker.isSaturated] 即拒
 * `Denied(QUOTA_EXCEEDED, "elevated cleanup backlog full...")` 且零 spawn
 * （與 quota 同級，位於配額門前，飽和拒絕不耗配額；飽和＝有不可回收清理，
 * #136-B3 需介入）。終審殘留第二條件：[ElevatedCleanupTracker.isBreakerOpen]
 * （連續不清創達上限）即拒 `Denied(QUOTA_EXCEEDED,
 * "elevated cleanup breaker open...")` 且零 spawn、不耗配額（訊息區分兩因）。
 * drain 層飽和仍嘗試監督清理（永不跳過 killer）；本旗標經
 * [ElevatedBinaryResult.Ok.cleanupIncomplete]／[ElevatedBinaryResult.TimedOut.cleanupIncomplete]
 * 穿透（僅監測，不改 verdict）。
 *
 * AB1 呼叫級准入（owner 新 P1-A＋P1-B）：飽和／熔斷門之後、配額之前
 * 單一原子 [ElevatedCleanupTracker.tryEnterReserved]（同一鎖內檢查＋佔席，
 * 上限 [ElevatedCleanupTracker.MAX_CONCURRENT_CALLS]＝4，特權通道宜序列，#136-B3 可調）；
 * 滿即拒 `Denied(QUOTA_EXCEEDED, "elevated concurrency full...")` 且零 spawn、
 * 不耗配額；成功後整個剩餘 runBinary 包 `try { ... } finally { leaveCall() }`
 * （配額拒、spawn 抛、drain 全路徑釋放）。
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

    /**
     * 文字相容殼：經 [toShellResult] 丟棄 cleanupIncomplete（[ShellResult] 無此欄位）；
     * 監測須用 runBinary（二進位 [ElevatedBinaryResult] 為準）。
     */
    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        runBinary(request, timeoutMs).toShellResult()

    override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult {
        // V1 全域 flavor 門（owner 新 P1-1，github-only）：首位即拒，
        // grantCheck／activation／bridge／status 門／spawn 之前（flavor 為構造屬性，fail-closed 早拒）。
        if (flavor != Flavor.GITHUB) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "unsupported flavor (github-only)",
            )
        }
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
        // AA1 resource gate＋AB1 呼叫級准入（P1-2 原子化，PR140 Phase1）：
        // 單一 [ElevatedCleanupTracker.tryEnterReserved] 原子決定（飽和／熔斷／
        // 併發預留同一鎖內檢查＋佔席，消除三段檢查 TOCTOU；3 backlog＋4 併發最壞
        // 19 槽（每 call 4 槽上界）經預留 `outstanding+(active+1)*4<=MAX` 截斷，
        // 實效接近串行，特權宜序列，#136-B3 可調 MAX／SLOTS）。
        // 拒絕原因僅供訊息區分（backlog vs breaker vs concurrency），准入決定原子。
        when (ElevatedCleanupTracker.tryEnterReserved()) {
            ElevatedCleanupTracker.Admission.OK -> Unit
            ElevatedCleanupTracker.Admission.BACKLOG_FULL -> {
                return ElevatedRunnerCommon.cleanupBacklogFull()
            }
            ElevatedCleanupTracker.Admission.BREAKER_OPEN -> {
                return ElevatedRunnerCommon.cleanupBreakerOpen()
            }
            ElevatedCleanupTracker.Admission.CONCURRENCY_FULL -> {
                return ElevatedRunnerCommon.concurrencyFull()
            }
        }
        try {
            // Phase3 spawn 前重求值（TOCTOU 收斂）：准入已占席（active+1）但配額尚未消耗；
            // 此處重求 grant＋activation＋bridge 新鮮快照＋重估 status()（首次 status
            // 在准入前、重估在准入後、配額在重估後——慢 IPC 阻塞不占配額），任一翻轉
            // 即按早拒同碼 Denied 且零 spawn；拒絕經 finally leaveCall 釋席，不耗配額
            //（quota 尚未 acquire）。
            val reGrant = try {
                grantCheck()
            } catch (_: Exception) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "grant check failed (fail-closed, no spawn)",
                )
            }
            when (reGrant) {
                is GrantCheck.Valid -> Unit
                is GrantCheck.Invalid -> return ElevatedBinaryResult.Denied(ShellDeny.NOT_WHITELISTED, reGrant.reason)
            }
            val reActivated = try {
                activationCheck()
            } catch (_: Exception) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "activation check failed (fail-closed, no spawn)",
                )
            }
            if (!reActivated) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "activation not ready (fail-closed, no spawn)",
                )
            }
            val reBridge = try {
                bridgeGranted()
            } catch (_: Exception) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "privilege bridge check failed (fail-closed, no spawn)",
                )
            }
            if (!reBridge) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "privilege bridge off (fail-closed, no spawn)",
                )
            }
            // status 重估（同早段門語義：抛異常折 BinderUnreachable，denyFor 映射；
            // 早段已求值一次，此處為 spawn 前最終快照，binder 翻轉即零 spawn 拒絕）。
            val reStatus = runCatching { status() }
                .getOrDefault(ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN))
            ShizukuStatusGate.denyFor(reStatus)
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
            val isScreenshot = ElevatedRunnerCommon.isScreenshotArgv(request.argv)
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
                    cleanupIncomplete = raw.cleanupIncomplete,
                )
            }
            // V2 gobbler 讀取失敗（owner 新 P1-2 correctness）：只有真 EOF（`read`
            // 回 -1）才算完整流；finished（非超時/取消，killer 未關流）下的讀取異常
            // 必為真實 I/O 錯誤（部分 PNG 當完整幀即虛構，故回 Failed，不回 Ok）。
            // 超時/取消路徑已先回 TimedOut，不受影響（預期 close 收斂保留）。
            if (raw.readFailed) {
                return ElevatedBinaryResult.Failed(
                    "elevated IO read failed (incomplete stream)",
                    cleanupIncomplete = raw.cleanupIncomplete,
                )
            }
            if (raw.exitCode != 0) {
                val redacted = Redactor.redactError(String(err.bytes, Charsets.UTF_8))
                return ElevatedBinaryResult.Failed(
                    "shizuku command failed exit=${raw.exitCode}: $redacted",
                    cleanupIncomplete = raw.cleanupIncomplete,
                )
            }
            return ElevatedBinaryResult.Ok(
                stdout = out.bytes,
                stderr = err.bytes,
                exitCode = raw.exitCode,
                truncated = truncated,
                cleanupIncomplete = raw.cleanupIncomplete,
            )
        } finally {
            ElevatedCleanupTracker.leaveCall()
        }
    }

    /**
     * P0 cwd 釘死：遠端建子進程時把 `dir` 釘到 privateRoot（原 `null` 會落在
     * system_server 預設 cwd，不可控）。privateRoot 為 null 時維持 null，
     * 但政策層已 fail-closed，殘餘僅非路徑子命令。
     *
     * 真機路徑（僅真機／裝置驗收調用，JVM 單測一律注入假 `spawn`，
     * 不得直調本函數；需真 Binder 驗收歸 #136-B3 E2E）。
     */
    private fun pinnedSpawn(argv: List<String>): Process = defaultSpawnWithDir(argv, privateRoot)

    companion object {
        /**
         * R3-B：grep 確認測試零引用，收斂為 private（僅 [pinnedSpawn]
         * 內部使用）；#136-B3 前重審（刪除或保留內部實現）。
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
