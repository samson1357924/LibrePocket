package dev.librepocket.privilege.github

import androidx.annotation.VisibleForTesting
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

/**
 * 提權子進程 IO 共用（github 源集內部）：超時讀取 + 硬上限防 OOM。
 * 截斷標記沿用 [ShellPolicy.MAX_OUTPUT_BYTES]，由各 Runner 呼叫
 * [ShellPolicy.truncate] 後判定（與 [dev.librepocket.shell.RestrictedShell]
 * 同語義）。
 *
 * B2 修正（Issue #136 B2）：
 * - 超時/cancel 走 [ElevatedProcessKiller.killTree]（盡力整組，舊碼只殺本體）；
 * - 殺後關流以解除 gobbler 的 blocking I/O（舊碼流殘留可卡住 join）；
 * - 輪詢式等待（50ms 切片）＋ [isCancelled] 鉤：外部取消（Thread 中斷或
 *   外部旗標）即盡力殺整組並帶 partial 回，不卡在 `waitFor(timeout)` 內；
 * - [killer]/[isCancelled] 可注入，JVM 單測以假進程斷言「超時/cancel 必殺」。
 *
 * R3-A 取消限制（owner P2-5）：預設 [isCancelled] 只看 Thread 中斷
 * （`Thread.currentThread().isInterrupted`），coroutine `Job` 取消不保證中斷
 * blocking `waitFor`；coroutine 呼叫方應顯式傳
 * `isCancelled = { !job.isActive }`（或 `!coroutineContext.isActive`），
 * 否則取消可能要等到 timeout 才收斂。抛異常的鉤一律折為未取消
 * （fail-closed：不斷流、不誤殺，見 drain 內 `getOrDefault(false)`）。
 *
 * P1-B review（成功路徑有界等待，超界即 truncated）：
 * - 成功路徑亦有界等待（雙流共享 `FINISHED_TAIL_BUDGET_MS` 2s，見 [drain]）：
 *   `finished==true` 後若 gobbler 流永不 EOF（遠端流未關），有界 tail 超界即停
 *   並經 [Drained.truncated] 標截斷（絕不靜默短讀不標）；
 * - 任何路徑 drain 總耗時有上界（timeoutMs + 常數，kill 路徑 ≈+19.5s／finished
 *   路徑 ≈+6.5s，見 `CLEANUP_JOIN_MS` KDoc 公式），不無限卡。
 *
 * P2-B 抽檔（行為零變更）：原內嵌於 `ShizukuShellRunner.kt`，搬至本檔；
 * [ShizukuShellRunner]/[RootSuRunner] 同包直接引用，免 import。
 *
 * R1-A 雙軌容量（Issue #136 R1-A，owner P1-2；U2-B 細化 stderr，owner P2）：
 * - 通用命令：截斷標記沿用 [ShellPolicy.MAX_OUTPUT_BYTES]（64K），硬讀取上限
 *   沿用 [ShellPolicy.HARD_READ_CAP_BYTES]（512K），OOM 有界（每流 512K）；
 * - 截圖二進位（`screencap -p` / `cat /dev/graphics/fb0`）：stdout 截斷上限
 *   [BINARY_SCREENSHOT_MAX_BYTES]（8M），硬讀取上限
 *   [BINARY_SCREENSHOT_HARD_CAP_BYTES]（8M），OOM 上界（stdout 8M）；
 *   stderr 維持通用軌（硬讀取 512K／截斷 64K）——截圖錯誤訊息短，
 *   不需 8M，降 OOM 峰值（雙流並發 8M＋512K，而非 8M＋8M）；
 * - 雙軌由各 Runner 以 argv 精確匹配判定（見兩 Runner 的 `isScreenshot`），
 *   本檔只提供可選參數 `hardCapBytes`（預設 512K，截圖 stdout 傳 8M）與
 *   `hardCapErrBytes`（預設 null＝同 stdout cap；截圖傳 512K）及截斷常數，
 *   不做 argv 判定；[Drained] 結構不變（仍為 stdout/stderr/exitCode/
 *   timedOut/truncated，`truncated` 另含硬上限溢出標記，見 [ElevatedCappedPipe]）。
 *
 * R3-B（PocketGuard SUGGESTION，ExecutorService）：維持現狀 —— 固定 2 daemon
 * threads＋有界 join 已防洩漏/hang，ExecutorService 為未來選項（#136-B3）。
 *
 * S2-B 容量矩陣（owner P2-4，僅 KDoc 文件化，行為零變更）：
 * - 典型 PNG 截圖 1-3MB：完整通過（<8M，Ok 非 truncated，capture 可摘要）；
 * - 600KB fb0：完整通過（見容量測試 600KB 雙 runner 通過）；
 * - 未壓縮 RGBA 1080x2400x4 ≈10.37MB：明確不支援（>8M 即 truncated→null，
 *   呼叫方降級手動截圖；截斷幀摘要會虛構完整截圖，故不摘要部分幀）；
 * - 高熵 PNG 超限同理：>8M 即 truncated→null，不分內容熵；
 * - 裝置容量驗收歸 #136-B3 E2E（見 [PrivilegeActivation]：`ELEVATED_RUNNERS_READY`
 *   翻 true 前需裝置驗收；JVM 單測僅以假位元組斷言容量邊界，不代表真機幀大小）。
 *
 * U2-B stderr 獨立小 cap（owner P2，僅 KDoc＋可選參數，通用軌行為零變更）：
 * - 截圖軌 stdout 8M＋stderr 通用軌（硬讀取 512K／截斷 64K）：截圖錯誤訊息短
 *   （exit 非零文案／warn 行），stderr 不需 8M；雙流 ElevatedCappedPipe 同時保留量由
 *   16M 降至 8.5M（僅 pipe 內保留，不等於程序峰值）；
 * - 通用軌不變（雙流 512K／截斷 64K，`hardCapErrBytes` 傳 null 即同 stdout cap）；
 * - 判定：任一流溢出即 [Drained.truncated]（stdout 超 8M 丟棄與 stderr 超 512K
 *   丟棄皆經溢出位帶出，不靜默短讀；見容量測試截圖＋1MB stderr 用例）。
 *
 * V2 OOM 峰值誠實說明（owner P2 memory）：cap 相加（截圖 8M＋512K ≈8.5M）
 * 僅是 [ElevatedCappedPipe] 同時保留量，不是程序峰值。實際峰值另含 `bytes()` 的
 * `toByteArray` 拷貝＋[ElevatedBinaryResult.Ok] 防禦複製（構造 `copyOf`＋取值
 * `copyOf`）＋capture 側 `copyOf`，約為 pipe 保留量的數倍。低 RAM 裝置實際
 * 峰值由 #136-B3 heap profile 驗收，本檔不承諾 8.5MiB 程序峰值。
 *
 * X1 有界 acquisition（owner 新 P1 lifecycle，W1 引入的 timeout 繞過）：
 * W1 在呼叫執行緒先取雙流（L177-203）→ Shizuku getter 為同步 Binder IPC，
 * stall 即呼叫執行緒卡死：deadline（L215）與首次 isCancelled（L218）永不進入；
 * kill 路徑重調 `process.inputStream/errorStream.close()` 同樣觸阻塞 getter。
 * 現：
 * - deadline 移到 drain 入口第一行（outputStream 關閉之前），全部開銷計入同一 budget；
 * - outputStream 起手關改 Y1 有界化（見下；X1 的同步 runCatching 已取代：同步關在
 *   getter/close stall 時仍卡死呼叫執行緒，binder 死亡門只保 fail-closed 不保有界）；
 * - getter 移回 gobbler 內、整段 try/catch（throw 即 markFailed，W1 語義保留，
 *   無未捕捉例外；block 則 caller 不受影響照常進 deadline/poll/kill）；
 * - kill 路徑改關已取流（不再重調 getter，根除 kill 路徑阻塞），kill 前先
 *   `tOut.interrupt()`／`tErr.interrupt()`（best-effort 助可中斷 stalls 脫離，無害）。
 *
 * Z1 監督清理現狀（owner 新 P1＋P2＋P3；W1 caller 取流舊描述已刪除）：
 * - getter 在 gobbler 內取值、整段 try/catch（throw 即 markFailed，W1 語義保留，
 *   無未捕捉例外；block 則 caller 不受影響照常進 deadline/poll/kill）；
 * - kill 路徑清理監督化：`killer`＋已取流 close 包入單一 daemon worker
 *   [runCleanupBounded]（上界 `min(max(remaining,CLEANUP_MIN_MS),CLEANUP_JOIN_MS)`，
 *   超界 interrupt＋[CLEANUP_GRACE_MS] grace 重 join；永不跳過 killer 嘗試），
 *   未完成即 [Drained.cleanupIncomplete]=true（不可中斷 Binder stall 殘留 daemon
 *   由文件揭露＋旗標帶出，不靜默）；
 * - stdin-closer 生命週期收斂：drain 結尾（兩路徑 gobbler join 後）仍存活即
 *   interrupt＋grace 重 join，仍存活即 cleanupIncomplete=true；kill 路徑順序為
 *   interrupt stdinCloser → 監督清理 → joins；
 * - runner verdict 映射不變：timedOut 仍 TimedOut，旗標僅供 #136-B3 監測可觀測。
 *
 * Y1 三入口＋CappedPipe 備註（owner 新 P1 兩入口＋P2；PocketGuard CappedPipe 可測性）：
 * - killer 去串流關閉：[ElevatedProcessKiller.killTree] 刪除
 *   `inputStream/errorStream/outputStream.close()` 三行（getter 先求值即阻塞），
 *   只留子孫列舉＋destroy/destroyForcibly（全 runCatching）。串流關閉是 drain 職責
 *   （kill 路徑關已取引用，不重調 getter），呼叫點僅 drain 預設值＋單測；
 * - stdin 關有界化：起手 `outputStream.close()` 改 daemon thread
 *  （`elevated-stdin-close`）執行＋主執行緒 join（上界 `min(剩餘 budget,
 *   STDIN_CLOSE_MAX_MS=2s)`，計入同一 deadline）；超界即放行繼續（best-effort，
 *   後續 killer destroy 兜底）。舊同步關在 getter/close stall 時無界卡死；
 * - finished 路徑 tail 有界（P1-4）：雙 gobbler joins 共享
 *   `min(剩餘 deadline, FINISHED_TAIL_BUDGET_MS=2s)`，每片查 isCancelled／中斷／
 *   deadline（命中即 interrupt＋記槽＋雙旗＋break）；舊兩處 SUCCESS_JOIN（10s）
 *   無 remaining／cancel 輪詢可釘 20s＋，現封頂 2s。首段超界即記
 *   不完整（grace 不清除標記）。只影響卡死執行緒（tail 內完成即無 interrupt）；
 * - [ElevatedCappedPipe]：原內嵌 `private CappedPipe` 更名提升為同檔 internal，
 *   行為零變更（drain 改用），僅為隔離可測（見 `ElevatedCappedPipeTest`：溢出→
 *   didOverflow、截斷語義、failed 單調、bytes 拷貝）。
 *
 * AA1 跨呼叫清理監督（owner 新 P1＋P2；PocketGuard 無動作項）：
 * 單次 drain 有界已由 Z1 完成，但每次 timeout 建新 daemon worker、不可中斷 stall
 * 永久殘留且無總量上限（quota 只限速率）。現 [ElevatedCleanupTracker] 跨呼叫計數
 * 未完成清理（雙 runner 共用；正常完成即釋放），飽和即兩 runner 入口拒絕新 spawn
 * （fail-closed），drain 層仍嘗試監督清理（永不跳過 killer）；旗標經二進位結果
 * cleanupIncomplete 穿透至 runner 層（legacy String 路徑不攜，二進位為準）。
 *
 * AB1 呼叫級准入＋殘留計數補齊（owner 新 P1-A＋P1-B；PocketGuard 多為過時項，
 * 核實後只補真正缺的）：
 * - 併發上限 4（[ElevatedCleanupTracker.MAX_CONCURRENT_CALLS]，
 *   單一原子 [ElevatedCleanupTracker.tryEnterReserved] 同一鎖內檢查＋佔席
 *   （飽和／熔斷／併發預留三條件，不做三段 CAS）；
 *   兩 runner 在飽和／熔斷門之後、配額之前准入，拒絕即
 *   `Denied(QUOTA_EXCEEDED, "elevated concurrency full...")` 且零 spawn、不耗配額；
 *   特權通道宜序列，#136-B3 可調本常數）；
 * - 殘留 gobbler／stdin-closer 計數（啟動不預佔；join 超界仍存活即 interrupt＋grace，
 *   仍存活即 [ElevatedCleanupTracker.markLingering]：每執行緒三態
 *   [AtomicInteger]（0=init／1=held／2=done）＋僅未計數時佔槽，
 *   執行緒本體 finally 釋放；清理 worker 維持啟動佔／
 *   finally 釋，不變）；
 * - linger 成功走 [ElevatedCleanupTracker.noteUnclean]（finished／kill 統一終審
 *   `if (cleanupIncomplete) noteUnclean() else if (outstanding()==0) noteClean()`；
 *   cleanupIncomplete 含殘留 gobbler／stdin；正常完成且全域無殘留仍 noteClean，
 *   不誤判良性；讀失敗不計入
 *   連續計數 —— 無資源殘留，verdict 已 Failed，避免數據錯誤誤觸熔斷）。
 */
internal object ElevatedCleanupTracker {
    /**
     * 跨呼叫未完成清理計數上限（AA1，owner 新 P1＋P2；P1-4 重推導，見 [SLOTS_PER_CALL]）。
     *
     * 每殘留清理 worker 持有 Process＋已取流＋Binder 參照（Shizuku 遠端 stall 時含
     * Binder 代理），無界殘留即無界 fd／執行緒／binder 參照洩漏。7 為單呼叫最壞
     * 殘留封包（見 [SLOTS_PER_CALL] 推導）：總量上限取同值，保證「任一殘留即擋新
     * 呼叫」（准入要求 `outstanding + (active+1) * SLOTS <= MAX`，見
     * [tryEnterReserved]；`outstanding>0` 即 `outstanding+7>7`，一律預留拒），
     * fail-closed 最保守；#136-B3 可以只調大本常數放寬可用性（放大即允殘留期間
     * 新呼叫，峰值保證見 [tryEnterReserved] KDoc）。
     *
     * 飽和（[isSaturated]）即 #136-B3 blocker 條件（見 [PrivilegeActivation]：清理缺口未解
     * 前產品不得放行，runner 入口資源門拒絕新 spawn）。
     *
     * P1-2 雙預算分離（PR140 Phase1）：本上限同時承擔併發預留檢查
     *（見 [SLOTS_PER_CALL]／[tryEnterReserved]）：准入要求
     * `outstanding + (active+1) * SLOTS_PER_CALL <= MAX_OUTSTANDING_CLEANUPS`。
     * 以 MAX=7、SLOTS=7 計，乾淨態（outstanding==0）僅允單一進行中呼叫
     *（0+1*7<=7 通過，0+2*7>7 拒絕）：實效串行。特權通道宜序列
     *（單次 drain 至多 1 清理 worker＋雙 gobbler＋stdin-closer＋監督殘留），#136-B3 可調本常數
     *（放寬即允更高併發，但最壞超額同步放大，見 [tryEnterReserved] KDoc）。
     */
    const val MAX_OUTSTANDING_CLEANUPS = 7

    /**
     * 連續不清創熔斷上限（終審殘留，fail-closed，不懲罰良性慢命令）。
     *
     * 只計「清理未完成」的連續次數（killer worker 超界未完成，即
     * [ElevatedProcessIo.drain] kill 路徑 `runCleanupBounded` 回 false；或殘留
     * gobbler／stdin（[markLingering] 持槽），見 drain finished／kill 統一終審
     * `if (cleanupIncomplete) noteUnclean()`）；
     * 清理完成且全域無殘留（`outstanding()==0 && !cleanupIncomplete`）即
     * [noteClean] 歸零；殘留期間的乾淨 drain（outstanding>0）保持計數
     *（不歸零亦不遞增），避免乾淨呼叫沖掉熔斷（P0-1，owner 新 P1）。
     * 可恢復序列 `0→1→0→1` 永不觸發（每次乾淨完成且無殘留即歸零）；連續
     * `MAX_CONSECUTIVE_UNCLEAN` 次未完成即 [isBreakerOpen] 熔斷，
     * runner 入口拒絕新 spawn（與 [isSaturated] 同級，皆零 spawn、不耗配額）。
     */
    const val MAX_CONSECUTIVE_UNCLEAN = 4

    /**
     * 呼叫級併發上限（AB1 P1-B，owner 新 P1-A＋P1-B）。
     *
     * [MAX_OUTSTANDING_CLEANUPS] 只計殘留清理（事後止血）；併發呼叫本身亦須有界：
     * 特權通道宜序列（單次 drain 至多 1 清理 worker＋雙 gobbler＋stdin-closer），
     * 無界併發即無界執行緒／fd／Binder 參照。4 為有界併發經驗值（#136-B3 可調本常數），
     * 飽和即兩 runner 入口拒絕新 spawn（fail-closed，見 [tryEnterReserved]）。
     *
     * 呼叫級准入（P1-2 原子化）：runner 在飽和／熔斷門之後、配額之前調用單一
     * [tryEnterReserved]（成功當且僅當預留檢查通過）；成功後整個剩餘 runBinary 包
     * `try { ... } finally { leaveCall() }`（配額拒、spawn 抛、drain 全路徑釋放）。
     * 併發拒絕與 quota 同級（`QUOTA_EXCEEDED`，零 spawn、不耗配額）。
     */
    const val MAX_CONCURRENT_CALLS = 4

    /**
     * 單呼叫槽位上界（P1-2，PR140 Phase1；P1-4 重推導：4→7）。
     *
     * 舊值 4 沿用舊假設（1 清理 worker＋2 gobbler＋1 stdin-closer），漏計 B3 後
     * 同一 call 可同時殘留的監督 worker。現重推導為 7，上限封包：
     * - drain 殘留：1 清理 worker（kill 路徑 [runCleanupBounded] 不可中斷 stall）＋
     *   2 gobbler（stdout／stderr 殘留 [markLingering]）＋1 stdin-closer 殘留 ＝4；
     * - 監督殘留：1 `elevated-wait`（[ElevatedCallSupervisor.awaitWaitSlice]
     *   stall，kill 路徑）＋1 `elevated-verify`
     *  （[ElevatedCallSupervisor.verifyParentStoppedBounded] stall，kill 路徑）＋
     *   1 `elevated-supervisor` exit（[ElevatedCallSupervisor.exitValueBounded]
     *   stall，finished 路徑）＝3；
     * - kill 路徑實測至多 6（無 exit：`exitCode=-1` 直回，不調 exitValue）；
     *   finished 路徑實測至多 4（無 wait 殘留：`finished==true` 即無 Expired 分支；
     *   另 pre-spawn 監督每 call 至多 1 殘留——首個 Expired 即回 runBinary——
     *   與 kill 路徑 6 疊加上限 7）。
     *   故跨兩路徑＋pre-spawn 疊加的保守封包為 7（寧可高估拒絕，不低估超額）。
     *
     * 准入預留以此上界估算（見 [tryEnterReserved]），寧可保守拒絕也不超額。
     * 不變量（串行契約 `active<=1` 下）：准入要求 `outstanding+7<=7` 即
     * `outstanding==0`，故峰值 `peakOutstanding <= outstanding_admit+7 <= 7 == MAX`
     *（見 `ElevatedRunnerPhase3HardeningTest` 六點同卡 barrier 斷言）。
     */
    const val SLOTS_PER_CALL = 7

    /**
     * 統一准入 verdict（P1-2）：准入決定必須原子，拒絕原因僅供訊息區分，
     * 不另做二次檢查。
     */
    enum class Admission {
        OK,
        BACKLOG_FULL,
        BREAKER_OPEN,
        CONCURRENCY_FULL,
    }

    private val lock = Any()

    private val outstandingCount = AtomicInteger(0)

    /** 連續未完成清理計數（killer worker 超界未完成或 finished 殘留累計；清理完成且全域無殘留才歸零，見 [noteClean]）。 */
    private val consecutiveUnclean = AtomicInteger(0)

    /** 進行中呼叫數（AB1 呼叫級准入，跨呼叫全域，雙 runner 共用；進入即佔，finally 釋放）。 */
    private val activeCalls = AtomicInteger(0)

    /**
     * floor 命中計數（診斷用，產品保持 floor 不變負但上報；正常路徑永不觸發，
     * 觸發即呼叫方 bug——未佔而釋）。
     */
    private val releaseFloorHits = AtomicInteger(0)
    private val leaveFloorHits = AtomicInteger(0)

    /**
     * 同步峰值（P1Fix 混合測試用，替代 sleep 採樣）：[acquireSlot] 同一 [lock] 內
     * 同步更新，呼叫方以 [peakOutstanding] 斷言峰值有界，無採樣遺漏。
     */
    private val peakOutstanding = AtomicInteger(0)

    /** 目前未完成清理數（跨呼叫全域，雙 runner 共用；僅計未完成，正常完成即釋放）。 */
    fun outstanding(): Int = synchronized(lock) { outstandingCount.get() }

    /** 目前進行中呼叫數（AB1 呼叫級准入；跨呼叫全域，雙 runner 共用）。 */
    fun activeCalls(): Int = synchronized(lock) { activeCalls.get() }

    /** 連續未完成計數快照（僅測試／診斷用；正式 verdict 以 [isBreakerOpen] 為準）。 */
    internal fun consecutiveCount(): Int = synchronized(lock) { consecutiveUnclean.get() }

    /** floor 命中快照（僅診斷用；正常路徑永為 0，測用不断言）。 */
    internal fun releaseFloorHits(): Int = releaseFloorHits.get()
    internal fun leaveFloorHits(): Int = leaveFloorHits.get()

    /** 同步峰值快照（僅測試／診斷用；[acquireSlot] 同鎖內同步更新，替代 sleep 採樣）。 */
    internal fun peakOutstanding(): Int = synchronized(lock) { peakOutstanding.get() }

    /** 重置同步峰值為當前值（測試隔離用）。 */
    internal fun resetPeak() {
        synchronized(lock) { peakOutstanding.set(outstandingCount.get()) }
    }

    /**
     * 呼叫級准入（AB1 P1-B，僅測用）：佔一席，成功當且僅當舊值 < [MAX_CONCURRENT_CALLS]。
     *
     * 僅測用，產品走單一原子 [tryEnterReserved]（飽和／熔斷／併發預留同一鎖內
     * 檢查＋佔席，消除三段檢查 TOCTOU）。本函數保留供既有單測直接斷言併發語義，
     * 不供 runner 入口使用（兩 runner 入口一律 [tryEnterReserved]）。
     *
     * 門位置（測用直調時）：false 即 fail-closed 拒絕（`QUOTA_EXCEEDED`，
     * 零 spawn、不耗配額）。成功後呼叫方須以
     * `try { ... } finally { leaveCall() }` 包住剩餘路徑，否則計數洩漏即永久拒絕。
     */
    internal fun tryEnterCall(): Boolean = synchronized(lock) {
        if (activeCalls.get() >= MAX_CONCURRENT_CALLS) return false
        activeCalls.incrementAndGet()
        true
    }

    /**
     * 呼叫結束釋一席（floor 0；僅成功 [tryEnterReserved] 後的 `finally` 調用）。
     *
     * floor 觸發即 bug（未進入而釋放）：產品保持 floor 不變負，但經
     * [leaveFloorHits] 計數上報（測用不断言，僅供診斷；正常路徑永不觸發）。
     */
    fun leaveCall() {
        synchronized(lock) {
            if (activeCalls.get() > 0) {
                activeCalls.decrementAndGet()
            } else {
                leaveFloorHits.incrementAndGet()
            }
        }
    }

    /** 是否飽和（未完成數達上限；飽和即有不可回收清理，runner 入口拒絕新 spawn）。 */
    fun isSaturated(): Boolean = synchronized(lock) { outstandingCount.get() >= MAX_OUTSTANDING_CLEANUPS }

    /** 是否熔斷（連續未完成數達上限；熔斷即持續不清創，runner 入口拒絕新 spawn）。
     *
     * 鎖存語義：熔斷後所有新 spawn 被入口門拒絕，故不會再有 drain 執行、
     * 計數器不會自然歸零；#136-B3 在驗證修復後須顯式呼叫 [noteClean] 重置
     * （internal，同模組可見），不得僅調大上限繞行。*/
    fun isBreakerOpen(): Boolean = synchronized(lock) { consecutiveUnclean.get() >= MAX_CONSECUTIVE_UNCLEAN }

    /**
     * 清理完成：僅全域無殘留（`outstanding==0`）才歸零（P0-1）。
     * 殘留期間（outstanding>0）的乾淨 drain 保持計數（不歸零亦不遞增），
     * 避免乾淨呼叫沖掉熔斷。
     *
     * 測試意圖：單測 setUp／tearDown／首尾呼叫皆屬測試隔離重置，非 #136-B3 修復驗證；
     * 產品 #136-B3 介入重置亦經此（見 [PrivilegeActivation]），單測僅借其歸零語義做隔離。
     */
    @VisibleForTesting
    internal fun noteClean() {
        synchronized(lock) {
            if (outstandingCount.get() == 0) consecutiveUnclean.set(0)
        }
    }

    /** 清理未完成（killer worker 超界未完成或殘留 gobbler／stdin）：連續計數遞增，飽和值封頂防溢出。 */
    internal fun noteUnclean() {
        synchronized(lock) {
            if (consecutiveUnclean.get() >= MAX_CONSECUTIVE_UNCLEAN) {
                consecutiveUnclean.set(MAX_CONSECUTIVE_UNCLEAN)
            } else {
                consecutiveUnclean.incrementAndGet()
            }
        }
    }

    /**
     * 統一原子准入（P1-2，PR140 Phase1）：同一 [lock] 內檢查三條件並佔席。
     *
     * 檢查順序（拒絕原因優先級，僅供訊息區分，准入決定單一原子點）：
     * 1. `outstanding >= MAX` → [Admission.BACKLOG_FULL]；
     * 2. `consecutive >= MAX_CONSECUTIVE_UNCLEAN` → [Admission.BREAKER_OPEN]；
     * 3. `active >= MAX_CONCURRENT_CALLS` → [Admission.CONCURRENCY_FULL]；
     * 4. `outstanding + (active+1) * SLOTS_PER_CALL > MAX` → [Admission.BACKLOG_FULL]
     *   （預留超額，視為 backlog 壓力）；
     * 5. 否則 `active++` 並回 [Admission.OK]。
     *
     * 特權宜序列：MAX=7、SLOTS=7 時乾淨態僅允單一進行中呼叫
     *（0+1*7<=7 通過，0+2*7>7 拒絕），實效串行；且任一殘留（`outstanding>=1`）
     * 即擋新呼叫（1+7>7）：fail-closed 最保守。#136-B3 可調 MAX／SLOTS
     * 放寬併發（只調大 MAX 即允殘留期間新呼叫），但最壞超額同步放大（理論最壞
     * `outstanding + active*SLOTS`，現參數下串行契約 `active<=1` 且准入要求
     * `outstanding==0`，故實測峰值 `peakOutstanding<=7==MAX`，見
     * 迴歸測試 six-point-barrier）。呼叫方成功（OK）後須 `try/finally{leaveCall()}`
     * 包住剩餘 runBinary；拒絕路徑零 spawn、不耗配額、不佔席。
     *
     * [Admission.CONCURRENCY_FULL] 保留但現參數下實效不可達（預留檢查先拒：
     * 乾淨態第二呼叫即 `0+2*7>7` 走 BACKLOG_FULL，不會走到 `active>=4`），
     * 為未來放寬 MAX／SLOTS 後的併發上限保留；reservation 矩陣直測見
     * `ElevatedCleanupTrackerTest`（不經 runner，直接斷言預留表）。
     */
    fun tryEnterReserved(): Admission = synchronized(lock) {
        if (outstandingCount.get() >= MAX_OUTSTANDING_CLEANUPS) return Admission.BACKLOG_FULL
        if (consecutiveUnclean.get() >= MAX_CONSECUTIVE_UNCLEAN) return Admission.BREAKER_OPEN
        if (activeCalls.get() >= MAX_CONCURRENT_CALLS) return Admission.CONCURRENCY_FULL
        if (outstandingCount.get() + (activeCalls.get() + 1) * SLOTS_PER_CALL > MAX_OUTSTANDING_CLEANUPS) {
            return Admission.BACKLOG_FULL
        }
        activeCalls.incrementAndGet()
        Admission.OK
    }

    /**
     * 佔一槽（[ElevatedProcessIo.drain] 經 `runCleanupBounded` 或 [markLingering] 調用；同鎖內同步更新峰值）。
     *
     * P1-4 飽和即拒（bounded accounting）：已達 [MAX_OUTSTANDING_CLEANUPS] 即回 false，
     * 不遞增（`outstanding`／峰值永不超 MAX，資源上界無條件成立，不依賴串行契約）。
     * 呼叫方須把 false 收斂為 `cleanupIncomplete=true`（經 drain 終審進 `noteUnclean`／
     * breaker，不靜默）：[runCleanupBounded] 無槽仍起 worker 嘗試 killer（永不跳過，
     * 只回 false）；[markLingering] 拒時記 `state=2`（終態，不欠釋放）。
     */
    internal fun acquireSlot(): Boolean {
        synchronized(lock) {
            if (outstandingCount.get() >= MAX_OUTSTANDING_CLEANUPS) return false
            outstandingCount.incrementAndGet()
            val cur = outstandingCount.get()
            if (cur > peakOutstanding.get()) peakOutstanding.set(cur)
            return true
        }
    }

    /**
     * 釋一槽（`runCleanupBounded` worker finally 或殘留執行緒本體 finally 調用）。
     *
     * floor 觸發即 bug（未佔而釋）：產品保持 floor 不變負，但經
     * [releaseFloorHits] 計數上報（測用不断言，僅供診斷；正常三態機制下永不觸發）。
     */
    internal fun releaseSlot() {
        synchronized(lock) {
            if (outstandingCount.get() > 0) {
                outstandingCount.decrementAndGet()
            } else {
                releaseFloorHits.incrementAndGet()
            }
        }
    }

    /**
     * 殘留執行緒記一槽（AB1 P1-A＋P0-1 三態修正）：drain 內 gobbler／stdin-closer
     * 啟動不預佔；join 超界 grace 後仍存活（重讀 `isAlive` 確認）即調用。
     *
     * 三態線性化點：以 `state` 本體為鎖（`synchronized(state)`），同一執行緒的
     * mark 與本體 finally 互斥，恰好一次，不漏計不重釋，不依賴 `isAlive` 第二讀：
     * - 狀態：0=init（未記）、1=held（已佔槽）、2=done（終態，不再轉換）；
     * - mark：僅 `state==0 && thread.state!=TERMINATED` 才 `state=1+acquire`；
     *   `state!=0` 直接返回（已記或已終）；已終止（TERMINATED）即 `state=2` 返回，
     *   不佔槽；無第二 `isAlive` 收斂分支（舊碼同鎖內第二讀無用——`isAlive`
     *   不受本鎖保護，worker 可在取鎖前釋放後、返回前窗口內退出，造成 ghost；
     *   新碼以狀態機消除該窗口）；
     * - finally：`synchronized(state){ if (state==1){state=2;release} else if (state==0) state=2 }`
     *   （自然退出時競態下平衡；1→釋放，0→直接終態，2→無動作）。
     *
     * 舊 CAS＋雙讀 ghost 根因：`compareAndSet(false,true)` 成功後、`acquireSlot()`
     * 之前，worker finally 的 `getAndSet(false)->releaseSlot()` 可插隊；
     * `releaseSlot` 在 outstanding==0 時 floor-to-zero 丟失一次遞減，隨後
     * `acquireSlot` 即永久 +1；同鎖內第二 `isAlive` 讀亦無法收斂（μs 窗口內
     * caller 可 set+acquire 成 ghost）。現以三態＋同一鎖包住 acquire／標記／釋放，
     * 刪除 `isAlive` 依賴（僅保留 mark 入口一次 `state==TERMINATED` 判定，
     * 為線性化點內的單次狀態讀，非快照＋重讀）。
     *
     * P1-4 飽和即拒（見 [acquireSlot]）：已飽和即 `state=2` 不佔槽（不欠釋放，
     * 本體 finally 見 2 無動作）；殘留由呼叫方既有 `cleanupIncomplete` 旗標經
     * drain 終審進 `noteUnclean`／breaker，不靜默（`outstanding` 封頂不增）。
     */
    internal fun markLingering(thread: Thread, state: AtomicInteger) {
        synchronized(state) {
            if (state.get() != 0) return
            if (thread.state == Thread.State.TERMINATED) {
                state.set(2)
                return
            }
            if (!acquireSlot()) {
                // P1-4 飽和即拒：無槽可佔即記終態（不欠釋放；本體 finally 見 2
                // 無動作），殘留由呼叫方既有 cleanupIncomplete 旗標帶出。
                state.set(2)
                return
            }
            state.set(1)
        }
    }
}

/**
 * B3 有界外部呼叫監督（owner P1-5，PR140 Phase B3）：
 * - 特權請求單一 deadline：入口 `deadlineNanos = now + timeoutMs`（單調時鐘），
 *   所有阻塞外部回呼（授權讀取／status ping／su 探測／newProcess／waitFor）
 *   皆以剩餘 budget 為上界，超界即 `TimedOut`（零 spawn／零殘留語義同既有取消）；
 * - 有限併發＋無界 stall 追蹤：呼叫執行緒先占 [ElevatedCleanupTracker] 席（兩 runner
 *   入口即 [tryEnterReserved]，拒絕不耗 quota），阻塞體跑 daemon worker，主執行緒
 *   `join(POLL)` 輪詢 cancel／deadline；超界即 `interrupt`＋grace，仍存活即
 *   [ElevatedCleanupTracker.markLingering]（啟動不預佔，僅殘留佔槽，本體 finally
 *   釋放；連續不清創經既有 [noteUnclean]／breaker 收斂，見各呼叫點）；
 * - Binder interrupt 不可靠：不依賴中斷必達，stall 即殘留 daemon＋計數＋旗標，
 *   呼叫方有界返回（見 [awaitPreSpawn]／[awaitWaitSlice]／[verifyParentStoppedBounded]）。
 *
 * P1-2 late-spawn ownership＋P1-3 deadline preflight（PR140 Phase 2）：
 * - 單一 deadline 誠實描述：Binder 已送達不可撤回。spawn 阻塞體一旦向系統提交
 *   建進程（`ProcessBuilder.start()`／Shizuku `newProcess` Binder 已送達對端），
 *   呼叫方超時即無法撤回，只能 late compensation（有界 kill／reap，見 [awaitSpawn]）。
 *   故 spawn 超時一律 `cleanupIncomplete=true`（保守：返回當下不知 late Process
 *   是否／何時送達、補償是否成功，不宣稱 cleaned）。
 * - owner 認領機制（見 [awaitSpawn]）：`expired` 單一原子旗標決定 late Process
 *   所有權。未逾期交付才經 holder handoff caller；逾期後 worker 取得的 Process
 *   在 worker 內立即有界補償（[lateCompensateSpawn]：killer＋`waitFor(1s)`＋
 *   三流 best-effort 關，不再經 holder）；認領競態（worker 發布與 caller 過期間
 *   的 µs 交錯）以 holder CAS 恰好一次交付清理方（worker 內聯或 caller 側
 *   `elevated-late-spawn` daemon），不雙殺不漏殺；
 * - preflight 三層（皆零新 spawn）：[awaitInternal]／[awaitSpawn] 入口
 *   `isExpired` 即不啟 worker 直回 `Expired(hasLinger=false)`；[awaitSpawn]
 *   worker 返回後再確認 deadline（逾期交付即補償＋`Expired(true)`）；quota 後、
 *   真正提交 spawn 前 runner 顯式 `isExpired`（見兩 runner；配額已耗仍零 spawn，
 *   無 refund API，fail-closed 窄競態）；runner 入口 `safeTimeoutMs<=0` 直回
 *   `preSpawnExpired(false)`（不佔席不耗 quota）；[drain] 入口 remaining<=0
 *   即監督殺＋有界驗證後回 `Drained(timedOut=true)`，不起 gobbler／stdin-closer；
 * - [Res.Expired.hasLinger] 僅表示「supervisor worker 仍存活（grace 後）或
 *   曾交付 late 值」：spawn 路徑穿透至 `TimedOut.cleanupIncomplete`（有 late
 *   Process 缺口）；非 spawn 路徑（grant／status／suExists 等）無 Process 資源，
 *   殘留執行緒已經 outstanding／breaker 追蹤，verdict 旗標保持 false（既有契約，
 *   見 B3 grant 測試），由呼叫方傳 `hasLinger=false` 映射（見 [ElevatedRunnerCommon]）。
 *
 * 常數不動 READY／MAX（新增本物件常數，不改 [ElevatedCleanupTracker] 上限）。
 */
internal object ElevatedCallSupervisor {
    /** 監督輪詢切片（呼叫執行緒 join 粒度，兼 cancel／deadline 響應）。 */
    const val POLL_MS = 20L

    /** 超界後 grace：`interrupt` 後再等本值，仍存活即殘留（mark＋計數）。 */
    const val GRACE_MS = 500L

    /** post-kill 驗證 join 上界（`KILL_WAIT_SEC` 5s＋grace，見 [verifyParentStoppedBounded]）。 */
    const val VERIFY_JOIN_MS = 5_500L

    /** `exitValue` 有界讀取上界（finished 後 Binder 亦可 stall，超界即 -1＋殘留記數）。 */
    const val EXIT_JOIN_MS = 2_000L

    /** P1-2 late 補償 reap 上界（[lateCompensateSpawn] 內 `waitFor` 有界等待，呼叫方已返回，不擋 budget）。 */
    const val LATE_REAP_WAIT_SEC = 1L

    sealed interface Res<out T> {
        data class Ok<T>(val v: T) : Res<T>
        data class Err(val t: Throwable) : Res<Nothing>
        /**
         * P1-2 殘留提示（PR140 Phase 2）：`hasLinger=true` 表示 supervisor worker
         * 在 grace 後仍存活（late 值仍可能送達）或曾交付逾期值。
         * spawn 路徑必須穿透至 `TimedOut.cleanupIncomplete`（late Process 缺口，
         * 見 [awaitSpawn]）；非 spawn 路徑無 Process 資源，殘留已由 outstanding／
         * breaker 追蹤，呼叫方映射 `preSpawnExpired(false)` 保持既有契約。
         */
        data class Expired(val hasLinger: Boolean = false) : Res<Nothing>
    }

    private data class Val<T>(val v: T)
    private data class Thr(val t: Throwable)

    fun remainingMs(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    fun isExpired(deadlineNanos: Long): Boolean =
        (deadlineNanos - System.nanoTime()) <= 0L

    /**
     * spawn 前阻塞體有界（grant／activation／bridge／status／suExists 共用；
     * spawn 本體改走 [awaitSpawn]，不經此）：
     * - P1-3 入口 preflight：`isExpired(deadline)` 即不啟 worker，直回
     *   `Expired(hasLinger=false)`（零殘留；剩餘<=0 與已逾期同義，[isExpired]
     *   覆蓋）；
     * - 快路徑（worker 首個 [POLL_MS] 內完成）不額外調 `isCancelled`（既有 B2 三點
     *   計數不變，見 `cancelMidDrainKillsAndUnblocks` 類斷言）；
     * - 慢路徑每 [POLL_MS] 查 `interrupt`／`isCancelled()`（抛即已取消，
     *   fail-closed，計數沿用 `swallowedIsCancelledThrows`）／deadline，命中即
     *   `interrupt`＋grace，仍存活即 lingering（`markLingering`＋`noteUnclean`，
     *   無 drain 終審故此處直接計入 breaker）；`Expired.hasLinger` 即 worker
     *   grace 後仍存活，非 spawn 呼叫方一律映射 `preSpawnExpired(false)`
     *   （無 Process 資源，殘留已追蹤，見本物件 P1-2 段）。
     */
    fun <T> awaitPreSpawn(
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
        block: () -> T,
    ): Res<T> = awaitInternal(
        deadlineNanos = deadlineNanos,
        cancelCheck = {
            if (Thread.currentThread().isInterrupted) {
                true
            } else {
                val c = try {
                    isCancelled()
                } catch (_: Exception) {
                    runCatching { ElevatedProcessIo.swallowedIsCancelledThrows.incrementAndGet() }
                    true
                }
                c || isExpired(deadlineNanos)
            }
        },
        block = block,
        threadName = "elevated-supervisor",
        markUncleanOnLinger = true,
    )

    /**
     * drain 輪詢 `waitFor(slice)` 單片有界（B3，Binder 同步 stall 即此卡住）：
     * - `isCancelled` 抛折為未取消（drain 語義，不誤殺，計數同上）；
     * - 超界（cancel／deadline／worker stall）即 `Expired`，呼叫方走 kill 路徑；
     *   lingering 只記槽（`markLingering`），`noteUnclean` 由 drain 終審經
     *   `cleanupIncomplete` 統一計入（避免一次 drain 雙計）。
     */
    fun awaitWaitSlice(
        process: Process,
        sliceMs: Long,
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
    ): Res<Boolean> = awaitInternal(
        deadlineNanos = deadlineNanos,
        cancelCheck = {
            val c = try {
                isCancelled()
            } catch (_: Exception) {
                runCatching { ElevatedProcessIo.swallowedIsCancelledThrows.incrementAndGet() }
                false
            }
            c || Thread.currentThread().isInterrupted || isExpired(deadlineNanos)
        },
        block = { process.waitFor(sliceMs, TimeUnit.MILLISECONDS) },
        threadName = "elevated-wait",
        markUncleanOnLinger = false,
    )

    /** 通用有界（verify／exitValue 用，不查 cancel，只看 deadline＋interrupt）。 */
    fun <T> awaitTimeout(
        timeoutMs: Long,
        threadName: String,
        block: () -> T,
    ): Res<T> {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L),
        )
        return awaitInternal(
            deadlineNanos = deadlineNanos,
            cancelCheck = { Thread.currentThread().isInterrupted || isExpired(deadlineNanos) },
            block = block,
            threadName = threadName,
            markUncleanOnLinger = false,
        )
    }

    private fun <T> awaitInternal(
        deadlineNanos: Long,
        cancelCheck: () -> Boolean,
        block: () -> T,
        threadName: String,
        markUncleanOnLinger: Boolean,
    ): Res<T> {
        // P1-3 入口 preflight：budget 已盡即不啟 worker（零執行緒、零殘留）。
        if (isExpired(deadlineNanos)) return Res.Expired(hasLinger = false)
        val holder = AtomicReference<Any?>()
        val state = AtomicInteger(0)
        val worker = Thread({
            try {
                val v = block()
                holder.set(Val(v))
            } catch (t: Throwable) {
                holder.set(Thr(t))
            } finally {
                synchronized(state) {
                    if (state.get() == 1) {
                        state.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (state.get() == 0) {
                        state.set(2)
                    }
                }
            }
        }, threadName)
        worker.isDaemon = true
        try {
            worker.start()
        } catch (_: Exception) {
            return Res.Expired(hasLinger = false)
        }
        try {
            while (true) {
                try {
                    worker.join(POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runCatching { worker.interrupt() }
                    try {
                        worker.join(GRACE_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    val lingering = worker.isAlive
                    if (lingering) {
                        ElevatedCleanupTracker.markLingering(worker, state)
                        if (markUncleanOnLinger) ElevatedCleanupTracker.noteUnclean()
                    }
                    return Res.Expired(hasLinger = lingering)
                }
                if (!worker.isAlive) {
                    val h = holder.get()
                    return when (h) {
                        is Val<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            Res.Ok((h as Val<T>).v)
                        }
                        is Thr -> Res.Err(h.t)
                        else -> Res.Err(IllegalStateException("supervisor worker died without result"))
                    }
                }
                val shouldExpire = try {
                    cancelCheck()
                } catch (_: Exception) {
                    true
                }
                if (shouldExpire) {
                    runCatching { worker.interrupt() }
                    try {
                        worker.join(GRACE_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    val lingering = worker.isAlive
                    if (lingering) {
                        ElevatedCleanupTracker.markLingering(worker, state)
                        if (markUncleanOnLinger) ElevatedCleanupTracker.noteUnclean()
                    }
                    return Res.Expired(hasLinger = lingering)
                }
            }
        } catch (_: Exception) {
            runCatching { worker.interrupt() }
            try {
                worker.join(GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val lingering = worker.isAlive
            if (lingering) {
                ElevatedCleanupTracker.markLingering(worker, state)
                if (markUncleanOnLinger) ElevatedCleanupTracker.noteUnclean()
            }
            return Res.Expired(hasLinger = lingering)
        }
    }

    /**
     * P1-2 spawn 專用有界監督（PR140 Phase 2）：`block` 建特權進程（`ProcessBuilder.start()`／
     * Shizuku `newProcess`，Binder 一旦送達即不可撤回），故逾期交付不能只丟棄引用，必須
     * late compensation（見 [lateCompensateSpawn]）。
     *
     * owner 認領機制：
     * - `expired` 單一原子旗標為線性化點。caller 決定過期即先置 true，再 interrupt＋grace；
     *   worker 發布值後必重讀本旗：false 才經 holder handoff，true 即自行 [lateCompensateSpawn]
     *   後（CAS 認領成功才動手）丟棄，不再經 holder；
     * - 認領競態（worker 發布與 caller 過期交錯）以 holder CAS 恰好一次交付清理方：
     *   worker 發布後若見 expired 即 `compareAndSet(發布值, null)`，成功則 worker 內聯補償，
     *   失敗（caller 已取走）則放手；caller 過期路徑同樣 CAS 取 holder 內逾期 Process，
     *   成功則起 `elevated-late-spawn` daemon 補償（caller 不為補償多擋 budget，
     *   有界性只由 [GRACE_MS] 承擔），失敗（worker 已認領或尚未發布——後者由 worker
     *   發布後重讀兜底）則放手。三方交錯下恰好一方動手，不雙殺（killer 本就冪等，
     *   destroy 重入無害）不漏殺（任一發布必有一方在置旗後 CAS）；
     * - 返回後再確認 deadline：worker 已死但交付發生在 budget 之後（邊緣回傳），
     *   即 CAS 取回＋daemon 補償＋`Expired(hasLinger=true)`，不把逾期建成當成功；
     * - `hasLinger=true` 條件：grace 後 worker 仍存活（late Process 仍可能送達，
     *   由 worker 發布後重讀兜底清理）或本次確有逾期 Process（已取走補償中，
     *   結果未知故保守置旗）。spawn 呼叫方必須穿透至 `TimedOut.cleanupIncomplete`。
     *
     * 入口 preflight 同 [awaitInternal]（已逾期即不啟 worker，`Expired(false)`，
     * 零 spawn）；`killer` 為 runner 注入（單測計數／destroy 可觀測，預設即
     * [ElevatedProcessKiller.killTree]）；残留記槽／breaker 計入同 [awaitPreSpawn]
     * （`markUncleanOnLinger=true` 語義：grace 後仍存活即記＋計）。
     */
    fun awaitSpawn(
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
        killer: (Process) -> Unit,
        block: () -> Process,
    ): Res<Process> {
        // P1-3 入口 preflight：budget 已盡即不提交 spawn（零特權進程）。
        if (isExpired(deadlineNanos)) return Res.Expired(hasLinger = false)
        val expired = AtomicBoolean(false)
        val holder = AtomicReference<Any?>()
        val state = AtomicInteger(0)
        val worker = Thread({
            try {
                val p = block()
                val published: Any = Val(p)
                holder.set(published)
                // 發布後重讀：caller 已過期即嘗試認領回來自行補償。
                if (expired.get()) {
                    if (holder.compareAndSet(published, null)) {
                        lateCompensateSpawn(p, killer)
                    }
                }
            } catch (t: Throwable) {
                // 例外不攜 OS 資源：未過期才發布供 caller 診斷，逾期即丟棄。
                if (!expired.get()) holder.set(Thr(t))
            } finally {
                synchronized(state) {
                    if (state.get() == 1) {
                        state.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (state.get() == 0) {
                        state.set(2)
                    }
                }
            }
        }, "elevated-supervisor")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (_: Exception) {
            return Res.Expired(hasLinger = false)
        }
        // 過期善後（caller 側）：置旗→interrupt→grace→記槽→CAS 認領逾期交付。
        // 三條過期路徑（caller 中斷／cancel-deadline／外層異常）共用，恰好一次。
        fun expireWorker(): Res<Process> {
            expired.set(true)
            runCatching { worker.interrupt() }
            try {
                worker.join(GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val lingering = worker.isAlive
            if (lingering) {
                ElevatedCleanupTracker.markLingering(worker, state)
                ElevatedCleanupTracker.noteUnclean()
            }
            var lateProcess = false
            val h = holder.get()
            if (h is Val<*>) {
                @Suppress("UNCHECKED_CAST")
                val v = (h as Val<Process>).v
                // CAS 認領：成功才起 daemon 補償（caller 不多擋 budget）；
                // 失敗即 worker 已認領（內聯補償中），放手。
                if (holder.compareAndSet(h, null)) {
                    lateProcess = true
                    val t = Thread({ lateCompensateSpawn(v, killer) }, "elevated-late-spawn")
                    t.isDaemon = true
                    runCatching { t.start() }
                } else {
                    lateProcess = true
                }
            }
            return Res.Expired(hasLinger = lingering || lateProcess)
        }
        try {
            while (true) {
                try {
                    worker.join(POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return expireWorker()
                }
                if (!worker.isAlive) {
                    val h = holder.get()
                    when (h) {
                        is Val<*> -> {
                            // P1-3 返回後再確認 deadline：邊緣回傳（交付在 budget
                            // 之後）不當成功，CAS 取回＋daemon 補償＋Expired(true)。
                            if (isExpired(deadlineNanos)) {
                                expired.set(true)
                                @Suppress("UNCHECKED_CAST")
                                val v = (h as Val<Process>).v
                                if (holder.compareAndSet(h, null)) {
                                    val t = Thread({ lateCompensateSpawn(v, killer) }, "elevated-late-spawn")
                                    t.isDaemon = true
                                    runCatching { t.start() }
                                }
                                return Res.Expired(hasLinger = true)
                            }
                            @Suppress("UNCHECKED_CAST")
                            return Res.Ok((h as Val<Process>).v)
                        }
                        is Thr -> return Res.Err(h.t)
                        else -> {
                            // worker 已死無交付：仍確認 deadline（reset 競態下
                            // 的逾期完成不當成功，直回 Expired(false)，無資源）。
                            if (isExpired(deadlineNanos)) return Res.Expired(hasLinger = false)
                            return Res.Err(IllegalStateException("supervisor worker died without result"))
                        }
                    }
                }
                val shouldExpire = try {
                    if (Thread.currentThread().isInterrupted) {
                        true
                    } else {
                        val c = try {
                            isCancelled()
                        } catch (_: Exception) {
                            runCatching { ElevatedProcessIo.swallowedIsCancelledThrows.incrementAndGet() }
                            true
                        }
                        c || isExpired(deadlineNanos)
                    }
                } catch (_: Exception) {
                    true
                }
                if (shouldExpire) return expireWorker()
            }
        } catch (_: Exception) {
            return expireWorker()
        }
    }

    /**
     * P1-2 late 補償（PR140 Phase 2）：逾期交付的 Process 在此收斂，不再經 holder／drain。
     *
     * 順序：runner 注入 `killer`（盡力整組，抛即吞＋計數，不升級崩潰）→
     * `waitFor([LATE_REAP_WAIT_SEC])` 有界 reap（僵屍不留；Binder stall 即本執行緒
     * 多活至多 1s——本體為已過期 worker／`elevated-late-spawn` daemon，caller 早已
     * 有界返回，不擋 budget）→ 三流 best-effort 關（此 Process 永不進 drain，
     * drain 的「關已取流」無人執行，此處即唯一關流處；getter 重調可 stall，
     * 逐段 runCatching，stall 只拖本 daemon）。
     * 結果未知故呼叫方一律 `cleanupIncomplete=true`（不因補償成功而清旗）。
     */
    private fun lateCompensateSpawn(p: Process, killer: (Process) -> Unit) {
        try {
            killer(p)
        } catch (_: Exception) {
            runCatching { ElevatedProcessIo.swallowedKillerThrows.incrementAndGet() }
        }
        try {
            p.waitFor(LATE_REAP_WAIT_SEC, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // 有界 reap 失敗即放手（daemon 不抛，旗標已由呼叫方保守置 true）。
        }
        runCatching { p.inputStream.close() }
        runCatching { p.errorStream.close() }
        runCatching { p.outputStream.close() }
    }

    /**
     * B3 有界父進程停止驗證（取代直調 `waitFor(5s)`＋`isAlive`；Binder stall
     * 即舊碼無界卡住）：全部 Binder 呼叫跑 `elevated-verify` worker，主執行緒
     * join [VERIFY_JOIN_MS]；超界即 interrupt＋grace，仍存活即 lingering（記槽，
     * `noteUnclean` 由 drain 終審經 `cleanupIncomplete` 統一計入，此處不直接計）
     * 並回 false（unknown→incomplete，不宣稱 cleaned）。語義同舊直調（true／
     * false／unknown→false 三分支，見舊 KDoc），只加有界。
     *
     * P1-4 保持 boolean（不做 tristate）的理由：真未停（`waitFor` 回 false／
     * `isAlive` 明確 true）與監督 stall（worker 超界仍存活）收斂為同一處置——
     * `cleanupIncomplete=true` 經 drain 終審進 `noteUnclean`／breaker，verdict
     * 與監測皆無分支差異；tristate 只增呼叫方分支，不加信息（殘留 worker 已由
     * outstanding 追蹤，真未停與 stall 在處置上同一動作：不宣稱 cleaned）。
     */
    fun verifyParentStoppedBounded(process: Process): Boolean {
        val holder = AtomicReference<Any?>()
        val state = AtomicInteger(0)
        val worker = Thread({
            try {
                val exited: Boolean? = try {
                    process.waitFor(5L, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } catch (_: Exception) {
                    null
                }
                val res: Boolean = if (exited == null) {
                    try {
                        !process.isAlive
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    } catch (_: Exception) {
                        false
                    }
                } else if (!exited) {
                    false
                } else {
                    try {
                        !process.isAlive
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        true
                    } catch (_: Exception) {
                        true
                    }
                }
                holder.set(Val(res))
            } catch (t: Throwable) {
                holder.set(Thr(t))
            } finally {
                synchronized(state) {
                    if (state.get() == 1) {
                        state.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (state.get() == 0) {
                        state.set(2)
                    }
                }
            }
        }, "elevated-verify")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (_: Exception) {
            return false
        }
        try {
            worker.join(VERIFY_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { worker.interrupt() }
            try {
                worker.join(GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) ElevatedCleanupTracker.markLingering(worker, state)
            return false
        }
        if (worker.isAlive) {
            runCatching { worker.interrupt() }
            try {
                worker.join(GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) ElevatedCleanupTracker.markLingering(worker, state)
            return false
        }
        val h = holder.get()
        return when (h) {
            is Val<*> -> {
                @Suppress("UNCHECKED_CAST")
                (h as Val<Boolean>).v
            }
            else -> false
        }
    }

    /** B3 有界 `exitValue`（finished 後 Binder 亦可 stall）：超界即 Expired＋殘留記槽。 */
    fun exitValueBounded(process: Process): Res<Int> {
        // B3 debug: preset-interrupt cleared path must not expire for immediate exitValue.
        // 若此處頻繁 Expired 且 `isInterrupted=false`，即 worker 啟動／調度異常，需查線程池。
        return awaitTimeout(EXIT_JOIN_MS, "elevated-supervisor") { process.exitValue() }
    }
}

internal object ElevatedProcessIo {
    /** R1-A 截圖二進位容量：`screencap -p` / `cat /dev/graphics/fb0` 的截斷上限（8M）。 */
    const val BINARY_SCREENSHOT_MAX_BYTES: Int = 8 * 1024 * 1024

    /** R1-A 截圖二進位硬讀取上限（8M，每流 OOM 上界；溢出經 [Drained.truncated] 帶出）。 */
    const val BINARY_SCREENSHOT_HARD_CAP_BYTES: Int = 8 * 1024 * 1024

    /**
     * 吞例外計數（P2 可觀測，僅診斷，不改 verdict）：`isCancelled`／`killer` 鉤抛
     * 即吞（fail-closed：前者折為未取消，後者已盡力殺），此處計數供單測斷言
     * 「恒抛鉤計數翻轉且 verdict 不變」。刻意靜默理由：鉤為外部注入（coroutine
     * Job／Binder killer），抛異常不得升級為崩潰或誤殺，收斂為 fail-closed
     * 即正確；計數僅供監測，不進 verdict。
     */
    internal val swallowedIsCancelledThrows = AtomicInteger(0)
    internal val swallowedKillerThrows = AtomicInteger(0)

    /** P1-B magic number 抽常數（行為不變）：輪詢切片 / gobbler 有界 join / 成功路徑放寬 join / 殺後等待 / 複製緩衝。 */
    private const val POLL_SLICE_MS = 50L
    private const val GOBBLER_JOIN_MS = 5_000L
    private const val SUCCESS_JOIN_MS = 10_000L
    /** Y1 成功路徑卡死 grace：首段 [SUCCESS_JOIN_MS] 超界仍存活即 interrupt＋本值重 join 回收線程（不清除不完整標記，見 drain）。 */
    private const val SUCCESS_JOIN_GRACE_MS = 1_000L
    /**
     * P1-4 finished tail 共享 budget（任務 2）：finished 路徑雙 gobbler joins＋
     * graces 共享 `min(剩餘 deadline, 本值)`。舊兩段 10s 無 remaining／cancel 輪詢
     * 可釘 20s＋；現封頂 2s（exit 後卡流＋極短 timeout／取消即有界返回，見
     * `ElevatedRunnerPhase3HardeningTest`）。
     */
    private const val FINISHED_TAIL_BUDGET_MS = 2_000L
    private const val KILL_WAIT_SEC = 5L
    /** Y1 stdin 有界關閉上界：`outputStream` getter/close 在 daemon thread 執行，主執行緒至多等本值（另受剩餘 budget 截頂），超界即放行。 */
    private const val STDIN_CLOSE_MAX_MS = 2_000L
    /**
     * Z1 清理監督上界（owner 新 P1）：kill 路徑 `killer`＋已取流 close 包入單一
     * daemon worker（見 [runCleanupBounded]），主執行緒 join 上界本值；
     * 實際 budget 為 `min(max(remaining, CLEANUP_MIN_MS), CLEANUP_JOIN_MS)`
     * （remaining 為 kill 進入時剩餘 deadline；永不跳過 killer 嘗試，最少仍給
     * [CLEANUP_MIN_MS]）。每 drain 至多 1 清理 worker（daemon），drain 總耗時
     * 上界（P1-4 重算，單調時鐘，皆計入同一 deadline 或獨立小 budget）：
     * - kill 路徑：`timeoutMs`（wait 輪詢至 deadline）＋3s（cleanup 監督，含 grace
     *   0.5s）＋6s（verify 5.5s＋grace 0.5s）＋10s（雙 gobbler joins 各 5s）＋
     *   0.5s（stdin 尾收 grace）＋2s（stdin 起手關，並行於 wait 前段，實效重疊）；
     * - finished 路徑：`timeoutMs`（wait 至 finished）＋2s（stdin 起手關上界，
     *   通常瞬時）＋2s（finished tail 雙流共享，見 [FINISHED_TAIL_BUDGET_MS]）＋
     *   0.5s（stdin 尾收 grace）＋2s（exitValue 獨立 deadline，不耗主剩餘）；
     * 不可中斷 Binder stall 殘留 daemon 由文件揭露＋[Drained.cleanupIncomplete]
     * 旗標帶出，不靜默。
     */
    private const val CLEANUP_JOIN_MS = 3_000L
    /** Z1 清理監督下界：remaining 再小仍給本值，保證 killer 至少被嘗試一次。 */
    private const val CLEANUP_MIN_MS = 500L
    /** Z1 清理／stdin 尾收 grace：首段 join 超界仍存活即 interrupt＋本值重 join 回收線程。 */
    private const val CLEANUP_GRACE_MS = 500L
    private const val COPY_BUF_SIZE = 8192

    data class Drained(
        val stdout: ByteArray,
        val stderr: ByteArray,
        val exitCode: Int,
        val timedOut: Boolean,
        /**
         * gobbler 有界 join 未完成（部分讀）：呼叫方須併入 truncated，絕不靜默短讀不標。
         * R1-A：另含硬上限溢出（任一流丟棄即 true，見 [ElevatedCappedPipe.didOverflow]），
         * 截圖 8M==8M 時靠此位區分恰上限通過與超限丟棄。
         *
         * P2 語義文件化（joinIncomplete vs overflow，不拆欄位，OR 收斂）：
         * - joinIncomplete：gobbler 有界 join 超界仍存活（遠端流未 EOF／不可中斷 stall），
         *   部分讀，經此位標截斷；
         * - overflow：任一流硬上限丟棄（通用 512K／截圖 stdout 8M＋stderr 512K 超額丟棄），
         *   部分保留，經此位標截斷；
         * 兩者皆為「部分位元組」，呼叫方一律併入 truncated（成功路徑仍回 Ok(truncated=true)，
         * 不降為 Failed；capture 側 Ok.truncated 即 null，不摘要部分幀）。
         */
        val truncated: Boolean = false,
        /**
         * V2 gobbler 讀取失敗旗標（owner 新 P1-2 correctness）：任一流
         * `stream.read` 抛異常即 true（見 [ElevatedCappedPipe.failed]，[copyCapped]
         * catch 內無條件 [ElevatedCappedPipe.markFailed]）。只有真 EOF（`read` 回 -1）
         * 才算完整流；finished（非超時/取消，killer 未關流）下的讀取異常必為
         * 真實 I/O 錯誤，呼叫方須回 Failed（見兩 Runner `raw.readFailed` 分支，
         * 位於 `timedOut` 之後、`exitCode != 0` 之前；超時/取消路徑已先回
         * TimedOut，不受影響，預期 close 收斂保留）。
         */
        val readFailed: Boolean = false,
        /**
         * Z1 清理不完整旗標（owner 新 P1 可觀測，僅監測用，不改 verdict）：
         * kill 監督 worker 未完成（[runCleanupBounded] 超界仍存活，killer／已取流
         * close 含不可中斷 Binder stall）或 stdin-closer 尾收殘留（drain 結尾兩路徑
         * gobbler join 後仍存活，interrupt＋[CLEANUP_GRACE_MS] 仍存活）即 true。
         * runner verdict 映射不變：timedOut 仍 TimedOut（僅經既有 truncated／
         * readFailed 分支），本旗標不進 verdict，僅供 #136-B3 監測清理缺口。
         *
         * AB1 P1-A：殘留 gobbler（兩路徑 join 超界仍存活，見 [markLingering]）
         * 亦置本旗標 true（成功路徑 finished＋殘留 gobbler 即 Ok cleanupIncomplete=true，
         * 不再誤判為完整清理）；finished 路徑本旗標為 true 即
         * [ElevatedCleanupTracker.noteUnclean] 累計，正常完成仍 noteClean。
         * 讀失敗（[Drained.readFailed]）不置本旗標亦不計入連續計數：無資源殘留，
         * verdict 已回 Failed，避免數據錯誤誤觸熔斷。
         */
        val cleanupIncomplete: Boolean = false,
    )

    fun drain(
        process: Process,
        timeoutMs: Long,
        hardCapBytes: Int = ShellPolicy.HARD_READ_CAP_BYTES,
        hardCapErrBytes: Int? = null,
    ): Drained =
        drain(
            process,
            timeoutMs,
            { ElevatedProcessKiller.killTree(it) },
            { Thread.currentThread().isInterrupted },
            hardCapBytes,
            hardCapErrBytes,
        )

    /**
     * R1-A 雙軌＋U2-B stderr 獨立小 cap：`hardCapBytes` 預設
     * [ShellPolicy.HARD_READ_CAP_BYTES]（512K，通用命令）；截圖二進位 stdout 傳
     * [BINARY_SCREENSHOT_HARD_CAP_BYTES]（8M）。`hardCapErrBytes` 預設 null＝
     * 同 stdout cap（通用軌行為不變，預設顯式即 [ShellPolicy.HARD_READ_CAP_BYTES]
     * 512K；截圖傳 [ShellPolicy.HARD_READ_CAP_BYTES]
     * 512K，截圖錯誤訊息短，降 OOM 峰值）。[Drained] 結構不變，
     * `truncated` 含 join 未完成或硬上限溢出（任一流丟棄即 true，絕不靜默短讀不標）。
     *
     * R3-A 取消限制（owner P2-5）：[isCancelled] 預設只看 Thread 中斷，
     * coroutine 呼叫方應傳 `isCancelled = { !job.isActive }`
     * （或 `!coroutineContext.isActive`）；鉤抛異常折為未取消（fail-closed，
     * 刻意靜默：外部鉤异常不得誤殺，計數見 [swallowedIsCancelledThrows]）。
     */
    fun drain(
        process: Process,
        timeoutMs: Long,
        killer: (Process) -> Unit,
        isCancelled: () -> Boolean,
        hardCapBytes: Int = ShellPolicy.HARD_READ_CAP_BYTES,
        hardCapErrBytes: Int? = null,
    ): Drained {
        // P2 caller 中斷級聯（僅監測，不改 verdict）：入口 `Thread.interrupted()`
        // 清除並記錄呼叫方預置中斷（清除後 isCancelled 預設鉤不再誤讀 caller 旗標，
        // 健康進程仍走 finished；尾收前 joins 不再因預置中斷即抛），出口恢復一次
        // （不吞 caller 中斷）。join 前不再暫清：入口已清，全程無 caller 旗標干擾。
        val callerInterrupted = Thread.interrupted()
        // X1 有界 acquisition：deadline 移到 drain 入口第一行（outputStream 關閉
        // 之前），全部開銷計入同一 budget（單調時鐘，不受 wall-clock 回撥影響）。
        // P2 上溢夾頂：timeoutMs 先 coerceAtMost(Long.MAX/1e6)，避免
        // MILLISECONDS.toNanos(Long.MAX) 上溢回負（deadline 回過去即假性即時超時，
        // Long.MAX 應為近無限而非即時超時；夾頂後 Long.MAX 即約 292M 年，不懸掛）。
        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safeTimeoutMs)
        // P1-3 drain 入口 preflight（PR140 Phase 2，最小改動）：budget 已盡即不起
        // gobbler／stdin-closer（零新執行緒），直接監督殺＋有界驗證後回
        // `Drained(timedOut=true)`。殺驗語義同 kill 路徑（killer 永不跳過，下界
        // [CLEANUP_MIN_MS]；驗證走有界 [verifyParentStoppedBounded]），終審計入
        // breaker 同正常路徑；caller 預置中斷照常出口恢復。流未起即無已取引用可關
        // （killer 契約本就不碰流；此 Process 的流隨 kill＋物件回收收斂）。
        if ((deadlineNanos - System.nanoTime()) <= 0L) {
            var preIncomplete = false
            val preOk = runCleanupBounded(CLEANUP_MIN_MS) {
                try {
                    killer(process)
                } catch (_: Exception) {
                    swallowedKillerThrows.incrementAndGet()
                }
            }
            if (!preOk) preIncomplete = true
            if (!ElevatedCallSupervisor.verifyParentStoppedBounded(process)) preIncomplete = true
            if (preIncomplete) {
                ElevatedCleanupTracker.noteUnclean()
            } else if (ElevatedCleanupTracker.outstanding() == 0) {
                ElevatedCleanupTracker.noteClean()
            }
            if (callerInterrupted) Thread.currentThread().interrupt()
            return Drained(
                stdout = ByteArray(0),
                stderr = ByteArray(0),
                exitCode = -1,
                timedOut = true,
                truncated = false,
                readFailed = false,
                cleanupIncomplete = preIncomplete,
            )
        }
        // P1-B：spawn 成功後立即關子進程 stdin（子進程 stdin EOF）：等 stdin EOF 的
        // 子進程（如 cat）不再假性 TimedOut。killer 內不關流，此處是唯一關 stdin 處
        //（drain kill 路徑另關已取的 stdout/stderr，不碰 stdin）。
        // 約束：本 drain 為無 stdin 模式（起手即關），未來需寫 stdin 必須另開
        // API，不得復用本函數（復用會與起手關流競態，並改變 EOF 語義）。
        // Y1 stdin 有界化（owner 新 P1 兩入口）：`outputStream` getter/close 可阻塞
        //（Shizuku 單次 Binder 亦可 stall；binder 死亡由狀態門 fail-closed，但
        // stall 本身無界）。改 daemon thread 執行＋主執行緒 join（上界
        // `min(剩餘 budget, STDIN_CLOSE_MAX_MS)`，計入同一 deadline）；超界即放行
        // 繼續（best-effort，後續 killer destroy 兜底），drain 總耗時仍有上界。
        // AB1 P1-A＋P0-1 三態：stdin-closer 啟動不預佔；尾收 grace 後仍存活才 [markLingering]
        // （每執行緒三態 [AtomicInteger] 0=init／1=held／2=done＋僅未計數時佔槽；
        // 本體 finally 釋放，見 [ElevatedCleanupTracker.markLingering]）。
        val stdinCounted = AtomicInteger(0)
        val stdinCloser = Thread({
            try {
                runCatching { process.outputStream.close() }
            } finally {
                synchronized(stdinCounted) {
                    if (stdinCounted.get() == 1) {
                        stdinCounted.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (stdinCounted.get() == 0) {
                        stdinCounted.set(2)
                    }
                }
            }
        }, "elevated-stdin-close").also {
            it.isDaemon = true
            it.start()
        }
        try {
            val stdinRemainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                .coerceAtLeast(0L)
            val stdinBudgetMs = minOf(stdinRemainMs, STDIN_CLOSE_MAX_MS)
            if (stdinBudgetMs > 0L) stdinCloser.join(stdinBudgetMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val out = ElevatedCappedPipe(hardCapBytes)
        // U2-B：stderr 獨立小 cap（null＝同 stdout cap；截圖傳 512K）。
        val err = ElevatedCappedPipe(hardCapErrBytes ?: hardCapBytes)
        // X1 getter 回 gobbler 內、整段 try/catch：throw 即 markFailed（W1 語義保留，
        // runner 側同一 "elevated IO read failed" 固定文案，不帶 remote 原文或輸出；
        // 無未捕捉例外）；block 則 caller 不受影響照常進 deadline/poll/kill。
        // @Volatile 語義由 AtomicReference 承載（函數內局部 var 不可 @Volatile，
        // 此處以原子引用持有已取流，供 kill 路徑關流）。
        // AB1 P1-A＋P0-1 三態：gobbler 啟動不預佔；join 超界 grace 後仍存活才
        // [markLingering]（三態＋僅未計數時佔槽；本體 finally 釋放，見上）。
        val outCounted = AtomicInteger(0)
        val errCounted = AtomicInteger(0)
        val inAcquired = AtomicReference<InputStream?>(null)
        val errAcquired = AtomicReference<InputStream?>(null)
        val tOut = Thread({
            try {
                val s = process.inputStream
                inAcquired.set(s)
                copyCapped(s, out)
            } catch (_: Exception) {
                out.markFailed()
            } finally {
                inAcquired.set(null)
                synchronized(outCounted) {
                    if (outCounted.get() == 1) {
                        outCounted.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (outCounted.get() == 0) {
                        outCounted.set(2)
                    }
                }
            }
        }, "elevated-stdout").also {
            it.isDaemon = true
            it.start()
        }
        val tErr = Thread({
            try {
                val s = process.errorStream
                errAcquired.set(s)
                copyCapped(s, err)
            } catch (_: Exception) {
                err.markFailed()
            } finally {
                errAcquired.set(null)
                synchronized(errCounted) {
                    if (errCounted.get() == 1) {
                        errCounted.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (errCounted.get() == 0) {
                        errCounted.set(2)
                    }
                }
            }
        }, "elevated-stderr").also {
            it.isDaemon = true
            it.start()
        }
        var finished = false
        // B3 有界 wait（owner P1-5）：直調 `process.waitFor(slice)` 在 Shizuku 同步
        // Binder stall 下無界卡住（slice／deadline／cancel 皆不生效）。現每片跑
        // `elevated-wait` worker（見 [ElevatedCallSupervisor.awaitWaitSlice]），主執行緒
        // 輪詢 cancel／deadline；超界即 kill 路徑（`finished=false`），殘留 worker
        // 由監督記槽（`markLingering`），`cleanupIncomplete` 由下方 kill 終審帶出。
        // 快路徑（`waitFor` 首片內即回）不額外調鉤（B2 計數語義不變）。
        // B3 wait 殘留旗標：`awaitWaitSlice` lingering 只記槽不直接 `noteUnclean`
        //（drain 終審統一計入，此處經本旗標傳遞，避免一次 drain 雙計）。
        var waitLingering = false
        try {
            while (true) {
                // P2 吞例外計數：isCancelled 抛即折為未取消（fail-closed，不誤殺；
                // 刻意靜默理由同上，計數見 swallowedIsCancelledThrows）。
                val cancelled = try {
                    isCancelled()
                } catch (_: Exception) {
                    swallowedIsCancelledThrows.incrementAndGet()
                    false
                }
                if (cancelled) break
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remaining <= 0) break
                val slice = minOf(remaining, POLL_SLICE_MS)
                when (val w = ElevatedCallSupervisor.awaitWaitSlice(process, slice, deadlineNanos, isCancelled)) {
                    is ElevatedCallSupervisor.Res.Ok -> if (w.v) {
                        finished = true
                        break
                    }
                    is ElevatedCallSupervisor.Res.Err -> break
                    is ElevatedCallSupervisor.Res.Expired -> {
                        // P1-4 per-worker 歸因：`hasLinger` 即本 slice worker 自身
                        // grace 後仍存活（[awaitInternal] 內 `worker.isAlive` 如實
                        // 回報），不經全域 `outstanding` 推斷。舊碼以
                        // `outstanding>0` 判定：他 call 殘留（outstanding 來自別的
                        // drain）會使本次乾淨超時亦置旗，經終審誤計 `noteUnclean`
                        // 誤觸 breaker；本 worker 真殘留時監督已記槽，
                        // `hasLinger==true` 與之一致，不漏計。終審仍由
                        // `cleanupIncomplete` 統一進 `noteUnclean`（良性超時無殘留
                        // 時 `outstanding==0` 仍走 `noteClean`，見尾收終審；此處只置旗，
                        // 不直接計數，避免雙計）。
                        if (w.hasLinger) {
                            waitLingering = true
                        }
                        break
                    }
                }
            }
        } catch (_: Exception) {
            // 輪詢異常收斂為未完成，走下方清理（fail-closed：timedOut，不偽造成功）。
        }
        // gobbler 有界 join 未完成標記：任一 join 超界/中斷後執行緒仍存活即部分讀，
        // 經 Drained.truncated 帶出（絕不靜默短讀不標）。
        var joinIncomplete = false
        // Z1 清理不完整旗標：kill 監督未完成或 stdin-closer 尾收殘留即 true
        //（runner verdict 映射不變，僅供 #136-B3 監測）。
        // P1-3：kill 終審推遲到尾收後（gobbler／stdin linger 亦計入，避免少計）。
        var cleanupIncomplete = false
        // B3：wait 殘留（`elevated-wait` worker stall）即清理缺口，不靜默。
        if (waitLingering) cleanupIncomplete = true
        if (!finished) {
            // X1：kill 前先 best-effort 中斷兩 gobbler（助可中斷 stalls 脫離，無害；
            // 若 getter 正阻塞於可中斷等待即抛，經 gobbler 整段 catch 收斂為 markFailed）。
            runCatching { tOut.interrupt() }
            runCatching { tErr.interrupt() }
            // Z1 順序：interrupt stdinCloser → 監督清理 → joins。stdin 先中斷
            // 助可中斷 stalls 脫離；尾收（見下方共通段）再確認殘留並記旗標。
            runCatching { stdinCloser.interrupt() }
            // Z1 kill 路徑清理監督化：killer＋已取流 close 包入單一 daemon worker，
            // 有界 join；超界 interrupt＋grace。永不跳過 killer 嘗試（下界保底）。
            // X1 語義保留：只關已取流，不再重調 getter（根除 kill 路徑 getter 阻塞）。
            val cleanupRemainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                .coerceAtLeast(0L)
            val cleanupBudgetMs = minOf(maxOf(cleanupRemainMs, CLEANUP_MIN_MS), CLEANUP_JOIN_MS)
            val cleanupOk = runCleanupBounded(cleanupBudgetMs) {
                // P2 吞例外計數：killer 抛即吞（best-effort，已盡力殺；刻意靜默，
                // 不升級為崩潰，計數見 swallowedKillerThrows；流 close 另逐段吞，
                // 預期 close 收斂，不計數）。
                try {
                    killer(process)
                } catch (_: Exception) {
                    swallowedKillerThrows.incrementAndGet()
                }
                runCatching { inAcquired.get()?.close() }
                runCatching { errAcquired.get()?.close() }
            }
            if (!cleanupOk) cleanupIncomplete = true
            // P1-3：終審推遲到尾收後（此處不再 noteClean/noteUnclean，避免
            // 後續 gobbler／stdin linger 少計；統一終審見尾收段）。
            // P1-3 post-kill 驗證（B2，killer true ≠ 進程真死）：killer 全
            // runCatching＋遠端 destroy 吞失敗，cleanupOk 只代表本機 thread
            // 離開，不代表父進程已停。此處以 waitFor／liveness 驗證父進程，
            // 失敗或不可驗證（binder 不可達視 unknown）即 cleanupIncomplete=true
            //（經尾收終審進 noteUnclean／breaker，不宣稱 cleaned）。子孫全滅
            // 仍為真機 blocker（JVM 真樹已驗，裝置 SELinux／Root 語義差異 E2E
            // 歸 #136-B3，見 ElevatedProcessKiller）。
            if (!ElevatedCallSupervisor.verifyParentStoppedBounded(process)) cleanupIncomplete = true
            // 超時/cancel 路徑保留有界 join：gobbler 仍可能卡住，避免無限等。
            // join 中斷必須 restore 中斷旗標（不吞 InterruptedException）。
            // AB1 P1-A：join 超界仍存活即殘留 gobbler（入口已中斷兩 gobbler，
            // 此處不再重斷；kill 監督＋kill 等待已是上界，不另給 grace）
            // → [markLingering] 計數＋旗標帶出。
            try {
                tOut.join(GOBBLER_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (tOut.isAlive) {
                    joinIncomplete = true
                    cleanupIncomplete = true
                    ElevatedCleanupTracker.markLingering(tOut, outCounted)
                }
            }
            try {
                tErr.join(GOBBLER_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (tErr.isAlive) {
                    joinIncomplete = true
                    cleanupIncomplete = true
                    ElevatedCleanupTracker.markLingering(tErr, errCounted)
                }
            }
        } else {
            // AB1 P1-A：本分支不再起手 noteClean；改由尾收後統一判定
            // `if (cleanupIncomplete) noteUnclean() else if (outstanding()==0) noteClean()`
            //（cleanupIncomplete 含殘留 gobbler／stdin；正常完成且全域無殘留仍
            // noteClean，不誤判良性；讀失敗不計，見尾收終審）。早拒路徑（門拒／
            // 配額拒，spawn 前）無 drain 執行，不碰計數器。
            // P1-4 finished tail budget：雙 gobbler joins 共享
            // `finishedTailBudget = min(剩餘 deadline, FINISHED_TAIL_BUDGET_MS=2s)`。
            // 舊碼兩段 SUCCESS_JOIN_MS（10s）＋grace 無 remaining／isCancelled 輪詢：
            // 300ms timeout＋已 exit 但流不 EOF 可釘 20s＋。現每片（POLL_SLICE_MS）
            // 後查 isCancelled／中斷／deadline，命中即 interrupt＋markLingering＋
            // 雙旗＋break（第二流亦跳過等待，直接中斷記槽）；自然耗盡（budget 用罄
            // 仍存活）即 interrupt＋capped grace（grace 亦取自剩餘 tail，不另加
            // budget）＋重判 `isAlive`（P0-2），仍存活即雙旗＋記槽。
            // SUCCESS_JOIN_MS（10s）保留為單流絕對上界（tail 2s 下實效不觸達，
            // 只防 tail 預算未來放寬後的單流無界）；grace 亦 capped，不清除已記標記
            //（超 tail 的讀數不視為完整，Y1 語義保留）。正常慢讀 tail 內完成即無
            // interrupt，不受影響；只有卡死執行緒才走到記槽分支。
            // 無界 join 在遠端流永不 EOF 時可卡死超 timeoutMs，故不用。
            val tailBudgetMs = minOf(
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L),
                FINISHED_TAIL_BUDGET_MS,
            )
            val tailDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(tailBudgetMs)
            fun tailRemainMs(): Long = minOf(
                TimeUnit.NANOSECONDS.toMillis(tailDeadlineNanos - System.nanoTime()).coerceAtLeast(0L),
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L),
            )
            var tailAborted = false
            fun joinFinishedTail(t: Thread, counted: AtomicInteger) {
                if (tailAborted) {
                    // 前一流已因取消／逾期 abort：本流不再等，直接中斷＋雙旗＋記槽。
                    if (t.isAlive) {
                        runCatching { t.interrupt() }
                        joinIncomplete = true
                        cleanupIncomplete = true
                        ElevatedCleanupTracker.markLingering(t, counted)
                    }
                    return
                }
                while (t.isAlive) {
                    // P2 吞例外計數：isCancelled 抛即折為未取消（fail-closed，不誤殺；
                    // 計數見 swallowedIsCancelledThrows）。
                    val cancelled = try {
                        isCancelled()
                    } catch (_: Exception) {
                        swallowedIsCancelledThrows.incrementAndGet()
                        false
                    }
                    if (cancelled || Thread.currentThread().isInterrupted ||
                        (deadlineNanos - System.nanoTime()) <= 0L
                    ) {
                        // 取消／呼叫執行緒中斷／deadline 耗盡：不再等流。
                        runCatching { t.interrupt() }
                        joinIncomplete = true
                        cleanupIncomplete = true
                        ElevatedCleanupTracker.markLingering(t, counted)
                        tailAborted = true
                        return
                    }
                    // P1-4 `join(0)` 防呆：`join(0)` 即無限等待。`tailRemainMs()` 取兩次
                    // （檢查與等待量）之間可被調度切走使第二次回 0，故單次取值、
                    // 非正即 break，傳入 join 的等待量恒正。
                    val waitMs = minOf(tailRemainMs(), POLL_SLICE_MS)
                    if (waitMs <= 0L) break
                    try {
                        t.join(waitMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (t.isAlive && !tailAborted) {
                    runCatching { t.interrupt() }
                    val graceMs = minOf(tailRemainMs(), SUCCESS_JOIN_GRACE_MS)
                    if (graceMs > 0L) {
                        try {
                            t.join(graceMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    // P0-2：grace 後必須重讀 `isAlive` 再判定（舊碼以 grace 前快照置旗，
                    // 而三態 mark 對已退出執行緒不佔槽，造成無槽 noteUnclean）。
                    if (t.isAlive) {
                        joinIncomplete = true
                        cleanupIncomplete = true
                        ElevatedCleanupTracker.markLingering(t, counted)
                    }
                }
            }
            joinFinishedTail(tOut, outCounted)
            joinFinishedTail(tErr, errCounted)
        }
        // Z1 stdin-closer 生命週期收斂（兩路徑共通尾收）：gobbler joins 後仍存活即
        // interrupt＋grace 重 join，仍存活即 cleanupIncomplete=true（daemon 殘留
        // 8s 類不可中斷 stall 由旗標帶出，不靜默；正常完成即無 interrupt 影響）。
        // AB1：仍存活即殘留 stdin-closer → [markLingering] 計數（啟動不預佔，
        // 僅此處佔槽；本體 finally 釋放，見 [ElevatedCleanupTracker.markLingering]）。
        if (stdinCloser.isAlive) {
            runCatching { stdinCloser.interrupt() }
            try {
                stdinCloser.join(CLEANUP_GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (stdinCloser.isAlive) {
                cleanupIncomplete = true
                ElevatedCleanupTracker.markLingering(stdinCloser, stdinCounted)
            }
        }
        // P1-3＋P0-1 統一終審（兩路徑共通，kill 終審推遲到此，避免少計）：
        // - cleanupIncomplete（含 kill 監督未完成、殘留 gobbler／stdin）即
        //   noteUnclean 累計（kill 路徑 cleanupOk==false 已併入本旗標；kill 後
        //   gobbler linger 亦併入，不再少計；finished 殘留同理）；
        // - 無殘留（cleanupIncomplete==false）僅全域無殘留（outstanding==0）才
        //   noteClean；殘留期間（outstanding>0）的乾淨 drain 保持計數
        //  （不歸零亦不遞增），避免乾淨呼叫沖掉熔斷（P0-1）。
        // 讀失敗（out／err.failed）不計入連續計數：finished 下讀異常必為真實 I／O
        // 錯誤但不持有任何資源（執行緒已退出、流已關），verdict 已回 Failed；
        // 連續計數只計資源清理未完成（殘留執行緒／worker 持槽），避免數據錯誤
        // 誤觸熔斷。
        // B3 有界 exitValue 先行（P2 中斷恢復之前）：`exitValueBounded` 以
        // `isInterrupted` 判超界，須在 caller 預置中斷恢復前求值，否則恢復後的
        // 置旗會使健康進程誤判為 stall（P2 `drainPresetCallerInterrupt...` 回歸）。
        // P1-4 tail 中斷快照清除：finished joins 在 `InterruptedException` 後一律
        // restore 旗標（見上兩分支 catch），若呼叫執行緒在 tail 期間被中斷（或預置
        // 中斷在某 join 內再次抛），此處旗標已置；不清即 `exitValueBounded` 的
        // cancelCheck（`isInterrupted || isExpired`）必命中，健康進程亦 Expired→
        // exit -1 誤報（kill -1 同值但 `timedOut` 區分，見下註記；此處 finished 下
        // -1 會使 runner 誤判 Failed）。故先 `Thread.interrupted()` 快照清除，
        // exitValue 用獨立 deadline（[ElevatedCallSupervisor.EXIT_JOIN_MS]，不耗
        // 主 deadline 剩餘），求值後立即恢復（出口 `callerInterrupted` 恢復仍執行，
        // caller 中斷不丟失）。
        // Expired（stall 殘留）即置 `cleanupIncomplete`，由下方終審統一進 breaker。
        val joinInterrupted = Thread.interrupted()
        val exitCode: Int = if (finished) {
            when (val ev = ElevatedCallSupervisor.exitValueBounded(process)) {
                is ElevatedCallSupervisor.Res.Ok -> ev.v
                is ElevatedCallSupervisor.Res.Err -> -1
                is ElevatedCallSupervisor.Res.Expired -> {
                    cleanupIncomplete = true
                    -1
                }
            }
        } else -1
        if (joinInterrupted) Thread.currentThread().interrupt()
        if (cleanupIncomplete) {
            ElevatedCleanupTracker.noteUnclean()
        } else if (ElevatedCleanupTracker.outstanding() == 0) {
            ElevatedCleanupTracker.noteClean()
        }
        // P2 caller 中斷恢復：入口預置中斷出口恢復一次（不吞 caller 中斷）。
        if (callerInterrupted) Thread.currentThread().interrupt()
        return Drained(
            stdout = out.bytes(),
            stderr = err.bytes(),
            // P2 exit -1 難分註記：finished 下 exitValue() 抛（遠端漂移）即 -1，
            // 與 kill 路徑（!finished）-1 同值但以 timedOut 區分（false vs true），
            // 不可僅看 exitCode；runner 側 finished -1 即 Failed（非零分支），
            // kill -1 即 TimedOut，verdict 不混。
            exitCode = exitCode,
            timedOut = !finished,
            // R1-A：join 未完成或任一流硬上限溢出（>hardCapBytes 丟棄）即 truncated；
            // 通用 512K 溢出本就經 64K truncate 標記，此處 OR 不改其 verdict；
            // 截圖 8M==8M 時需此位區分「恰 8M 通過」與「>8M 丟棄」（後者 truncate(8M,8M)
            // 無法檢出，故必須由溢出位帶出，否則 >8M 會誤判為完整幀）。
            truncated = joinIncomplete || out.didOverflow() || err.didOverflow(),
            // V2：任一流 gobbler 讀取異常即 true（無條件記錄；超時/取消路徑
            // 已先回 TimedOut，runner 側按 finished 區分語義，見 Drained.readFailed）。
            readFailed = out.failed || err.failed,
            cleanupIncomplete = cleanupIncomplete,
        )
    }

    /**
     * Z1 清理監督 worker（owner 新 P1）：daemon thread 執行 [block]（killer＋已取流
     * close），主執行緒 join 上界 [timeoutMs]；超界即 interrupt＋[CLEANUP_GRACE_MS]
     * grace 重 join，回是否完成（grace 後仍存活即 false，殘留 daemon 由
     * [Drained.cleanupIncomplete] 帶出，不靜默）。每 drain 至多 1 worker，
     * daemon 故不擋 JVM 退出；不可中斷 Binder stall 殘留 daemon 至多存活至 block
     * 自然返回（如 8s 睡眠模型），呼叫方有界返回不受影響。
     *
     * AA1 跨呼叫計數（owner 新 P1）：worker 啟動即 [ElevatedCleanupTracker.acquireSlot]
     * （飽和即拒：回 false 即無槽，見下）、finally 僅已佔槽才
     * [ElevatedCleanupTracker.releaseSlot]（正常完成即釋放；永久卡住期間
     * 即殘留計數）。drain 層飽和亦不跳過 killer（入口拒絕由 runner 資源門承擔，
     * 此處無槽仍嘗試，回 false 保守記不完整）。
     *
     * P1-4 `start()` 抛收斂：`Thread.start()` 抛（`IllegalThreadState`／
     * `SecurityException` 等）即先釋放已佔槽再回 false（呼叫方置
     * `cleanupIncomplete=true` 統一進終審），不抛穿透 drain（drain 全路徑有界
     * 返回契約：任何內部建線程失敗皆收斂為旗標，不把異常抛給呼叫方）。
     *
     * P1-4 飽和即拒（[acquireSlot] 回 false）：無槽仍起 worker 嘗試 killer
     * （永不跳過），但回 false（無法記槽即無法自證有界完成，保守記不完整，
     * 呼叫方置 `cleanupIncomplete=true`）；worker 本體僅已佔槽才釋放
     * （未佔而釋會扣減他人槽位，見 `releaseSlot` floor 語義）。
     *
     * AB1 P1-A：gobbler／stdin-closer 殘留改經
     * [ElevatedCleanupTracker.markLingering] 計數（啟動不預佔，僅 grace 後仍存活
     * 才佔槽；執行緒本體 finally 釋放）；清理 worker 保持本函數既有語義不變。
     */
    private fun runCleanupBounded(timeoutMs: Long, block: () -> Unit): Boolean {
        // AA1: worker 啟動即佔一槽（呼叫執行緒計數，避免啟動競態漏計），finally 釋放
        // （正常完成即--；永久卡住即殘留計數，見 ElevatedCleanupTracker）。
        // 飽和時 drain 層仍嘗試監督清理（永不跳過 killer，只記錄）：入口拒絕由兩
        // runner 資源門承擔，此處不拒絕，只計數；無槽（acquireSlot 回 false）仍嘗試，
        // 但回 false 保守記不完整（見本函數 P1-4 段）。
        val acquired = ElevatedCleanupTracker.acquireSlot()
        val t = Thread({
            try {
                block()
            } catch (_: Exception) {
                // block 內已逐段 runCatching，此處僅兜底線程體異常，不外抛。
            } finally {
                if (acquired) ElevatedCleanupTracker.releaseSlot()
            }
        }, "elevated-cleanup").also {
            it.isDaemon = true
            try {
                it.start()
            } catch (_: Exception) {
                if (acquired) ElevatedCleanupTracker.releaseSlot()
                return false
            }
        }
        try {
            t.join(timeoutMs.coerceAtLeast(0L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (t.isAlive) {
            runCatching { t.interrupt() }
            try {
                t.join(CLEANUP_GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // P1-4 飽和即拒：未佔槽（acquired==false）即回 false（上已嘗試 killer，
        // 永不跳過；但無法記槽即無法自證有界完成，保守記不完整）。
        return acquired && !t.isAlive
    }

    /**
     * P1-3 post-kill 父進程停止驗證（B2，僅 kill 路徑調用）：
     * killer 不抛≠已停（destroy／destroyForcibly 全 runCatching，遠端 destroy
     * 吞失敗），cleanupOk 只代表監督 worker 離開。此函數以 `waitFor` 回傳為
     * 主證據、liveness（`isAlive`）為輔助，僅可驗證已停才回 true：
     * - `waitFor(KILL_WAIT_SEC)` 回 true：已停；再讀 `isAlive`，僅其明確回 true
     *   才翻為未停（矛盾競態），`isAlive` 抛即忽略（主證據已足）；
     * - `waitFor` 回 false：5s  grace 仍存活，未停；
     * - `waitFor` 抛（遠端 binder 死亡等）或中斷：主證據缺失，退讀 `isAlive`；
     *   明確 false 才算已停，true／抛／中斷一律 unknown→false（binder 不可達
     *   視 unknown，fail-closed 為 incomplete，不宣稱 cleaned）。
     * 中斷一律 restore，不吞 caller 中斷；子孫全滅不在此驗（JVM 真樹已驗，
     * 裝置語義差異 E2E 歸 #136-B3，見 [ElevatedProcessKiller]）。
     */
    private fun verifyParentStopped(process: Process): Boolean {
        val exited: Boolean? = try {
            process.waitFor(KILL_WAIT_SEC, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
        if (exited == null) {
            return try {
                !process.isAlive
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (_: Exception) {
                false
            }
        }
        if (!exited) return false
        return try {
            !process.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        } catch (_: Exception) {
            true
        }
    }

    /**
     * 有界截斷管（Y1 由內嵌 `CappedPipe` 更名提升，行為零變更，僅為隔離可測）：
     * 硬上限 `hardCapBytes` 內保留前綴，超額丟棄並經 [didOverflow] 單調標溢出
     * （drain 併入 `Drained.truncated`，絕不靜默短讀不標）；讀取異常經 [markFailed]
     * 單調標 [failed]（drain 併入 `Drained.readFailed`）。`internal` 僅為同模組
     * 隔離單測（見 `ElevatedCappedPipeTest`），產品語義不變。
     */
    internal class ElevatedCappedPipe(private val hardCapBytes: Int) {
        private val sink = ByteArrayOutputStream()
        @Volatile private var overflowed = false
        /** V2 gobbler 讀取失敗旗標（owner 新 P1-2）：`copyCapped` 內 `read` 抛即標 true，單調不清除。 */
        @Volatile var failed: Boolean = false
            private set
        /** 無條件記錄讀取異常（超時/取消殺後關流的預期 close 亦標 true，由呼叫方按 finished 區分語義）。 */
        fun markFailed() {
            failed = true
        }
        fun write(buf: ByteArray, n: Int) {
            // S3 競爭收斂（PocketGuard SUGGESTION，語義不變）：鎖外先讀 size
            // 快照做快速判斷（room<=0 即標溢出直接返回；n>room 預標溢出），
            // 鎖內只做最終判定＋寫入。快照僅啟發式（stale 亦無害：溢出位單調，
            // 早標不錯；最終是否寫／寫多少一律以鎖內 room 為準，故絕不溢寫）。
            val roomSnap = hardCapBytes - runCatching { sink.size() }.getOrDefault(0)
            if (roomSnap <= 0) {
                overflowed = true
                return
            }
            if (n > roomSnap) overflowed = true
            synchronized(sink) {
                val room = hardCapBytes - sink.size()
                if (room <= 0) {
                    overflowed = true
                    return
                }
                if (n > room) overflowed = true
                sink.write(buf, 0, minOf(n, room))
            }
        }
        /** 是否已達硬上限（P1-B review：僅觀測/斷言用；cap 強制由 write 內丟棄承擔）。 */
        fun isFull(): Boolean = synchronized(sink) { sink.size() >= hardCapBytes }
        /** 硬上限溢出（丟棄過任一位元組）：drain 經 [Drained.truncated] 帶出，不靜默短讀。 */
        fun didOverflow(): Boolean = overflowed
        fun bytes(): ByteArray = synchronized(sink) { sink.toByteArray() }
    }

    private fun copyCapped(stream: java.io.InputStream, pipe: ElevatedCappedPipe) {
        val buf = ByteArray(COPY_BUF_SIZE)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                pipe.write(buf, n)
                if (pipe.isFull()) {
                    // P1-B review：超標後仍繼續讀丟棄，絕不關流（省 CPU 的優化撤回，
                    // 正確性優先）。提前關讀端會致寫端 EPIPE/SIGPIPE，使本可
                    // exit 0 + truncated 的成功改寫為 141 轉 Failed。
                    // OOM 防護由 write() 內 hardCap 丟棄超額承擔，語義不變
                    //（通用雙流 512K／截圖 stdout 8M＋stderr 512K，見 drain 的
                    // hardCapBytes／hardCapErrBytes）。
                    // （此分支無動作，僅留檢查點明語義；讀丟棄經由下輪 read 繼續。）
                }
            }
        } catch (_: Exception) {
            // V2：無條件記錄讀取失敗（finished 下必為真實 I/O 錯誤，經
            // Drained.readFailed 帶出；被殺時流中斷亦標 true，但超時/取消路徑
            // 已先回 TimedOut，不受影響，預期 close 收斂保留）。
            pipe.markFailed()
        } finally {
            runCatching { stream.close() }
        }
    }
}

/**
 * 兩 runner 共用拒絕字串/ verdict（P2-B 收斂，僅 github 風味）。
 *
 * - `validate→reasonCode→quota→spawn→truncate→timeout→exit` 順序下，
 *   `reasonCode` 缺失與配額耗盡兩處拒絕字串原在 [ShizukuShellRunner] /
 *   [RootSuRunner] 各寫一次（改一漏一風險），收斂至此；
 * - verdict 映射單一來源：缺原因碼一律 `BAD_ARGUMENT`，配額耗盡一律
 *   `QUOTA_EXCEEDED`（與 [dev.librepocket.shell.ShellDeny] 對齊，不另發明碼）。
 *
 * AA1 資源門（owner 新 P1）：清理 backlog 飽和（[cleanupBacklogFull]）與連續不清創
 * 熔斷（[cleanupBreakerOpen]，訊息區分兩因）拒絕收斂至此，與 quota 同級，同為
 * `QUOTA_EXCEEDED` 且零 spawn；飽和＝有不可回收清理，#136-B3 需介入，見
 * [ElevatedCleanupTracker]）。
 *
 * AB1 呼叫級准入（owner 新 P1-A＋P1-B）：併發上限（[concurrencyFull]，訊息含
 * "concurrency full" 以區分飽和／熔斷兩因）拒絕收斂至此，與 quota 同級，同為
 * `QUOTA_EXCEEDED` 且零 spawn、不耗配額（准入在飽和／熔斷門之後、配額之前；
 * 成功准入後剩餘 runBinary 包 `try/finally` 釋放，見兩 runner）。
 */
internal object ElevatedRunnerCommon {
    /** 原因碼缺失拒絕訊息（矩陣 §3 審計要求原因碼非空）。 */
    const val REASON_CODE_REQUIRED_MESSAGE = "reasonCode required (matrix §3 audit)"

    /** 截圖 argv 精確匹配（R1-A 雙軌＋U2-B stderr 小 cap 共用判定，兩 runner 共用）。 */
    internal fun isScreenshotArgv(argv: List<String>): Boolean =
        argv == listOf("screencap", "-p") || argv == listOf("cat", "/dev/graphics/fb0")

    /** 配額耗盡拒絕訊息。 */
    const val QUOTA_EXCEEDED_MESSAGE = "elevated shell quota exceeded"

    /** 原因碼為空（blank）時的 typed 拒絕：`BAD_ARGUMENT`，不建子進程。 */
    fun missingReasonCode(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.BAD_ARGUMENT, REASON_CODE_REQUIRED_MESSAGE)

    /** 配額耗盡時的 typed 拒絕：`QUOTA_EXCEEDED`，不建子進程。 */
    fun quotaExceeded(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, QUOTA_EXCEEDED_MESSAGE)

    /** 清理 backlog 飽和拒絕訊息（AA1 資源門，與 quota 同級）。 */
    const val CLEANUP_BACKLOG_FULL_MESSAGE =
        "elevated cleanup backlog full (fail-closed, no spawn)"

    /** 清理 backlog 飽和時的 typed 拒絕：`QUOTA_EXCEEDED`，不建子進程。 */
    fun cleanupBacklogFull(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, CLEANUP_BACKLOG_FULL_MESSAGE)

    /** 連續不清創熔斷拒絕訊息（終審殘留，與飽和同級，零 spawn、不耗配額）。 */
    const val CLEANUP_BREAKER_OPEN_MESSAGE =
        "elevated cleanup breaker open (consecutive unclean, fail-closed, no spawn)"

    /** 連續不清創熔斷時的 typed 拒絕：`QUOTA_EXCEEDED`，不建子進程。 */
    fun cleanupBreakerOpen(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, CLEANUP_BREAKER_OPEN_MESSAGE)

    /** 呼叫級併發上限拒絕訊息（AB1 P1-B 准入門，與 quota 同級，零 spawn、不耗配額）。 */
    const val CONCURRENCY_FULL_MESSAGE =
        "elevated concurrency full (fail-closed, no spawn)"

    /** 併發上限時的 typed 拒絕：`QUOTA_EXCEEDED`，不建子進程。 */
    fun concurrencyFull(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, CONCURRENCY_FULL_MESSAGE)

    /**
     * P1-4 spawn 前取消檢查（B2，兩 runner 共用；P1-2/P1-3 語義保留，不看 deadline）：
     * 任何 spawn 前調用（入口＋最後授權重驗後，確保中途取消亦零新 spawn）。
     * - `Thread.currentThread().isInterrupted`（不清旗標，保留原始 interrupt）
     *   或 `isCancelled()` 任一為 true 即已取消；
     * - `isCancelled()` 抛即視為已取消（對尚未 spawn fail-closed：drain 內抛折
     *   為未取消是為不誤殺已建進程，未建時 fail-closed 方向是拒新 spawn；
     *   計數沿用 [ElevatedProcessIo.swallowedIsCancelledThrows]，不改 verdict
     *   映射外的可觀測）；
     * - 永不清 interrupt（不用 `Thread.interrupted()`），不把已取消翻成未取消；
     *   中斷下清理 join 有界由 drain 既有上界承擔。
     * 已取消時呼叫方回 `TimedOut`（空 partial，`cleanupIncomplete=false`，
     * 無進程故無殘留），零 spawn、不耗配額（配額前檢查）／已占席由 finally 釋放。
     *
     * P1-3 deadline 語義分離（PR140 Phase 2）：本檢查刻意不看 deadline（中斷／
     * 取消語義純粹，B2 計數不變）；budget 耗盡由各層顯式 `isExpired(deadline)`
     * 承擔——supervisor 入口 preflight（[ElevatedCallSupervisor]）、quota 後
     * 提交前檢查、runner 入口 `safeTimeoutMs<=0` 早拒（見兩 runner），逾期映射
     * 一律經 [preSpawnExpired]（`hasLinger` 決定旗標）。
     */
    internal fun isPreSpawnCancelled(isCancelled: () -> Boolean): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        return try {
            isCancelled()
        } catch (_: Exception) {
            runCatching { ElevatedProcessIo.swallowedIsCancelledThrows.incrementAndGet() }
            true
        }
    }

    /**
     * P1-2/P1-3 逾期 spawn 前 verdict（PR140 Phase 2，兩 runner 共用）：
     * 零 handoff 的 `TimedOut`（空 partial）。
     * - `hasLinger=false`：worker 從未啟動（入口 preflight／quota 後顯式檢查）或
     *   逾期值非 Process——無 late 進程缺口，`cleanupIncomplete=false`（舊
     *   `preSpawnCancelled` 語義，非 spawn 逾期與取消共用）；
     * - `hasLinger=true`：supervisor worker grace 後仍存活或確有逾期交付——
     *   late Process 缺口（Binder 已送達不可撤回，補償結果未知），
     *   `cleanupIncomplete=true`（僅 spawn 逾期置 true，見 [ElevatedCallSupervisor]）。
     */
    internal fun preSpawnExpired(timeoutMs: Long, hasLinger: Boolean): ElevatedBinaryResult.TimedOut =
        ElevatedBinaryResult.TimedOut(
            ByteArray(0),
            ByteArray(0),
            timeoutMs,
            false,
            hasLinger,
        )
}
