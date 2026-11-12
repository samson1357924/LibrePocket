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
import java.util.concurrent.TimeUnit

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
 * - su exit 語義：B4 前拒絕（[isSuDenied]）回 `Denied(NOT_WHITELISTED)`；
 *   B4 起（owner P2-7）`^su:` 前綴可被內層仿造，無獨立可信來源，不再斷言
 *   Denied，一律回 `Failed`（不確定失敗，含可能的真拒絕；見 [isSuDenied]
  *   B4 段與 B4 負向單測）；Phase 4（F8）不再基於 stderr 做二分級，
  *   一律單一 `Failed("su failed exit=...")`；一般非零回 `Failed`，皆不回 `Ok`
 *   （舊實現誤回 Ok 已修正）；
 *   訊息內 stderr 經 `Redactor.redactError` 收斂（保留 redacted，不洩密）；
 * - 超時/cancel 走 [ElevatedProcessKiller.killTree]（盡力整組殺，舊實現只殺 `su`
 *   本體；Android 多數退化為單殺，串流由 drain 關，見 [ElevatedProcessKiller]）＋ [isCancelled] 輪詢鉤，解除 blocking I/O；
 * - su 本體經 [SuBinaryResolver] 解析可信絕對路徑（舊碼 bare `"su"` 走 PATH，
 *   可劫持），缺席即 Failed 且零 spawn；
 * - 二進位通道：[runBinary] 以 [ByteArray] 直達，[run] 僅為文字相容殼。
 *
  * B2 失敗訊息收斂 pin（Phase3 P3 低風險，行為不變；Phase 4 F8 後單一形）：
  * 非零 exit 一律回 `Failed("su failed exit=...: $redacted")`（不再分 `possible denial`
  * 變體，見下 F8 段），`$redacted` 經
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
 * [ElevatedProcessIo.BINARY_SCREENSHOT_HARD_CAP_BYTES] 硬讀取，pipe 內保留上界 stdout 8M（非程序峰值）），
 * stderr 維持通用軌（[ShellPolicy.MAX_OUTPUT_BYTES] 64K 截斷＋
 * [ShellPolicy.HARD_READ_CAP_BYTES] 512K 硬讀取；截圖錯誤訊息短，降 OOM 峰值）；
 * pipe 保留 8M＋512K≈8.5M，非程序峰值（另含拷貝＋防禦複製，見 [ElevatedProcessIo]
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
  * Round-3 Phase E（P1-1）：未驗證 quarantine 非空即拒
  * `Denied(QUARANTINE, "elevated unverified process quarantine held...")` 且零 spawn、
  * 不耗配額（准入前先 [ElevatedCleanupTracker.kickSweepAsync] 觸發背景重掃，
  * 已驗證死亡即由背景解除；原因碼獨立，不與 backlog／breaker 混碼；
  * Round-4 Phase M＋Round-5 Phase Q：呼叫側零 IPC 永不等待＋背景 singleflight＋受監督 verifier，
  * 絕不用 `waitFor(0)` 作跨 Binder 時限保證）。
 *
 * AB1 呼叫級准入（owner 新 P1-A＋P1-B）：飽和／熔斷門之後、配額之前
 * 單一原子 [ElevatedCleanupTracker.tryEnterReserved]（同一鎖內檢查＋佔席，
 * 上限 [ElevatedCleanupTracker.MAX_CONCURRENT_CALLS]＝4，特權通道宜序列，#136-B3 可調）；
 * 滿即拒 `Denied(QUOTA_EXCEEDED, "elevated concurrency full...")` 且零 spawn、
 * 不耗配額；成功後整個剩餘 runBinary 包 `try { ... } finally { leaveCall() }`
 * （配額拒、spawn 抛、drain 全路徑釋放）。
 *
 * B2 P1-4 spawn 前取消（owner 新 P1-4）：入口＋最後授權重驗後（配額前）＋
 * 配額→spawn 窗口三點經 [ElevatedRunnerCommon.checkPreSpawnCancel] 有界檢查
 * （R2-PhaseC P2-1：hook 只跑監督 worker，呼叫側只看中斷＋deadline；
 * 中斷不清旗標或鉤真即已取消；鉤抛即已取消，未建 fail-closed），任一命中即
 * 零 spawn 回 `TimedOut`（空 partial；`cleanupIncomplete` 經
 * [ElevatedRunnerCommon.preSpawnLinger] 判定——中斷／鉤真／鉤抛即 false，
 * hook 阻塞至逾期即殘留 worker 持槽為 true，見 R3-PhaseG P2-4 契約；
 * interrupt 原樣保留，不清除，不把已取消翻成未取消）。drain 層入口清中斷＋
 * 一次性取消決策語義見 [ElevatedProcessIo.drain]（預置中斷進 drain 即受監督
 * kill，不當健康進程跑；出口恢復，不吞 caller 中斷），runner 層已取消一律不建
 * （見 `ElevatedRunnerP2Test` 改名＋新 B2 取消測試的分工）。
 *
 * P1-2 late-spawn ownership＋P1-3 deadline preflight（PR140 Phase 2）：
 * 單一 deadline 誠實描述——Binder 已送達不可撤回，spawn 逾期只能 late
 * compensation（見 [ElevatedCallSupervisor.awaitSpawn]：owner 認領＋worker 內
 * 有界補償，逾期交付即 `TimedOut(cleanupIncomplete=true)`）。preflight 三層皆
 * 零 spawn：入口 `safeTimeoutMs<=0` 直回（不佔席不耗 quota）；quota 後提交前
 * 顯式 `isExpired`（配額已耗仍零 spawn）；supervisor 入口＋返回後雙確認。
 * 取消語義（中斷不清旗標、不看 deadline）見 [ElevatedRunnerCommon.checkPreSpawnCancel]。
 *
 * R2-PhaseC P2-1 取消 hook 有界化（review P2-1）：舊直調 hook 在 deadline 前
 * 可卡 caller（blocking supplier（latch／Binder 充當 cancel）可卡
 * caller 使 timeout 永不返回（甚至 `activeCalls==0` 尚未准入）。現 deadline 先算，
 * 三門一律經 [ElevatedRunnerCommon.checkPreSpawnCancel]（hook 只跑 worker，
 * Expired→preSpawnExpired 有界回）；hook 不可阻塞契約見該函數 KDoc
 * （產品只用 `Job.isActive` 一類本地原子快取）。
 *
 * R3-PhaseF P1-2 pre-admission 有界取消：cheap [ElevatedCleanupTracker.tryEnterReserved]
 * 移到任何 worker 啟動之前（含首個 cancel 門），callback 未取 global lease
 * 絕不 `Thread.start()`（併發 caller 在准入處即序列化，飽和下零線程拒絕；
 * 拒絕不耗 quota；verdict 優先級准入拒絕先於取消判定，見 runBinary 准入段）。
 * R3-PhaseF P2-1 慢輪詢 hook 統一：一切 supervisory polling（gates 慢路徑／
 * spawn／drain）皆經 [ElevatedCallSupervisor.CancelWatch]（hook 只跑 watcher
 * worker，caller 只看中斷＋deadline＋緩存 bit，bit 單調閂）；blocked hook 只卡
 * worker，caller 經 deadline 有界返回（首 false、後 blocked 交錯全請求有界，
 * 見 `ElevatedRunnerPhaseFCancelBoundTest`）。
 *
 * R2-PhaseC P2-2 tail verdict（review P2-2）：drain tail（finished 但流未排空）
 * 期間取消／超時致 gobbler 中斷＋殘留（[ElevatedProcessIo.Drained.tailIncomplete]），
 * finished 一經 true 不變回 false——舊 runner 只依 `raw.timedOut` 分支，exit=0 但
 * 流卡住即回 `Ok(truncated=true, cleanupIncomplete=true)`，上層僅看 Ok 即宣告完成。
 * 現 would-be Ok（exit 0 且無 readFailed）升級 `TimedOut`（攜 partial＋truncated＋
 * cleanupIncomplete），不再回 Ok；`readFailed`／非零 exit 仍既有 `Failed`
 * （皆非 Ok，安全）。審計記 TIMEOUT（見 verdict 坍縮表）。
 *
 * R3-PhaseH P2-6 入口順序（PR140 Round-3）：flavor→deadline→preflight→席位→
 * （席內）取消門→（席內同 deadline 監督）argv 快照→bridge→validate→…。
 * 舊碼快照在 deadline／席位之前，直拷貝非受信任 List 即資源門外無界
 * （阻塞 iterator 卡呼叫執行緒、極大 argv OOM）；現快照在席位內經
 * [ElevatedCallSupervisor.awaitPreSpawn]（超界即 `TimedOut` 零 spawn，殘留進
 * slot／breaker 終審），上限見 [ShellPolicy.copyArgvBounded]。flavor／deadline／
 * preflight／席位本身語義不動（E/F/G 已定，只調順序：三者皆不觸碰 argv）。
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
     * 文字相容殼：經 [toShellResult] 保留 TimedOut.cleanupIncomplete（Stage 4 起穿透）；
     * Denied／Failed 無對應欄仍丟棄。監測須用 runBinary（二進位 [ElevatedBinaryResult] 為準）。
     */
    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        runBinary(request, timeoutMs).toShellResult()

    override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult {
        // V1 全域 flavor 門（owner 新 P1-1，github-only）：首位即拒，
        // grantCheck／activation／bridge／spawn 之前（flavor 為構造屬性，fail-closed 早拒）。
        // Round-3 Phase H（P2-6）：本門與 deadline／准入皆不觸碰 argv，故一律置於
        // 快照之前——argv 入站上限與凍結只在此後執行（快照在席位內、同 deadline 監督）。
        if (flavor != Flavor.GITHUB) {
            return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "unsupported flavor (github-only)",
            )
        }
        // B3 單一 deadline（owner P1-5）：整個特權請求同一 budget（單調時鐘），
        // 後續阻塞外部回呼（授權讀取／su 探測／spawn）＋有界取消門＋drain 皆以
        // 剩餘為上界，超界即 TimedOut（零 spawn／零殘留語義同既有取消，不耗 quota）。
        // R2-PhaseC P2-1：deadline 先算（純算術，非阻塞），取消門才有界可依。
        // Round-3 Phase H（P2-6）：deadline 計算本身不觸碰 argv（純算術），故置於
        // 快照之前——快照拷貝（含阻塞 iterator）納入同一 deadline 監督（見下）。
        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L)
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(safeTimeoutMs)
        // P1-3 入口 preflight（PR140 Phase 2）：`safeTimeoutMs<=0` 即 budget 全無，
        // 不佔席不耗 quota，直回 `TimedOut(cleanupIncomplete=false)`（worker 從未啟動，
        // 無 late 缺口）。timeoutMs=0 仍可能 spawn 的舊缺口即在此封（見 Phase2 迴歸）。
        if (safeTimeoutMs <= 0L) {
            return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
        }
        // R3-PhaseF P1-2 pre-admission 有界（准入先於一切 worker 啟動）：
        // cheap [tryEnterReserved] 移到任何 worker 啟動之前（含首個 cancel 門）——
        // callback 未取 global lease 絕不 `Thread.start()`：併發 caller 在准入處
        // 即序列化（乾淨態僅單一進行中呼叫，見 [ElevatedCleanupTracker]），飽和／
        // 熔斷／quarantine 下零線程拒絕（不耗 quota、不佔席外資源）。
        // verdict 優先級：准入拒絕（Denied）先於取消判定（TimedOut）——席位未取得
        // 即無門可進；sweep（[kickSweepAsync]，Round-5 Phase Q 非同步 singleflight：
        // 呼叫側只做本地快照判斷、零 IPC 永不等待 verifier，隔離非空即觸發背景重掃
        // 後直進准入；背景沿用 Phase M 受監督 verifier（內層 worker＋verify＋grace、
        // 逾期放棄保持 quarantine），caller wall 零依賴遠端，准入判定仍單一原子點）。
        // B3 cheap 准入前置（owner P1-5）：可能阻塞的外部回呼前先占全域席
        //（[tryEnterReserved] 原子：飽和／熔斷／併發預留同一鎖內檢查＋佔席），
        // 拒絕不耗 quota、不占席外資源；阻塞體皆有界監督（見下），stall 即殘留
        // 追蹤（`markLingering`＋`noteUnclean`），呼叫方有界返回（Binder 中斷不可靠）。
        // 位置取舍：validate（純）亦入席內（需 bridge 快照，bridge 本身阻塞，
        // 故席位須在 bridge 之前；黑名單等純拒絕亦短暫占席後經 finally 釋放，
        // 終態 `activeCalls==0` 不變，見 `zeroSpawnRejectionsLeaveNoActiveCall`）。
        // Round-3 Phase E（P1-1）：准入前先觸發 quarantine 背景重掃（[kickSweepAsync]：
        // 已驗證死亡即由背景解除，無需 B3 手動介入；仍存活／逾期／中斷即保持，後續准入
        // 以 QUARANTINE 拒）。重掃為背景 singleflight 受監督探測（Round-4 Phase M
        // verifier：呼叫側零 IPC 永不等待，絕不用 `waitFor(0)` 作跨 Binder 時限保證），
        // 准入判定仍單一原子點（Round-5 Phase Q：sweep 移出整體 deadline，caller wall
        // 只含本地快照＋准入，不含遠端驗證）。
        ElevatedCleanupTracker.kickSweepAsync()
        when (ElevatedCleanupTracker.tryEnterReserved()) {
            ElevatedCleanupTracker.Admission.OK -> Unit
            ElevatedCleanupTracker.Admission.BACKLOG_FULL -> {
                return ElevatedRunnerCommon.cleanupBacklogFull()
            }
            ElevatedCleanupTracker.Admission.BREAKER_OPEN -> {
                return ElevatedRunnerCommon.cleanupBreakerOpen()
            }
            ElevatedCleanupTracker.Admission.QUARANTINE -> {
                return ElevatedRunnerCommon.quarantineHeld()
            }
            ElevatedCleanupTracker.Admission.CONCURRENCY_FULL -> {
                // Phase 5 B3：分支保留不刪。現參數下實效不可達（預留檢查先拒，
                // 見 tryEnterReserved KDoc），為未來放寬 MAX／SLOTS 後保留。
                return ElevatedRunnerCommon.concurrencyFull()
            }
        }
        try {
        // P1-4 spawn 前取消入口檢查（B2＋R2-PhaseC P2-1 有界化＋R3-PhaseF P1-2 席內化）：
        // 已取消（Thread 中斷不清旗標或鉤真；鉤抛即已取消，未建 fail-closed）即零 spawn
        // 回 TimedOut；interrupt 原樣保留，不清除。
        // 有界：hook 只跑監督 worker（見 [ElevatedRunnerCommon.checkPreSpawnCancel]，
        // 呼叫側只看中斷＋deadline），blocked hook（latch／Binder stall 充當 cancel）
        // 經 Expired 有界回 preSpawnExpired，不斷 timeout 上界；席內執行（已占席，
        // 拒絕經 finally 釋席，不耗配額）。
        ElevatedRunnerCommon.checkPreSpawnCancel(timeoutMs, deadlineNanos, isCancelled)?.let { return it }
        // Round-3 Phase H（P2-6）：argv 快照在席位內、同 deadline 監督。
        // 舊碼快照在 deadline／席位之前（`ArrayList(request.argv)` 直拷貝）：自訂
        // AbstractList 的阻塞 iterator／極大 argv OOM 皆在資源門外無界。
        // 現快照經 [ElevatedCallSupervisor.awaitPreSpawn]（快路徑 µs 級，不額外調鉤，
        // B2 計數語義不變；慢／阻塞路徑超界即 `Expired`→`TimedOut` 零 spawn，殘留進
        // slot／breaker 終審）：
        // - P1-2 不變語義保留：後續 validation／容量特判／spawn 一律用同一份已核驗
        //   快照，不再觸碰原始 request.argv（MutableList 在 grant／bridge supplier 內
        //   竄改亦不影響本份，見 [ElevatedExecPolicy]）；
        // - Phase 4（F9）保留：拷貝抛（併發竄改）即 `Denied(BLACKLISTED)` 零 spawn；
        // - P2-6 入站上限（見 [ShellPolicy.copyArgvBounded]：argc≤64／單項≤4K 字元／
        //   總量≤64K＋孤立代理拒絕）超限同映 `Denied(BLACKLISTED)` 零 spawn；
        // - 已取消（首門）先於快照：已取消即 TimedOut，不為無效呼叫起快照 worker。
        val snapshotRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) {
            ElevatedExecPolicy.snapshot(request)
        }
        val snapshot = when (snapshotRes) {
            is ElevatedCallSupervisor.Res.Ok -> snapshotRes.v ?: return ElevatedBinaryResult.Denied(
                ShellDeny.BLACKLISTED,
                "elevated snapshot failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.BLACKLISTED,
                "elevated snapshot failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(
                timeoutMs,
                ElevatedRunnerCommon.preSpawnLinger(snapshotRes.hasLinger),
            )
        }
        val argvSnapshot: List<String> = snapshot.argv
        val reasonSnapshot: String = snapshot.reasonCode
        // U1：bridge 當次 call 新鮮求值快照一次，供 validate 與橋門共用
        //（同 call 內一致；B3 有界：超界／取消即 TimedOut 零 spawn，不耗 quota）。
        val bridgeRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { bridgeGranted() }
        val bridge = when (bridgeRes) {
            is ElevatedCallSupervisor.Res.Ok -> bridgeRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "privilege bridge check failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(bridgeRes.hasLinger))
        }
        // P1-4 validate FS 有界化：[ShellPolicy.validateElevated] 含實體解析門
        // （逐段 lstat＋toRealPath，FUSE stall 可無界卡呼叫執行緒）。純詞法部分快
        // （µs 級）但與 FS 門同函數，整體跑等效有界監督（快路徑首 POLL 內完成，
        // 不額外調鉤，B2 計數語義不變）。Expired 即
        // `preSpawnExpired(preSpawnLinger(...))`：stall 殘留 worker 可能持 FS 參照，
        // 保守穿透（R3-PhaseG P2-4 決策 a：`cleanupIncomplete` 涵蓋 background
        // worker，`hasLinger || outstanding>0` 即置旗，全鏈一致）。
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
                ElevatedRunnerCommon.preSpawnLinger(validateRes.hasLinger),
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
        // 席位已在上占（B3 前置），經 finally 釋放。`execResolver` 注入可阻塞，
        // 故亦跑監督（快路徑不額外調鉤，見監督 KDoc）。
        // spawn 一律用已固定目標，不再用原始 argv[0]（見 toSpawnArgv）。
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
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(resolvedRes.hasLinger))
        }
        // R1-B：grant 新鮮求值（B3 有界：超界／取消即 TimedOut 零 spawn，不耗 quota）。
        //（validate→reasonCode→grantCheck→activation→bridge→su 門→quota→spawn）。
        // Invalid 即 Denied(NOT_WHITELISTED, reason) 且零 spawn；抛異常同樣
        // fail-closed Denied 且零 spawn（不吞入 quota，不碰 su 解析/spawn）。
        val grantRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { grantCheck() }
        val grant = when (grantRes) {
            is ElevatedCallSupervisor.Res.Ok -> grantRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "grant check failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(grantRes.hasLinger))
        }
        when (grant) {
            is GrantCheck.Valid -> Unit
            is GrantCheck.Invalid -> return ElevatedBinaryResult.Denied(ShellDeny.NOT_WHITELISTED, grant.reason)
        }
        // U1 執行門：activation 未就緒即 Denied 且零 spawn；抛異常同樣
        // fail-closed Denied 且零 spawn（不碰 su 解析/quota/spawn；B3 有界同上）。
        val activatedRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { activationCheck() }
        val activated = when (activatedRes) {
            is ElevatedCallSupervisor.Res.Ok -> activatedRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                ShellDeny.NOT_WHITELISTED,
                "activation check failed (fail-closed, no spawn)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(activatedRes.hasLinger))
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
        // B3 有界 su 探測（owner P1-5）：`resolve(suExists)` 含至多 6 次文件存在性
        // 檢查，注入 `suExists` 可永久阻塞；跑監督 worker，超界／取消即 TimedOut。
        val rawSuRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) {
            suBinary ?: SuBinaryResolver.resolve(suExists)
        }
        val rawSu = when (rawSuRes) {
            is ElevatedCallSupervisor.Res.Ok -> rawSuRes.v
            is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Failed(
                "su unavailable (no trusted su binary; fallback to unprivileged path)",
            )
            is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(rawSuRes.hasLinger))
        }
        if (rawSu == null) {
            return ElevatedBinaryResult.Failed("su unavailable (no trusted su binary; fallback to unprivileged path)")
        }
        // 正規化後再判可信、再 spawn：`/system/bin/su/` 等 trailing "/" 字串層
        // 可判 trusted，但直接 exec 會 ENOTDIR；此處統一以正規化後路徑執行。
        val su = SuBinaryResolver.normalizeSuPath(rawSu)
        if (!SuBinaryResolver.isTrusted(su)) {
            return ElevatedBinaryResult.Failed("su untrusted path (fail-closed, no spawn)")
        }
            // B3 註記：第二准入已前移至入口（單一 `tryEnterReserved`，見上）；
            // 此處不再二次准入，仍在同一 `try { ... } finally { leaveCall() }` 內
            //（B1/B2 語義不變：快照／身分／三點取消／verify 皆保留）。
            // Phase3 spawn 前重求值（TOCTOU 收斂，B3 有界）：准入已占席但配額尚未消耗；
            // 此處重求 grant＋activation＋bridge 新鮮快照，任一翻轉即按早拒同碼
            // Denied 且零 spawn；拒絕經 finally leaveCall 釋席，不耗配額。
            // B3：慢門阻塞不耗配額（quota 尚未 acquire），但已占席（有限併發 1，
            // stall 即殘留追蹤，呼叫方有界返回）。
            val reGrantRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { grantCheck() }
            val reGrant = when (reGrantRes) {
                is ElevatedCallSupervisor.Res.Ok -> reGrantRes.v
                is ElevatedCallSupervisor.Res.Err -> return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "grant check failed (fail-closed, no spawn)",
                )
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(reGrantRes.hasLinger))
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
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(reActivatedRes.hasLinger))
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
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(reBridgeRes.hasLinger))
            }
            if (!reBridge) {
                return ElevatedBinaryResult.Denied(
                    ShellDeny.NOT_WHITELISTED,
                    "privilege bridge off (fail-closed, no spawn)",
                )
            }
            // su 解析到執行 TOCTOU 收斂（B3 有界）：spawn 前重做 exists＋isTrusted。
            // 僅自動解析路徑（suBinary == null）重驗存在性（suExists 翻轉即 Failed 零 spawn，
            // 可用性語義，同早段缺 su 文案）；顯式 suBinary 沿既有語義只重驗可信，
            // 不新增存在性門（既有單測顯式路徑不注存在性，保持相容）。
            // 注記：本層為字串正規化＋存在性重驗，不解 symlink、不持 O_NOFOLLOW 句柄；
            // 重驗→exec 窗口的替換仍依賴裝置側 root 方案路徑完整性（見 [SuBinaryResolver] KDoc）。
            if (suBinary == null) {
                val suAliveRes = ElevatedCallSupervisor.awaitPreSpawn(deadlineNanos, isCancelled) { suExists(su) }
                val suAlive = when (suAliveRes) {
                    is ElevatedCallSupervisor.Res.Ok -> suAliveRes.v
                    is ElevatedCallSupervisor.Res.Err -> false
                    is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(suAliveRes.hasLinger))
                }
                if (!suAlive) {
                    return ElevatedBinaryResult.Failed("su unavailable (no trusted su binary; fallback to unprivileged path)")
                }
            }
            if (!SuBinaryResolver.isTrusted(su)) {
                return ElevatedBinaryResult.Failed("su untrusted path (fail-closed, no spawn)")
            }
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
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, ElevatedRunnerCommon.preSpawnLinger(reResolvedRes.hasLinger))
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
            // P1-4 spawn 前最終取消檢查（B2＋B3＋R2-PhaseC P2-1 有界化）：重驗後、
            // 配額前再驗一次，中途取消／超界／hook 阻塞亦零新 spawn（不耗配額；
            // 已占席由 finally 釋放；interrupt 不清除；blocked hook 有界回，不多擋席位）。
            ElevatedRunnerCommon.checkPreSpawnCancel(timeoutMs, deadlineNanos, isCancelled)?.let { return it }
            if (!quota.tryAcquire()) {
                return ElevatedRunnerCommon.quotaExceeded()
            }
            // P1-4 spawn 競態收斂：配額→spawn 窗口再驗一次（配額已耗仍零 spawn，
            // 覆蓋 quota.acquire 後取消的窄競態；drain 層 isCancelled 輪詢只覆
            // 已建進程，此處覆未建；R2-PhaseC P2-1 有界化同上）。
            ElevatedRunnerCommon.checkPreSpawnCancel(timeoutMs, deadlineNanos, isCancelled)?.let { return it }
            // P1-3 quota 後顯式 deadline 檢查（PR140 Phase 2）：重驗→配額間 budget
            // 耗盡即零 spawn（配額已耗無 refund，fail-closed 窄競態，不建特權進程；
            // worker 從未啟動故 `hasLinger=false`）。取消語義（中斷不清旗標、不看 deadline）見 checkPreSpawnCancel
            // （不看 deadline），此處為真正的提交前最後一門。
            if (ElevatedCallSupervisor.isExpired(deadlineNanos)) {
                return ElevatedRunnerCommon.preSpawnExpired(timeoutMs, false)
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
            // B3 有界 spawn（owner P1-5）：`spawn`（含真機 `pinnedSpawn` 的
            // `ProcessBuilder.start()`）可阻塞；跑監督 worker，超界／取消即
            // TimedOut（配額已耗仍零 handoff，見下 late 補償；`cleanupIncomplete`
            // 由 `Expired.hasLinger` 穿透）。
            // P1-2 late-spawn ownership（PR140 Phase 2）：一律經 [awaitSpawn]
            // （owner 認領＋worker 內有界補償，不再經 holder）；逾期交付的 Process
            // 由 worker／`elevated-late-spawn` daemon 補償（killer＋1s reap＋關流），
            // 本次即 `TimedOut(cleanupIncomplete=true)`（Binder 已送達不可撤回，
            // 不宣稱 cleaned）。
            val spawnRes = ElevatedCallSupervisor.awaitSpawn(deadlineNanos, isCancelled, killer) {
                (spawn ?: ::pinnedSpawn)(wrapped)
            }
            val process: Process = when (spawnRes) {
                is ElevatedCallSupervisor.Res.Ok -> spawnRes.v
                is ElevatedCallSupervisor.Res.Err -> {
                    val e = spawnRes.t
                    val redacted = Redactor.redactError(e.message ?: e.javaClass.simpleName)
                    return ElevatedBinaryResult.Failed("su spawn failed: $redacted")
                }
                is ElevatedCallSupervisor.Res.Expired -> return ElevatedRunnerCommon.preSpawnExpired(
                    timeoutMs,
                    spawnRes.hasLinger,
                )
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
            // B3 單一 deadline：drain 只用剩餘（`deadline - now`），不重取滿 `timeoutMs`，
            // 全請求（含授權／探測／spawn）同一 budget；剩餘<=0 即 drain 首輪即超時。
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
            // R2-PhaseC P2-2 tail 取消／超時升級：finished（命令已 exit 0）但 tail
            // 未排空（取消／deadline／tail budget 致 gobbler 中斷＋殘留，
            // `Drained.tailIncomplete`）→ TimedOut（攜 partial＋truncated＋
            // cleanupIncomplete），不再回 Ok（exit=0 但流卡住不得宣告工具完成；
            // 上層僅看 Ok 即完成、`auditAndExtract` truncated-Ok→null 皆不再誤判，
            // 審計記 TIMEOUT）。`readFailed`／非零 exit 的 tail 中斷仍走既有
            // Failed（皆非 Ok，安全；只有 would-be Ok 才升級，不新增 verdict 常數）。
            if (raw.tailIncomplete && !raw.readFailed && raw.exitCode == 0) {
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
                // Phase 4（F8，PR #140）：`^su:` 前綴可被內層仿造（合併 stderr／
                // 共享 exit code 下不可區分真拒絕與內層失敗），不再基於 stderr
                // 做啟發式分級（舊 `possible denial` 變體已刪除），一律單一
                // Failed（不確定失敗；[isSuDenied] 保留供單測啟發式本身，
                // runner 不再以此分支）。
                return ElevatedBinaryResult.Failed(
                    "su failed exit=${raw.exitCode}: $redacted",
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
     * P0 cwd 釘死：真實建子進程時把工作目錄釘到 privateRoot。
     * privateRoot 為 null（未配置作用域）時維持預設 cwd，但政策層已對
     * 任何 path-like bare（含 `.`）fail-closed，殘餘僅非路徑子命令。
     *
     * Phase 4（F2 跟進，PR #140）：env 清空後只放 [ShellExecutables.CLEAN_ENV]
     * （固定 `PATH`＋`LANG`，不繼承 App 進程環境，與直接通道
     * [dev.librepocket.shell.RestrictedShell]＋Shizuku 側 [buildCleanEnv]
     * 同策；舊碼沿用 `ProcessBuilder` 預設繼承，已修正）。
     *
     * 非目錄即抛（由 [runBinary] 的 spawn catch 轉 Failed），
     * 不靜默續行：靜默回落到預設 cwd 會讓相對路徑解析到不可控目錄。
     *
     * 真機路徑（僅真機／裝置驗收調用，JVM 單測一律注入假 `spawn`，
     * 不得直調本函數；需真子進程驗收歸 #136-B3 E2E）。
     */
    private fun pinnedSpawn(argv: List<String>): Process {
        val pb = ProcessBuilder(argv)
        pb.environment().clear()
        pb.environment().putAll(ShellExecutables.CLEAN_ENV)
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
