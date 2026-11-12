package dev.librepocket.web

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.librepocket.tool.WebSearchLocal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.IOException

private val Context.webDataStore by preferencesDataStore(
    name = "librepocket_web",
)

internal val WEB_MASTER_ENABLED_KEY = booleanPreferencesKey(WebSearchLocal.SWITCH)
internal val WEB_DDGS_OPTIN_KEY = booleanPreferencesKey(WebSearchSettings.DDGS_SWITCH)
internal val WEB_CONSENT_ACK_KEY = booleanPreferencesKey(WebSearchSettings.CONSENT_ACK_KEY)
internal val WEB_SEARXNG_URL_KEY = stringPreferencesKey(WebSearchSettings.SEARXNG_URL_KEY)

private const val TAG = "WebSwitchStore"

/**
 * D3 網路搜尋開關快照三態（web 包，JVM 可測）。
 *
 * - loading（[loaded]=false）：首發射前，門禁一律關閉（fail-closed）；
 * - loaded：四鍵恆在（master 預設關、ddgs 預設關、consent 預設 false、URL 預設空）；
 * - loadFailed：讀取拋 [IOException] → 全關 fail-closed（master/ddgs false、
 *   consent false、URL 空），其餘例外照拋。
 */
data class WebSearchSnapshot(
    val masterEnabled: Boolean,
    val ddgsOptIn: Boolean,
    val consentAck: Boolean,
    val searxngBaseUrl: String,
    val loaded: Boolean,
    val loadFailed: Boolean,
) {
    companion object {
        val LOADING = WebSearchSnapshot(
            masterEnabled = false,
            ddgsOptIn = false,
            consentAck = false,
            searxngBaseUrl = "",
            loaded = false,
            loadFailed = false,
        )
    }
}

/**
 * D3 網路搜尋開關單一真相源（DataStore 持久化）。
 *
 * - `websearch`（master，預設關）、`websearch_ddgs`（opt-in，預設關）、
 *   `websearch_consent_ack`（只問一次，預設 false）、
 *   `websearch_searxng_url`（空＝未設定 → `MISSING_URL`）。
 * - 形狀沿用 [dev.librepocket.voice.VoiceSwitchStore]：雙建構子、可注入
 *   dispatcher、讀取 fail-closed（僅 [IOException] 降級，其餘照拋，含 CE）。
 * - 同一底層檔案不可有兩個 active DataStore（見 VoiceSwitchStoreTest 註記）。
 */
class WebSwitchStore(
    internal val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.webDataStore)

    fun observeState(): Flow<WebSearchSnapshot> = dataStore.data
        .map { prefs ->
            WebSearchSnapshot(
                masterEnabled = prefs[WEB_MASTER_ENABLED_KEY] ?: WebSearchLocal.SWITCH_DEFAULT,
                ddgsOptIn = prefs[WEB_DDGS_OPTIN_KEY] ?: WebSearchSettings.DDGS_SWITCH_DEFAULT,
                consentAck = prefs[WEB_CONSENT_ACK_KEY] ?: false,
                searxngBaseUrl = prefs[WEB_SEARXNG_URL_KEY] ?: "",
                loaded = true,
                loadFailed = false,
            )
        }
        .catch { e ->
            if (e is IOException) {
                runCatching {
                    Log.w(TAG, "web switches read failed, fail-closed", e)
                }
                emit(
                    WebSearchSnapshot(
                        masterEnabled = false,
                        ddgsOptIn = false,
                        consentAck = false,
                        searxngBaseUrl = "",
                        loaded = true,
                        loadFailed = true,
                    ),
                )
            } else {
                throw e
            }
        }
        .onStart { emit(WebSearchSnapshot.LOADING) }

    suspend fun setMasterEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs -> prefs[WEB_MASTER_ENABLED_KEY] = enabled }
        }
    }

    suspend fun setDdgsOptIn(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs -> prefs[WEB_DDGS_OPTIN_KEY] = enabled }
        }
    }

    suspend fun setSearxngBaseUrl(url: String) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs -> prefs[WEB_SEARXNG_URL_KEY] = url }
        }
    }

    /** 首次開啟 consent 確認（只問一次）。 */
    suspend fun acknowledgeConsent() {
        withContext(ioDispatcher) {
            dataStore.edit { prefs -> prefs[WEB_CONSENT_ACK_KEY] = true }
        }
    }
}
