package dev.librepocket.privilege.github

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.librepocket.shell.PendingRevokeMarker
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * P2-A 可見性：本擴展僅 [ShizukuGrantStore] 內部使用，改為 private，
 * 外部不得直取 DataStore（經 Store API 讀寫，fail-closed 語義集中）。
 */
private val Context.shizukuGrantDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "librepocket_shizuku_grant",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

const val SHIZUKU_PENDING_REVOKE_FILE_NAME = "librepocket_shizuku_revoke.pending"

internal val SHIZUKU_GRANT_PROVIDER_ID = stringPreferencesKey("shizuku_grant_provider_id_v1")
internal val SHIZUKU_GRANT_CONFIG_REVISION = longPreferencesKey("shizuku_grant_config_revision_v1")
internal val SHIZUKU_GRANT_AT_MS = longPreferencesKey("shizuku_grant_at_ms_v1")
internal val SHIZUKU_GRANT_SERVICE_VERSION = intPreferencesKey("shizuku_grant_service_version_v1")
internal val SHIZUKU_REVOKE_JOURNAL = booleanPreferencesKey("shizuku_revoke_journal_v1")

private const val TAG = "ShizukuGrantStore"

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
 * Shizuku 授權持久化（#136-B1，BACKLOG D09，Issue #136-B1，僅 github 風味）。
 *
 * - 授權結果綁定 endpoint 身分（providerId）＋設定版本（configRevision，
 *   見 [dev.librepocket.agent.ui.setup.EndpointStore]）：endpoint 切換或
 *   任何設定寫入推進 revision 後，舊授權即失效（fail-closed，需重新授權）；
 * - 撤銷（[ShizukuGrantStore.clear]/一鍵收回）即失效：讀不到即無授權，
 *   讀取失敗/格式損毀同樣折為無授權，絕不靜默放行；
 * - 版本相容歸狀態門（[ShizukuStatus]）判定：[ShizukuGrant.serviceVersion]
 *   僅留存稽核，不參與有效性判定，授權只綁 endpoint 身分＋設定版本；
 * - 純策略（[ShizukuGrantPolicy]）零 Android 依賴，JVM 可測；
 *   DataStore 薄封裝（[ShizukuGrantStore]）只做存取，判定全委派策略。
 *
 * R1-B 接線說明（Issue #136 R1-B，owner P1-3＋PocketGuard WARN grant）：
 * Store 隔離測試證 policy（見 `ShizukuGrantTest`：綁定 endpoint/revision、
 * 撤銷即失效、fail-closed）；runner 強制由 runner 測試證
 * （見 runner grant 測試：`grantCheck=Invalid` 即 `Denied(NOT_WHITELISTED)` 且
 * 零 spawn，`grantCheck` 抛異常同樣 fail-closed 零 spawn，跨 call 翻轉同實例即拒）；
 * 全鏈路（endpoint 切換/revision 推進/grant clear→已建 runner 拒絕→零 spawn）
 * 見 runner grant 測試，不在本檔重證。
 *
 * S1 fail-closed（owner P1-1 security）：兩 runner 預設不再放行；
 * [dev.librepocket.privilege.github.RootSuRunner]／[dev.librepocket.privilege.github.ShizukuShellRunner]
 * 的 `grantCheck` 無預設值（mandatory dependency），缺接線編譯失敗；
 * #136-B3 產品供給責任：`{ store.check(freshProviderId, freshRevision) }`（每次新鮮求值）。
 * [dev.librepocket.shell.PrivilegeBridgeState.revoke] 與本 Store 的統一路徑為
 * #136-B3 職責（本檔不跨域調 bridge 狀態，避免 main/github 源集耦合）。
 */
data class ShizukuGrant(
    val providerId: String,
    val configRevision: Long,
    val grantedAtMs: Long,
    val serviceVersion: Int,
)

/** 授權有效性判定（typed，fail-closed）。 */
sealed interface GrantCheck {
    data object Valid : GrantCheck
    data class Invalid(val reason: String) : GrantCheck
}

object ShizukuGrantPolicy {
    const val REASON_NEVER_GRANTED = "SHIZUKU_GRANT_NEVER_GRANTED"
    const val REASON_MALFORMED = "SHIZUKU_GRANT_MALFORMED"
    const val REASON_ENDPOINT_CHANGED = "SHIZUKU_GRANT_ENDPOINT_CHANGED"
    const val REASON_REVISION_STALE = "SHIZUKU_GRANT_REVISION_STALE"
    const val REASON_CONFIG_UNREADABLE = "SHIZUKU_GRANT_CONFIG_UNREADABLE"

    fun grant(
        providerId: String,
        configRevision: Long,
        serviceVersion: Int,
        atMs: Long = System.currentTimeMillis(),
    ): ShizukuGrant {
        require(providerId.isNotBlank() && providerId.none { it.isISOControl() }) { "PROVIDER_ID_INVALID" }
        require(configRevision > 0L) { "CONFIG_REVISION_INVALID" }
        require(atMs >= 0L) { "AT_MS_NEGATIVE" }
        return ShizukuGrant(
            providerId = providerId,
            configRevision = configRevision,
            grantedAtMs = atMs,
            serviceVersion = serviceVersion,
        )
    }

    /**
     * @param grant 已持久化授權（null＝從未授權或已被撤銷）。
     * @param currentProviderId 當前 endpoint 身分（null/blank＝讀不到當前設定，
     * fail-closed 為無效，禁止沿用舊授權）。
     * @param currentRevision 當前設定版本（null＝讀不到版本，同樣 fail-closed）。
     */
    fun check(
        grant: ShizukuGrant?,
        currentProviderId: String?,
        currentRevision: Long?,
    ): GrantCheck {
        if (grant == null) return GrantCheck.Invalid(REASON_NEVER_GRANTED)
        if (grant.providerId.isBlank() ||
            grant.providerId.any { it.isISOControl() } ||
            grant.configRevision <= 0L ||
            grant.grantedAtMs < 0L
        ) {
            return GrantCheck.Invalid(REASON_MALFORMED)
        }
        if (currentProviderId.isNullOrBlank() || currentRevision == null) {
            return GrantCheck.Invalid(REASON_CONFIG_UNREADABLE)
        }
        if (grant.providerId != currentProviderId) {
            return GrantCheck.Invalid(REASON_ENDPOINT_CHANGED)
        }
        if (grant.configRevision != currentRevision) {
            return GrantCheck.Invalid(REASON_REVISION_STALE)
        }
        return GrantCheck.Valid
    }
}

/**
 * 授權 DataStore 持久化（github-only）。
 *
 * - **B3 硬契約**：B3 callback 落庫（本類別 [save]）前必須以新鮮讀確認撤權未發生（marker pending／journal==true／epoch 不符 → 不得 save()，直接 fail-closed 返回）；[save] 須與 `FlavorPrivilegeScope.withdraw()`／本類別 [clear] 序列化（跨實例不可）；禁止僅憑 `grantResult==PERMISSION_GRANTED` 落庫——詳見 THREAT_MODEL「#136-B1 提權橋」第 4 項 Gate，fail-closed。
 * - 讀寫皆 fail-closed：讀取異常/缺鍵/空白 providerId 一律回 null
 *   （呼叫方視為未授權）；寫入校驗失敗抛給呼叫方（呼叫方不得吞錯放行）；
 * - 撤銷＝[clear]：marker＋journal＋（擦除授權鍵＋journal）的有序序列
 *   （marker → journal → 擦除）。第二次 edit 失敗時授權鍵可能殘留，但
 *   journal（與 marker）為持久兜底——[load] 見 journal 即自癒擦除
 *   （雙重失敗殘留見「已知限制」(ii)）；擦除失敗僅 logcat 一行，絕不靜默放行。
 * - 序列化契約：本 Store 無內部互斥，save/clear/load heal 跨 edit 無全序；
 *   #136-B3 接線前不得並發 save/clear（直接呼叫 Store 的呼叫端同此契約），
 *   否則 fresh grant 可能被 in-flight clear 擦除（fail-closed 方向，非越權）。
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
class ShizukuGrantStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val revokeMarker: PendingRevokeMarker = PendingRevokeMarker(null),
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
        dataStore = (context.applicationContext ?: context).shizukuGrantDataStore,
        ioDispatcher = Dispatchers.IO,
        revokeMarker = PendingRevokeMarker(
            // noBackupFilesDir 不可用時不得退回 filesDir（備份域內）：
            // 傳 null 走 in-memory-only 模式，重啟語義由 journal 承接。
            (context.applicationContext ?: context).noBackupFilesDir?.let { dir ->
                File(dir, SHIZUKU_PENDING_REVOKE_FILE_NAME)
            },
        ),
    )

    /**
     * 落庫授權。單一 edit 內寫入 grant 鍵並移除撤權 journal
     * （新授權取代撤權日誌；journal 殘留將使下次 [load] 誤擦除新授權），
     * 隨後清 pending marker。
     */
    suspend fun save(grant: ShizukuGrant) {
        require(grant.providerId.isNotBlank() && grant.providerId.none { it.isISOControl() }) { "PROVIDER_ID_INVALID" }
        require(grant.configRevision > 0L) { "CONFIG_REVISION_INVALID" }
        require(grant.grantedAtMs >= 0L) { "AT_MS_NEGATIVE" }
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[SHIZUKU_GRANT_PROVIDER_ID] = grant.providerId
                prefs[SHIZUKU_GRANT_CONFIG_REVISION] = grant.configRevision
                prefs[SHIZUKU_GRANT_AT_MS] = grant.grantedAtMs
                prefs[SHIZUKU_GRANT_SERVICE_VERSION] = grant.serviceVersion
                // 新授權取代撤權日誌：journal 殘留將使下次 load() 誤擦除新授權。
                prefs.remove(SHIZUKU_REVOKE_JOURNAL)
            }
            revokeMarker.clearPending()
        }
    }

    suspend fun load(): ShizukuGrant? = withContext(ioDispatcher) {
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
                    prefs.remove(SHIZUKU_GRANT_PROVIDER_ID)
                    prefs.remove(SHIZUKU_GRANT_CONFIG_REVISION)
                    prefs.remove(SHIZUKU_GRANT_AT_MS)
                    prefs.remove(SHIZUKU_GRANT_SERVICE_VERSION)
                    prefs.remove(SHIZUKU_REVOKE_JOURNAL)
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
            if (prefs[SHIZUKU_REVOKE_JOURNAL] == true) {
                try {
                    dataStore.edit { p ->
                        if (p[SHIZUKU_REVOKE_JOURNAL] == true) {
                            p.remove(SHIZUKU_GRANT_PROVIDER_ID)
                            p.remove(SHIZUKU_GRANT_CONFIG_REVISION)
                            p.remove(SHIZUKU_GRANT_AT_MS)
                            p.remove(SHIZUKU_GRANT_SERVICE_VERSION)
                            p.remove(SHIZUKU_REVOKE_JOURNAL)
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
    private fun parseGrant(prefs: Preferences): ShizukuGrant? {
        if (prefs[SHIZUKU_REVOKE_JOURNAL] == true) return null
        val providerId = prefs[SHIZUKU_GRANT_PROVIDER_ID] ?: return null
        if (providerId.isBlank()) return null
        return ShizukuGrant(
            providerId = providerId,
            configRevision = prefs[SHIZUKU_GRANT_CONFIG_REVISION] ?: return null,
            grantedAtMs = prefs[SHIZUKU_GRANT_AT_MS] ?: return null,
            serviceVersion = prefs[SHIZUKU_GRANT_SERVICE_VERSION] ?: return null,
        )
    }

    /**
     * 撤權（完整撤權 journal）：
     * 1) 落 pending marker（記憶體先置位；落盤失敗僅 logcat 一行，不阻斷——
     *    journal 為持久保障）；
     * 2) 在同一 DataStore 寫入 [SHIZUKU_REVOKE_JOURNAL]=true（原子提交，與 grant
     *    同 durability 假設）；寫入失敗時先盡力擦除授權鍵（best-effort，
     *    不刪 journal）、logcat 一行後 rethrow 原始例外（fail-closed：記憶體
     *    pending 已置，同行程 load 必回 null；呼叫端應顯示「收回失敗請重試」）。
     *    盡力擦除僅縮小重啟復活窗口——wipe 仍可能失敗（與 journal 寫入失敗
     *    同根因高度相關），雙失敗時舊授權可能復活；呼叫端必須視 clear() 例外
     *    為「撤權未完成」並重試；
     * 3) 擦除四個授權鍵＋journal（同一 edit，條件式：journal==true 才擦，
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
                    prefs[SHIZUKU_REVOKE_JOURNAL] = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // journal 未寫成：不刪 journal（本來就沒有），盡力擦除授權鍵——
                // 僅盡力縮小重啟復活窗口；wipe 仍可能失敗（與 journal 寫入失敗
                // 同根因高度相關），雙失敗時舊授權可能復活；呼叫端必須視 clear()
                // 例外為「撤權未完成」並重試。wipe 失敗另記一行 log。
                // 最後 rethrow 原始例外（不得遮蔽；CancellationException 不吞，直接上抛）。
                try {
                    dataStore.edit { p ->
                        p.remove(SHIZUKU_GRANT_PROVIDER_ID)
                        p.remove(SHIZUKU_GRANT_CONFIG_REVISION)
                        p.remove(SHIZUKU_GRANT_AT_MS)
                        p.remove(SHIZUKU_GRANT_SERVICE_VERSION)
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
                if (p[SHIZUKU_REVOKE_JOURNAL] == true) {
                    p.remove(SHIZUKU_GRANT_PROVIDER_ID)
                    p.remove(SHIZUKU_GRANT_CONFIG_REVISION)
                    p.remove(SHIZUKU_GRANT_AT_MS)
                    p.remove(SHIZUKU_GRANT_SERVICE_VERSION)
                    p.remove(SHIZUKU_REVOKE_JOURNAL)
                }
                // else：並發 save() 已取代 journal 並寫入新 grant → no-op，新 grant 存活
            }
            revokeMarker.clearPending()
        }
    }

    /** 當前設定下的有效性：load＋policy 一次判定，讀不到即 Invalid。
     *
     * #136-B3 blocking 包裝契約（Phase3 P2，防懸空／撕裂讀；兩 runner 的 `grantCheck`
     * KDoc 引用此處為準，runner 側只消費 supplier、不自建快取）：
     * - runner 非 suspend，故 #136-B3 須以 blocking 包裝供給 `grantCheck`
     *  （例 `runBlocking(ioDispatcher) { store.check(providerId, revision) }`
     *   或等價橋接）；包裝必須在 IO dispatcher 執行，不得在呼叫執行緒直讀；
     * - 每次呼叫必須新鮮供給：grant 側以 [load] 單發射讀 DataStore
     *  （單 `data.first()` emission 解析 providerId＋revision＋atMs＋
     *   serviceVersion 四鍵，同一快照內判定，禁止分鍵多次 collect）；
     * - 當前 endpoint 身分＋revision 必須取自
     *   `EndpointStore.snapshotWithRevisionStrict()` 同一 emission
     *  （單發射 providerId＋revision，禁止「先讀 provider 再讀 revision」
     *   的兩讀序列——併發 endpoint 切換可落在兩讀之間造成撕裂：舊 provider＋
     *   新 revision 混代）；
     * - 永不快取結果：每次 `grantCheck()` 呼叫重走上述新鮮讀；快取即把撤銷窗口
     *   延長到快取 TTL，runner 的跨 call 翻轉即拒與 spawn 前重求值將失效；
     * - 撕裂讀禁用＋混代 fail-closed：撕裂的混代組合經 [ShizukuGrantPolicy.check]
     *   仍為 Invalid（provider 不等或 revision 不等即拒，見撕裂契約測試），
     *   禁止在混代時放行或重試放行。
     *
     * P3-1 現狀 pin（行為不變）：[ShizukuGrant.serviceVersion] 與 `grantedAtMs`
     * 僅留存稽核，不參與有效性判定（見 `ShizukuGrantTest` 的
     * serviceVersionChangeKeepsValid／grantedAtMsDoesNotAffectCheck 與
     * `ElevatedRunnerPhase3Test` 的 P3-1 pin）；無 TTL（永不過期）。
     * #136-B3 在另行綁定 serviceVersion／TTL 前不得假設本層會過期舊授權——
     * 過期語義歸 #136-B3（需改 policy＋同步更新 pin 測試，此處僅文件化，見 TODO）。
     *
     * TODO(#136-B3): 若需綁定 serviceVersion（如 Shizuku 升級即重授權）或 TTL
     * （授權時效），在此 policy 新增判定分支＋原因碼，並更新兩 runner 的
     * grant 門測試；現階段刻意不做（P3-1 文件化不改行為）。
     */
    suspend fun check(
        currentProviderId: String?,
        currentRevision: Long?,
    ): GrantCheck = ShizukuGrantPolicy.check(load(), currentProviderId, currentRevision)
}
