package dev.librepocket.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val Context.voiceDataStore by preferencesDataStore(
    name = "librepocket_voice",
)

internal val VOICE_INPUT_ENABLED_KEY = booleanPreferencesKey(VoiceTools.SWITCH_INPUT)
internal val VOICE_OUTPUT_ENABLED_KEY = booleanPreferencesKey(VoiceTools.SWITCH_OUTPUT)
internal val VOICE_TTS_DISCLOSURE_ACK_KEY = booleanPreferencesKey("voice_tts_disclosure_ack")

/**
 * 語音開關單一真相源（#3，Stage3 完整接線）。
 *
 * - DataStore 持久化 `voice_input` / `voice_output`（key 名與
 *   [VoiceTools.SWITCH_INPUT] / [VoiceTools.SWITCH_OUTPUT] 一致，缺鍵即預設開）；
 * - [observeSwitches] 回傳的 map 可直接餵給 `ChatScreen(userSwitches=...)` 與
 *   `ProjectionContext(userSwitches=...)`，UI 門禁與 tool 投影讀同一組值、
 *   預設一致（見 [VoiceTools.inputEnabled] / [VoiceTools.outputEnabled]）；
 * - 生產路徑 tool 投影目前並無其他 userSwitches 來源（policy store 存的是
 *   ruleset 而非開關；`TurnController` 尚未把工具掛上請求），故本 store 即
 *   未來投影接線時唯一的開關讀取點；
 * - 朗讀首次揭露確認狀態（只問一次）亦持久化於此（[observeTtsAcknowledged]）。
 *
 * 形狀沿用 [dev.librepocket.agent.ui.setup.EndpointStore] 慣例：雙建構子、
 * 可注入 [ioDispatcher]、讀取 fail-closed（讀錯回預設，不拋）。
 */
class VoiceSwitchStore(
    internal val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.voiceDataStore)

    /** 開關快照（兩鍵恆在，缺鍵填預設值；讀錯回全預設）。 */
    fun observeSwitches(): Flow<Map<String, Boolean>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            mapOf(
                VoiceTools.SWITCH_INPUT to (prefs[VOICE_INPUT_ENABLED_KEY] ?: VoiceTools.SWITCH_INPUT_DEFAULT),
                VoiceTools.SWITCH_OUTPUT to (prefs[VOICE_OUTPUT_ENABLED_KEY] ?: VoiceTools.SWITCH_OUTPUT_DEFAULT),
            )
        }

    /** 朗讀首次揭露是否已確認（只問一次；預設 false）。 */
    fun observeTtsAcknowledged(): Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[VOICE_TTS_DISCLOSURE_ACK_KEY] ?: false }

    suspend fun observeSwitchesSnapshot(): Map<String, Boolean> =
        try {
            withContext(ioDispatcher) { observeSwitches().first() }
        } catch (_: Exception) {
            mapOf(
                VoiceTools.SWITCH_INPUT to VoiceTools.SWITCH_INPUT_DEFAULT,
                VoiceTools.SWITCH_OUTPUT to VoiceTools.SWITCH_OUTPUT_DEFAULT,
            )
        }

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
