package dev.librepocket.privilege.github

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.coroutines.flow.map
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
 * - 讀寫皆 fail-closed：讀取異常/缺鍵/空白 providerId 一律回 null
 *   （呼叫方視為未授權）；寫入校驗失敗抛給呼叫方（呼叫方不得吞錯放行）；
 * - 撤銷＝[clear]：鍵全刪，後續 [load] 回 null，舊授權無殘留。
 */
class ShizukuGrantStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val revokeMarker: PendingRevokeMarker = PendingRevokeMarker(null),
) {
    constructor(context: Context) : this(
        dataStore = (context.applicationContext ?: context).shizukuGrantDataStore,
        ioDispatcher = Dispatchers.IO,
        revokeMarker = PendingRevokeMarker(
            File(
                (context.applicationContext ?: context).noBackupFilesDir
                    ?: (context.applicationContext ?: context).filesDir,
                SHIZUKU_PENDING_REVOKE_FILE_NAME,
            ),
        ),
    )

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
            }
            revokeMarker.clearPending()
        }
    }

    suspend fun load(): ShizukuGrant? = withContext(ioDispatcher) {
        if (revokeMarker.isPending()) {
            try {
                dataStore.edit { prefs ->
                    prefs.remove(SHIZUKU_GRANT_PROVIDER_ID)
                    prefs.remove(SHIZUKU_GRANT_CONFIG_REVISION)
                    prefs.remove(SHIZUKU_GRANT_AT_MS)
                    prefs.remove(SHIZUKU_GRANT_SERVICE_VERSION)
                }
                revokeMarker.clearPending()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // self-healing best effort
            }
            return@withContext null
        }
        try {
            dataStore.data.map { prefs ->
                val providerId = prefs[SHIZUKU_GRANT_PROVIDER_ID] ?: return@map null
                if (providerId.isBlank()) return@map null
                ShizukuGrant(
                    providerId = providerId,
                    configRevision = prefs[SHIZUKU_GRANT_CONFIG_REVISION] ?: return@map null,
                    grantedAtMs = prefs[SHIZUKU_GRANT_AT_MS] ?: return@map null,
                    serviceVersion = prefs[SHIZUKU_GRANT_SERVICE_VERSION] ?: return@map null,
                )
            }.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clear() {
        withContext(ioDispatcher) {
            revokeMarker.markPending()
            dataStore.edit { prefs ->
                prefs.remove(SHIZUKU_GRANT_PROVIDER_ID)
                prefs.remove(SHIZUKU_GRANT_CONFIG_REVISION)
                prefs.remove(SHIZUKU_GRANT_AT_MS)
                prefs.remove(SHIZUKU_GRANT_SERVICE_VERSION)
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
