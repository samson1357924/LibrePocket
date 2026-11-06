package dev.librepocket.privilege.github

import androidx.annotation.VisibleForTesting
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 * - 成功路徑亦有界等待（上界 `SUCCESS_JOIN_MS` 10s，見 [drain]）：
 *   `finished==true` 後若 gobbler 流永不 EOF（遠端流未關），有界 join 超界即停
 *   並經 [Drained.truncated] 標截斷（絕不靜默短讀不標）；
 * - 任何路徑 drain 總耗時有上界（timeoutMs + 常數），不無限卡。
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
 * - finished 路徑 join 超時 interrupt：兩處 SUCCESS_JOIN（10s）join 後仍存活即
 *   interrupt＋短 grace（SUCCESS_JOIN_GRACE_MS=1s）重 join 回收線程；首段超界即記
 *   不完整（grace 不清除標記）。只影響卡死執行緒（10s 內完成即無 interrupt）；
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
     * 跨呼叫未完成清理計數上限（AA1，owner 新 P1＋P2）。
     *
     * 每殘留清理 worker 持有 Process＋已取流＋Binder 參照（Shizuku 遠端 stall 時含
     * Binder 代理），無界殘留即無界 fd／執行緒／binder 參照洩漏。4 為有界併發經驗值
     * （單次 drain 至多 1 worker，允許數次偶發 stall 疊加而不立即拒絕；持續 stall
     * 即飽和 fail-closed，#136-B3 可調本常數）。
     *
     * 飽和（[isSaturated]）即 #136-B3 blocker 條件（見 [PrivilegeActivation]：清理缺口未解
     * 前產品不得放行，runner 入口資源門拒絕新 spawn）。
     *
     * P1-2 雙預算分離（PR140 Phase1）：本上限同時承擔併發預留檢查
     *（見 [SLOTS_PER_CALL]／[tryEnterReserved]）：准入要求
     * `outstanding + (active+1) * SLOTS_PER_CALL <= MAX_OUTSTANDING_CLEANUPS`。
     * 以 MAX=4、SLOTS=4 計，乾淨態（outstanding==0）僅允單一進行中呼叫
     *（0+1*4<=4 通過，0+2*4<=4 拒絕）：實效接近串行。特權通道宜序列
     *（單次 drain 至多 1 清理 worker＋雙 gobbler＋stdin-closer），#136-B3 可調本常數
     *（放寬即允更高併發，但最壞超額同步放大，見 [tryEnterReserved] KDoc）。
     */
    const val MAX_OUTSTANDING_CLEANUPS = 4

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
     * 單呼叫槽位上界（P1-2，PR140 Phase1）。
     *
     * 單次 drain 最壞持有：1 清理 worker（kill 路徑 [runCleanupBounded]）＋
     * 2 gobbler（stdout／stderr 殘留 [markLingering]）＋1 stdin-closer 殘留
     * ＝4 槽。准入預留以此上界估算（見 [tryEnterReserved]），寧可保守拒絕
     * 也不超額。
     */
    const val SLOTS_PER_CALL = 4

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
     * 特權宜序列：MAX=4、SLOTS=4 時乾淨態僅允單一進行中呼叫
     *（0+1*4<=4 通過，0+2*4<=4 拒絕），實效接近串行；#136-B3 可調 MAX／SLOTS
     * 放寬併發，但最壞超額同步放大（理論最壞 `outstanding + active*SLOTS`
     * 可達 `MAX + MAX_CONCURRENT*SLOTS`，現參數下單槽實測峰值有界，見
     * 迴歸測試 mixed-backlog）。呼叫方成功（OK）後須 `try/finally{leaveCall()}`
     * 包住剩餘 runBinary；拒絕路徑零 spawn、不耗配額、不佔席。
     *
     * [Admission.CONCURRENCY_FULL] 保留但現參數下實效不可達（預留檢查先拒：
     * 乾淨態第二呼叫即 `0+2*4>4` 走 BACKLOG_FULL，不會走到 `active>=4`），
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

    /** 佔一槽（[ElevatedProcessIo.drain] 經 `runCleanupBounded` 或 [markLingering] 調用；同鎖內同步更新峰值）。 */
    internal fun acquireSlot() {
        synchronized(lock) {
            outstandingCount.incrementAndGet()
            val cur = outstandingCount.get()
            if (cur > peakOutstanding.get()) peakOutstanding.set(cur)
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
     */
    internal fun markLingering(thread: Thread, state: AtomicInteger) {
        synchronized(state) {
            if (state.get() != 0) return
            if (thread.state == Thread.State.TERMINATED) {
                state.set(2)
                return
            }
            state.set(1)
            acquireSlot()
        }
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
    private const val KILL_WAIT_SEC = 5L
    /** Y1 stdin 有界關閉上界：`outputStream` getter/close 在 daemon thread 執行，主執行緒至多等本值（另受剩餘 budget 截頂），超界即放行。 */
    private const val STDIN_CLOSE_MAX_MS = 2_000L
    /**
     * Z1 清理監督上界（owner 新 P1）：kill 路徑 `killer`＋已取流 close 包入單一
     * daemon worker（見 [runCleanupBounded]），主執行緒 join 上界本值；
     * 實際 budget 為 `min(max(remaining, CLEANUP_MIN_MS), CLEANUP_JOIN_MS)`
     * （remaining 為 kill 進入時剩餘 deadline；永不跳過 killer 嘗試，最少仍給
     * [CLEANUP_MIN_MS]）。每 drain 至多 1 清理 worker（daemon），drain 總耗時
     * 上界為 timeoutMs＋3s＋既有 joins（kill 等待＋gobbler joins＋stdin 尾收）；
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
                val done = try {
                    process.waitFor(slice, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                if (done) {
                    finished = true
                    break
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
            if (!verifyParentStopped(process)) cleanupIncomplete = true
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
            // 成功路徑亦有界等待（P1-B review）：上界 SUCCESS_JOIN_MS（10s），
            // 超界即停並標 truncated（絕不靜默短讀不標）；drain 總耗時有上界
            //（timeoutMs + 常數），不無限卡。
            // 無界 join 在遠端流永不 EOF 時可卡死超 timeoutMs，故不用。
            // Y1（owner 新 P1 兩入口）：首段超界仍存活即 best-effort interrupt＋
            // 短 grace（SUCCESS_JOIN_GRACE_MS）重 join 回收線程。首段超界即記
            // 不完整（grace 只為回收，不清除標記：超 10s 的讀數不視為完整）。
            // 正常慢讀 10s 內完成即無 interrupt，不受影響；只有卡死執行緒才走到此分支。
            // P0-2：grace 後必須重讀 `isAlive` 再判定（舊碼以 grace 前快照置旗，
            // 而三態 mark 對已退出執行緒不佔槽，造成無槽 noteUnclean）。
            try {
                tOut.join(SUCCESS_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (tOut.isAlive) {
                runCatching { tOut.interrupt() }
                try {
                    tOut.join(SUCCESS_JOIN_GRACE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (tOut.isAlive) {
                joinIncomplete = true
                cleanupIncomplete = true
                ElevatedCleanupTracker.markLingering(tOut, outCounted)
            }
            try {
                tErr.join(SUCCESS_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (tErr.isAlive) {
                runCatching { tErr.interrupt() }
                try {
                    tErr.join(SUCCESS_JOIN_GRACE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (tErr.isAlive) {
                joinIncomplete = true
                cleanupIncomplete = true
                ElevatedCleanupTracker.markLingering(tErr, errCounted)
            }
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
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
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
     * AA1 跨呼叫計數（owner 新 P1）：worker 啟動即 [ElevatedCleanupTracker.acquireSlot]、
     * finally [ElevatedCleanupTracker.releaseSlot]（正常完成即釋放；永久卡住期間
     * 即殘留計數）。drain 層飽和亦不跳過 killer（入口拒絕由 runner 資源門承擔）。
     *
     * AB1 P1-A：gobbler／stdin-closer 殘留改經
     * [ElevatedCleanupTracker.markLingering] 計數（啟動不預佔，僅 grace 後仍存活
     * 才佔槽；執行緒本體 finally 釋放）；清理 worker 保持本函數既有語義不變。
     */
    private fun runCleanupBounded(timeoutMs: Long, block: () -> Unit): Boolean {
        // AA1: worker 啟動即佔一槽（呼叫執行緒計數，避免啟動競態漏計），finally 釋放
        // （正常完成即--；永久卡住即殘留計數，見 ElevatedCleanupTracker）。
        // 飽和時 drain 層仍嘗試監督清理（永不跳過 killer，只記錄）：入口拒絕由兩
        // runner 資源門承擔，此處不拒絕，只計數。
        ElevatedCleanupTracker.acquireSlot()
        val t = Thread({
            try {
                block()
            } catch (_: Exception) {
                // block 內已逐段 runCatching，此處僅兜底線程體異常，不外抛。
            } finally {
                ElevatedCleanupTracker.releaseSlot()
            }
        }, "elevated-cleanup").also {
            it.isDaemon = true
            try {
                it.start()
            } catch (e: Exception) {
                ElevatedCleanupTracker.releaseSlot()
                throw e
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
        return !t.isAlive
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
     * P1-4 spawn 前取消檢查（B2，兩 runner 共用）：
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

    /** P1-4 已取消的 spawn 前 verdict：零 spawn 的 TimedOut（無 partial，無殘留）。 */
    internal fun preSpawnCancelled(timeoutMs: Long): ElevatedBinaryResult.TimedOut =
        ElevatedBinaryResult.TimedOut(
            ByteArray(0),
            ByteArray(0),
            timeoutMs,
            false,
            false,
        )
}
