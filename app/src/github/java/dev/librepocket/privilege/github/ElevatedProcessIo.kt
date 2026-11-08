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
 *   [BINARY_SCREENSHOT_HARD_CAP_BYTES]（8M），pipe 內保留上界 stdout 8M
 *   （非程序峰值，見 V2 誠實說明）；
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
     * R3-PhaseF 重核（P1-2/P2-1，常數不動，仍為 7）：
     * - 入口 cancel 門移入准入之後（pre-admission 零 worker，見兩 runner）：門
     *   worker 即席內首個監督殘留候選，不新增疊加項；
     * - gates 慢路徑：block worker＋[CancelWatch] watcher 至多 2 殘留，且首個
     *   Expired 即回 runBinary，不與 drain 疊加（`2 < 7`，被既有 pre-spawn 項覆蓋）；
     * - spawn 慢路徑：worker＋watcher＋late daemon 至多 3（Expired 即回，
     *   不與 drain 疊加）；
     * - drain：kill 路徑 6＋drain 級 watcher 1 ＝7（既有六點 barrier 斷言仍為
     *   `==6`：預設 hook 下 watcher 乾淨退出不佔槽；blocked hook 下 watcher 殘留
     *   即第 7 槽，`acquireSlot` 封頂保證 `peakOutstanding<=7==MAX` 無條件成立）；
     *   finished 路徑至多 5（3＋exit 1＋watcher 1）。
     *   故峰值封包仍為 7，常數與 barrier 測試皆不需改（見 PhaseF cancel bound 測試）。
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
     *
     * Round-3 Phase E（P1-1）：新增 [QUARANTINE]——未驗證停止的特權 process
     * 持久 quarantine 非空即拒（獨立維度，見 [quarantineUnverified]；
     * slot 常數 7 語義保持，quarantine 不佔槽、不計 consecutive）。
     */
    enum class Admission {
        OK,
        BACKLOG_FULL,
        BREAKER_OPEN,
        CONCURRENCY_FULL,
        QUARANTINE,
    }

    private val lock = Any()

    private val outstandingCount = AtomicInteger(0)

    /** 連續未完成清理計數（killer worker 超界未完成或 finished 殘留累計；清理完成且全域無殘留才歸零，見 [noteClean]）。 */
    private val consecutiveUnclean = AtomicInteger(0)

    /** 進行中呼叫數（AB1 呼叫級准入，跨呼叫全域，雙 runner 共用；進入即佔，finally 釋放）。 */
    private val activeCalls = AtomicInteger(0)

    /**
     * Round-3 Phase E 未驗證死亡 process 持久 quarantine（P1-1，fail-closed latch，
     * 與 slot／consecutive 計數分離）：
     * - 登記對象：post-kill／late-compensation 驗證失敗（verify=false／no-op kill／
     *   reap=false）仍未證死亡的 `Process` handle（本地 OS 進程與 Shizuku 遠端進程
     *   同經 `Process` 介面登記，雙軌同一機制）。worker 執行緒完成 ≠ process 已驗證
     *   死亡（killer best-effort 抛吞／no-op 皆可正常返回）：只有
     *   [ElevatedCallSupervisor.verifyParentStoppedBounded] 回 true 才算 verified；
     *   其餘一律登記，不宣稱 cleaned；
     * - 登記是冪等閂（同一 handle 以 `===` 去重，不計數、不佔槽）：飽和無槽／起線程
     *   失敗的 owner 亦轉入此登記（而非丟棄），同樣擋後續 spawn；
     * - 解除只兩途：[sweepQuarantine] 有界重驗見確死（`waitFor(0)` 回 true），或
     *   [clearUnverified] 顯式 recovery（僅 B3 已驗證 recovery 或測試隔離可調）。
     *   [noteClean]／成功 drain 永不清除（見其 KDoc 分離聲明）；
     * - 有界性：quarantine 非空即擋新 spawn，故登記數上界為准入前已在飛的併發呼叫數
     *   （`<=MAX_CONCURRENT_CALLS` 量級，不無界增長）；重驗見 [sweepQuarantine]
     *   （快照＋單次非阻塞狀態查詢，不起線程、不設定時器）。
     *
     * 鎖：一切經 [lock]（與槽／計數器同一鎖，准入判定單一原子點）。
     */
    private val quarantine = ArrayList<Process>()

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
     * Round-3 Phase E 分離聲明：本函數只碰 [consecutiveUnclean]，永不清除
     * quarantine（見 [quarantine]）：後續正常成功 drain 在 `outstanding==0`
     * 時重置連續計數，仍擋不住未驗證 process——quarantine 非空即
     * [Admission.QUARANTINE] 拒絕，與 breaker 計數獨立。
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
     * Round-3 Phase E：未驗證停止的 process 登記入 quarantine（P1-1）。
     *
     * 調用點（缺一即漏回收，見各處 KDoc）：
     * - [ElevatedCallSupervisor.lateCompensateSpawn] 未驗證（回 false）；
     * - `awaitSpawn` 內 worker 內聯補償飽和無槽、`startLateDaemon` 飽和無槽／
     *   `Thread.start()` 抛（owner 轉入登記，而非丟棄）；
     * - [ElevatedProcessIo.drain] 正常／preflight kill 後
     *   `verifyParentStoppedBounded` 失敗。
     *
     * 冪等（同一 handle 以 `===` 去重，不雙計）；只持引用，不佔槽、不計
     * consecutive（與 breaker 計數分離，見 [noteClean] 分離聲明）。
     * worker 完成 ≠ verified dead：呼叫方 breaker 計數保持（`countUncleanOnce`），
     * verdict `cleanupIncomplete=true` 保持（由各路徑既有旗標帶出），quarantine
     * 以 [Admission.QUARANTINE] 把 fail-closed 擴展到後續一切新 spawn。
     */
    internal fun quarantineUnverified(p: Process) {
        synchronized(lock) {
            if (quarantine.none { it === p }) quarantine.add(p)
        }
    }

    /** quarantine 是否非空（快照，僅測試／診斷用；正式准入 verdict 以 [tryEnterReserved] 為準）。 */
    internal fun isQuarantined(): Boolean = synchronized(lock) { quarantine.isNotEmpty() }

    /** quarantine 登記數快照（僅測試／診斷用）。 */
    internal fun quarantinedCount(): Int = synchronized(lock) { quarantine.size }

    /**
     * Round-3 Phase E 有界重試回收（P1-1；重試管道，不無界）：
     * 對快照內每個登記做一次非阻塞存活查詢（`waitFor(0)`：本地真進程即時回；
     * 假件按其語義回；Shizuku 遠端為一次 Binder 狀態查詢），確死（回 true）
     * 即解除登記，仍存活／查詢抛／中斷即保留（fail-closed：中斷恢復旗標）。
     *
     * 有界性：快照＋逐個單次查詢，不起線程、不設定時器、不用 `ProcessHandle.onExit`
     * 回調（假件無 `pid`／`toHandle` 真語義，見 [ElevatedProcessKiller] S2-A；
     * 回調的執行緒／生命週期在 Binder 死亡語義下不可控）。quarantine 非空即擋新
     * spawn，登記數天然上界（見 [quarantine]），故本函數耗時上界為
     * `登記數 × 單次狀態查詢`。
     *
     * 調用：兩 runner 每次准入前先調本函數再調 [tryEnterReserved]（sweep-then-admit；
     * 本函數鎖外探測、不持 [lock] 做外部呼叫，准入判定仍單一原子點）。
     * 生產 Binder stall 注記：遠端查詢 stall 只延遲本次拒絕（條目保留，下次仍拒），
     * 永不放行 spawn（fail-closed 方向不變）。
     *
     * @return 本次解除的登記數（測試斷言回收用）。
     */
    internal fun sweepQuarantine(): Int {
        val snapshot: List<Process> = synchronized(lock) { quarantine.toList() }
        if (snapshot.isEmpty()) return 0
        var cleared = 0
        for (p in snapshot) {
            val exited = try {
                p.waitFor(0L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (_: Exception) {
                false
            }
            if (exited) {
                synchronized(lock) {
                    val idx = quarantine.indexOfFirst { it === p }
                    if (idx >= 0) {
                        quarantine.removeAt(idx)
                        cleared++
                    }
                }
            }
        }
        return cleared
    }

    /**
     * Round-3 Phase E 顯式解除 quarantine（P1-1）：
     * 僅 B3 明確驗證的 recovery（帶外驗證已死後）或測試隔離可調。
     * 成功 drain／[noteClean]／[sweepQuarantine] 未見確死一律不得調用
     * （quarantine 與 breaker 計數分離，見 [noteClean] 分離聲明）。
     */
    @VisibleForTesting
    internal fun clearUnverified() {
        synchronized(lock) { quarantine.clear() }
    }

    /**
     * 純判定（B3 Phase 5 拆分，行為不變）：僅計算准入 verdict，不佔席、無副作用。
     *
     * 須在 [lock] 內調用（調用方 [tryEnterReserved] 同一鎖內先判後佔，仍單一原子點，
     * 不做三段 CAS）。判定順序同 [tryEnterReserved]（backlog／breaker／併發／預留／quarantine）。
     */
    private fun classifyAdmissionLocked(): Admission {
        // Round-3 Phase E（P1-1）：quarantine 末門（既有門相對順序不動，Phase F 範圍；
        // quarantine 只補 P1-1 缺口——outstanding==0、consecutive==1、預留通過時，
        // 前四門皆不觸發，此門獨立兜底；多門同成立時既有 reason 優先，quarantine
        // 仍經 isQuarantined／quarantinedCount 可觀測，backlog 清後即 surface）。
        if (outstandingCount.get() >= MAX_OUTSTANDING_CLEANUPS) return Admission.BACKLOG_FULL
        if (consecutiveUnclean.get() >= MAX_CONSECUTIVE_UNCLEAN) return Admission.BREAKER_OPEN
        if (activeCalls.get() >= MAX_CONCURRENT_CALLS) return Admission.CONCURRENCY_FULL
        if (outstandingCount.get() + (activeCalls.get() + 1) * SLOTS_PER_CALL > MAX_OUTSTANDING_CLEANUPS) {
            return Admission.BACKLOG_FULL
        }
        if (quarantine.isNotEmpty()) return Admission.QUARANTINE
        return Admission.OK
    }

    /**
     * 統一原子准入（P1-2，PR140 Phase1）：同一 [lock] 內檢查三條件並佔席。
     *
     * Phase 5 B3 拆分（行為不變）：純判定抽為 [classifyAdmissionLocked]
     * （backlog／breaker／reservation 無副作用），本函數僅在 `OK` 時單一原子佔席
     * （`active++`）。判定＋佔席仍在同一 `synchronized(lock)` 內，不引入 TOCTOU。
     *
     * 檢查順序（拒絕原因優先級，僅供訊息區分，准入決定單一原子點；
     * Round-3 Phase E（P1-1）：既有 1-4 相對順序不動，quarantine 只為末門）：
     * 1. `outstanding >= MAX` → [Admission.BACKLOG_FULL]；
     * 2. `consecutive >= MAX_CONSECUTIVE_UNCLEAN` → [Admission.BREAKER_OPEN]；
     * 3. `active >= MAX_CONCURRENT_CALLS` → [Admission.CONCURRENCY_FULL]；
     * 4. `outstanding + (active+1) * SLOTS_PER_CALL > MAX` → [Admission.BACKLOG_FULL]
     *   （預留超額，視為 backlog 壓力）；
     * 5. `quarantine 非空` → [Admission.QUARANTINE]（Round-3 Phase E 末門，
     *   見 [quarantine]；不與 backlog／breaker 混碼）；
     * 6. 否則 `active++` 並回 [Admission.OK]。
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
     * 為未來放寬 MAX／SLOTS 後的併發上限保留，不刪分支；reservation 矩陣直測見
     * `ElevatedCleanupTrackerTest`（不經 runner，直接斷言預留表）。
     */
    fun tryEnterReserved(): Admission = synchronized(lock) {
        val decision = classifyAdmissionLocked()
        if (decision != Admission.OK) return decision
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

    /** post-kill 驗證 join 上界（5s＋grace，見 [verifyParentStoppedBounded]；另見 `VERIFY_JOIN_MS`）。 */
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
         * 見 [awaitSpawn]）；非 spawn 路徑見 R3-PhaseG P2-4 旗標契約
         * （[ElevatedRunnerCommon.preSpawnLinger]）：`cleanupIncomplete` 涵蓋
         * background worker，`hasLinger || outstanding>0` 即置旗，不再一律 false。
         *
         * R3-PhaseG P2-2：任何 `Ok` 返回前皆經 deadline＋中斷 postcheck
         * （worker 在 deadline 後交付但 caller 延遲醒來不得回 Ok，見 [awaitInternal]）。
         */
        data class Expired(val hasLinger: Boolean = false) : Res<Nothing>
    }

    private data class Val<T>(val v: T)
    private data class Thr(val t: Throwable)

    /**
     * R3-PhaseG P2-2 可注入時鐘接縫：`nowNanos` 預設單調時鐘（`System.nanoTime`），
     * 單測傳假時鐘＋latch 排序做「worker 在 deadline 邊緣交付但 caller 延遲醒來」
     * 的確定性迴歸（不靠 sleep 猜調度窗口）。產品呼叫沿用預設，不傳參。
     */
    fun remainingMs(
        deadlineNanos: Long,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Long =
        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos()).coerceAtLeast(0L)

    fun isExpired(
        deadlineNanos: Long,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Boolean =
        (deadlineNanos - nowNanos()) <= 0L

    /**
     * spawn 前阻塞體有界（grant／activation／bridge／status／suExists 共用；
     * spawn 本體改走 [awaitSpawn]，不經此）：
     * - P1-3 入口 preflight：`isExpired(deadline)` 即不啟 worker，直回
     *   `Expired(hasLinger=false)`（零殘留；剩餘<=0 與已逾期同義，[isExpired]
     *   覆蓋）；
     * - 快路徑（worker 首個 [POLL_MS] 內完成）不啟 watcher、不求值 `isCancelled`
     *  （既有 B2 三點計數不變，見 `cancelMidDrainKillsAndUnblocks` 類斷言）；
     * - R3-PhaseF P2-1 慢路徑 hook 一律跑 [CancelWatch] worker：caller 只看自身
     *   中斷＋deadline＋緩存 bit，永不直調 hook（blocked hook 只卡 watcher，
     *   caller 經 deadline 有界回 `Expired`）。拋策略 fail-closed（拋即取消＋
     *   計數，沿用 `swallowedIsCancelledThrows`）；
     * - 慢路徑每 [POLL_MS] 查 `interrupt`／緩存 bit／deadline，命中即
     *   `interrupt`＋grace，仍存活即 lingering（`markLingering`＋`noteUnclean`，
     *   無 drain 終審故此處直接計入 breaker；watcher 殘留同計，單 call 恰好一次）；
     *   `Expired.hasLinger` 即 block worker grace 後仍存活（watcher 殘留不穿透，
     *   非 spawn 呼叫方經 [ElevatedRunnerCommon.preSpawnLinger] 映射旗標，
     *   見 R3-PhaseG P2-4 契約）；
     * - R3-PhaseG P2-2 快路徑 postcheck：watcher 從未啟動的 `Ok` 返回前重驗
     *   deadline＋呼叫執行緒中斷（worker 在 deadline 後交付但 caller 延遲醒來
     *   不得回 Ok，逾期即 `Expired` 丟棄交付值，非 spawn 無 owner 缺口）；
     *   慢路徑 handoff 的取消證據檢查即 postcheck（見 [awaitInternal]）。
     * - R3-PhaseF review G1 決策 (a) 慢成功照常成功＋caller-claim 合成：watcher
     *   啟動後已交付 Val 仍須消費——曾見明確 false（[CancelWatch.observedFalse]）＋
     *   無取消證據（bit 真／中斷／deadline 皆無）即 Ok handoff，不因超首 POLL 而
     *   丟棄；取消證據明確時按取消收斂（fail-closed），逾期即 `Expired` 丟棄交付值
     *   （非 spawn 無 owner 缺口）；unknown（watcher 首求值即阻塞，從未見 false）
     *   則繼續等 hook 明確／中斷／deadline，不樂觀消費（PhaseB caller-claim 確定性：
     *   發佈於 caller 阻塞期間的 Val 須由 expireWorker CAS 認領補償）。
     *   快路徑（watcher 從未啟動）沿舊語義立即消費。
     */
    fun <T> awaitPreSpawn(
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
        nowNanos: () -> Long = { System.nanoTime() },
        block: () -> T,
    ): Res<T> = awaitInternal(
        deadlineNanos = deadlineNanos,
        cancelCheck = { Thread.currentThread().isInterrupted || isExpired(deadlineNanos, nowNanos) },
        block = block,
        threadName = "elevated-supervisor",
        markUncleanOnLinger = true,
        hook = isCancelled,
        hookThrowMeansCancelled = true,
        nowNanos = nowNanos,
    )

    /**
     * R2-PhaseC P2-1 caller-safe 取消位有界求值（spawn 前三門專用）：
     * - 呼叫執行緒永不直接求值 hook：[cancelCheck] 只看自身中斷＋deadline，
     *   hook 本體跑 worker。blocking supplier（latch／Binder stall 充當 cancel）
     *   只卡 worker，呼叫方經 deadline＋grace 有界回 `Expired`，不斷 timeout 上界；
     * - 快路徑（首 [POLL_MS] 內回）不額外動作，B2 計數語義不變；
     * - hook 抛 → `Err`（呼叫方 fail-closed 視為已取消＋計數，不誤放行）；
     * - lingering（grace 後仍存活）記槽＋`noteUnclean`（同 [awaitPreSpawn]，
     *   無 drain 終審故此處直接計入 breaker）；`Expired.hasLinger` 經
     *   [ElevatedRunnerCommon.preSpawnLinger] 映射旗標（R3-PhaseG P2-4），
     *   見 [ElevatedRunnerCommon]）。
     *
     * 與 [awaitPreSpawn] 的分工：後者為阻塞體監督（慢路徑 hook 另跑
     * [CancelWatch] watcher 連續觀察，見其 KDoc）；本函數為 hook 本體單次求值
     * （每門精確求值一次，保 spawnRace 計數確定性，連續觀察會打亂門順序計數）。
     * 三門檢查必須經本函數（或等效「hook 只跑 worker」包裝），不得直調 hook。
     */
    fun awaitCancelBit(
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Res<Boolean> = awaitInternal(
        deadlineNanos = deadlineNanos,
        cancelCheck = { Thread.currentThread().isInterrupted || isExpired(deadlineNanos, nowNanos) },
        block = { isCancelled() },
        threadName = "elevated-supervisor",
        markUncleanOnLinger = true,
        nowNanos = nowNanos,
    )

    /**
     * R3-PhaseF P2-1 caller-safe 取消位（atomic cancellation token，慢輪詢統一原語）：
     * - hook 本體只跑 watcher worker（`elevated-supervisor` daemon，[POLL_MS] 節拍
     *   循環求值）；caller 只讀 [bit]（受信原子讀）＋自身中斷＋deadline，永不在
     *   caller 同步求值不可信 supplier——blocking supplier（latch／Binder stall
     *   充當 cancel）只卡 worker，caller 經 deadline 有界返回；
     * - [bit] 單調（false→true 閂，不回退）：hook 一旦真／按策略拋即閂定，caller
     *   按取消收斂（fail-closed）。hook 真後又假（抖動）不翻轉：已觀察到的取消
     *   不得因後續求值丟失；
     * - 拋策略 [throwMeansCancelled]：gates／spawn 側 true（未建 fail-closed，
     *   拋即取消＋計數）；drain 側 false（已建 fail-closed 反方向：拋折為未取消
     *   繼續觀察＋計數，不誤殺，見 [ElevatedProcessIo.drain]）；
     * - worker 循環兼看 deadline（[isExpired] 即自行退出，不佔槽不計數——仍在
     *   循環求值中的 fast hook 不會因呼叫方異常逃逸而永久殘留；卡死在 hook 內的
     *   仍由 [close] 記槽）；
     * - [close] 冪等：done→interrupt→join([GRACE_MS])；grace 後仍存活即
     *   [ElevatedCleanupTracker.markLingering]（持槽）並回 true，計數由呼叫方終審
     *   （awaitPreSpawn 直接 `noteUnclean`／awaitSpawn 經 `countUncleanOnce`
     *   guard／drain 置 `cleanupIncomplete` 由終審統一進 breaker）；
     * - 起線程失敗（[Thread.start] 拋，資源枯竭）即 fail-closed：置位 true，
     *   呼叫方按取消收斂，不靜默放行。
     *
     * 與 [awaitCancelBit] 的分工：後者為單次門專用（每門精確求值一次，保
     * spawnRace 計數確定性）；本原語為慢輪詢連續觀察專用（gates 慢路徑／
     * spawn 慢路徑／drain 全程）。快路徑（block 首 [POLL_MS] 內完成）不起
     * watcher、零 hook 求值（B2 計數語義不變）。
     */
    internal class CancelWatch internal constructor(
        private val deadlineNanos: Long,
        private val hook: () -> Boolean,
        private val throwMeansCancelled: Boolean,
        private val nowNanos: () -> Long = { System.nanoTime() },
    ) {
        private val bit = AtomicBoolean(false)
        private val done = AtomicBoolean(false)
        private val state = AtomicInteger(0)
        // R3-PhaseF review G1 精煉：黏性「曾觀察到非取消」證據。watcher 每次求值回
        // false 即閂定（G1(a) 慢成功 handoff 的必要條件：交付值消費前必須至少見過一次
        // 明確 false；首求值即阻塞／抛（gates 策略）＝unknown，交付值不得樂觀消費，
        // 須等 hook 明確／中斷／deadline——PhaseB caller-claim 確定性：發佈發生於
        // caller 阻塞期間時 worker 見 expired==false 留 Val，caller 仍須等取消明確後
        // 才經 expireWorker CAS 認領，不可提前 Ok）。
        private val seenFalse = AtomicBoolean(false)
        private val worker = Thread({
            try {
                while (!done.get() && !isExpired(deadlineNanos, nowNanos)) {
                    val cancel: Boolean? = try {
                        hook()
                    } catch (_: Exception) {
                        runCatching { ElevatedProcessIo.swallowedIsCancelledThrows.incrementAndGet() }
                        if (throwMeansCancelled) true else null
                    }
                    if (cancel == true) {
                        bit.set(true)
                        break
                    }
                    if (cancel == false) seenFalse.set(true)
                    if (done.get()) break
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
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

        /** 受信原子讀：caller 輪詢專用（永不拋，永不阻塞）。 */
        fun bit(): Boolean = bit.get()

        /**
         * 是否至少觀察到一次明確非取消（黏性證據，G1(a) 慢成功 handoff 必要條件）。
         * false＝unknown（watcher 尚未完成任何一次求值，或只見過 drain 策略下被折為
         * 未取消的抛）：交付值不得樂觀消費，caller 須繼續等 hook 明確／中斷／deadline。
         */
        fun observedFalse(): Boolean = seenFalse.get()

        /** 啟動 watcher（呼叫方須已持全域席位，慢輪詢 worker 皆受槽管控，見兩 runner 准入順序）。 */
        fun start() {
            worker.isDaemon = true
            try {
                worker.start()
            } catch (_: Exception) {
                // 起線程失敗即 fail-closed：再無求值能證明未取消，置位由呼叫方收斂。
                bit.set(true)
            }
        }

        /**
         * 停止觀察（冪等）：done→interrupt→[GRACE_MS] join。
         * @return watcher 是否殘留（grace 後仍存活，已記槽；計數由呼叫方終審）。
         */
        fun close(): Boolean {
            done.set(true)
            runCatching { worker.interrupt() }
            try {
                worker.join(GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return if (worker.isAlive) {
                ElevatedCleanupTracker.markLingering(worker, state)
                true
            } else {
                false
            }
        }
    }

    /**
     * 啟動呼叫方安全取消觀察（見 [CancelWatch]）：hook 只跑 worker，caller 只讀位。
     * 呼叫方須在全域席位內呼叫（慢輪詢 worker 皆受槽管控），並在返回前 [CancelWatch.close]。
     */
    internal fun startCancelWatch(
        deadlineNanos: Long,
        hook: () -> Boolean,
        throwMeansCancelled: Boolean,
        nowNanos: () -> Long = { System.nanoTime() },
    ): CancelWatch {
        val watch = CancelWatch(deadlineNanos, hook, throwMeansCancelled, nowNanos)
        watch.start()
        return watch
    }

    /**
     * drain 輪詢 `waitFor(slice)` 單片有界（B3，Binder 同步 stall 即此卡住）：
     * - R3-PhaseF P2-1：`isCancelled` 參數須為受信原子讀（產品一律傳 drain 級
     *   [CancelWatch.bit] 讀閉包；hook 本體只跑 watcher worker，caller 永不直調。
     *   直接傳不可信 supplier 即重蹈 caller 卡死，見 [CancelWatch]）。拋折為
     *   未取消（drain 語義，不誤殺，計數由 watcher 側同策略承擔）；
     * - 超界（cancel／deadline／worker stall）即 `Expired`，呼叫方走 kill 路徑；
     *   lingering 只記槽（`markLingering`），`noteUnclean` 由 drain 終審經
     *   `cleanupIncomplete` 統一計入（避免一次 drain 雙計；watcher 殘留經
     *   `hasLinger` 同通道帶出，見 drain）。
     * - R3-PhaseG P2-2：`Ok(true)` 不代表整個 request 未逾期——drain 呼叫方須在
     *   置 `finished` 前重驗 deadline＋取消（見 drain 等待循環 postcheck），
     *   整體逾期完成走 kill 路徑（`timedOut=true`），與 tail 中止
     *   （`tailIncomplete`，`timedOut` 仍 false）區分，不新增 verdict 常數。
     */
    fun awaitWaitSlice(
        process: Process,
        sliceMs: Long,
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Res<Boolean> = awaitInternal(
        deadlineNanos = deadlineNanos,
        cancelCheck = { Thread.currentThread().isInterrupted || isExpired(deadlineNanos, nowNanos) },
        block = { process.waitFor(sliceMs, TimeUnit.MILLISECONDS) },
        threadName = "elevated-wait",
        markUncleanOnLinger = false,
        hook = isCancelled,
        hookThrowMeansCancelled = false,
        nowNanos = nowNanos,
    )

    /** 通用有界（verify／exitValue 用，不查 cancel，只看 deadline＋interrupt）。 */
    fun <T> awaitTimeout(
        timeoutMs: Long,
        threadName: String,
        nowNanos: () -> Long = { System.nanoTime() },
        block: () -> T,
    ): Res<T> {
        val deadlineNanos = nowNanos() + TimeUnit.MILLISECONDS.toNanos(
            timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L),
        )
        return awaitInternal(
            deadlineNanos = deadlineNanos,
            cancelCheck = { Thread.currentThread().isInterrupted || isExpired(deadlineNanos, nowNanos) },
            block = block,
            threadName = threadName,
            markUncleanOnLinger = false,
            nowNanos = nowNanos,
        )
    }

    /**
     * R3-PhaseG P2-2：`Ok` 返回前一律 postcheck（快慢路徑皆然）。
     * 快路徑（`watch` 從未啟動）舊碼見 worker 已死即回 `Ok`：caller 被調度延遲、
     * worker 在 deadline 後交付但 caller 復甦前結束即把逾期完成映為成功。
     * 現快路徑 `Val` 返回前重驗 deadline＋呼叫執行緒中斷，逾期／中斷即 `Expired`
     * （worker 已死故 `hasLinger=false`；非 spawn 無 owner 缺口，交付值丟棄）。
     * hook 本體仍不求值（B2 快路徑零 hook 求值計數語義不變）；慢路徑 handoff 的
     * 取消證據檢查（`cancelNow`：中斷／bit／deadline）即等效 postcheck。
     */
    private fun <T> awaitInternal(
        deadlineNanos: Long,
        cancelCheck: () -> Boolean,
        block: () -> T,
        threadName: String,
        markUncleanOnLinger: Boolean,
        hook: (() -> Boolean)? = null,
        hookThrowMeansCancelled: Boolean = true,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Res<T> {
        // P1-3 入口 preflight：budget 已盡即不啟 worker（零執行緒、零殘留）。
        if (isExpired(deadlineNanos, nowNanos)) return Res.Expired(hasLinger = false)
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
        // R3-PhaseF P2-1：慢輪詢 hook 觀察者（惰性：首片後 block 仍存活才啟動；
        // 快路徑零 hook 求值）。caller 側判定（[cancelNow]）只看自身中斷＋deadline
        // ＋緩存 bit，永不直調 hook。
        var watch: CancelWatch? = null
        fun cancelNow(): Boolean {
            if (Thread.currentThread().isInterrupted) return true
            val w = watch
            if (w != null && w.bit()) return true
            return isExpired(deadlineNanos, nowNanos)
        }
        // 無 hook 呼叫方（awaitTimeout／verify 系）：沿既有用中斷＋deadline 判定。
        fun legacyCancel(): Boolean =
            try {
                cancelCheck()
            } catch (_: Exception) {
                true
            }
        // R3-PhaseF：殘留關閉（冪等，只記槽不計數；計數由各返回點
        // `lingering || watchLingering` 合併恰好一次，見下）。
        fun closeWatch(): Boolean = watch?.close() ?: false
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
                    val watchLingering = closeWatch()
                    if (lingering) ElevatedCleanupTracker.markLingering(worker, state)
                    if ((lingering || watchLingering) && markUncleanOnLinger) {
                        ElevatedCleanupTracker.noteUnclean()
                    }
                    return Res.Expired(hasLinger = lingering)
                }
                if (!worker.isAlive) {
                    val h = holder.get()
                    // R3-PhaseF review G1 決策 (a)：慢成功照常成功。watcher 已啟動後
                    // 已交付 Val 仍須消費——無取消證據（bit 真／中斷／deadline 皆無）
                    // 即 Ok handoff，不因超過首 POLL 而丟棄；取消證據明確時才按
                    // 取消收斂（fail-closed：bit 真與交付同迭代競態時取消優先，
                    // 見 PhaseB caller-claim）。逾期即 Expired 丟棄交付值
                    // （非 spawn 語境無 owner 缺口）。快路徑（watcher 從未啟動）
                    // 沿舊語義立即消費。非 Val（Err／異常）無資源攜帶，立即消費。
                    if (h is Val<*> && watch != null) {
                        val w = watch
                        val cancelEvidence = try {
                            cancelNow()
                        } catch (_: Exception) {
                            true
                        }
                        if (cancelEvidence) {
                            runCatching { worker.interrupt() }
                            try {
                                worker.join(GRACE_MS)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            val lingering = worker.isAlive
                            val watchLingering = closeWatch()
                            if (lingering) ElevatedCleanupTracker.markLingering(worker, state)
                            if ((lingering || watchLingering) && markUncleanOnLinger) {
                                ElevatedCleanupTracker.noteUnclean()
                            }
                            return Res.Expired(hasLinger = lingering)
                        } else if (w.observedFalse()) {
                            // G1(a)＋caller-claim 合成：曾見明確 false＋無取消證據即
                            // Ok handoff；unknown（watcher 首求值即阻塞，從未見 false）
                            // 則落到下方慢檢查繼續等 hook 明確／中斷／deadline，
                            // 不樂觀消費（PhaseB caller-claim 確定性）。
                            val watchLingering = closeWatch()
                            if (watchLingering && markUncleanOnLinger) ElevatedCleanupTracker.noteUnclean()
                            // R3-PhaseG P2-2：關 watcher 期間的邊緣回傳兜底（見
                            // awaitSpawn 同位檢查）：此處已無取消證據，但關 watcher
                            // 的 grace 可把 caller 推過 deadline，仍不當成功。
                            val edgeOverdue = try {
                                isExpired(deadlineNanos, nowNanos)
                            } catch (_: Exception) {
                                true
                            } || Thread.currentThread().isInterrupted
                            if (edgeOverdue) return Res.Expired(hasLinger = false)
                            @Suppress("UNCHECKED_CAST")
                            return Res.Ok((h as Val<T>).v)
                        }
                        // else unknown：交付值 pending，落到下方慢檢查等待。
                    } else {
                        val watchLingering = closeWatch()
                        if (watchLingering && markUncleanOnLinger) ElevatedCleanupTracker.noteUnclean()
                        // R3-PhaseG P2-2 快路徑 postcheck（見本函數 KDoc）：worker
                        // 已死＋watcher 從未啟動時，仍須重驗 deadline＋中斷。
                        // Thr／無交付（Err 語義）不轉 Expired，只有成功交付才驗。
                        if (h is Val<*>) {
                            val overdue = try {
                                isExpired(deadlineNanos, nowNanos)
                            } catch (_: Exception) {
                                true
                            } || Thread.currentThread().isInterrupted
                            if (overdue) return Res.Expired(hasLinger = false)
                        }
                        return when (h) {
                            is Val<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                Res.Ok((h as Val<T>).v)
                            }
                            is Thr -> Res.Err(h.t)
                            else -> Res.Err(IllegalStateException("supervisor worker died without result"))
                        }
                    }
                }
                if (hook != null && watch == null) {
                    watch = startCancelWatch(deadlineNanos, hook, hookThrowMeansCancelled, nowNanos)
                }
                val shouldExpire = try {
                    if (hook != null) cancelNow() else legacyCancel()
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
                    val watchLingering = closeWatch()
                    if (lingering) ElevatedCleanupTracker.markLingering(worker, state)
                    if ((lingering || watchLingering) && markUncleanOnLinger) {
                        ElevatedCleanupTracker.noteUnclean()
                    }
                    return Res.Expired(hasLinger = lingering)
                }
                // Val pending 但 unknown（watcher 從未見 false、亦無取消證據）：
                // worker 已死（join 瞬時返回），睡一片避免空轉，等 hook 明確／
                // 中斷／deadline（PhaseB caller-claim 即此等待）。
                if (hook != null && !worker.isAlive) {
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        runCatching { worker.interrupt() }
                        val watchLingering = closeWatch()
                        if (watchLingering && markUncleanOnLinger) ElevatedCleanupTracker.noteUnclean()
                        return Res.Expired(hasLinger = false)
                    }
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
            val watchLingering = closeWatch()
            if (lingering) ElevatedCleanupTracker.markLingering(worker, state)
            if ((lingering || watchLingering) && markUncleanOnLinger) {
                ElevatedCleanupTracker.noteUnclean()
            }
            return Res.Expired(hasLinger = lingering)
        }
    }

    /**
     * P1-2 spawn 專用有界監督（PR140 Phase 2；R2-PhaseB 全域管理）：
     * `block` 建特權進程（`ProcessBuilder.start()`／Shizuku `newProcess`，
     * Binder 一旦送達即不可撤回），故逾期交付不能只丟棄引用，必須
     * late compensation（見 [lateCompensateSpawn]）。
     *
     * owner 認領機制：
     * - `expired` 單一原子旗標為線性化點。caller 決定過期即先置 true，再 interrupt＋grace；
     *   worker 發布值後必重讀本旗：false 才經 holder handoff，true 即自行 [lateCompensateSpawn]
     *   後（CAS 認領成功才動手）丟棄，不再經 holder；
     * - 認領競態（worker 發布與 caller 過期交錯）以 holder CAS 恰好一次交付清理方：
     *   worker 發布後若見 expired 即 `compareAndSet(發布值, null)`，成功則 worker 內聯補償，
     *   失敗（caller 已取走）則放手；caller 過期路徑同樣 CAS 取 holder 內逾期 Process，
     *   成功則起 `elevated-late-spawn` daemon 補償（caller 不為補償多擋 budget），
     *   失敗（worker 已認領或尚未發布——後者由 worker發布後重讀兜底）則放手。
     *   三方交錯下恰好一方動手，不雙殺（killer 本就冪等，destroy 重入無害）
     *   不漏殺（任一發布必有一方在置旗後 CAS）；
     * - 返回後再確認 deadline：worker 已死但交付發生在 budget 之後（邊緣回傳），
     *   即 CAS 取回＋daemon 補償＋`Expired(hasLinger=true)`，不把逾期建成當成功；
     * - `hasLinger=true` 條件：grace 後 worker 仍存活（late Process 仍可能送達，
     *   由 worker 發布後重讀兜底清理）或本次確有逾期 Process（已取走補償中，
     *   保守置旗）或 worker 內聯補償已驗證為 incomplete（[lateIncomplete]）。
     *   spawn 呼叫方必須穿透至 `TimedOut.cleanupIncomplete`。
     *
     * R2-PhaseB 全域管理（P1-2 late 缺口，slot 常數 7／MAX 7 語義保持）：
     * - late 補償執行前先 [ElevatedCleanupTracker.acquireSlot]（worker 內聯與 caller
     *   daemon 同一全域管理，佔槽即自然受 7 管；持槽全程，stall 即 outstanding 擋
     *   新 spawn，不再有 untracked 永久卡住）；
     * - 飽和（acquire false）即不起無界 daemon，經 [lateIncomplete]＋breaker 終審
     *   （fail-closed：owner 仍為 late Process 唯一追蹤者，記為殘留而非丟棄）；
     * - `t.start()` 抛即釋槽＋計數（同步有界收斂，不靜默丟 owner；資源壓力最高時
     *   不放大，不重試起線程）；
     * - 單 call breaker 恰好一次（[uncleanCounted] guard：lingering 與 late 雙因不雙計，
     *   首因勝出；verified clean 不計，outstanding 釋放後 admission 自然恢復）。
     *   每 call 至多新增槽位：spawn worker lingering 1＋late daemon 1＋verify 1＝3≤7，
     *   不需調常數（若調必須重推導＋更新 barrier 測試）。
     *
     * 入口 preflight 同 [awaitInternal]（已逾期即不啟 worker，`Expired(false)`，
     * 零 spawn）；`killer` 為 runner 注入（單測計數／destroy 可觀測，預設即
     * [ElevatedProcessKiller.killTree]）；殘留記槽／breaker 計入同 [awaitPreSpawn]
     * （grace 後仍存活即記＋計，現經 guard 恰好一次）。
     * R3-PhaseF P2-1 慢輪詢 hook 統一：caller 永不直調 `isCancelled`。首片後
     * block 仍存活即惰性啟 [CancelWatch]（hook 只跑 watcher worker，fail-closed
     * 拋策略；快路徑零 hook 求值）；caller 只看自身中斷＋deadline＋緩存 bit。
     * R3-PhaseF review G1 決策 (a) 慢成功照常成功＋caller-claim 合成：watcher
     * 啟動後慢交付成功同樣 handoff 而非 expireWorker 認領殺掉——曾見明確 false
     * （[CancelWatch.observedFalse]）＋無取消證據即 `Ok`，取消證據（bit 真／中斷／
     * deadline）明確時才經 expireWorker CAS 認領補償（fail-closed：bit 真與交付同
     * 迭代競態時按取消收斂，不漏殺）；unknown（首求值即阻塞）則繼續等待，不樂觀
     * 消費（PhaseB caller-claim 確定性）。
     * 快路徑（watcher 從未啟動）沿舊語義立即消費。
     * watcher 殘留經 `countUncleanOnce` guard 同通道恰好一次。
     */
    fun awaitSpawn(
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
        killer: (Process) -> Unit,
        nowNanos: () -> Long = { System.nanoTime() },
        block: () -> Process,
    ): Res<Process> {
        // P1-3 入口 preflight：budget 已盡即不提交 spawn（零特權進程）。
        if (isExpired(deadlineNanos, nowNanos)) return Res.Expired(hasLinger = false)
        val expired = AtomicBoolean(false)
        val holder = AtomicReference<Any?>()
        val state = AtomicInteger(0)
        // R2-PhaseB（P1-2 late 缺口）：worker 內聯補償結果回傳旗標。
        // worker 認領並補償後若未驗證（no-op kill／waitFor=false／verify=false／
        // 無槽／daemon 起失敗），置 true，caller expireWorker 即使見 holder==null
        // ＋lingering==false 亦回 Expired(true)（cleanupIncomplete=true），不誤報 cleaned。
        // 驗證通過才保持 false（caller 方可回 false，見 lateCompensateSpawn）。
        val lateIncomplete = AtomicBoolean(false)
        // R2-PhaseB：單 call breaker 恰好一次（lingering＋late 雙因不雙計）。
        // caller lingering 計數與 worker／daemon incomplete 計數經本旗互斥，首因勝出；
        // 仍 fail-closed（任一殘留必計 1），不漏不重，連續 4 次熔斷語義不變。
        val uncleanCounted = AtomicBoolean(false)
        fun countUncleanOnce() {
            if (uncleanCounted.compareAndSet(false, true)) ElevatedCleanupTracker.noteUnclean()
        }
        // R2-PhaseB：caller 側 daemon 起動（含飽和／起失敗收斂）。
        // - acquired 才起 daemon（daemon 持槽全程，stall 即 outstanding 擋 admission，
        //   受 MAX 7 管，不再有無界 untracked daemon）；
        // - 飽和（acquire false）即不起 daemon，經 countUncleanOnce 記殘留
        //   （fail-closed：owner 仍為唯一追蹤者，記為殘留而非丟棄，breaker 終審擋新 spawn）；
        // - t.start() 抛即釋槽＋計數（同步有界收斂，不靜默丟 owner；不再重試起線程，
        //   資源壓力最高時不放大）；
        // - daemon 終審：incomplete 才計數（guard 保 lingering 不雙計），verified
        //   clean 不計（outstanding 釋放後 admission 自然恢復，見 lateCompensateSpawn）。
        // - Round-3 Phase E（P1-1）：飽和無槽／起線程失敗的 owner 一律轉入
        //   quarantine 登記（[ElevatedCleanupTracker.quarantineUnverified]，而非丟棄），
        //   outstanding 歸零後仍以 QUARANTINE 擋後續 spawn（同條漏回收路徑消除，
        //   不只寫 breaker 計數）。
        fun startLateDaemon(v: Process) {
            val acquired = ElevatedCleanupTracker.acquireSlot()
            if (!acquired) {
                ElevatedCleanupTracker.quarantineUnverified(v)
                countUncleanOnce()
                return
            }
            val t = Thread({
                try {
                    val clean = lateCompensateSpawn(v, killer)
                    if (!clean) countUncleanOnce()
                } finally {
                    ElevatedCleanupTracker.releaseSlot()
                }
            }, "elevated-late-spawn")
            t.isDaemon = true
            try {
                t.start()
            } catch (_: Exception) {
                ElevatedCleanupTracker.releaseSlot()
                ElevatedCleanupTracker.quarantineUnverified(v)
                countUncleanOnce()
            }
        }
        val worker = Thread({
            try {
                val p = block()
                val published: Any = Val(p)
                holder.set(published)
                // 發布後重讀：caller 已過期即嘗試認領回來自行補償。
                if (expired.get()) {
                    if (holder.compareAndSet(published, null)) {
                        // R2-PhaseB：worker 內聯補償亦先佔槽（與 caller daemon 同一全域管理）。
                        // 飽和即不補償、置旗＋計數（fail-closed，不靜默丟 owner）。
                        // Round-3 Phase E（P1-1）：飽和 owner 同轉入 quarantine 登記。
                        val acquired = ElevatedCleanupTracker.acquireSlot()
                        if (!acquired) {
                            ElevatedCleanupTracker.quarantineUnverified(p)
                            lateIncomplete.set(true)
                            countUncleanOnce()
                        } else {
                            try {
                                val clean = lateCompensateSpawn(p, killer)
                                if (!clean) {
                                    lateIncomplete.set(true)
                                    countUncleanOnce()
                                }
                            } finally {
                                ElevatedCleanupTracker.releaseSlot()
                            }
                        }
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
        // R3-PhaseF P2-1：慢輪詢 hook 觀察者（惰性：首片後 block 仍存活才啟動；
        // 快路徑零 hook 求值）。caller 只看自身中斷＋deadline＋緩存 bit，
        // 永不直調 hook（blocked hook 只卡 watcher，caller 經 deadline 有界返回）。
        var watch: CancelWatch? = null
        // 過期善後（caller 側）：置旗→interrupt→grace→記槽→CAS 認領逾期交付。
        // 三條過期路徑（caller 中斷／cancel-deadline／外層異常）共用，恰好一次。
        // R3-PhaseF：入口先關 watcher（殘留經 [countUncleanOnce] guard 恰好一次）。
        fun expireWorker(): Res<Process> {
            expired.set(true)
            if (watch?.close() == true) countUncleanOnce()
            runCatching { worker.interrupt() }
            try {
                worker.join(GRACE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val lingering = worker.isAlive
            if (lingering) {
                ElevatedCleanupTracker.markLingering(worker, state)
                countUncleanOnce()
            }
            var lateProcess = false
            val h = holder.get()
            if (h is Val<*>) {
                @Suppress("UNCHECKED_CAST")
                val v = (h as Val<Process>).v
                // CAS 認領：成功才起 daemon 補償（caller 不多擋 budget，受槽管控）；
                // 失敗即 worker 已認領（內聯補償中），放手（worker 側已置旗＋計數）。
                if (holder.compareAndSet(h, null)) {
                    lateProcess = true
                    startLateDaemon(v)
                } else {
                    lateProcess = true
                }
            }
            return Res.Expired(hasLinger = lingering || lateProcess || lateIncomplete.get())
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
                    // R3-PhaseF review G1 決策 (a)：慢成功照常成功。watcher 已啟動後
                    // 慢交付 Val 仍須 handoff 而非 expireWorker 認領殺掉——無取消證據
                    // （bit 真／中斷／deadline 皆無）即 Ok handoff；取消證據明確時才
                    // 經 expireWorker CAS 認領補償（fail-closed：bit 真與交付同迭代
                    // 競態時按取消收斂，不漏殺）。快路徑（watcher 從未啟動）沿舊語義
                    // 立即消費。非 Val（Err／異常）無資源攜帶，立即消費＋關 watcher。
                    if (h is Val<*> && watch != null) {
                        val w0 = watch
                        val expiredNow = Thread.currentThread().isInterrupted ||
                            w0.bit() || isExpired(deadlineNanos, nowNanos)
                        if (expiredNow) {
                            return expireWorker()
                        } else if (w0.observedFalse()) {
                            // G1(a)＋caller-claim 合成：曾見明確 false＋無取消證據即
                            // Ok handoff；unknown 則落到下方慢檢查繼續等 hook 明確／
                            // 中斷／deadline，不樂觀消費（PhaseB caller-claim 確定性）。
                            if (watch?.close() == true) countUncleanOnce()
                            // P1-3 返回後再確認 deadline（上一行已確認未逾期，此處為
                            // 關 watcher 期間的邊緣回傳兜底，仍不當成功即補償）。
                            if (isExpired(deadlineNanos, nowNanos)) {
                                expired.set(true)
                                @Suppress("UNCHECKED_CAST")
                                val v = (h as Val<Process>).v
                                if (holder.compareAndSet(h, null)) {
                                    startLateDaemon(v)
                                }
                                return Res.Expired(hasLinger = true)
                            }
                            @Suppress("UNCHECKED_CAST")
                            return Res.Ok((h as Val<Process>).v)
                        }
                        // else unknown：交付值 pending，落到下方慢檢查等待。
                    } else {
                        if (watch?.close() == true) countUncleanOnce()
                        when (h) {
                            is Val<*> -> {
                                // P1-3 返回後再確認 deadline：邊緣回傳（交付在 budget
                                // 之後）不當成功，CAS 取回＋daemon 補償＋Expired(true)。
                                // R2-PhaseB：同一受槽 daemon（見 startLateDaemon），不再裸起
                                // untracked daemon；CAS 失敗即 worker 已認領，放手。
                                if (isExpired(deadlineNanos, nowNanos)) {
                                    expired.set(true)
                                    @Suppress("UNCHECKED_CAST")
                                    val v = (h as Val<Process>).v
                                    if (holder.compareAndSet(h, null)) {
                                        startLateDaemon(v)
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
                                if (isExpired(deadlineNanos, nowNanos)) return Res.Expired(hasLinger = false)
                                return Res.Err(IllegalStateException("supervisor worker died without result"))
                            }
                        }
                    }
                }
                val w = watch ?: startCancelWatch(deadlineNanos, isCancelled, true, nowNanos).also { watch = it }
                val shouldExpire = try {
                    if (Thread.currentThread().isInterrupted) {
                        true
                    } else {
                        w.bit() || isExpired(deadlineNanos, nowNanos)
                    }
                } catch (_: Exception) {
                    true
                }
                if (shouldExpire) return expireWorker()
                // Val pending 但 unknown（watcher 從未見 false、亦無取消證據）：
                // worker 已死（join 瞬時返回），睡一片避免空轉，等 hook 明確／
                // 中斷／deadline（PhaseB caller-claim 即此等待）。
                if (!worker.isAlive) {
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return expireWorker()
                    }
                }
            }
        } catch (_: Exception) {
            return expireWorker()
        }
    }

    /**
     * P1-2 late 補償（PR140 Phase 2；R2-PhaseB 受監督重寫）：逾期交付的 Process
     * 在此收斂，不再經 holder／drain。
     *
     * R2-PhaseB 誠實邊界（P1-2 late 缺口）：
     * - 受監督 bounded cleanup：呼叫方（worker 內聯或 `elevated-late-spawn` daemon）
     *   進入前已持一槽（見 [awaitSpawn]／[ElevatedCleanupTracker.acquireSlot]），
     *   本體 stall（killer／reap／verify／關流吞 interrupt）即持槽殘留，
     *   `outstanding` 擋新 spawn（受 MAX 7 管），不再有 untracked 永久卡住；
     * - 完成／未知／失敗判定：`killer` 抛即 incomplete（計數＋續驗，不升級崩潰）；
     *   `waitFor([LATE_REAP_WAIT_SEC])` 回 false／抛／中斷一律 incomplete
     *   （false 不可算成功→unknown/incomplete）；父進程必須經
     *   [verifyParentStoppedBounded] 驗證已死才可清 incomplete（true 才可回 true）。
     *   子孫整組仍為 E2E（見 [ElevatedProcessKiller]，裝置 SELinux 差異歸 #136-B3），
     *   但 parent／reap 必須驗；
     * - 快速路徑（destroy no-op＋waitFor=false 立即返回）必經本驗證：verify 回 false
     *   即回 false（呼叫方置 `cleanupIncomplete=true`＋breaker 計數，不恢復 admission）；
     *   只有 killer＋reap＋verify 全過才回 true（verified clean 方可不清旗）；
     * - 三流 best-effort 關：此 Process 永不進 drain，此處即唯一關流處；getter 重調
     *   可 stall，stall 即持槽殘留（由槽追蹤，不靜默），抛即吞（不翻 verdict，
     *   parent 驗證已定 verdict）；
     * - 本函數不碰槽／breaker（槽由呼叫方持有全程，breaker 由呼叫方終審經返回值計入），
     *   不抛（全段 catch，daemon 不死於異常）。
     * - Round-3 Phase E（P1-1）：未驗證（回 false）即登記 quarantine
     *   （[ElevatedCleanupTracker.quarantineUnverified]，冪等閂）：worker 完成
     *   ≠ verified dead，登記是本函數的直接職責（非計數，故不經呼叫方 guard），
     *   breaker 計數仍由呼叫方經返回值終審（不雙計語義不變）。
     *
     * 上界：killer（注入側有界，stall 即持槽）＋reap 1s＋verify 5.5s＋grace 0.5s＋
     * 關流（stall 即持槽）。呼叫方（awaitSpawn caller）早已返回，不擋 budget。
     * owner 語義：late Process 的唯一追蹤者即本補償持有者（worker 內聯或 daemon 持槽）；
     * 飽和無槽時不起 daemon，呼叫方記殘留＋quarantine 登記（見 startLateDaemon）。
     */
    private fun lateCompensateSpawn(p: Process, killer: (Process) -> Unit): Boolean {
        var ok = true
        try {
            killer(p)
        } catch (_: Exception) {
            runCatching { ElevatedProcessIo.swallowedKillerThrows.incrementAndGet() }
            ok = false
        }
        // 有界 reap：false／抛／中斷一律 incomplete（不可算成功）。
        try {
            val reaped = p.waitFor(LATE_REAP_WAIT_SEC, TimeUnit.SECONDS)
            if (!reaped) ok = false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ok = false
        } catch (_: Exception) {
            ok = false
        }
        // 父進程必須驗證已死才可清 incomplete（unknown→false，不宣稱 cleaned）。
        try {
            if (!verifyParentStoppedBounded(p)) ok = false
        } catch (_: Exception) {
            ok = false
        }
        runCatching { p.inputStream.close() }
        runCatching { p.errorStream.close() }
        runCatching { p.outputStream.close() }
        // Round-3 Phase E（P1-1）：未驗證即登記 quarantine（worker 完成≠verified dead；
        // 呼叫方 breaker 計數經返回值另行終審，此處只登記，不雙計）。
        if (!ok) ElevatedCleanupTracker.quarantineUnverified(p)
        return ok
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
    fun exitValueBounded(
        process: Process,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Res<Int> {
        // B3 debug: preset-interrupt cleared path must not expire for immediate exitValue.
        // 若此處頻繁 Expired 且 `isInterrupted=false`，即 worker 啟動／調度異常，需查線程池。
        return awaitTimeout(EXIT_JOIN_MS, "elevated-supervisor", nowNanos) { process.exitValue() }
    }
}

internal object ElevatedProcessIo {
    /** R1-A 截圖二進位容量：`screencap -p` / `cat /dev/graphics/fb0` 的截斷上限（8M）。 */
    const val BINARY_SCREENSHOT_MAX_BYTES: Int = 8 * 1024 * 1024

    /** R1-A 截圖二進位硬讀取上限（8M，pipe 內保留上界，非程序峰值；溢出經 [Drained.truncated] 帶出）。 */
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
        /**
         * R2-PhaseC P2-2 tail 未完成旗標（命令 exit vs 整個 request finished 分開）：
         * `finished`（`waitFor` 回 true，命令已 exit）只代表子進程退出，不代表
         * 整個 request 完成（雙流排空＋exitValue 讀回）。finished 路徑 tail（雙
         * gobbler joins 共享 tail budget）在取消（`isCancelled`／呼叫執行緒中斷）／
         * 超時（主 deadline／tail budget 耗盡）下中止（gobbler 中斷＋殘留，
         * `joinIncomplete` 同置）即 true；kill 路徑（`!finished`）永 false
         * （`timedOut` 已覆蓋）；stdin-closer 殘留不置本旗標（流已排空，輸出完整，
         * 見 AB1 linger-success 仍 Ok）。
         * runner 映射：would-be Ok（exit 0 且無 [readFailed]）→ `TimedOut`
         * （攜 partial＋truncated＋cleanupIncomplete），不再回 Ok；`readFailed`／
         * 非零 exit 的 tail 中斷仍走既有 `Failed`（皆非 Ok，安全；Y1 readFailed
         * pin 保留，只有 would-be Ok 才升級，不新增 verdict 常數）。
         */
        val tailIncomplete: Boolean = false,
    )

    fun drain(
        process: Process,
        timeoutMs: Long,
        hardCapBytes: Int = ShellPolicy.HARD_READ_CAP_BYTES,
        hardCapErrBytes: Int? = null,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Drained =
        drain(
            process,
            timeoutMs,
            { ElevatedProcessKiller.killTree(it) },
            { Thread.currentThread().isInterrupted },
            hardCapBytes,
            hardCapErrBytes,
            nowNanos,
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
     *
     * R2-PhaseC P2-2 finished 語義（命令 exit vs 整個 request finished 分開）：
     * `finished`（`waitFor` 回 true）只代表命令已 exit；tail（雙流排空）因取消／
     * 超時中止即 [Drained.tailIncomplete]=true（`timedOut` 仍為 `!finished` 不動，
     * 命令 exit 語義不動），runner 側 would-be Ok 升級 TimedOut（見兩 runner）。
     *
     * R3-PhaseF P2-1 慢輪詢 hook 統一：[isCancelled] 只跑 drain 級
     * [ElevatedCallSupervisor.CancelWatch] worker（節拍循環求值，
     * 拋折為未取消繼續觀察＋計數）；wait 主循環／[awaitWaitSlice]／finished
     * tail 一律只讀緩存 bit，caller 永不直調 hook（blocked hook 只卡 watcher，
     * caller 經 deadline 有界進 kill／tail 中止）。drain 返回前 watcher 收斂：
     * 殘留即記槽＋`cleanupIncomplete=true`（由終審統一進 breaker）。
     *
     * R3-PhaseG P2-2 整體逾期完成（命令 exit vs 整個 request 逾期分開）：
     * [awaitWaitSlice] 回 `Ok(true)` 只代表該片內命令已 exit，不代表整個 request
     * 未逾期——置 `finished` 前重驗 deadline＋取消＋[cancelledAtEntry]（見等待循環
     * postcheck）：整體逾期完成走 kill 路徑（`timedOut=true`），與 tail 中止
     * （`tailIncomplete=true` 但 `timedOut` 仍 false，命令 exit 語義不動）區分，
     * 不新增 verdict 常數（runner 兩路徑皆映 `TimedOut`，審計皆 `TIMEOUT`）。
     *
     * R3-PhaseG P2-3 入口取消一次性決策（[cancelledAtEntry]）：入口
     * `Thread.interrupted()` 快照在清旗的同時轉為一次性取消決策——預置中斷的
     * caller 進 drain 即走受監督 kill 路徑（killer＋有界驗證＋終審計入 breaker，
     * 進程已啟動不當健康進程跑），不再因清旗後預設鉤讀到 false 而健康跑到
     * timeout。清旗＋出口恢復保證不變（不吞 caller 中斷）。
     */
    fun drain(
        process: Process,
        timeoutMs: Long,
        killer: (Process) -> Unit,
        isCancelled: () -> Boolean,
        hardCapBytes: Int = ShellPolicy.HARD_READ_CAP_BYTES,
        hardCapErrBytes: Int? = null,
        nowNanos: () -> Long = { System.nanoTime() },
    ): Drained {
        // P2 caller 中斷級聯＋R3-PhaseG P2-3 一次性決策：入口 `Thread.interrupted()`
        // 清除並記錄呼叫方預置中斷。清除保證尾收前 joins 不再因預置中斷即抛；
        // 同步記錄的 [cancelledAtEntry] 保證已取消不因清旗而遺失——預置中斷的
        // caller 進 drain 即 cancel，走下方受監督 kill 路徑（kill＋驗證＋終審），
        // 不當健康進程跑。出口恢復一次（不吞 caller 中斷）。
        val callerInterrupted = Thread.interrupted()
        // R3-PhaseG P2-3：入口取消快照（一次性，不重讀）。只取無阻塞的中斷位，
        // 永不在此直調 `isCancelled`（blocked hook 只卡 watcher，見 PhaseF）；
        // job 式取消由 drain 級 watcher 在首片內觀測到位，同样有界收斂。
        val cancelledAtEntry = callerInterrupted
        // X1 有界 acquisition：deadline 移到 drain 入口第一行（outputStream 關閉
        // 之前），全部開銷計入同一 budget（單調時鐘，不受 wall-clock 回撥影響）。
        // P2 上溢夾頂：timeoutMs 先 coerceAtMost(Long.MAX/1e6)，避免
        // MILLISECONDS.toNanos(Long.MAX) 上溢回負（deadline 回過去即假性即時超時，
        // Long.MAX 應為近無限而非即時超時；夾頂後 Long.MAX 即約 292M 年，不懸掛）。
        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L)
        val deadlineNanos = nowNanos() + TimeUnit.MILLISECONDS.toNanos(safeTimeoutMs)
        // P1-3 drain 入口 preflight（PR140 Phase 2，最小改動）：budget 已盡即不起
        // gobbler／stdin-closer（零新執行緒），直接監督殺＋有界驗證後回
        // `Drained(timedOut=true)`。殺驗語義同 kill 路徑（killer 永不跳過，下界
        // [CLEANUP_MIN_MS]；驗證走有界 [verifyParentStoppedBounded]），終審計入
        // breaker 同正常路徑；caller 預置中斷照常出口恢復。流未起即無已取引用可關
        // （killer 契約本就不碰流；此 Process 的流隨 kill＋物件回收收斂）。
        if ((deadlineNanos - nowNanos()) <= 0L) {
            var preIncomplete = false
            val preOk = runCleanupBounded(CLEANUP_MIN_MS) {
                try {
                    killer(process)
                } catch (_: Exception) {
                    swallowedKillerThrows.incrementAndGet()
                }
            }
            if (!preOk) preIncomplete = true
            if (!ElevatedCallSupervisor.verifyParentStoppedBounded(process)) {
                preIncomplete = true
                // Round-3 Phase E（P1-1）：preflight 監督殺後仍未驗證即登記 quarantine
                // （worker 完成≠verified dead；僅 verify 失敗才登記，worker stall 但已驗證
                // 死亡不登記——process 已死，無未驗證 handle；後續 spawn 以 QUARANTINE 拒，
                // 不靠 breaker 計數）。
                ElevatedCleanupTracker.quarantineUnverified(process)
            }
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
            val stdinRemainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos())
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
        // R3-PhaseF P2-1 drain 級取消觀察者：hook 只跑 watcher worker（拋折為
        // 未取消繼續觀察＋計數，不誤殺）；caller 只讀 [cancelBit] 受信位＋中斷＋
        // deadline，永不直調 hook（blocked hook 只卡 watcher，caller 經 deadline
        // 有界進 kill／tail 中止）。gobbler 起後、wait 輪詢前啟動（首求值時機≈舊
        // 主循環首輪 hook 位，B2 計數語義不變）。
        val cancelWatch = ElevatedCallSupervisor.startCancelWatch(deadlineNanos, isCancelled, false, nowNanos)
        // 受信取消讀閉包：傳入 [awaitWaitSlice]（其慢路徑 watcher 再包一層求此位，
        // 永不觸不可信代碼，見其 KDoc 契約）與 tail 循環。
        val cancelBit: () -> Boolean = { cancelWatch.bit() }
        try {
            while (true) {
                // R3-PhaseF＋PhaseG P2-3：只讀緩存 bit（hook 在 watcher worker 求值，
                // 此處永不直調）＋入口一次性 [cancelledAtEntry]。預置中斷的 caller
                // 在此即 break 走 kill 路徑，不當健康進程跑。
                if (cancelledAtEntry || cancelBit()) break
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos())
                if (remaining <= 0) break
                val slice = minOf(remaining, POLL_SLICE_MS)
                when (val w = ElevatedCallSupervisor.awaitWaitSlice(process, slice, deadlineNanos, cancelBit, nowNanos)) {
                    is ElevatedCallSupervisor.Res.Ok -> if (w.v) {
                        // R3-PhaseG P2-2 postcheck：該片 `waitFor==true` 只代表命令
                        // 已 exit，不代表整個 request 未逾期。整體逾期完成
                        // （deadline 已過／caller 已取消／入口已取消）不得置
                        // finished——break 走 kill 路徑（`timedOut=true`），與 tail
                        // 中止（`tailIncomplete`，`timedOut` 仍 false）區分。
                        // 逾期完成不攜 linger（slice worker 已死），只 break。
                        val overdueOrCancelled = cancelledAtEntry ||
                            Thread.currentThread().isInterrupted ||
                            cancelBit() ||
                            ElevatedCallSupervisor.isExpired(deadlineNanos, nowNanos)
                        if (overdueOrCancelled) break
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
        // R2-PhaseC P2-2：finished 路徑 tail 中止（取消／超時致 gobbler 中斷＋殘留）
        // 即 true（與 joinIncomplete 同置三處，見下；kill 路徑永 false）。
        // 命令 exit（finished）vs 整個 request finished 分開：本旗標真即 request 未完，
        // runner 側 would-be Ok 升級 TimedOut（見兩 runner P2-2 分支）。
        var tailIncomplete = false
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
            val cleanupRemainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos())
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
            // Round-3 Phase E（P1-1）：驗證失敗即登記 quarantine（worker 完成≠
            // verified dead；僅 verify 失敗才登記，cleanupOk==false 但已驗證死亡
            // 不登記）。verdict cleanupIncomplete 保持 true（經尾收終審），
            // quarantine 以 QUARANTINE 把 fail-closed 擴展到後續 spawn。
            if (!ElevatedCallSupervisor.verifyParentStoppedBounded(process)) {
                cleanupIncomplete = true
                ElevatedCleanupTracker.quarantineUnverified(process)
            }
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
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos()).coerceAtLeast(0L),
                FINISHED_TAIL_BUDGET_MS,
            )
            val tailDeadlineNanos = nowNanos() + TimeUnit.MILLISECONDS.toNanos(tailBudgetMs)
            fun tailRemainMs(): Long = minOf(
                TimeUnit.NANOSECONDS.toMillis(tailDeadlineNanos - nowNanos()).coerceAtLeast(0L),
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos()).coerceAtLeast(0L),
            )
            var tailAborted = false
            fun joinFinishedTail(t: Thread, counted: AtomicInteger) {
                if (tailAborted) {
                    // 前一流已因取消／逾期 abort：本流不再等，直接中斷＋三旗＋記槽。
                    if (t.isAlive) {
                        runCatching { t.interrupt() }
                        joinIncomplete = true
                        cleanupIncomplete = true
                        tailIncomplete = true
                        ElevatedCleanupTracker.markLingering(t, counted)
                    }
                    return
                }
                while (t.isAlive) {
                    // R3-PhaseF＋PhaseG P2-3：只讀緩存 bit（hook 在 watcher worker
                    // 求值，此處永不直調；拋折為未取消的語義由 watcher 側同策略
                    // 承擔，不誤殺）＋入口一次性 [cancelledAtEntry]。
                    if (cancelledAtEntry || cancelBit() || Thread.currentThread().isInterrupted ||
                        (deadlineNanos - nowNanos()) <= 0L
                    ) {
                        // 取消／呼叫執行緒中斷／deadline 耗盡：不再等流。
                        runCatching { t.interrupt() }
                        joinIncomplete = true
                        cleanupIncomplete = true
                        tailIncomplete = true
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
                        tailIncomplete = true
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
        // R3-PhaseF：watcher 收斂（殘留即記槽＋置旗，由下方終審統一進 breaker；
        // 正常完成即無 interrupt 影響，watcher 自行退出，零殘留）。
        if (cancelWatch.close()) cleanupIncomplete = true
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
        // `isInterrupted` 判超界，須在 caller 預置中斷恢復前求值。
        // R3-PhaseG P2-3 注記：預置中斷的 drain 已由 [cancelledAtEntry] 進 kill
        // 路徑（`finished=false`），不到此 finished 分支；此處只處理 tail 期間
        // 新到的中斷（join 內抛後 restore 的旗標），語義不變。
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
            when (val ev = ElevatedCallSupervisor.exitValueBounded(process, nowNanos)) {
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
            // R2-PhaseC P2-2：finished tail 中止原樣帶出（runner 側 would-be Ok
            // 升級 TimedOut；`timedOut` 本體仍為 `!finished`，命令 exit 語義不動）。
            tailIncomplete = tailIncomplete,
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

    // Phase 5 B3 死碼刪除：舊直調 `verifyParentStopped`（`waitFor(5s)` 無界版）
    // 已無呼叫者（grep 零程式呼叫，僅註解提及，現一律走有界 `verifyParentStoppedBounded`），刪除。
    // 語義見 `verifyParentStoppedBounded` KDoc（true／false／unknown→false 三分支保留）。

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
 * Round-3 Phase E（P1-1）：未驗證 quarantine 持有（[quarantineHeld]）拒絕亦收斂至此，
 * 與 quota 同級（零 spawn、不耗配額），但原因碼獨立（`QUARANTINE`，不與上兩因混碼）。
 *
 * AB1 呼叫級准入（owner 新 P1-A＋P1-B）：併發上限（[concurrencyFull]，訊息含
 * "concurrency full" 以區分飽和／熔斷兩因）拒絕收斂至此，與 quota 同級，同為
 * `QUOTA_EXCEEDED` 且零 spawn、不耗配額（准入在飽和／熔斷門之後、配額之前；
 * 成功准入後剩餘 runBinary 包 `try/finally` 釋放，見兩 runner）。
 *
 * Phase 5 B4 可調參數索引（僅 KDoc，不搬移常數；#136-B3 調參入口）：
 * - 准入／熔斷：`ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS`（7）／
 *   `MAX_CONSECUTIVE_UNCLEAN`（4）／`MAX_CONCURRENT_CALLS`（4）／
 *   `SLOTS_PER_CALL`（7，單呼叫槽位上界推導見其 KDoc）；
 * - 未驗證 quarantine（Round-3 Phase E，無上限常數可調：登記數天然上界見
 *   `ElevatedCleanupTracker.quarantine` KDoc；解除只經 `sweepQuarantine`
 *   有界重驗或 `clearUnverified` 顯式 recovery，不得調參繞行）；
 * - 監督節拍：`ElevatedCallSupervisor.POLL_MS`（20ms）／`GRACE_MS`（500ms）／
 *   `VERIFY_JOIN_MS`（5.5s）／`EXIT_JOIN_MS`（2s）／`LATE_REAP_WAIT_SEC`（1s）；
 * - 容量雙軌：`ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES`（8M 截斷）／
 *   `BINARY_SCREENSHOT_HARD_CAP_BYTES`（8M 硬讀，pipe 內保留上界）／
 *   `ShellPolicy.MAX_OUTPUT_BYTES`（64K）／`ShellPolicy.HARD_READ_CAP_BYTES`（512K）；
 * - drain 有界：`POLL_SLICE_MS`／`GOBBLER_JOIN_MS`／`FINISHED_TAIL_BUDGET_MS`（2s）／
 *   `CLEANUP_JOIN_MS`（3s）／`CLEANUP_MIN_MS`（500ms）／`CLEANUP_GRACE_MS`（500ms）／
 *   `STDIN_CLOSE_MAX_MS`（2s）（皆本檔 `ElevatedProcessIo` 私有常數，行為不變）。
 * 只調數值、不搬位置；放寬任一上限即放寬峰值保證，須同步重審 `peakOutstanding`
 * 上界與 six-point-barrier 迴歸。
 *
 * Phase 5 B1 大檔註記：本檔（`ElevatedProcessIo.kt`，含 `ElevatedCleanupTracker`／
 * `ElevatedCallSupervisor`／`ElevatedProcessIo`／`ElevatedRunnerCommon`）不拆分
 * （拆分重構風險＞收益，移動任一對象即大 diff＋KDoc 鏈斷裂）。
 * TODO(#136-B3): 大檔拆分重審（按對象分檔，保留 KDoc 鏈與常數位置）。
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

    /**
     * Round-3 Phase E 未驗證 quarantine 持有拒絕訊息（P1-1，與 quota 同級，
     * 零 spawn、不耗配額；原因碼獨立，不與 backlog／breaker 混碼）。
     */
    const val QUARANTINE_HELD_MESSAGE =
        "elevated unverified process quarantine held (fail-closed, no spawn)"

    /**
     * Round-3 Phase E 未驗證 quarantine 持有時的 typed 拒絕（P1-1）：
     * `QUARANTINE` 獨立原因碼（不與 backlog／breaker 的 `QUOTA_EXCEEDED` 混碼），
     * 不建子進程。`cleanupIncomplete=true`（quarantine 明確接管前 cleanup 仍未完成，
     * fail-closed 信號經 verdict 持久帶出；`toShellResult` 照既有契約丟棄本旗標，
     * 監測須用 runBinary）。
     */
    fun quarantineHeld(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.QUARANTINE, QUARANTINE_HELD_MESSAGE, true)

    /** 呼叫級併發上限拒絕訊息（AB1 P1-B 准入門，與 quota 同級，零 spawn、不耗配額）。 */
    const val CONCURRENCY_FULL_MESSAGE =
        "elevated concurrency full (fail-closed, no spawn)"

    /** 併發上限時的 typed 拒絕：`QUOTA_EXCEEDED`，不建子進程。 */
    fun concurrencyFull(): ElevatedBinaryResult.Denied =
        ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, CONCURRENCY_FULL_MESSAGE)

    /**
     * R2-PhaseC P2-1 spawn 前取消有界門（兩 runner 入口＋配額前／後三點共用）：
     * 經 [ElevatedCallSupervisor.awaitCancelBit] 求值（hook 只跑 worker，呼叫側
     * 只看自身中斷＋deadline），blocked hook 不能無限卡 caller。
     * - 中斷（不清旗標）／`Ok(true)`／`Err`（hook 抛，fail-closed＋計數）→
     *   `TimedOut`（空 partial，`cleanupIncomplete=false`；門 worker 已正常回收，
     *   無新增殘留）；
     * - `Expired`（hook 阻塞至 deadline／grace，或 budget 耗盡）→ `TimedOut`
     *   （有界返回，不斷 timeout 上界），旗標經 [preSpawnLinger] 判定
     *   （R3-PhaseG P2-4：block worker 殘留或已占 outstanding 即 true）；
     * - `Ok(false)` → null（未取消，呼叫方繼續）。
     * 呼叫形：`checkPreSpawnCancel(timeoutMs, deadlineNanos, isCancelled)?.let { return it }`.
     * R3-PhaseF P2-1 pre-admission 不變量：三門皆在全域席位內執行（入口門亦在
     * 准入之後，見兩 runner 准入順序——callback 未取 global lease 絕不 start
     * worker；拒絕路徑不耗 quota，佔席經 finally 釋放）。飽和／熔斷／quarantine
     * 拒絕優先於取消判定（席位未取得即無門可進，見准入 verdict 順序）。
     *
     * 語義保留（原 `isPreSpawnCancelled` 直調語義，現一律經本門，R3-PhaseF 已刪直調函數）：
     * 中斷不清旗標（不用 `Thread.interrupted()`）；`isCancelled()` 抛即已取消
     * （未建 fail-closed＋計數，見 [ElevatedProcessIo.swallowedIsCancelledThrows]）；
     * 本門刻意不看 deadline（取消語義純粹），budget 耗盡由各層顯式 `isExpired`
     * 承擔（supervisor 入口 preflight、quota 後提交前檢查、runner 入口早拒）。
     * hook 須為本地原子快照（µs 回、永不阻塞）；違約 blocking supplier 只卡
     * worker、caller 經 deadline 有界回 Expired（不斷 timeout 上界）。
     */
    internal fun checkPreSpawnCancel(
        timeoutMs: Long,
        deadlineNanos: Long,
        isCancelled: () -> Boolean,
    ): ElevatedBinaryResult.TimedOut? {
        if (Thread.currentThread().isInterrupted) return preSpawnExpired(timeoutMs, false)
        return when (val r = ElevatedCallSupervisor.awaitCancelBit(deadlineNanos, isCancelled)) {
            is ElevatedCallSupervisor.Res.Ok -> if (r.v) preSpawnExpired(timeoutMs, false) else null
            is ElevatedCallSupervisor.Res.Err -> {
                runCatching { ElevatedProcessIo.swallowedIsCancelledThrows.incrementAndGet() }
                preSpawnExpired(timeoutMs, false)
            }
            is ElevatedCallSupervisor.Res.Expired -> preSpawnExpired(timeoutMs, preSpawnLinger(r.hasLinger))
        }
    }

    /**
     * R3-PhaseG P2-4 非 spawn linger 旗標契約（決策 a：`cleanupIncomplete` 涵蓋
     * background worker，不另設欄位、不改名）：
     * - `cleanupIncomplete` 語義＝「本 request 已知有未完成清理（OS process 或
     *   background worker 持槽殘留），需進 breaker 終審」；`false` 僅在
     *   `!expiredHasLinger && outstanding()==0` 時成立——`result=false` 與同
     *   request `outstanding==1` 並存即為契約違反（舊 blocked entry 缺口）；
     * - `expiredHasLinger` 即本次監督 `Expired.hasLinger`（block worker grace 後
     *   仍存活）；`outstanding()>0` 覆蓋 watcher-only 殘留（block 已死但 watcher
     *   持槽）。准入序列化保證被准入 request 見到的 `outstanding>0` 必為自身
     *   殘留（他 call 殘留會先被准入門拒，見 [ElevatedCleanupTracker]），故無
     *   跨 request 誤傷；測試隔離另封跨測污染（見各測試 Before/After）。
     * - 適用範圍：一切「曾啟監督 worker 的逾期」映射（cancel 門／grant／bridge／
     *   status／suExists／validate 等 `Expired` 分支）；worker 從未啟動的純
     *   preflight（入口 `safeTimeoutMs<=0`、quota 後顯式 `isExpired`）保持
     *   `preSpawnExpired(false)`（零新增殘留，舊語義不動）。
     */
    internal fun preSpawnLinger(expiredHasLinger: Boolean): Boolean =
        expiredHasLinger || ElevatedCleanupTracker.outstanding() > 0

    /**
     * P1-2/P1-3 逾期 spawn 前 verdict（PR140 Phase 2，兩 runner 共用）：
     * 零 handoff 的 `TimedOut`（空 partial）。
     * - `hasLinger=false`：worker 從未啟動（入口 preflight／quota 後顯式檢查，
     *   見 [preSpawnLinger] 適用範圍）或門 worker 已正常回收且全域無殘留——
     *   `cleanupIncomplete=false`（舊 `preSpawnCancelled` 語義）；
     * - `hasLinger=true`：supervisor worker grace 後仍存活、確有逾期交付、或
     *   已占 outstanding（R3-PhaseG P2-4 決策 a：`cleanupIncomplete` 涵蓋
     *   background worker，經 [preSpawnLinger] 判定，不再「僅 spawn 逾期置 true」）——
     *   late Process 缺口或 background worker 殘留（Binder 已送達不可撤回／
     *   殘留 daemon 持槽，補償或回收結果未知），`cleanupIncomplete=true`。
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
