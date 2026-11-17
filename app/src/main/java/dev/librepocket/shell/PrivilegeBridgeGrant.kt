package dev.librepocket.shell

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.privilegeBridgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "librepocket_privilege_bridge",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal const val PRIVILEGE_PENDING_REVOKE_FILE_NAME = "librepocket_privilege_revoke.pending"

internal val PRIVILEGE_GRANT_PROVIDER_ID = stringPreferencesKey("privilege_grant_provider_id_v1")
internal val PRIVILEGE_GRANT_CONFIG_REVISION = longPreferencesKey("privilege_grant_config_revision_v1")
internal val PRIVILEGE_GRANT_AT_MS = longPreferencesKey("privilege_grant_at_ms_v1")
internal val PRIVILEGE_GRANT_EXPIRES_AT_MS = longPreferencesKey("privilege_grant_expires_at_ms_v1")
internal val PRIVILEGE_REVOKE_JOURNAL = booleanPreferencesKey("privilege_revoke_journal_v1")

private const val TAG = "PrivilegeBridgeStore"

/**
 * android.util.Log 在非 Robolectric 的 JVM 單元測試中未 mock（呼叫即抛
 * RuntimeException）；日誌絕不得影響 fail-closed 控制流，故包一層容錯。
 */
private fun safeLogW(tag: String, msg: String, e: Throwable? = null) {
    try {
        if (e != null) {
            Log.w(tag, msg, e)
        } else {
            Log.w(tag, msg)
        }
    } catch (_: Throwable) {
        // 單元測試環境無 android.util.Log：吞掉
    }
}

/**
 * 提權橋持久撤銷標記檔封裝（跨進程/重啟防死灰復燃）。
 *
 * - [markPending]：記憶體旗標先置位，再盡力落盤。落盤失敗回 `false`
 *   （marker 路徑為目錄／父路徑為普通檔案或唯讀／檔案系統或安全例外等），
 *   不抛例外；記憶體旗標已先置位，同行程 [isPending] 仍為 true（fail-closed）。
 *   呼叫端應以撤權 journal 為持久兜底，marker 僅為快速 fail-closed 訊號。
 * - [isPending]：記憶體旗標 OR 檔案分支（[File.isFile]；marker 是空目錄時
 *   `exists()==true` 會造成永久 pending 且永不好——反向失效，故用 `isFile`）；
 *   `file == null` 時只看記憶體。檔案系統/安全例外一律 fail-closed 回 true。
 */
internal class PendingRevokeMarker(val file: File? = null) {
    private val inMemoryPending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isPending(): Boolean = try {
        // 檔案分支用 isFile（marker 是空目錄時 exists()==true 會永久 pending 且永不好——
        // 反向失效）；記憶體旗標 OR 檔案分支（記憶體優先語義：同行程記憶體 pending
        // 不可因檔案缺失/為目錄而被放行）。file == null 時只看記憶體。
        inMemoryPending.get() || (file != null && file.isFile)
    } catch (t: Exception) {
        if (t is CancellationException) throw t
        true // Fail-closed: assume pending on filesystem/security error so grant is not loaded
    }

    /**
     * 置 pending 標記：記憶體先置位，再盡力落盤。
     *
     * @return true＝撤銷意圖已至少以記憶體／檔案形式確立；false＝檔案落盤失敗
     *（父路徑為普通檔案／唯讀、marker 路徑為目錄、檔案系統/安全例外等）。
     * 不抛例外——呼叫端依需求自行 fail-closed（journal 為持久保障）。
     */
    fun markPending(): Boolean {
        inMemoryPending.set(true)
        val f = file ?: return true
        return try {
            val parent = f.parentFile
            // mkdirs() 於目錄已存在時回 false，故僅「mkdirs 失敗且父仍非目錄」
            // 才判定落盤失敗（父為普通檔案／唯讀皆在此折回 false，不得吞成靜默成功）。
            if (parent != null && !parent.mkdirs() && !parent.isDirectory) {
                return false
            }
            // createNewFile() 對「已存在的普通檔案」回 false 但授權 pending 仍成立（isFile 分支）；
            // 對「路徑為目錄」回 false 且 isFile 亦 false → 落盤失敗回 false。
            if (f.createNewFile()) {
                true
            } else {
                f.isFile
            }
        } catch (t: Exception) {
            if (t is CancellationException) throw t
            false
        }
    }

    fun clearPending() {
        inMemoryPending.set(false)
        val f = file ?: return
        try {
            if (f.exists()) {
                f.delete()
            }
        } catch (t: Exception) {
            if (t is CancellationException) throw t
            // best effort
        }
    }
}

/**
 * 提權橋授權持久化（#136-B1，BACKLOG D09，矩陣 §3，三 flavor 共用 main 側）。
 *
 * - 授權綁定 endpoint 身分（providerId）＋設定版本（configRevision，
 *   見 `EndpointStore`）：endpoint 切換或設定寫入推進 revision 後舊授權即失效
 *   （fail-closed，需重新授權）；另可攜到期時間（[expiresAtMs]，null＝永不過期）；
 * - 撤銷（[PrivilegeBridgeStore.clear]／[PrivilegeBridgeState.revoke]）即失效：
 *   讀不到即無授權，讀取失敗／格式損毀同樣折為無授權，絕不靜默放行；
 * - 純策略（[PrivilegeGrantPolicy]）零 Android 依賴，JVM 可測；
 *   DataStore 薄封裝（[PrivilegeBridgeStore]）只做存取，判定全委派策略；
 * - 本檔零第三方提權 IPC/庫引用：play／foss artifact gate（字串黑名單＋
 *   manifest pin）不受影響；執行接線仍歸 #136-B3
 *   （`PrivilegeActivation.ELEVATED_RUNNERS_READY` 維持 `false`，
 *   兩 runner 預設零 spawn）。
 * - 行程生命週期：提權狀態與橋授權僅存在於主 App 行程，獨立的 `:voice`
 *   服務行程（語音助理）不持有亦不執行提權指令，避免多行程狀態同步與撕裂。
 *
 * 與 github 側 ShizukuGrant 的分工：
 * Shizuku 側授權（Manager 內的手動允許）由該 Store 持有；
 * 本 Store 持有「提權橋開關」自身的授權（UI 一鍵授權／收回的持久真相源）。
 * 一鍵收回的跨域統一（同時清兩 Store）由 B3 產品接線經
 * [PrivilegeBridgeState.revoke] 的 `withdrawScope` 參數完成，本檔不跨源集引用。
 */
data class PrivilegeGrant(
    val providerId: String,
    val configRevision: Long,
    val grantedAtMs: Long,
    /**
     * 到期毫秒（epoch）。null＝永不過期；到達該時刻即失效
     * （判定 `nowMs >= expiresAtMs`，到期瞬間已不可用）。
     */
    val expiresAtMs: Long?,
)

/** 橋授權有效性判定（typed，fail-closed）。 */
sealed interface PrivilegeGrantCheck {
    data object Valid : PrivilegeGrantCheck
    data class Invalid(val reason: String) : PrivilegeGrantCheck
}

object PrivilegeGrantPolicy {
    const val REASON_NEVER_GRANTED = "PRIVILEGE_GRANT_NEVER_GRANTED"
    const val REASON_MALFORMED = "PRIVILEGE_GRANT_MALFORMED"
    const val REASON_ENDPOINT_CHANGED = "PRIVILEGE_GRANT_ENDPOINT_CHANGED"
    const val REASON_REVISION_STALE = "PRIVILEGE_GRANT_REVISION_STALE"
    const val REASON_CONFIG_UNREADABLE = "PRIVILEGE_GRANT_CONFIG_UNREADABLE"
    const val REASON_CLOCK_ROLLBACK = "PRIVILEGE_GRANT_CLOCK_ROLLBACK"
    const val REASON_EXPIRED = "PRIVILEGE_GRANT_EXPIRED"

    /**
     * 簽發授權。
     *
     * @param ttlMs 授權時效（毫秒，null＝永不過期；須非負，負值 fail-fast 抛錯，
     * 呼叫方不得以「已過期授權」試探判定路徑）。溢出時 fail-fast 抛錯。
     */
    fun grant(
        providerId: String,
        configRevision: Long,
        ttlMs: Long? = null,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrant {
        require(providerId.isNotBlank() && providerId.none { it.isISOControl() }) { "PROVIDER_ID_INVALID" }
        require(configRevision > 0L) { "CONFIG_REVISION_INVALID" }
        require(atMs >= 0L) { "AT_MS_NEGATIVE" }
        require(ttlMs == null || ttlMs >= 0L) { "TTL_NEGATIVE" }
        val expiresAtMs = ttlMs?.let {
            try {
                Math.addExact(atMs, it)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("TTL_OVERFLOW")
            }
        }
        return PrivilegeGrant(
            providerId = providerId,
            configRevision = configRevision,
            grantedAtMs = atMs,
            expiresAtMs = expiresAtMs,
        )
    }

    /**
     * @param grant 已持久化授權（null＝從未授權或已被撤銷）。
     * @param currentProviderId 當前 endpoint 身分（null/blank＝讀不到當前設定，
     * fail-closed 為無效，禁止沿用舊授權）。
     * @param currentRevision 當前設定版本（null＝讀不到版本，同樣 fail-closed）。
     * @param nowMs 以此時刻判定到期（到期瞬間 `nowMs >= expiresAtMs` 即失效）。
     */
    fun check(
        grant: PrivilegeGrant?,
        currentProviderId: String?,
        currentRevision: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrantCheck {
        if (grant == null) return PrivilegeGrantCheck.Invalid(REASON_NEVER_GRANTED)
        if (grant.providerId.isBlank() ||
            grant.providerId.any { it.isISOControl() } ||
            grant.configRevision <= 0L ||
            grant.grantedAtMs < 0L ||
            (grant.expiresAtMs != null && grant.expiresAtMs < grant.grantedAtMs)
        ) {
            return PrivilegeGrantCheck.Invalid(REASON_MALFORMED)
        }
        if (currentProviderId.isNullOrBlank() || currentRevision == null) {
            return PrivilegeGrantCheck.Invalid(REASON_CONFIG_UNREADABLE)
        }
        if (grant.providerId != currentProviderId) {
            return PrivilegeGrantCheck.Invalid(REASON_ENDPOINT_CHANGED)
        }
        if (grant.configRevision != currentRevision) {
            return PrivilegeGrantCheck.Invalid(REASON_REVISION_STALE)
        }
        val expiresAtMs = grant.expiresAtMs
        // 時鐘回撥防禦：僅在設定到期時間（expiresAtMs != null）時生效。
        // 永不過期授權（expiresAtMs == null）之 TTL 無延期風險，避免因輕微 NTP 調整造成誤判無效。
        if (expiresAtMs != null && nowMs < grant.grantedAtMs) {
            return PrivilegeGrantCheck.Invalid(REASON_CLOCK_ROLLBACK)
        }
        if (expiresAtMs != null && nowMs >= expiresAtMs) {
            return PrivilegeGrantCheck.Invalid(REASON_EXPIRED)
        }
        return PrivilegeGrantCheck.Valid
    }
}

/**
 * 橋授權 DataStore 持久化（三 flavor 共用 main 側）。
 *
 * - 讀寫皆 fail-closed：讀取異常/缺鍵/空白 providerId 一律回 null
 *   （呼叫方視為未授權）；寫入校驗失敗抛給呼叫方（呼叫方不得吞錯放行）；
 * - `expiresAtMs` 缺鍵＝永不過期（與 policy `null` 語義一致）；
 * - 撤銷＝[clear]：先置 pending marker，再寫撤權 journal（revoke_journal=true），
 *   再擦除四 grant 鍵與 journal（marker → journal → 擦除的有序序列；journal
 *   與 grant 同 durability 假設）。第二次 edit 失敗時 grant 鍵可能殘留，但
 *   journal（與 marker）為持久兜底——[load] 見 journal 即自癒擦除
 *   （雙重失敗殘留見「已知限制」(ii)）；擦除失敗僅 logcat 一行（[Log.w]），
 *   絕不靜默放行。
 * - 序列化契約：本 Store 無內部互斥，save/clear/load heal 跨 edit 無全序；
 *   產品路徑（[PrivilegeBridgeState]）由該 State 的 opMutex 序列化；直接呼叫
 *   本 Store 的呼叫端同此契約——否則 fresh grant 可能被 in-flight clear 擦除
 *   （fail-closed 方向，非越權）。
 *
 * 已知限制：
 * (i) marker 分支 heal 無條件擦鍵：marker pending 期間並發 save() 的新授權可能被誤擦
 *     （fail-closed；generation/epoch 需 Shizuku 與 State 側同構修改，併 #136-B3 處理）。
 * (ii) clear() journal 寫入失敗＋best-effort wipe 亦失敗＋marker 不可落盤 ⇒
 *     重啟後舊授權可能復活（雙重失敗殘留，呼叫端收到例外必須重試）。
 * (iii) Store 無內部互斥（見上方序列化契約）；同行程記憶體 pending 旗標不被他實例
 *     heal 解除，僅 save() 可清（OR 語義）。
 * (iv) 跨 Store 實例共享 marker 檔競態：一方 heal 的 clearPending 可刪掉另一方 clear()
 *     剛落盤的 marker（該方記憶體旗標仍置位，fail-closed；但跨重啟 marker 保障削弱）。
 * (v) ReplaceFileCorruptionHandler 損毀 ⇒ journal 一併消失 ⇒ 表現為「從未授權」（fail-closed）；
 *     save() 於 edit 之後、clearPending() 之前進程死亡 ⇒ 殘留 marker 讓 load() 即使授權已落盤亦回 null
 *     （fail-closed、可用性損失，非復活路徑；與 (i) 同族，併 #136-B3 處理）。
 * (vi) heal/wipe 失敗僅 logcat 一行，無 audit 計數。
 * (vii) 本檔測試以單執行緒注入 seam 釘住順序語義；未證明真並行 interleaving 的行為；
 *     並行 save() vs load()-heal 的完整化併 #136-B3 generation fence。
 */
class PrivilegeBridgeStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val revokeMarker: PendingRevokeMarker = PendingRevokeMarker(null),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Context 便利構造子。
     *
     * marker 放置：`noBackupFilesDir` 可用時落該目錄（天然不備份）；
     * 不可用時（direct-boot 未解鎖／儲存不可用等）[PendingRevokeMarker]
     * 以 `null` 檔案參數構造＝in-memory-only，不落盤——不落備份域，
     * 跨重啟語義由撤權 journal 承接（見類別 KDoc「已知限制」(ii)）。
     */
    constructor(context: Context) : this(
        dataStore = (context.applicationContext ?: context).privilegeBridgeDataStore,
        ioDispatcher = Dispatchers.IO,
        revokeMarker = PendingRevokeMarker(
            // noBackupFilesDir 不可用時不得退回 filesDir（備份域內）：
            // 傳 null 走 in-memory-only 模式，重啟語義由 journal 承接。
            (context.applicationContext ?: context).noBackupFilesDir?.let { dir ->
                File(dir, PRIVILEGE_PENDING_REVOKE_FILE_NAME)
            },
        ),
    )

    /**
     * 落庫授權。單一 edit 內寫入 grant 鍵並移除撤權 journal
     * （新授權取代撤權日誌；journal 殘留將使下次 [load] 誤擦除新授權），
     * 隨後清 pending marker。
     */
    suspend fun save(grant: PrivilegeGrant) {
        require(grant.providerId.isNotBlank() && grant.providerId.none { it.isISOControl() }) { "PROVIDER_ID_INVALID" }
        require(grant.configRevision > 0L) { "CONFIG_REVISION_INVALID" }
        require(grant.grantedAtMs >= 0L) { "AT_MS_NEGATIVE" }
        require(grant.expiresAtMs == null || grant.expiresAtMs >= grant.grantedAtMs) { "EXPIRES_AT_INVALID" }
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[PRIVILEGE_GRANT_PROVIDER_ID] = grant.providerId
                prefs[PRIVILEGE_GRANT_CONFIG_REVISION] = grant.configRevision
                prefs[PRIVILEGE_GRANT_AT_MS] = grant.grantedAtMs
                if (grant.expiresAtMs != null) {
                    prefs[PRIVILEGE_GRANT_EXPIRES_AT_MS] = grant.expiresAtMs
                } else {
                    prefs.remove(PRIVILEGE_GRANT_EXPIRES_AT_MS)
                }
                // 新授權取代撤權日誌：journal 殘留將使下次 load() 誤擦除新授權。
                prefs.remove(PRIVILEGE_REVOKE_JOURNAL)
            }
            revokeMarker.clearPending()
        }
    }

    suspend fun load(): PrivilegeGrant? = withContext(ioDispatcher) {
        if (revokeMarker.isPending()) {
            // 註：marker 分支 heal 刻意維持「無條件擦除」授權鍵＋journal——
            // marker 位於 DataStore 之外，edit 轉換內無法讀 marker 來做條件式
            // heal；且「先擦除後清 marker」與「並發 save 落 grant」之間仍存在
            // 預存 TOCTOU（輸贏取決於 edit 與 save 的交錯順序）。該殘留風險
            // 為預存設計，見本類別 KDoc「已知限制」(i)，不於此層消除。
            // 與 journal 分支不同，此分支不 fall-through：marker pending 即
            // 撤銷意圖已確立，一律回 null。
            try {
                dataStore.edit { prefs ->
                    prefs.remove(PRIVILEGE_GRANT_PROVIDER_ID)
                    prefs.remove(PRIVILEGE_GRANT_CONFIG_REVISION)
                    prefs.remove(PRIVILEGE_GRANT_AT_MS)
                    prefs.remove(PRIVILEGE_GRANT_EXPIRES_AT_MS)
                    prefs.remove(PRIVILEGE_REVOKE_JOURNAL)
                }
                revokeMarker.clearPending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // self-healing best effort：失敗僅 logcat 一行，仍回 null（fail-closed）。
                safeLogW(TAG, "pending marker self-heal failed", e)
            }
            return@withContext null
        }
        try {
            var prefs = dataStore.data.first()
            // 撤權 journal 自癒（條件式 heal）：上次 clear() 在寫入 journal 後、
            // 擦除 grant 鍵前失敗（如進程被殺）。grant 鍵可能殘留，絕不復活。
            // 條件式 heal 防 TOCTOU：並發 save() 可能已清 journal 並寫入新 grant，
            // 此時 edit 內 journal==false → no-op，新 grant 必須存活。
            // wipe 發生時 keys 已空；no-op 發生時 parse 回 save() 剛寫的新 grant；
            // heal 失敗時 journal 殘留 → 下方 fall-through re-read 解析回 null。
            if (prefs[PRIVILEGE_REVOKE_JOURNAL] == true) {
                try {
                    dataStore.edit { p ->
                        if (p[PRIVILEGE_REVOKE_JOURNAL] == true) {
                            p.remove(PRIVILEGE_GRANT_PROVIDER_ID)
                            p.remove(PRIVILEGE_GRANT_CONFIG_REVISION)
                            p.remove(PRIVILEGE_GRANT_AT_MS)
                            p.remove(PRIVILEGE_GRANT_EXPIRES_AT_MS)
                            p.remove(PRIVILEGE_REVOKE_JOURNAL)
                        }
                        // else：並發 save() 已取代 journal 並寫入新 grant → no-op，新 grant 存活
                    }
                    revokeMarker.clearPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 自癒失敗不得放行：保留 journal，fall-through re-read 仍見
                    // journal → parse 回 null（journal 殘留，下次 load 重試自癒）。
                    safeLogW(TAG, "revoke journal self-heal failed", e)
                }
                // fall-through：以 re-read 的最新值判定（涵蓋 wipe 成功／no-op／
                // heal 失敗三態），不在 heal 分支直接回傳 null。
                prefs = dataStore.data.first()
            }
            parseGrant(prefs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 由 Preferences 快照解析授權；fail-closed（缺鍵／空白 providerId／
     * journal 殘留一律 null）。journal==true 表示上次 heal 未能擦除（如寫入
     * 持續失敗）——絕不復活。
     */
    private fun parseGrant(prefs: Preferences): PrivilegeGrant? {
        if (prefs[PRIVILEGE_REVOKE_JOURNAL] == true) return null
        val providerId = prefs[PRIVILEGE_GRANT_PROVIDER_ID] ?: return null
        if (providerId.isBlank()) return null
        return PrivilegeGrant(
            providerId = providerId,
            configRevision = prefs[PRIVILEGE_GRANT_CONFIG_REVISION] ?: return null,
            grantedAtMs = prefs[PRIVILEGE_GRANT_AT_MS] ?: return null,
            expiresAtMs = prefs[PRIVILEGE_GRANT_EXPIRES_AT_MS],
        )
    }

    /**
     * 撤權（完整撤權 journal）：
     * 1) 落 pending marker（記憶體先置位；落盤失敗僅 logcat 一行，不阻斷——
     *    journal 為持久保障）；
     * 2) 在同一 DataStore 寫入 [PRIVILEGE_REVOKE_JOURNAL]=true（原子提交，與 grant
     *    同 durability 假設）；寫入失敗時先盡力擦除四 grant 鍵（best-effort，
     *    不刪 journal）、logcat 一行後 rethrow 原始例外（fail-closed：記憶體
     *    pending 已置，同行程 load 必回 null；呼叫端應顯示「收回失敗請重試」）。
     *    盡力擦除僅縮小重啟復活窗口——wipe 仍可能失敗（與 journal 寫入失敗
     *    同根因高度相關），雙失敗時舊授權可能復活；呼叫端必須視 clear() 例外
     *    為「撤權未完成」並重試；
     * 3) 擦除四個 grant 鍵＋journal（同一 edit，條件式：journal==true 才擦，
     *    與 [load] heal 同款；失敗即 rethrow；journal 已在 → 重啟後 load() 自癒擦
     *    除；no-op 時保留新授權）；
     * 4) 清 pending marker。
     */
    suspend fun clear() {
        withContext(ioDispatcher) {
            val markerPersisted = revokeMarker.markPending()
            if (!markerPersisted) {
                safeLogW(TAG, "pending marker NOT persisted; revocation relies on journal")
            }
            try {
                dataStore.edit { prefs ->
                    prefs[PRIVILEGE_REVOKE_JOURNAL] = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // journal 未寫成：不刪 journal（本來就沒有），盡力擦除 grant 鍵——
                // 僅盡力縮小重啟復活窗口；wipe 仍可能失敗（與 journal 寫入失敗
                // 同根因高度相關），雙失敗時舊授權可能復活；呼叫端必須視 clear()
                // 例外為「撤權未完成」並重試。wipe 失敗另記一行 log。
                // 最後 rethrow 原始例外（不得遮蔽；CancellationException 不吞，直接上抛）。
                try {
                    dataStore.edit { p ->
                        p.remove(PRIVILEGE_GRANT_PROVIDER_ID)
                        p.remove(PRIVILEGE_GRANT_CONFIG_REVISION)
                        p.remove(PRIVILEGE_GRANT_AT_MS)
                        p.remove(PRIVILEGE_GRANT_EXPIRES_AT_MS)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e2: Exception) {
                    safeLogW(TAG, "grant keys wipe also failed", e2)
                }
                safeLogW(TAG, "revoke journal write failed; attempted grant keys wipe", e)
                throw e
            }
            // 條件式擦除（與 load() heal 同款條件）：save() 於同鍵覆寫並清 journal，
            // 條件化不會復活舊授權；並發 save 已清 journal 時本 edit 變 no-op →
            // 新授權不被 in-flight clear 擦除。
            dataStore.edit { p ->
                if (p[PRIVILEGE_REVOKE_JOURNAL] == true) {
                    p.remove(PRIVILEGE_GRANT_PROVIDER_ID)
                    p.remove(PRIVILEGE_GRANT_CONFIG_REVISION)
                    p.remove(PRIVILEGE_GRANT_AT_MS)
                    p.remove(PRIVILEGE_GRANT_EXPIRES_AT_MS)
                    p.remove(PRIVILEGE_REVOKE_JOURNAL)
                }
                // else：並發 save() 已取代 journal 並寫入新 grant → no-op，新 grant 存活
            }
            revokeMarker.clearPending()
        }
    }

    /**
     * 當前設定下的有效性：load＋policy 一次判定，讀不到即 Invalid。
     *
     * #136-B3 blocking 包裝契約（runner 非 suspend，故產品接線須以 blocking
     * 包裝供給 `bridgeGranted`／grant 門；本 Store 側只消費新鮮值，不自建快取）：
     * - 每次呼叫必須新鮮供給：grant 側以 [load] 單發射讀 DataStore；
     * - 當前 endpoint 身分＋revision 必須取自
     *   [dev.librepocket.agent.ui.setup.EndpointStore.snapshotDeleteStateStrict]
     *   （或 `snapshotWithRevisionStrict()`）同一 emission
     *   （禁止先讀 provider 再讀 revision 的兩讀序列，併發 endpoint 切換
     *   可落在兩讀之間造成撕裂混代）；
     * - 永不快取結果：撕裂混代經 [PrivilegeGrantPolicy.check] 仍為 Invalid，
     *   禁止在混代時放行或重試放行。
     */
    suspend fun check(
        currentProviderId: String?,
        currentRevision: Long?,
        nowMs: Long? = null,
    ): PrivilegeGrantCheck {
        val grant = load()
        val effectiveNowMs = nowMs ?: clock()
        return PrivilegeGrantPolicy.check(grant, currentProviderId, currentRevision, effectiveNowMs)
    }
}
