package dev.librepocket.voice

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.IOException

private val Context.voiceDataStore by preferencesDataStore(
    name = "librepocket_voice",
)

internal val VOICE_INPUT_ENABLED_KEY = booleanPreferencesKey(VoiceTools.SWITCH_INPUT)
internal val VOICE_OUTPUT_ENABLED_KEY = booleanPreferencesKey(VoiceTools.SWITCH_OUTPUT)
internal val VOICE_TTS_DISCLOSURE_ACK_KEY = booleanPreferencesKey("voice_tts_disclosure_ack")

// 語音持久化讀取失敗的可觀測性（仿 MainScreen 類名字串 TAG 的 Log.w 先例）。
private const val TAG = "VoiceSwitchStore"

/**
 * 語音開關快照三態（R4-2，voice 包，JVM 可測）。
 *
 * Map 形狀無法區分的三種情形在此顯式分開：
 * - loading（[loaded]=false）：首發射前／DataStore 尚未吐值，[switches] 為空，
 *   UI 門禁一律視為關閉（fail-closed），不經產品預設開；
 * - loaded（[loaded]=true，[loadFailed]=false）：[switches] 兩鍵恆在，
 *   無 key 照常填產品預設 true；
 * - loadFailed（[loadFailed]=true）：讀取拋預期 IO 例外，[switches] 為全關
 *   fail-closed 值，UI 門禁與 tool 投影皆視為關閉。
 */
data class VoiceSwitchSnapshot(
    val switches: Map<String, Boolean>,
    val loaded: Boolean,
    val loadFailed: Boolean,
) {
    companion object {
        /** 首幀初始值：尚未讀到真相源，門禁一律關閉。 */
        val LOADING = VoiceSwitchSnapshot(emptyMap(), loaded = false, loadFailed = false)
    }
}

/**
 * 語音開關單一真相源（#3，Stage3 完整接線）。
 *
 * - DataStore 持久化 `voice_input` / `voice_output`（key 名與
 *   [VoiceTools.SWITCH_INPUT] / [VoiceTools.SWITCH_OUTPUT] 一致，缺鍵即預設開）；
 * - [observeSwitches] 回傳 [VoiceSwitchSnapshot] 三態（loading／loaded／loadFailed）：
 *   UI 門禁經 `VoiceTools.inputEnabled(snapshot)`／`outputEnabled(snapshot)` 讀快照
 *   （`!loaded || loadFailed` 一律關閉），`ChatScreen(userSwitches=...)` 仍收 Map
 *   （呼叫方傳 fail-closed 後的 map，見 MainScreen），`ProjectionContext(userSwitches=...)`
 *   經 `VoiceTools.projectionSwitches` 同一快照轉換（loadFailed 視為關），三處讀同一真相源、
 *   預設一致（見 [VoiceTools.inputEnabled] / [VoiceTools.outputEnabled]）；
 * - 生產路徑 tool 投影目前並無其他 userSwitches 來源（policy store 存的是
 *   ruleset 而非開關；`TurnController` 尚未把工具掛上請求），故本 store 即
 *   未來投影接線時唯一的開關讀取點；
 * - 朗讀首次揭露確認狀態（只問一次）亦持久化於此（[observeTtsAcknowledged]）。
 *
 * 形狀沿用 [dev.librepocket.agent.ui.setup.EndpointStore] 慣例：雙建構子、
 * 可注入 [ioDispatcher]、讀取 fail-closed。讀取例外只對預期 IO 例外降級
 * （DataStore.read 失敗多為 [IOException]，降級為 loadFailed 快照並 Log.w；
 * 其餘例外——含 CancellationException——照常拋，避免把取消或程式錯誤靜默成預設值）。
 * DataStore 主建構子供測試注入；同一底層檔案不可有兩個 active DataStore（見 VoiceSwitchStoreTest
 * 註記），故同一檔案勿與 Context 建構子混用。
 */
class VoiceSwitchStore(
    internal val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.voiceDataStore)

    /** 開關快照三態（首發射為 loading；兩鍵恆在，缺鍵填預設值；讀錯為 loadFailed 全關）。 */
    fun observeSwitches(): Flow<VoiceSwitchSnapshot> = dataStore.data
        .map { prefs ->
            VoiceSwitchSnapshot(
                switches = mapOf(
                    VoiceTools.SWITCH_INPUT to (prefs[VOICE_INPUT_ENABLED_KEY] ?: VoiceTools.SWITCH_INPUT_DEFAULT),
                    VoiceTools.SWITCH_OUTPUT to (prefs[VOICE_OUTPUT_ENABLED_KEY] ?: VoiceTools.SWITCH_OUTPUT_DEFAULT),
                ),
                loaded = true,
                loadFailed = false,
            )
        }
        .catch { e ->
            // 舊 catch-all 回 emptyPreferences 即預設全開，是冷啟動雙開的另一半成因：
            // 讀錯無信號卻開門。現僅 IOException 降級（fail-closed 全關快照），其餘照拋。
            if (e is IOException) {
                // 仿 TurnController warnOnTimeContextDegraded 先例：runCatching 包 Log.w，
                // JVM 單測（無 mocked android.util.Log）保持綠燈。
                runCatching {
                    Log.w(TAG, "voice switches read failed, fail-closed", e)
                }
                emit(
                    VoiceSwitchSnapshot(
                        switches = mapOf(
                            VoiceTools.SWITCH_INPUT to false,
                            VoiceTools.SWITCH_OUTPUT to false,
                        ),
                        loaded = true,
                        loadFailed = true,
                    ),
                )
            } else {
                throw e
            }
        }
        .onStart { emit(VoiceSwitchSnapshot.LOADING) }

    /** 朗讀首次揭露是否已確認（只問一次；預設 false）。 */
    fun observeTtsAcknowledged(): Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[VOICE_TTS_DISCLOSURE_ACK_KEY] ?: false }

    suspend fun setInputEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs -> prefs[VOICE_INPUT_ENABLED_KEY] = enabled }
        }
    }

    suspend fun setOutputEnabled(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs -> prefs[VOICE_OUTPUT_ENABLED_KEY] = enabled }
        }
    }

    /** 朗讀內聯確認（只問一次）：確認後持久化，後續朗讀不再詢問。 */
    suspend fun acknowledgeTtsDisclosure() {
        withContext(ioDispatcher) {
            dataStore.edit { prefs -> prefs[VOICE_TTS_DISCLOSURE_ACK_KEY] = true }
        }
    }
}
