package dev.librepocket.shell

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.privilegeBridgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "librepocket_privilege_bridge",
)

internal val PRIVILEGE_GRANT_PROVIDER_ID = stringPreferencesKey("privilege_grant_provider_id_v1")
internal val PRIVILEGE_GRANT_CONFIG_REVISION = longPreferencesKey("privilege_grant_config_revision_v1")
internal val PRIVILEGE_GRANT_AT_MS = longPreferencesKey("privilege_grant_at_ms_v1")
internal val PRIVILEGE_GRANT_EXPIRES_AT_MS = longPreferencesKey("privilege_grant_expires_at_ms_v1")

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
 * - 本檔零 `rikka.shizuku` 引用：play／foss artifact gate（字串黑名單＋
 *   manifest pin）不受影響；執行接線仍歸 #136-B3
 *   （`PrivilegeActivation.ELEVATED_RUNNERS_READY` 維持 `false`，
 *   兩 runner 預設零 spawn）。
 *
 * 與 github 側 [dev.librepocket.privilege.github.ShizukuGrant] 的分工：
 * Shizuku 側授權（Shizuku Manager 內的手動允許）由該 Store 持有；
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
    const val REASON_EXPIRED = "PRIVILEGE_GRANT_EXPIRED"

    /**
     * 簽發授權。
     *
     * @param ttlMs 授權時效（毫秒，null＝永不過期；須非負，負值 fail-fast 抛錯，
     * 呼叫方不得以「已過期授權」試探判定路徑）。極大值溢出時飽和為
     * [Long.MAX_VALUE]（實效永不過期，見單測溢出 pin）。
     */
    fun grant(
        providerId: String,
        configRevision: Long,
        ttlMs: Long? = null,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrant {
        require(providerId.isNotBlank()) { "PROVIDER_ID_BLANK" }
        require(ttlMs == null || ttlMs >= 0) { "TTL_NEGATIVE" }
        val expiresAtMs = ttlMs?.let {
            try {
                Math.addExact(atMs, it)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
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
     * Wall-clock 殘留：回撥系統時鐘可延長 TTL（威脅模型接受：攻擊者＝機主，
     * 無遠端向量；如需嚴格單調請改用 elapsedRealtime，見 B3）。
     */
    fun check(
        grant: PrivilegeGrant?,
        currentProviderId: String?,
        currentRevision: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrantCheck {
        if (grant == null) return PrivilegeGrantCheck.Invalid(REASON_NEVER_GRANTED)
        if (grant.providerId.isBlank()) return PrivilegeGrantCheck.Invalid(REASON_MALFORMED)
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
 * - 撤銷＝[clear]：四鍵全刪，後續 [load] 回 null，舊授權無殘留。
 */
class PrivilegeBridgeStore(
    private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.privilegeBridgeDataStore)

    suspend fun save(grant: PrivilegeGrant) {
        require(grant.providerId.isNotBlank()) { "PROVIDER_ID_BLANK" }
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
            }
        }
    }

    suspend fun load(): PrivilegeGrant? = withContext(ioDispatcher) {
        try {
            dataStore.data.map { prefs ->
                val providerId = prefs[PRIVILEGE_GRANT_PROVIDER_ID] ?: return@map null
                if (providerId.isBlank()) return@map null
                PrivilegeGrant(
                    providerId = providerId,
                    configRevision = prefs[PRIVILEGE_GRANT_CONFIG_REVISION] ?: return@map null,
                    grantedAtMs = prefs[PRIVILEGE_GRANT_AT_MS] ?: return@map null,
                    expiresAtMs = prefs[PRIVILEGE_GRANT_EXPIRES_AT_MS],
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
                prefs.remove(PRIVILEGE_GRANT_PROVIDER_ID)
                prefs.remove(PRIVILEGE_GRANT_CONFIG_REVISION)
                prefs.remove(PRIVILEGE_GRANT_AT_MS)
                prefs.remove(PRIVILEGE_GRANT_EXPIRES_AT_MS)
            }
        }
    }

    /**
     * 當前設定下的有效性：load＋policy 一次判定，讀不到即 Invalid。
     *
     * #136-B3 blocking 包裝契約（runner 非 suspend，故產品接線須以 blocking
     * 包裝供給 `bridgeGranted`／grant 門；本 Store 側只消費新鮮值，不自建快取）：
     * - 每次呼叫必須新鮮供給：grant 側以 [load] 單發射讀 DataStore；
     * - 當前 endpoint 身分＋revision 必須取自
     *   `EndpointStore.snapshotWithRevisionStrict()` 同一 emission
     *   （禁止先讀 provider 再讀 revision 的兩讀序列，併發 endpoint 切換
     *   可落在兩讀之間造成撕裂混代）；
     * - 永不快取結果：撕裂混代經 [PrivilegeGrantPolicy.check] 仍為 Invalid，
     *   禁止在混代時放行或重試放行。
     */
    suspend fun check(
        currentProviderId: String?,
        currentRevision: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrantCheck = PrivilegeGrantPolicy.check(load(), currentProviderId, currentRevision, nowMs)
}
