package dev.librepocket.shell

import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
 * 殘餘誠實聲明：超界殘留的 worker 為 daemon（不擋 JVM 退出），但若其阻塞於不可中斷
 * 操作即永久持一份 argv 引用（單次呼叫至多一殘留；供給阻塞 list 的本來就是呼叫方
 * 自身，屬自限 DoS，不放大為跨呼叫無界 worker——每次凍結恰一 worker，絕無一呼叫
 * 多 worker）。github 側 runner 內快照另經 `awaitPreSpawn` 同 deadline 監督
 * （殘留進 slot／breaker 終審，見 runner KDoc），本門只覆 main 側入口。
 */
internal const val SNAPSHOT_MAX_WAIT_MS: Long = 1_000L

/** 快照門超界 grace（見上）。 */
internal const val SNAPSHOT_GRACE_MS: Long = 200L

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
 * 不足：抛可收斂，阻塞不可），現拷貝跑 daemon worker、呼叫側 `join` 上界
 * （[SNAPSHOT_MAX_WAIT_MS]＋[SNAPSHOT_GRACE_MS]），阻塞即 fail-closed 回 null
 * （零 spawn、不建無界背景 worker：每凍結恰一 worker；中斷後仍存活即 daemon 殘留，
 * 見上殘餘聲明）。起線程失敗同樣回 null（資源枯竭 fail-closed）。
 */
internal fun ElevatedRequest.frozenOrNull(): ElevatedRequest? {
    val holder = AtomicReference<Any?>()
    val worker = Thread({
        try {
            holder.set(ShellPolicy.copyArgvBounded(argv))
        } catch (t: Throwable) {
            // copyArgvBounded 內已全收斂（理論不可達）；兜底不外抛。
            holder.set(t)
        }
    }, "elevated-snapshot")
    worker.isDaemon = true
    try {
        worker.start()
    } catch (_: Throwable) {
        return null
    }
    try {
        worker.join(SNAPSHOT_MAX_WAIT_MS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        runCatching { worker.interrupt() }
        return null
    }
    if (worker.isAlive) {
        runCatching { worker.interrupt() }
        runCatching { worker.join(SNAPSHOT_GRACE_MS) }
        return null
    }
    val h = holder.get()
    if (h is List<*>) {
        @Suppress("UNCHECKED_CAST")
        return ElevatedRequest(
            java.util.Collections.unmodifiableList(h as List<String>),
            reasonCode,
        )
    }
    return null
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
