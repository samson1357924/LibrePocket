package dev.librepocket.privilege.github

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
)

internal val SHIZUKU_GRANT_PROVIDER_ID = stringPreferencesKey("shizuku_grant_provider_id_v1")
internal val SHIZUKU_GRANT_CONFIG_REVISION = longPreferencesKey("shizuku_grant_config_revision_v1")
internal val SHIZUKU_GRANT_AT_MS = longPreferencesKey("shizuku_grant_at_ms_v1")
internal val SHIZUKU_GRANT_SERVICE_VERSION = intPreferencesKey("shizuku_grant_service_version_v1")

/**
 * Shizuku 授權持久化（B1，BACKLOG D09，Issue #136 B1，僅 github 風味）。
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
 * B3 產品供給責任：`{ store.check(freshProviderId, freshRevision) }`（每次新鮮求值）。
 * [dev.librepocket.shell.PrivilegeBridgeState.revoke] 與本 Store 的統一路徑為
 * B3 職責（本檔不跨域調 bridge 狀態，避免 main/github 源集耦合）。
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
        require(providerId.isNotBlank()) { "PROVIDER_ID_BLANK" }
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
        if (grant.providerId.isBlank()) return GrantCheck.Invalid(REASON_MALFORMED)
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
) {
    constructor(context: Context) : this(context.applicationContext.shizukuGrantDataStore)

    suspend fun save(grant: ShizukuGrant) {
        require(grant.providerId.isNotBlank()) { "PROVIDER_ID_BLANK" }
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[SHIZUKU_GRANT_PROVIDER_ID] = grant.providerId
                prefs[SHIZUKU_GRANT_CONFIG_REVISION] = grant.configRevision
                prefs[SHIZUKU_GRANT_AT_MS] = grant.grantedAtMs
                prefs[SHIZUKU_GRANT_SERVICE_VERSION] = grant.serviceVersion
            }
        }
    }

    suspend fun load(): ShizukuGrant? = withContext(ioDispatcher) {
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
            dataStore.edit { prefs ->
                prefs.remove(SHIZUKU_GRANT_PROVIDER_ID)
                prefs.remove(SHIZUKU_GRANT_CONFIG_REVISION)
                prefs.remove(SHIZUKU_GRANT_AT_MS)
                prefs.remove(SHIZUKU_GRANT_SERVICE_VERSION)
            }
        }
    }

    /** 當前設定下的有效性：load＋policy 一次判定，讀不到即 Invalid。 */
    suspend fun check(
        currentProviderId: String?,
        currentRevision: Long?,
    ): GrantCheck = ShizukuGrantPolicy.check(load(), currentProviderId, currentRevision)
}
