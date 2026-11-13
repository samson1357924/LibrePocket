package dev.librepocket.shell

import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 提權子進程預留介面（BACKLOG D05/D09，矩陣 §3）。
 *
 * - 本層只做「探測」，不實作任何提權執行：[ElevatedShellRunner] 沒有預設
 *   真實現，呼叫一律被 [DenyingElevatedRunner] 拒絕。
 * - Shizuku/Root 的真正橋接屬於 foss/github 風味後續工作（D09），屆時在風味源集
 *   另行提供實現；play 風味永遠不可見提權路徑。
 */
enum class Privilege {
    SHIZUKU,
    ROOT,
}

/** 被動探測結果：失敗不打擾使用者，呼叫方自動回落免 Root 路徑。 */
data class ProbeResult(
    val privilege: Privilege,
    val available: Boolean,
)

/** 提權執行請求：每次跨權限邊界呼叫必須攜帶原因碼（矩陣 §3 審計要求）。 */
data class ElevatedRequest(
    val argv: List<String>,
    val reasonCode: String,
)

/**
 * 快照專用有界門上限（Round-3 Phase H，P2-6）：
 * - [SNAPSHOT_MAX_WAIT_MS]：入口凍結等待上界（固定 1s，與執行 `timeoutMs` 無關——
 *   凍結是廉價結構准入（正常 argv 為 µs 級），不是執行 budget；阻塞 iterator 在此
 *   界內 fail-closed，不把呼叫執行緒無限卡住）；
 * - [SNAPSHOT_GRACE_MS]：超界後 `interrupt` 再等的 grace（worker 仍存活即殘留，
 *   見下）。
 *
 * Round-4 Phase N（P2-1，有界化）：超界殘留 worker 上限由
 * [ElevatedSnapshotPool] 限定（池容量 [ElevatedSnapshotPool.POOL_SIZE]＝2，
 * daemon，不擋 JVM 退出）。阻塞於不可中斷操作（吞中斷的自訂 iterator）即永久
 * 持一份 argv 引用，但全域至多 [ElevatedSnapshotPool.POOL_SIZE] 份（池满后新
 * caller 即时 fail-closed，不再起 worker）；供给阻塞 list 的本来就是呼叫方
 * 自身，属自限 DoS，不放大为跨呼叫無界（旧“每冻结恰一 worker”已消除）。
 * github 侧 runner 内快照另经 `awaitPreSpawn` 同 deadline 监督
 * （残留进 slot／breaker 终审，见 runner KDoc），本门只覆 main 侧入口。
 */
internal const val SNAPSHOT_MAX_WAIT_MS: Long = 1_000L

/** 快照門超界 grace（見上）。 */
internal const val SNAPSHOT_GRACE_MS: Long = 200L

/**
 * main 側快照共用有界池（Round-4 Phase N，P2-1）。
 *
 * 推導（固定小池＋零佇列，二選一之抉擇）：
 * - 不用「每呼叫各起一 thread」（舊碼）：20／100 caller 各創一永久活 daemon，
 *   `outstanding==0` 仍 thread 數無界——呼叫數即殘留上界，不可接受。
 * - 不用「原子 entry permit（semaphore）單獨限流」：permit 只限併發快照數，
 *   每呼叫仍各起一自有 thread（permit 外排隊的 caller 照樣持 thread 等，
 *   殘留 thread 數仍隨呼叫數成長）；且 permit 需另配 worker 承載拷貝，
 *   兩層機制不如一層池誠實。
 * - 固定小池 N＝2＋零佇列（[SynchronousQueue]）：池同時限定併發數與 thread
 *   數（全域恆 ≤2 worker，零新 thread——超限即 [RejectedExecutionException]
 *   轉 fail-closed，不排隊：排隊即把 caller 卡成隱性等待，與 1s 門重複且
 *   掩蓋飽和）；N 取 2（1 槽在單卡住時全拒、>2 放大殘留引用上限，快照正常
 *   µs 級故 2 槽不把正常呼叫串行化）。
 *
 * 飽和語義 fail-closed：池忙（2 槽皆卡於阻塞 iterator）時新呼叫即時回 null
 * （呼叫方映 `Denied(BLACKLISTED)`＋一筆 DENY 審計，零等待、零 spawn）；
 * 正常短碼呼叫在池空時恆 µs 級通过，不受池化影響。
 */
internal object ElevatedSnapshotPool {
    /** 池容量（見上推導；殘留引用全域上界同值）。 */
    const val POOL_SIZE: Int = 2

    private val threadSeq = AtomicInteger(0)
    private val pool = ThreadPoolExecutor(
        POOL_SIZE,
        POOL_SIZE,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        ThreadFactory { r ->
            Thread(r, "elevated-snapshot-${threadSeq.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
    )

    /** 忙即 null（fail-closed，不抛，零新 thread）。 */
    fun <T> trySubmit(block: () -> T): Future<T>? = try {
        pool.submit(block)
    } catch (_: RejectedExecutionException) {
        null
    } catch (_: Throwable) {
        // 起线程失败（资源枯竭）同样 fail-closed。
        null
    }

    /** 執行中 worker 數（僅單測斷言有界用；近似值）。 */
    internal fun activeCount(): Int = pool.activeCount
}

/**
 * Phase 4（P2-1，PR #140）：入口防禦性凍結。
 *
 * 呼叫方可能傳入 `MutableList` 並保留引用，在 runner 執行期間
 * （grant／status supplier 內）竄改內容；runner 內雖已用
 * 快照，但審計若讀原始 `request.argv` 即讀到竄改後值。
 * 本函數在入口即拷貝＋不可變包裝，後續 gate／執行／審計一律用
 * 同一份凍結物件。拷貝本身可能因併發竄改抛
 * [java.util.ConcurrentModificationException]（見 F9），故包
 * runCatching，失敗回 null，呼叫方 fail-closed 拒絕且零 spawn。
 *
 * Round-3 Phase H（P2-6）：拷貝體換為 [ShellPolicy.copyArgvBounded]
 *（argc≤64／單項≤4K 字元／總量≤64K UTF-8 位元組＋孤立代理拒絕，超限回 null，
 * 呼叫方同映 `Denied(BLACKLISTED)` 零 spawn），且整體跑快照專用有界門——自訂
 * `AbstractList` 的 `iterator()`／`size()` 可永久阻塞呼叫執行緒（舊 `runCatching`
 * 不足：抛可收斂，阻塞不可），现拷贝跑 [ElevatedSnapshotPool] 共用 worker、呼叫侧
 * 有界等（[SNAPSHOT_MAX_WAIT_MS]＋[SNAPSHOT_GRACE_MS]），阻塞即 fail-closed 回 null
 * （零 spawn；池忙即时回 null，零新 thread；中斷後仍存活即池內殘留，全域上界
 * [ElevatedSnapshotPool.POOL_SIZE]，見上殘餘聲明）。提交失敗（池滿／起線程失敗）
 * 同樣回 null（資源枯竭／飽和 fail-closed）。
 */
internal fun ElevatedRequest.frozenOrNull(): ElevatedRequest? {
    val future = ElevatedSnapshotPool.trySubmit { ShellPolicy.copyArgvBounded(argv) }
        ?: return null
    val copy: List<String>? = try {
        future.get(SNAPSHOT_MAX_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        // grace：给可中断 worker 退出机会；仍未 done 即池内残留（有界，见上）。
        val graceEnd = System.nanoTime() + SNAPSHOT_GRACE_MS * 1_000_000L
        while (!future.isDone && System.nanoTime() < graceEnd) {
            try {
                Thread.sleep(10L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        future.cancel(true)
        return null
    } catch (_: Throwable) {
        // 拷貝抛（理论不可达，copyArgvBounded 内已全收敛）即 fail-closed。
        return null
    }
    if (copy == null) return null
    return ElevatedRequest(
        java.util.Collections.unmodifiableList(copy),
        reasonCode,
    )
}

/**
 * 提權執行器介面（僅介面）。D05 階段不提供真實現；
 * D09 在 foss/github 源集落地前，呼叫方應使用 [DenyingElevatedRunner]。
 */
interface ElevatedShellRunner {
    fun run(request: ElevatedRequest, timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS): ShellResult
}

/** 佔位實現：任何提權執行一律拒絕且不建子進程（證明「僅介面」）。 */
class DenyingElevatedRunner(
    private val message: String = "elevated execution not implemented (D09)",
) : ElevatedShellRunner {
    override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
        ShellResult.Denied(ShellDeny.NOT_WHITELISTED, message)
}

/**
 * 被動探測器：Shizuku binder 可達性 / su 可用性。
 *
 * - 預設 su 探測只做常見路徑存在性檢查，不執行任何二進位。
 * - Shizuku 預設探測固定回 false（真實 binder 檢查需 Android/Shizuku 依賴，
 *   由 foss/github 風味的 D09 實現注入）；兩探測函數均可注入，便於單測。
 */
class PrivilegeProbe(
    private val shizukuProbe: () -> Boolean = { false },
    private val suProbe: () -> Boolean = defaultSuProbe,
) {
    fun probe(): List<ProbeResult> = listOf(
        ProbeResult(Privilege.SHIZUKU, runCatching { shizukuProbe() }.getOrDefault(false)),
        ProbeResult(Privilege.ROOT, runCatching { suProbe() }.getOrDefault(false)),
    )

    fun anyAvailable(): Boolean = probe().any { it.available }

    companion object {
        private val SU_PATHS: List<String> = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )

        /** 預設 su 探測：僅檢查檔案存在性，不執行。 */
        val defaultSuProbe: () -> Boolean = {
            SU_PATHS.any { File(it).exists() }
        }
    }
}
