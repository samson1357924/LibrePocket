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
import java.io.File
import java.nio.file.Path

/**
 * Root 提權執行器（S3，BACKLOG D09，矩陣 §3，僅 github 風味，極客可選）。
 *
 * 執行順序：[ShellPolicy.validateElevated] 先行（[bridgeGranted] 當次求值） →
 * 原因碼 → grant 門（[grantCheck] 每次呼叫新鮮求值，見 R1-B 段）→
 * activation 門（[activationCheck] 新鮮求值，未就緒即拒，見 U1 段）→ 橋門
 *（[bridgeGranted] 快照，未授權即拒，對一切命令生效，見 U1 段）→ 可信 su 解析
 * （[suBinary] 非空直接用（仍須 [SuBinaryResolver.isTrusted]）；為空才走
 * [SuBinaryResolver.resolve] 以 [suExists] 做存在性檢查）→ 配額 →
 * `su -c` argv 直達 → 超時殺（盡力整組；Android 多數退化為單殺，
 * 串流由 drain 經已取引用關，見 [ElevatedProcessKiller]） → 輸出截斷。
 * 策略拒絕/配額拒絕/su 不可用一律不建子進程；缺 su 不耗配額。
 * su 不可用（二進位缺席/拒絕提權）回
 * typed Denied/Failed 並附降級話術，呼叫方回落免 Root 路徑。
 *
 * B2 修正（Issue #136 B2）：
 * - su exit 語義：拒絕（[isSuDenied]）回 `Denied(NOT_WHITELISTED)`，
 *   一般非零回 `Failed`，皆不回 `Ok`（舊實現誤回 Ok 已修正）；
 *   訊息內 stderr 經 `Redactor.redactError` 收斂（保留 redacted，不洩密）；
 * - 超時/cancel 走 [ElevatedProcessKiller.killTree]（盡力整組殺，舊實現只殺 `su`
 *   本體；Android 多數退化為單殺，串流由 drain 關，見 [ElevatedProcessKiller]）＋ [isCancelled] 輪詢鉤，解除 blocking I/O；
 * - su 本體經 [SuBinaryResolver] 解析可信絕對路徑（舊碼 bare `"su"` 走 PATH，
 *   可劫持），缺席即 Failed 且零 spawn；
 * - 二進位通道：[runBinary] 以 [ByteArray] 直達，[run] 僅為文字相容殼。
 *
 * B2 失敗訊息收斂 pin（Phase3 P3 低風險，行為不變）：
 * 非零 exit 回 `Failed("su failed exit=...: $redacted")`／拒絕回
 * `Denied(NOT_WHITELISTED, "su denied ...: $redacted")`，`$redacted` 經
 * `Redactor.redactError` 收斂。路徑覆蓋邊界（見 `Redactor.ANDROID_PATH`）：
 * 覆蓋 `/data/data|user|app|media|misc`、`/storage/emulated`、`/sdcard`；
 * 不覆蓋 `/system|/vendor|/data/adb`——su 可信路徑常量屬公開 allowlist
 *（見 [RootSuProbe.SU_BINARIES]），非秘密，原文保留可接受。
 * 保守做法：保留 redact 不改審計格式（B2 紅線），僅以本 KDoc＋
 * `ElevatedRunnerPhase3Test.redactorPathBoundaryPinsBehavior` 負向測試鎖定邊界。
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
 * R2-A 可驗證化（owner P1-1）：`su -c` 拉起的子孫經同一
 * [ElevatedProcessKiller.killTree] 清理（宣告類查表，JVM 真子進程已驗，
 * 見 `ElevatedProcessKillerTest`）；裝置側 SELinux/Root 語義差異 E2E 為 #136-B3。
 *
 * 注入說明（雙注入保留，不合併，避免 API 破壞）：[suBinary] 為直接覆寫的
 * 候選 su 路徑（測試／呼叫方可釘死，仍須過可信判定），[suExists] 為解析時
 * 的存在性檢查（預設走檔案系統，單測可注入假映射）；前者管「用哪條」，
 * 後者管「哪條存在」，正交不合併。
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
 * `activationCheck`＋`bridgeGranted` 新鮮快照（Shizuku 側另重估 `status()`，
 * 見 [ShizukuShellRunner]），任一翻轉即按早拒同錯誤碼 Denied 且零 spawn；
 * 拒絕經 `finally { leaveCall() }` 釋席，且配額尚未 acquire 故不耗配額。
 * 位置取舍：首次 su `exists` 在准入前（早段自動解析缺席即 Failed），重驗在准入後、
 * 配額之前——慢門阻塞不占配額（早段阻塞尚未占席，重驗阻塞已占席但配額尚未 acquire），
 * 拒絕亦不耗配額；已占席由 finally 釋放。
 * su 解析到執行 TOCTOU 另在此同點重做 `exists`＋`isTrusted`（resolve 後路徑，
 * 僅自動解析路徑；顯式 [suBinary] 沿既有語義只重驗可信，不新增存在性門），
 * 翻轉即 `Failed("su unavailable...")` 且零 spawn（可用性語義，同早段缺 su）。
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
 * bridge／spawn 之前（flavor 為構造屬性，fail-closed 早拒）。
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
class RootSuRunner(
    private val privateRoot: String? = null,
    private val safRoots: List<String> = emptyList(),
    private val quota: ShellQuota = ShellQuota(),
    private val bridgeGranted: () -> Boolean = { false },
    private val activationCheck: () -> Boolean = { PrivilegeActivation.ELEVATED_RUNNERS_READY },
    private val flavor: Flavor = Flavor.GITHUB,
    private val spawn: ((List<String>) -> Process)? = null,
    private val suBinary: String? = null,
    private val suExists: (String) -> Boolean = { path -> File(path).exists() },
    private val killer: (Process) -> Unit = { ElevatedProcessKiller.killTree(it) },
    private val isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    private val grantCheck: () -> GrantCheck,
    private val execSearchDirs: List<String> = ShellExecutables.TRUSTED_BIN_DIRS,
    private val execAllowedRoots: List<String>? = null,
    private val execIsWritable: (Path) -> Boolean = ShellExecutables::defaultIsWritable,
    private val execResolver: ((String) -> ShellExecutables.ResolvedExec?)? = null,
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
        val snapshot = ElevatedExecPolicy.snapshot(request)
        val argvSnapshot: List<String> = snapshot.argv
        val reasonSnapshot: String = snapshot.reasonCode
        // V1 全域 flavor 門（owner 新 P1-1，github-only）：首位即拒，
        // grantCheck／activation／bridge／spawn 之前（flavor 為構造屬性，fail-closed 早拒）。
        if (flavor != Flavor.GITHUB) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "unsupported flavor (github-only)",
            )
        }
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
                argvSnapshot,
                privateRoot,
                safRoots,
                flavor,
                bridge,
                false,
                execSearchDirs,
            )
        ) {
            is Validation.Denied -> return ElevatedBinaryResult.Denied(v.reason, v.message)
            is Validation.Allowed -> Unit
        }
        if (reasonSnapshot.isBlank()) {
            return ElevatedRunnerCommon.missingReasonCode()
        }
        // P1-1 可信身分（提權與直接同等）：bare 經受控搜尋固定為已驗證實體，
        // 絕對 argv[0] 經同一實體驗證（containment＋writability＋entry 閘）；
        // 失敗即 Denied 且零 spawn、不耗配額、不占席（准入之前）。
        // spawn 一律用已固定目標，不再用原始 argv[0]（見 toSpawnArgv）。
        val resolvedExec = when (
            val outcome = ElevatedExecPolicy.resolveForSnapshot(
                argvSnapshot[0],
                execSearchDirs,
                execAllowedRoots,
                execIsWritable,
                execResolver,
            )
        ) {
            is ShellExecutables.ResolveOutcome.Ok -> outcome.exec
            is ShellExecutables.ResolveOutcome.Denied ->
                return ElevatedBinaryResult.Denied(
                    ShellDeny.BLACKLISTED,
                    "untrusted executable: ${argvSnapshot[0].trim()} (reason=${outcome.reason})",
                )
        }
        // R1-B：grant 新鮮求值
        //（validate→reasonCode→grantCheck→activation→bridge→su 門→quota→spawn）。
        // Invalid 即 Denied(NOT_WHITELISTED, reason) 且零 spawn；抛異常同樣
        // fail-closed Denied 且零 spawn（不吞入 quota，不碰 su 解析/spawn）。
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
        // fail-closed Denied 且零 spawn（不碰 su 解析/quota/spawn）。
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
            // 此處重求 grant＋activation＋bridge 新鮮快照，任一翻轉即按早拒同碼
            // Denied 且零 spawn；拒絕經 finally leaveCall 釋席，不耗配額
            //（quota 尚未 acquire）。早段 suExists 在准入前、此處重驗在准入後、
            // 配額在重驗後，故慢門阻塞不耗配額（見本 KDoc 位置取舍）。
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
            // su 解析到執行 TOCTOU 收斂：spawn 前重做 exists＋isTrusted（resolve 後路徑）。
            // 僅自動解析路徑（suBinary == null）重驗存在性（suExists 翻轉即 Failed 零 spawn，
            // 可用性語義，同早段缺 su 文案）；顯式 suBinary 沿既有語義只重驗可信，
            // 不新增存在性門（既有單測顯式路徑不注存在性，保持相容）。
            // 注記：本層為字串正規化＋存在性重驗，不解 symlink、不持 O_NOFOLLOW 句柄；
            // 重驗→exec 窗口的替換仍依賴裝置側 root 方案路徑完整性（見 [SuBinaryResolver] KDoc）。
            if (suBinary == null) {
                val suAlive = try {
                    suExists(su)
                } catch (_: Exception) {
                    false
                }
                if (!suAlive) {
                    return ElevatedBinaryResult.Failed("su unavailable (no trusted su binary; fallback to unprivileged path)")
                }
            }
            if (!SuBinaryResolver.isTrusted(su)) {
                return ElevatedBinaryResult.Failed("su untrusted path (fail-closed, no spawn)")
            }
            // P1-1 spawn 前重驗 executable（縮小驗過→spawn 窗口，與直接通道同策）：
            // 同一解析再跑一次並比全字段，不一致／失敗即拒且零 spawn（配額尚未扣除）。
            val reResolved = when (
                val outcome = ElevatedExecPolicy.resolveForSnapshot(
                    argvSnapshot[0],
                    execSearchDirs,
                    execAllowedRoots,
                    execIsWritable,
                    execResolver,
                )
            ) {
                is ShellExecutables.ResolveOutcome.Ok -> outcome.exec
                is ShellExecutables.ResolveOutcome.Denied ->
                    return ElevatedBinaryResult.Denied(
                        ShellDeny.BLACKLISTED,
                        "untrusted executable: ${argvSnapshot[0].trim()} (reason=${outcome.reason})",
                    )
            }
            if (reResolved != resolvedExec) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.BLACKLISTED,
                    "untrusted executable: ${argvSnapshot[0].trim()} (reason=REVERIFY_MISMATCH)",
                )
            }
            if (!quota.tryAcquire()) {
                return ElevatedRunnerCommon.quotaExceeded()
            }
            // su -c 傳送層：內層為已固定目標（[ElevatedExecPolicy.toSpawnArgv]，
            // 不再用原始 argv[0]），此處 joinToString 拼成單一 `-c` 字串
            //（裝置側 `su` 必經一次字串解析，非 argv 直達；
            // 參數衛生已先拒絕串接/重定向字元，此處再經 quoteArg 包覆縱深防禦）。
            // P0 cwd 釘死：外層 ProcessBuilder 目錄釘到 privateRoot（見 pinnedSpawn），
            // 內層再 `cd <root> && exec ...` 雙保險，避免裝置側 su 重置 cwd 後
            // 相對路徑解析到不可控目錄。
            val spawnInner = ElevatedExecPolicy.toSpawnArgv(snapshot, resolvedExec)
            val inner = spawnInner.joinToString(" ") { quoteArg(it) }
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
            // R1-A 雙軌＋U2-B stderr 獨立小 cap：截圖 stdout 即 8M 軌，stderr 維持
            // 通用軌（硬讀取 512K／截斷 64K；截圖錯誤訊息短，降 OOM 峰值）；
            // 其餘通用命令走舊 64K/512K。容量特判用同一快照（邏輯 argv），
            // 不用已固定實體路徑（`[real, ...]` 不再精確等於 `["screencap","-p"]`）。
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
                return if (isSuDenied(err.bytes, raw.exitCode)) {
                    ElevatedBinaryResult.Denied(
                        ShellDeny.NOT_WHITELISTED,
                        "su denied exit=${raw.exitCode} (NO_PRIVILEGE; fallback to unprivileged path): $redacted",
                        cleanupIncomplete = raw.cleanupIncomplete,
                    )
                } else {
                    ElevatedBinaryResult.Failed(
                        "su failed exit=${raw.exitCode}: $redacted",
                        cleanupIncomplete = raw.cleanupIncomplete,
                    )
                }
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
     * P0 cwd 釘死：真實建子進程時把工作目錄釘到 privateRoot。
     * privateRoot 為 null（未配置作用域）時維持預設 cwd，但政策層已對
     * 任何 path-like bare（含 `.`）fail-closed，殘餘僅非路徑子命令。
     *
     * 非目錄即抛（由 [runBinary] 的 spawn catch 轉 Failed），
     * 不靜默續行：靜默回落到預設 cwd 會讓相對路徑解析到不可控目錄。
     *
     * 真機路徑（僅真機／裝置驗收調用，JVM 單測一律注入假 `spawn`，
     * 不得直調本函數；需真子進程驗收歸 #136-B3 E2E）。
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
         *
         * P2 可見性：同包測試直接調用，故為 internal（不跨包暴露）。
         */
        internal fun quoteArg(arg: String): String {
            if (arg.isNotEmpty() && arg.all { it in SAFE_ARG_CHARS }) {
                return arg
            }
            return "'" + arg.replace("'", "'\\''") + "'"
        }
    }
}
