package dev.librepocket.privilege.github

import android.content.pm.PackageManager
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellResult
import rikka.shizuku.Shizuku

/**
 * Shizuku 型別化狀態機（B1，BACKLOG D09，Issue #136 B1，僅 github 風味）。
 *
 * 取代裸 boolean 探測：binder 可用性、授權狀態、版本相容各自收斂為
 * [ShizukuStatus]，非 [ShizukuStatus.Available] 一律經
 * [ShizukuStatusGate] 回 typed [ShellResult.Denied] 且不建任何子進程
 * （「拒絕即 typed 不執行」；runner 真正接線是 B2/B3，本階段只提供門）。
 *
 * - 設計沿用 [ShizukuProbe] 的 fail-closed 慣例：任何探測異常收斂為
 *   不可用，呼叫方回落免 Root 路徑，不打擾使用者；
 * - 版本相容：服務版本未知（異常/回傳異常值）或低於
 *   [MIN_SERVICE_VERSION] 皆視為不相容（fail-closed），禁止以舊服務
 *   執行提權；
 * - 零真機依賴：[evaluate] 為純函數，JVM 單測以假 lambda 斷言；
 *   真實 Shizuku 呼叫只發生在預設參數內（產品路徑）。
 */
sealed interface ShizukuStatus {
    /** 可執行：binder 存活＋已授權＋版本相容。 */
    data class Available(val serviceVersion: Int) : ShizukuStatus

    /** binder 存活但使用者未授權（`checkSelfPermission != GRANTED`）。 */
    data class PermissionDenied(val serviceVersion: Int) : ShizukuStatus

    /** 服務版本過舊或未知：fail-closed，不可執行。 */
    data class VersionUnsupported(
        val serviceVersion: Int,
        val required: Int = MIN_SERVICE_VERSION,
    ) : ShizukuStatus

    /** binder 不可達（未安裝 Shizuku Manager / binder 死亡 / ping 失敗）。 */
    data class BinderUnreachable(val reason: String) : ShizukuStatus

    companion object {
        /**
         * 最低服務版本（B1 決議）。
         *
         * 版本來源為 `Shizuku.getVersion()`（見 [ShizukuStatusProbe.status]
         * 的 `serviceVersion` 預設參數）；邊界取 pre-V11：pre-V11 缺乏現行
         * 授權/binder 語義（對應 `Shizuku.isPreV11()` 劃分），禁止執行提權。
         * `newProcess` 等遠端執行 API 屬 B2 接線範圍，本常數只守 B1 狀態門，
         * 不代表 B2 已驗證。
         */
        const val MIN_SERVICE_VERSION: Int = 11

        /** binder 未達原因碼（審計只記碼，不記堆疊）。 */
        const val REASON_BINDER_DOWN = "SHIZUKU_BINDER_DOWN"

        /** 授權拒絕原因碼。 */
        const val REASON_PERMISSION_DENIED = "SHIZUKU_PERMISSION_DENIED"

        /** 版本不相容原因碼（含未知版本 fail-closed）。 */
        const val REASON_VERSION_UNSUPPORTED = "SHIZUKU_VERSION_UNSUPPORTED"

        /**
         * 純求值：binder → 版本 → 授權依序判定，任一步驟失敗即定型。
         *
         * @param binderAlive binder 可達性（ping 包裝，異常由呼叫方收斂為 false）。
         * @param permissionGranted `checkSelfPermission == GRANTED`（異常收斂為 false）。
         * @param serviceVersion `Shizuku.getVersion()`（異常收斂為 null；null/低於門檻皆不相容）。
         */
        fun evaluate(
            binderAlive: Boolean,
            permissionGranted: Boolean,
            serviceVersion: Int?,
        ): ShizukuStatus {
            if (!binderAlive) return BinderUnreachable(REASON_BINDER_DOWN)
            val version = serviceVersion
            if (version == null || version < MIN_SERVICE_VERSION) {
                return VersionUnsupported(serviceVersion = version ?: -1)
            }
            if (!permissionGranted) return PermissionDenied(serviceVersion = version)
            return Available(serviceVersion = version)
        }
    }
}

/** 是否允許提權執行：僅 [ShizukuStatus.Available] 放行。 */
val ShizukuStatus.canExecute: Boolean
    get() = this is ShizukuStatus.Available

/**
 * Shizuku 執行門（B1）：typed 拒絕，不建子進程。
 *
 * B3 dispatcher 在每次 call 前以此門守衛：非 Available 即回
 * [ShellResult.Denied]（reason 為各狀態的原因碼），spawn 永不被呼叫。
 */
object ShizukuStatusGate {
    /**
     * 非 Available 回 typed [ShellResult.Denied]；Available 回 null
     * （放行，由呼叫方经自有内门执行——两 runner 在 runBinary 内自带
     * grant/activation/bridge/status 门，不经此处 spawn）。
     *
     * 語義（P2-B 釐清）：四種非 Available 狀態一律回
     * [ShellDeny.NOT_WHITELISTED]（[ShellDeny] 無更精確的
     * binder/授權/版本碼，故不另映射）；細因只放在 `message`
     *（各狀態的 `REASON_*` 碼＋上下文），呼叫方不得對 `message`
     * 做程式分支（審計若按 reason 分類會失真：verdict 層只見
     * NOT_WHITELISTED）。
     *
     * P3-2 现状 pin（行为不变）：verdict 粒度坍缩为单码 NOT_WHITELISTED 系刻意
     * （[ShellDeny] 无 binder/授权/版本细分）；细因仅进 `message` 作审计文本，
     * 不得作程序分支（见上）。TODO(B3): 若需结构化原因码（如新增
     * `DeniedWithCode(reason, code)` 或审计侧 reasonCode 字段），须同步
     * [dev.librepocket.shell.ShellResult] 与审计映射并更新门测试；
     * 现阶段刻意不做（P3-2 文件化不改行为）。
     *
     * 相容壳移除说明（Phase3 P2）：旧 `@Deprecated runIfAvailable` 已删除。
     * 其 `Available → 直调 spawn` 语义允许 B3 经缓存的 Available 绕过 runner
     * 内门直达 spawn；删除后放行路径唯一：`denyFor(...) == null` 再由呼叫方
     * （runner 内门，非缓存状态）执行。测试替代：门语义由
     * `ShizukuStatusTest` 的 denyFor 断言覆盖，零 spawn 由 runner 侧
     * `ElevatedRunnerB2Test.shizukuGateBlocksSpawnWhenNotAvailable` 覆盖。
     */
    fun denyFor(status: ShizukuStatus): ShellResult.Denied? = when (status) {
        is ShizukuStatus.Available -> null
        is ShizukuStatus.PermissionDenied -> ShellResult.Denied(
            ShellDeny.NOT_WHITELISTED,
            "${ShizukuStatus.REASON_PERMISSION_DENIED} (shizuku permission not granted)",
        )
        is ShizukuStatus.VersionUnsupported -> ShellResult.Denied(
            ShellDeny.NOT_WHITELISTED,
            "${ShizukuStatus.REASON_VERSION_UNSUPPORTED} " +
                "(running=${status.serviceVersion} required>=${status.required})",
        )
        is ShizukuStatus.BinderUnreachable -> ShellResult.Denied(
            ShellDeny.NOT_WHITELISTED,
            "${status.reason} (shizuku binder unreachable; fallback to unprivileged path)",
        )
    }
}

/**
 * Shizuku binder 生命週期事件（B1）。
 *
 * 真實 `addBinderReceivedListener` / `addBinderDeadListener` 只在 [attach]
 * 內註冊（產品路徑，需 binder 環境）；狀態分發走純 JVM 回呼表，
 * 單測以 [emitReceived]/[emitDead] 驅動，零真機、零 su、零真連線。
 *
 * 重試語義：[attach] 僅在兩段上游註冊都成功後才將守衛置 true；
 * 任一段失敗即回滾已註冊項並保持 false，呼叫方可重試。JVM 單測下
 * Shizuku 類初始化即失敗（android.os.Binder 未 mock），attach 恆走
 * 失敗路徑、守衛恆 false——此恰可斷言失敗不卡死守衛；真機缺服務時
 * 同理，重試不被冪等守衛吞掉。
 * 上游為靜態 Shizuku 類無法注入失敗，單測只斷言本地語義
 * （守衛＋回呼表）。
 *
 * S2-B Binder E2E 誠實化（owner P2-5，僅 KDoc 文件化，行為零變更）：
 * - JVM 單測走註冊失敗路徑（Shizuku 類初始化失敗，`isAttached` 恆 false）：
 *   僅斷言本地語義（守衛＋回呼表＋失敗不卡死＋detach 清表），不代表真機 binder 語義；
 * - 真實 binder attach→detach→attach 不重複（上游不累積、重建後單分發）
 *   需裝置 E2E 驗收（歸 B3，見 [PrivilegeActivation]；JVM 下上游靜態表無法斷言，
 *   僅由 [detach] 內同一引用 remove* 保證）。
 *
 * B3 接線立約（Phase3 P2，僅 KDoc，行為不變）：
 * - `onDead` 產品接線：binder 死亡即視為授權失效——回呼內執行 grant 撤銷
 *   （`ShizukuGrantStore.clear()`）並記審計；撤銷後已建 runner 次 call 即經
 *   grant 門／spawn 前重求值拒絕且零 spawn（見兩 runner grant 測試的全鏈路）。
 * - 禁止快取 [ShizukuStatus]：狀態只在每次 call 經 [ShizukuStatusProbe.status]
 *   新鮮求值（含 runner spawn 前重估）；任何「存 Available 複用」皆會繞過
 *   runner 內門直達 spawn（`runIfAvailable` 已刪即為封堵此路徑）。
 *   回呼內只做撤銷＋審計，不得存狀態供後續放行。
 */
class ShizukuBinderEvents {
    private val receivedCallbacks = mutableListOf<() -> Unit>()
    private val deadCallbacks = mutableListOf<() -> Unit>()
    private val lock = Any()

    /** 是否已向 Shizuku 註冊過 listener（冪等守衛，僅 [attach]/[detach]/[clear] 內讀寫）。 */
    private var attached = false

    /**
     * 暫存的上游 listener 引用：attach 建新並以同一物件註冊，
     * detach 以同一引用反註冊；讀寫皆在 [lock] 下，失敗/解除即置 null。
     */
    private var receivedListener: Shizuku.OnBinderReceivedListener? = null
    private var deadListener: Shizuku.OnBinderDeadListener? = null

    fun onReceived(callback: () -> Unit) {
        synchronized(lock) { receivedCallbacks += callback }
    }

    /**
     * binder 死亡回呼註冊（B3 接線：回呼內执行 grant 撤銷＋審計，見本類 KDoc 立約；
     * 回呼內禁止快取 [ShizukuStatus]，狀態只在每次 call 新鮮求值）。
     */
    fun onDead(callback: () -> Unit) {
        synchronized(lock) { deadCallbacks += callback }
    }

    /** 是否已成功註冊上游 listener（守衛讀取；供單測斷言重試/重建語義）。 */
    fun isAttached(): Boolean = synchronized(lock) { attached }

    /** 僅測試/真實 listener 轉發用：觸發 received 快照（異常隔離，逐個收斂）。 */
    fun emitReceived() {
        val snapshot = synchronized(lock) { receivedCallbacks.toList() }
        snapshot.forEach { runCatching { it() } }
    }

    /** 僅測試/真實 listener 轉發用：觸發 dead 快照（異常隔離，逐個收斂）。 */
    fun emitDead() {
        val snapshot = synchronized(lock) { deadCallbacks.toList() }
        snapshot.forEach { runCatching { it() } }
    }

    /**
     * 產品接線：向 Shizuku 註冊 binder received/dead listener，事件轉發進回呼表。
     * 註冊本身異常 runCatching 收斂（未安裝/服務異常時不崩，狀態門仍 fail-closed）。
     * 冪等：成功後重複呼叫只註冊一次（[attached] 守衛）；失敗不置守衛，允許重試：
     * 首段失敗直接丟棄暫存引用，次段失敗先反註冊首段已註冊項再丟棄，
     * 避免半註冊殘留與重試累積。
     */
    fun attach() {
        synchronized(lock) {
            if (attached) return
            // 同一物件註冊/反註冊：暫存引用，重試復用殘留（失敗路徑已清，此處多為新建）。
            val stagedReceived: Shizuku.OnBinderReceivedListener =
                receivedListener ?: Shizuku.OnBinderReceivedListener { emitReceived() }
                    .also { receivedListener = it }
            val stagedDead: Shizuku.OnBinderDeadListener =
                deadListener ?: Shizuku.OnBinderDeadListener { emitDead() }
                    .also { deadListener = it }
            if (runCatching { Shizuku.addBinderReceivedListener(stagedReceived) }.isFailure) {
                if (receivedListener === stagedReceived) receivedListener = null
                if (deadListener === stagedDead) deadListener = null
                return
            }
            if (runCatching { Shizuku.addBinderDeadListener(stagedDead) }.isFailure) {
                runCatching { Shizuku.removeBinderReceivedListener(stagedReceived) }
                if (receivedListener === stagedReceived) receivedListener = null
                if (deadListener === stagedDead) deadListener = null
                return
            }
            attached = true
        }
    }

    /**
     * 解除接線：反註冊上游 listener 並清空本地回呼表、丟棄暫存引用、重置守衛。
     *
     * 產品唯一出口：任何 attach 成功路徑必須以 [detach] 收尾，不得只調 [clear]——
     * [clear] 不觸碰上游註冊，只清本地會造成上游靜態表殘留累積（listener 洩漏，
     * 每次 attach→clear 循環在 Shizuku 上游靜態表多留兩個引用；JVM 單測無上游
     * 故不斷言，見本類 KDoc S2-B）。
     *
     * 上游 13.1.5 已提供 `removeBinderReceivedListener` /
     * `removeBinderDeadListener`（簽名已對本地 gradle cache 的
     * api-13.1.5.aar 以 javap 驗證；舊版上游缺此方法時
     * NoSuchMethodError 由 runCatching 收斂為空轉，屬版本差異）。
     * 空 detach（未 attach 即調）為無操作，不外抛。
     */
    fun detach() {
        val stagedReceived: Shizuku.OnBinderReceivedListener?
        val stagedDead: Shizuku.OnBinderDeadListener?
        synchronized(lock) {
            stagedReceived = receivedListener
            stagedDead = deadListener
        }
        clear()
        if (stagedReceived != null) {
            runCatching { Shizuku.removeBinderReceivedListener(stagedReceived) }
        }
        if (stagedDead != null) {
            runCatching { Shizuku.removeBinderDeadListener(stagedDead) }
        }
    }

    /**
     * 清空本地回呼表並重置 [attached] 守衛與暫存 listener 引用
     * （synchronized；測試/生命週期重建用）。
     *
     * 注意：此函數不觸碰上游註冊（純 JVM，JVM 單測安全）；
     * 產品路徑請用 [detach] 以同時反註冊上游，避免上游殘留累積。
     * 可見性收斂（Phase3 P2）：internal——產品只暴露 [detach]，
     * 外部誤調本函數會造成上游 listener 洩漏（同上）。
     */
    internal fun clear() {
        synchronized(lock) {
            receivedCallbacks.clear()
            deadCallbacks.clear()
            receivedListener = null
            deadListener = null
            attached = false
        }
    }
}

/**
 * B1 狀態探測組合（[ShizukuProbe] 的 typed 延伸；原 boolean API 不動）。
 *
 * 求值順序刻意與 [ShizukuStatus.evaluate] 對齊：ping 失敗即短路，
 * 不再觸碰 `checkSelfPermission` / `getVersion`（binder 已死時後兩者
 * 必然異常，短路避免無意義異常與 Android stub 依賴）；
 * 版本未知/過舊亦在 fetch 層短路為 [ShizukuStatus.VersionUnsupported]，
 * 不再呼叫 `checkSelfPermission`。
 */
object ShizukuStatusProbe {
    fun status(
        pingBinder: () -> Boolean = { ShizukuProbe.ping() },
        checkPermission: () -> Boolean = {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        },
        serviceVersion: () -> Int = { Shizuku.getVersion() },
    ): ShizukuStatus {
        val alive = runCatching { pingBinder() }.getOrDefault(false)
        if (!alive) return ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN)
        val version = runCatching { serviceVersion() }.getOrNull()
        if (version == null || version < ShizukuStatus.MIN_SERVICE_VERSION) {
            return ShizukuStatus.VersionUnsupported(serviceVersion = version ?: -1)
        }
        val granted = runCatching { checkPermission() }.getOrDefault(false)
        return ShizukuStatus.evaluate(
            binderAlive = true,
            permissionGranted = granted,
            serviceVersion = version,
        )
    }
}
