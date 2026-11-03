package dev.librepocket.privilege.github

import android.content.pm.PackageManager
import dev.librepocket.shell.ElevatedRequest
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
         * 最低服務版本（B1 決議，Current）。
         *
         * 對應 `Shizuku.isPreV11()` 邊界：pre-V11 缺乏現行授權/binder
         * 語義，禁止執行提權。`newProcess` 等遠端執行 API 屬 B2 接線範圍，
         * 本常數只守 B1 狀態門，不代表 B2 已驗證。
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
    /** 非 Available 回對應 typed Denied；Available 回 null（放行，由呼叫方執行）。 */
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

    /**
     * 門控執行：拒絕即回 Denied 且不碰 [spawn]；放行才委派 [spawn]。
     * 純函數可測（spawn 為假 lambda；B2/B3 前真實 runner 不經此函數）。
     */
    fun runIfAvailable(
        status: ShizukuStatus,
        request: ElevatedRequest,
        timeoutMs: Long,
        spawn: (ElevatedRequest, Long) -> ShellResult,
    ): ShellResult = denyFor(status) ?: spawn(request, timeoutMs)
}

/**
 * Shizuku binder 生命週期事件（B1）。
 *
 * 真實 `addBinderReceivedListener` / `addBinderDeadListener` 只在 [attach]
 * 內註冊（產品路徑，需 binder 環境）；狀態分發走純 JVM 回呼表，
 * 單測以 [emitReceived]/[emitDead] 驅動，零真機、零 su、零真連線。
 */
class ShizukuBinderEvents {
    private val receivedCallbacks = mutableListOf<() -> Unit>()
    private val deadCallbacks = mutableListOf<() -> Unit>()
    private val lock = Any()

    fun onReceived(callback: () -> Unit) {
        synchronized(lock) { receivedCallbacks += callback }
    }

    fun onDead(callback: () -> Unit) {
        synchronized(lock) { deadCallbacks += callback }
    }

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
     */
    fun attach() {
        runCatching {
            Shizuku.addBinderReceivedListener { emitReceived() }
        }
        runCatching {
            Shizuku.addBinderDeadListener { emitDead() }
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
