package dev.librepocket.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.UUID

/**
 * S2 系統 TTS 朗讀器（主源集，系統優先，零新權限）。
 *
 * - 引擎：`android.speech.tts.TextToSpeech`（系統內建，不新增任何權限）；
 * - 佇列：首段 `QUEUE_FLUSH`，後續 `QUEUE_ADD`；
 * - 進度：掛載 [UtteranceProgressListener]（起/訖/錯三回調皆為 no-op 佔位，
 *   保證 utteranceId 可追蹤、不拋異常）；
 * - 分段：[VoiceTts.split] 4000 字一段；
 * - 語言：`zh-TW` → `zh-CN` → 列 `voices` 找 `zh`（見 [VoiceTts.resolveLocale]）。
 *
 * 初始化為非同步：[speak] 在引擎就緒前只暫存待播正文，就緒後一次排播。
 */
class VoiceSpeaker(
    appContext: Context,
    private val maxChunk: Int = VoiceTts.MAX_CHUNK_CHARS,
) {

    private val context: Context = appContext.applicationContext

    @Volatile
    private var ready = false

    @Volatile
    private var pending: String? = null

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            applyLanguage()
            ready = true
            pending?.let {
                pending = null
                speakInternal(it)
            }
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?, errorCode: Int) = Unit
        })
    }

    private fun applyLanguage() {
        val resolved = VoiceTts.resolveLocale(
            isSupported = { locale ->
                try {
                    val r = tts.isLanguageAvailable(locale)
                    r == TextToSpeech.LANG_AVAILABLE ||
                        r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                        r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                } catch (_: Exception) {
                    false
                }
            },
            voiceLangs = try {
                tts.voices?.mapNotNull { voice: Voice? ->
                    voice?.locale?.toLanguageTag()
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            },
        ) ?: return
        try {
            tts.setLanguage(resolved)
        } catch (_: Exception) {
        }
    }

    /** 朗讀正文（本地 TTS，不送雲；空字串直接忽略）。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pending = text
            return
        }
        speakInternal(text)
    }

    private fun speakInternal(text: String) {
        val chunks = try {
            VoiceTts.split(text, maxChunk)
        } catch (_: Exception) {
            return
        }
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle()
            try {
                tts.speak(chunk, mode, params, "${UUID.randomUUID()}")
            } catch (_: Exception) {
                return
            }
        }
    }

    /** 停播（保留引擎）。 */
    fun stop() {
        try {
            tts.stop()
        } catch (_: Exception) {
        }
    }

    /** 釋放引擎（擁有方在 DisposableEffect onDispose 呼叫）。 */
    fun shutdown() {
        try {
            tts.stop()
        } catch (_: Exception) {
        }
        try {
            tts.shutdown()
        } catch (_: Exception) {
        }
    }
}
