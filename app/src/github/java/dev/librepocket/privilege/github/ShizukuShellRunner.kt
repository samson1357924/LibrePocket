package dev.librepocket.privilege.github

import dev.librepocket.redact.Redactor
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellExecutables
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
 *
 * B2 P1-4 spawn 前取消（owner 新 P1-4）：入口＋最後授權重驗後（配額前）＋
 * 配額→spawn 窗口三點經 [ElevatedRunnerCommon.isPreSpawnCancelled] 檢查
 * （Thread 中斷不清旗標或鉤真；鉤抛即已取消，未建 fail-closed），任一命中即
 * 零 spawn 回 `TimedOut`（空 partial，`cleanupIncomplete=false`，無進程故無殘留；
 * interrupt 原樣保留，不清除，不把已取消翻成未取消）。drain 層入口清中斷＋
 * 健康收尾語義不變（drain 不吞中斷，出口恢復），runner 層已取消一律不建
 * （見 `ElevatedRunnerP2Test` 改名＋新 B2 取消測試的分工）。
 *
 * P1-2 late-spawn ownership＋P1-3 deadline preflight（PR140 Phase 2）：
 * 單一 deadline 誠實描述——Binder 已送達不可撤回，spawn 逾期只能 late
 * compensation（見 [ElevatedCallSupervisor.awaitSpawn]：owner 認領＋worker 內
 * 有界補償，逾期交付即 `TimedOut(cleanupIncomplete=true)`）。preflight 三層皆
 * 零 spawn：入口 `safeTimeoutMs<=0` 直回（不佔席不耗 quota）；quota 後提交前
 * 顯式 `isExpired`（配額已耗仍零 spawn）；supervisor 入口＋返回後雙確認。
 * [ElevatedRunnerCommon.isPreSpawnCancelled] 語義保留（不看 deadline）。
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
    private val execSearchDirs: List<String> = ShellExecutables.TRUSTED_BIN_DIRS,
    private val execAllowedRoots: List<String>? = null,
    private val execIsWritable: (Path) -> Boolean = ShellExecutables::defaultIsWritable,
    private val execResolver: ((String) -> ShellExecutables.ResolvedExec?)? = null,
    /**
     * P1-4 validate 有界化測試接縫（僅單測注入阻塞 validate 模擬 FUSE stall，
     * 見 `ElevatedRunnerPhase3HardeningTest`；生產一律預設 null，即
     * [ShellPolicy.validateElevated] 本體，同參數同語義）。
     *
     * 有界包裝（[ElevatedCallSupervisor.awaitPreSpawn]）不經本接縫區分：注入與
     * 本體一律跑同一監督＋同一逾期映射（`Expired.hasLinger` 穿透至
     * `TimedOut.cleanupIncomplete`）。
     */
    private val validateFn: ((argv: List<String>, bridge: Boolean) -> Validation)? = null,
) : BinaryElevatedRunner {

    /**
     * 文字相容殼：經 [toShellResult] 丟棄 cleanupIncomplete（[ShellResult] 無此欄位）；
     * 監測須用 runBinary（二進位 [ElevatedBinaryResult] 為準）。
     */
    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        runBinary(request, timeoutMs).toShellResult()

    override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult {
        // P1-2 不變快照（入口即凍結）：後續 validation／容量特判／spawn 一律用
        // 同一份已核驗快照，不再觸碰原始 request.argv（MutableList 在 grant／
        // bridge／status supplier 內竄改亦不影響本份，見 ElevatedExecPolicy）。
        // Phase 4（F9）：凍結失敗（併發竄改致拷貝抛）即 Denied 且零 spawn，
        // 不外抛（Denied＋審計契約：上游 ShellExecTool 仍寫一筆 DENY）。
        val snapshot = ElevatedExecPolicy.snapshot(request) ?: return ElevatedBinaryResult.Denied(
            ShellDeny.BLACKLISTED,
            "elevated snapshot failed (fail-closed, no spawn)",
        )
        val argvSnapshot: List<String> = snapshot.argv
        val reasonSnapshot: String = snapshot.reasonCode
        // V1 全域 flavor 門（owner 新 P1-1，github-only）：首位即拒，
        // grantCheck／activation／bridge／status 門／spawn 之前（flavor 為構造屬性，fail-closed 早拒）。
        if (flavor != Flavor.GITHUB) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "unsupported flavor (github-only)",
            )
        }
        // P1-4 spawn 前取消入口檢查（B2）：已取消（Thread 中斷不清旗標或
        // isCancelled 真；鉤抛即已取消，未建 fail-closed）即零 spawn 回 TimedOut，
        // 不進後續門／准入／配額／spawn；interrupt 原樣保留，不清除。
        if (ElevatedRunnerCommon.isPreSpawnCancelled(isCancelled)) {
            return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
        }
        // B3 單一 deadline（owner P1-5）：整個特權請求同一 budget（單調時鐘），
        // 後續阻塞外部回呼（授權讀取／status ping／newProcess／wait）＋drain 皆以
        // 剩餘為上界，超界即 TimedOut（零 spawn／零殘留語義同既有取消，不耗 quota）。
        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safeTimeoutMs)
        // P1-3 入口 preflight（PR140 Phase 2）：`safeTimeoutMs<=0` 即 budget 全無，
        // 不佔席不耗 quota，直回 `TimedOut(cleanupIncomplete=false)`（worker 從未啟動，
        // 無 late 缺口）。timeoutMs=0 仍可能 spawn 的舊缺口即在此封（見 Phase2 迴歸）。
        if (safeTimeoutMs <= 0L) {
            return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
        }
        // B3 cheap 准入前置（owner P1-5）：可能阻塞的外部回呼前先占全域席
        //（[tryEnterReserved] 原子；拒絕不耗 quota；阻塞體皆有界監督，stall 即殘留
        // 追蹤，呼叫方有界返回）。validate（純）亦入席內（需 bridge 快照，bridge
        // 本身阻塞，故席位須在 bridge 之前；黑名單等純拒絕亦短暫占席後經 finally 釋放）。
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
        // P1-B 門順序＋R1-B grant＋U1 執行門（B3 有界）：
        // validate → reasonCode → grantCheck → activation → bridge → status 門 → quota → spawn。
        // U1：bridge 當次 call 新鮮求值快照一次，供 validate 與橋門共用
        //（同 call 內一致；B3 有界：超界／取消即 TimedOut 零 spawn，不耗 quota）。
        val bridgeRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { bridgeGranted() }
        val bridge = when (bridgeRes) {
            is ElevatedCallSupervisor.Res.Ok -> bridgeRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "privilege bridge check failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
        }
        // P1-4 validate FS 有界化：[ShellPolicy.validateElevated] 含實體解析門
        // （逐段 lstat＋toRealPath，FUSE stall 可無界卡呼叫執行緒）。純詞法部分快
        // （µs 級）但與 FS 門同函數，整體跑等效有界監督（快路徑首 POLL 內完成，
        // 不額外調鉤，B2 計數語義不變）。Expired 即
        // `preSpawnExpired(hasLinger)`：stall 殘留 worker 可能持 FS 參照，保守
        // 穿透 hasLinger（grant／status 等非 spawn 逾期仍按既有契約 false）。
        // [validateFn] 非空即調注入（僅單測 FUSE-stall 模擬），否則走產品本體。
        val validateRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) {
            validateFn?.invoke(argvSnapshot, bridge)
                ?: ShellPolicy.validateElevated(
                    argvSnapshot,
                    privateRoot,
                    safRoots,
                    flavor,
                    bridge,
                    false,
                    execSearchDirs,
                )
        }
        val validated = when (validateRes) {
            is ElevatedCallSupervisor.Res.Ok -> validateRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "validation check failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(
                timeoutMs,
                validateRes.hasLinger,
            )
        }
        when (validated) {
            is Validation.Denied -> return ElevatedBinaryResult.Denied(validated.reason, validated.message)
            is Validation.Allowed -> Unit
        }
        if (reasonSnapshot.isBlank()) {
            return ElevatedRunnerCommon.missingReasonCode()
        }
        // P1-1 可信身分（B3 有界）：bare 經受控搜尋固定為已驗證實體，
        // 絕對 argv[0] 經同一實體驗證；失敗即 Denied 且零 spawn、不耗配額；
        // 席位已在上占，經 finally 釋放。`execResolver` 注入可阻塞，故亦跑監督。
        val resolvedRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) {
            ElevatedExecPolicy.resolveForSnapshot(
                argvSnapshot[0],
                execSearchDirs,
                execAllowedRoots,
                execIsWritable,
                execResolver,
            )
        }
        val resolvedExec = when (resolvedRes) {
            is ElevatedCallSupervisor.Res.Ok -> when (
                val outcome = resolvedRes.v
            ) {
                is ShellExecutables.ResolveOutcome.Ok -> outcome.exec
                is ShellExecutables.ResolveOutcome.Denied ->
                    return ElevatedBinaryResult.Denied(
                        ShellDeny.BLACKLISTED,
                        "untrusted executable: ${argvSnapshot[0].trim()} (reason=${outcome.reason})",
                    )
            }
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.BLACKLISTED,
                "untrusted executable: ${argvSnapshot[0].trim()} (reason=NOT_FOUND)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
        }
        // R1-B：grant 新鮮求值（B3 有界：超界／取消即 TimedOut 零 spawn，不耗 quota）。
        // Invalid 即 Denied(NOT_WHITELISTED, reason) 且零 spawn；抛異常同樣
        // fail-closed Denied 且零 spawn（不耗 quota，不打 binder IPC，不 spawn）。
        val grantRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { grantCheck() }
        val grant = when (grantRes) {
            is ElevatedCallSupervisor.Res.Ok -> grantRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "grant check failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
        }
        when (grant) {
            is GrantCheck.Valid -> Unit
            is GrantCheck.Invalid -> return ElevatedBinaryResult.Denied(ShellDeny.NOT_WHITELISTED, grant.reason)
        }
        // U1 執行門：activation 未就緒即 Denied 且零 spawn；抛異常同樣
        // fail-closed Denied 且零 spawn（B3 有界同上）。
        val activatedRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { activationCheck() }
        val activated = when (activatedRes) {
            is ElevatedCallSupervisor.Res.Ok -> activatedRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "activation check failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
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
        // B3 有界 status（owner P1-5）：`status()` 內 ping／version／permission
        // 皆為同步 Binder IPC，可無限等；跑監督 worker，超界／取消即 TimedOut
        // 零 spawn（抛異常仍折 BinderUnreachable Denied，見下；快路徑不額外調鉤）。
        val statusRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { status() }
        val curStatus = when (statusRes) {
            is ElevatedCallSupervisor.Res.Ok -> statusRes.v
            is ElevatedCallSupervisor.Res.Err -> ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN)
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
        }
        ShizukuStatusGate.denyFor(curStatus)
            ?.let { return ElevatedBinaryResult.Denied(it.reason, it.message) }
        // B3 註記：第二准入已前移至入口（單一 `tryEnterReserved`，見上）；
        // 此處不再二次准入，仍在同一 `try { ... } finally { leaveCall() }` 內。
        // （B1/B2 語義不變：快照／身分／三點取消／verify 皆保留）。
            // Phase3 spawn 前重求值（TOCTOU 收斂，B3 有界）：准入已占席但配額尚未消耗；
            // 此處重求 grant＋activation＋bridge 新鮮快照＋重估 status()，任一翻轉
            // 即按早拒同碼 Denied 且零 spawn；拒絕經 finally leaveCall 釋席，不耗配額。
            val reGrantRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { grantCheck() }
            val reGrant = when (reGrantRes) {
                is ElevatedCallSupervisor.Res.Ok -> reGrantRes.v
                is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "grant check failed (fail-closed, no spawn)",
                )
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            when (reGrant) {
                is GrantCheck.Valid -> Unit
                is GrantCheck.Invalid -> return ElevatedBinaryResult.Denied(ShellDeny.NOT_WHITELISTED, reGrant.reason)
            }
            val reActivatedRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { activationCheck() }
            val reActivated = when (reActivatedRes) {
                is ElevatedCallSupervisor.Res.Ok -> reActivatedRes.v
                is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "activation check failed (fail-closed, no spawn)",
                )
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            if (!reActivated) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "activation not ready (fail-closed, no spawn)",
                )
            }
            val reBridgeRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { bridgeGranted() }
            val reBridge = when (reBridgeRes) {
                is ElevatedCallSupervisor.Res.Ok -> reBridgeRes.v
                is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "privilege bridge check failed (fail-closed, no spawn)",
                )
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            if (!reBridge) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "privilege bridge off (fail-closed, no spawn)",
                )
            }
            // status 重估（B3 有界，同早段門語義：抛異常折 BinderUnreachable，denyFor 映射；
            // 超界／取消即 TimedOut 零 spawn）。
            val reStatusRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { status() }
            val reStatus = when (reStatusRes) {
                is ElevatedCallSupervisor.Res.Ok -> reStatusRes.v
                is ElevatedCallSupervisor.Res.Err -> ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN)
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            ShizukuStatusGate.denyFor(reStatus)
                ?.let { return ElevatedBinaryResult.Denied(it.reason, it.message) }
            // P1-1 spawn 前重驗 executable（B3 有界）：縮小驗過→spawn 窗口。
            // 同一解析再跑一次並比全字段，不一致／失敗即拒且零 spawn（配額尚未扣除）。
            val reResolvedRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) {
                ElevatedExecPolicy.resolveForSnapshot(
                    argvSnapshot[0],
                    execSearchDirs,
                    execAllowedRoots,
                    execIsWritable,
                    execResolver,
                )
            }
            val reOutcome = when (reResolvedRes) {
                is ElevatedCallSupervisor.Res.Ok -> reResolvedRes.v
                is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                    ShellDeny.BLACKLISTED,
                    "untrusted executable: ${argvSnapshot[0].trim()} (reason=NOT_FOUND)",
                )
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            val reResolved = when (reOutcome) {
                is ShellExecutables.ResolveOutcome.Ok -> reOutcome.exec
                is ShellExecutables.ResolveOutcome.Denied ->
                    return ElevatedBinaryResult.Denied(
                        ShellDeny.BLACKLISTED,
                        "untrusted executable: ${argvSnapshot[0].trim()} (reason=${reOutcome.reason})",
                    )
            }
            if (reResolved != resolvedExec) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.BLACKLISTED,
                    "untrusted executable: ${argvSnapshot[0].trim()} (reason=REVERIFY_MISMATCH)",
                )
            }
            // P1-4 spawn 前最終取消檢查：重驗後、配額前再驗一次，
            // 中途取消／超界亦零新 spawn（不耗配額；已占席由 finally 釋放）。
            if (ElevatedRunnerCommon.isPreSpawnCancelled(isCancelled)) {
                return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            if (!quota.tryAcquire()) {
                return ElevatedRunnerCommon.quotaExceeded()
            }
            // P1-4 spawn 競態收斂：配額→spawn 窗口再驗一次（配額已耗仍零 spawn）。
            if (ElevatedRunnerCommon.isPreSpawnCancelled(isCancelled)) {
                return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            // P1-3 quota 後顯式 deadline 檢查（PR140 Phase 2）：重驗→配額間 budget
            // 耗盡即零 spawn（配額已耗無 refund，fail-closed 窄競態，不建特權進程；
            // worker 從未啟動故 `hasLinger=false`）。[isPreSpawnCancelled] 語義保留
            // （不看 deadline），此處為真正的提交前最後一門。
            if (ElevatedCallSupervisor.isExpired(deadlineNanos)) {
                return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
            }
            // 遠端建子進程（B3 有界，owner P1-5）：`newProcess` 同步 Binder 可無限等；
            // 跑監督 worker，超界／取消即 TimedOut（配額已耗仍零 handoff，
            // `cleanupIncomplete` 由 `Expired.hasLinger` 穿透）。
            // P1-2 late-spawn ownership（PR140 Phase 2）：一律經 [awaitSpawn]
            // （owner 認領＋worker 內有界補償，不再經 holder）；逾期交付的 Process
            // 由 worker／`elevated-late-spawn` daemon 補償（killer＋1s reap＋關流），
            // 本次即 `TimedOut(cleanupIncomplete=true)`（Binder 已送達不可撤回，
            // 不宣稱 cleaned）。
            val spawnArgv = ElevatedExecPolicy.toSpawnArgv(snapshot, resolvedExec)
            val spawnRes = ElevatedCallSupervisor.awaitSpawn(deadlineNanos, isCancelled, killer) {
                (spawn ?: ::pinnedSpawn)(spawnArgv)
            }
            val process: Process = when (spawnRes) {
                is ElevatedCallSupervisor.Res.Ok -> spawnRes.v
                is ElevatedCallSupervisor.Res.Err -> {
                    val e = spawnRes.t
                    val redacted = Redactor.redactError(e.message ?: e.javaClass.simpleName)
                    return ElevatedBinaryResult.Failed("shizuku spawn failed: $redacted")
                }
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(
                    timeoutMs,
                    spawnRes.hasLinger,
                )
            }
            // R1-A 雙軌＋U2-B stderr 獨立小 cap：截圖 stdout 即 8M 軌，stderr 維持
            // 通用軌（硬讀取 512K／截斷 64K；截圖錯誤訊息短，降 OOM 峰值）；
            // 其餘通用命令走舊 64K/512K。容量特判用同一快照（邏輯 argv），
            // 不用已固定實體路徑。
            val isScreenshot = ElevatedRunnerCommon.isScreenshotArgv(argvSnapshot)
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
            // B3 單一 deadline：drain 只用剩餘（`deadline - now`），不重取滿 `timeoutMs`。
            val drainBudgetMs = ElevatedCallSupervisor.remainingMs(deadlineNanos)
            val raw = ElevatedProcessIo.drain(process, drainBudgetMs, killer, isCancelled, hardCapBytes, hardCapErrBytes)
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
     *
     * B4 反射相容（owner P2-8）：真機經 [defaultSpawnWithDir] 反射調用
     * Shizuku 13.x 私有 `newProcess`（`getDeclaredMethod＋isAccessible`），
     * 上游弃用提醒，真機未驗。裝置矩陣驗收歸 #136-B3 E2E：授權／拒絕／
     * service 重啟／死亡／版本升級；長期路線評估 UserService（TODO #136-B3）。
     * JVM 側缺方法／版本漂移 fail-closed（反射抛即 spawn 側收斂為 Failed，
     * 零殘留，見 B4 pin），不抛。
     */
    private fun pinnedSpawn(argv: List<String>): Process = defaultSpawnWithDir(argv, privateRoot)

    companion object {
        /**
         * Phase 4（F2，PR #140）：`newProcess` 專用乾淨 env（`KEY=VALUE` 陣列形）。
         * 固定 [ShellExecutables.CLEAN_ENV]（`PATH`＋`LANG`），不繼承
         * system_server 環境（`LD_PRELOAD`／`LD_LIBRARY_PATH`／代理變數等
         * 經此消除，與直接通道 [dev.librepocket.shell.RestrictedShell]
         * 同策）。純函數，永不回 null；反射缺方法等失敗由呼叫方 spawn
         * catch 收斂為 Failed（fail-closed），不存在傳 null 繼承的路徑。
         */
        internal fun buildCleanEnv(): Array<String> =
            ShellExecutables.CLEAN_ENV.map { (k, v) -> "$k=$v" }.toTypedArray()

        /**
         * R3-B：grep 確認測試零引用，收斂為 private（僅 [pinnedSpawn]
         * 內部使用）；#136-B3 前重審（刪除或保留內部實現）。
         *
         * B4 反射相容注記（owner P2-8）：`Shizuku.newProcess` 為 13.x 私有
         * API（`getDeclaredMethod("newProcess", String[], String[], String)`＋
         * `isAccessible=true`），方法缺席／簽名漂移／版本升級即抛
         * （`NoSuchMethodException` 等），由呼叫方 spawn catch 收斂為
         * `Failed("shizuku spawn failed: ...")` 且零殘留（fail-closed，不抛；
         * 見 B4 pin）。真機相容未驗，裝置矩陣（授權／拒絕／service 重啟／
         * 死亡／版本升級）＋UserService 長期路線歸 #136-B3 E2E。
         *
         * Phase 4（F2）：env 傳 [buildCleanEnv]，永不傳 null（舊碼傳 null
         * 即繼承 system_server 環境，已修正）。
         */
        private fun defaultSpawnWithDir(argv: List<String>, dir: String?): Process {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val raw = method.invoke(null, argv.toTypedArray(), buildCleanEnv(), dir)
            return ShizukuRemoteProcess.wrap(raw)
        }
    }
}
