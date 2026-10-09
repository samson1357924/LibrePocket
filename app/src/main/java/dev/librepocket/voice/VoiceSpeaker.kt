package dev.librepocket.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.UUID

/**
 * S2 Android 系統 TTS 朗讀器。
 *
 * - 引擎：Android [TextToSpeech] API；實際語音引擎由裝置安裝／設定提供，其離線能力與資料處理方式各異；
 * - 佇列：首段 `QUEUE_FLUSH`，後續 `QUEUE_ADD`；
 * - 進度：掛載 [UtteranceProgressListener]（各回呼為 no-op）；
 * - 分段：[VoiceTts.split] 每段最多 4000 字；
 * - 語言：`zh-TW` → `zh-CN` → 第一個列出的 `zh` voice（見 [VoiceTts.resolveLocale]）。
 *
 * 初始化為非同步：[speak] 在成功回呼前只保留最新待播文字。初始化失敗時清除待播文字；本類別不提供重新初始化引擎的 API。
 */
class VoiceSpeaker(
    appContext: Context,
    private val maxChunk: Int = VoiceTts.MAX_CHUNK_CHARS,
) {

    private val context: Context = appContext.applicationContext
    private val lifecycleLock = Any()

    /** Engine is assigned only after the constructor returns (which may invoke its callback). */
    private var tts: TextToSpeech? = null
    private var deferredInitStatus: Int? = null
    private var initCallbackHandled = false
    private var initializationFailed = false
    private var ready = false
    private var terminal = false
    private var speechEpoch = 0L
    private var pending: PendingSpeech? = null

    private data class PendingSpeech(val text: String, val epoch: Long)

    init {
        val engine = TextToSpeech(context) { status ->
            synchronized(lifecycleLock) {
                val initializedEngine = tts
                if (initializedEngine == null) {
                    // TextToSpeech is allowed to call back from its constructor. Defer that result
                    // until the engine reference and listener setup are complete.
                    if (!initCallbackHandled && deferredInitStatus == null) {
                        deferredInitStatus = status
                    }
                } else {
                    handleInitLocked(initializedEngine, status)
                }
            }
        }
        synchronized(lifecycleLock) {
            try {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?, errorCode: Int) = Unit
                })
            } catch (_: Exception) {
            }
            tts = engine
            val callbackStatus = deferredInitStatus
            deferredInitStatus = null
            if (callbackStatus != null) handleInitLocked(engine, callbackStatus)
        }
    }

    /** 朗讀正文，交由 Android 系統 TTS；離線能力及資料處理取決於已安裝的語音引擎。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        synchronized(lifecycleLock) {
            if (terminal || initializationFailed) return
            val engine = tts ?: return
            if (!ready) {
                pending = PendingSpeech(text, speechEpoch)
                return
            }
            speakInternalLocked(engine, text)
        }
    }

    /** 停播並清除尚未送入引擎的文字；保留已初始化的引擎，之後仍可 [speak]。 */
    fun stop() {
        synchronized(lifecycleLock) {
            if (terminal) return
            speechEpoch++
            pending = null
            try {
                tts?.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** 終止並釋放引擎；重複呼叫無副作用。擁有方可在 DisposableEffect onDispose 呼叫。 */
    fun shutdown() {
        synchronized(lifecycleLock) {
            if (terminal) return
            // Fence callbacks and future speak calls before invoking any engine methods.
            terminal = true
            ready = false
            speechEpoch++
            pending = null
            val engine = tts ?: return
            try {
                engine.stop()
            } catch (_: Exception) {
            }
            try {
                engine.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleInitLocked(engine: TextToSpeech, status: Int) {
        if (terminal || initCallbackHandled) return
        initCallbackHandled = true
        if (status != TextToSpeech.SUCCESS) {
            initializationFailed = true
            pending = null
            speechEpoch++
            return
        }

        applyLanguage(engine)
        ready = true
        val queued = pending
        pending = null
        if (queued != null && queued.epoch == speechEpoch) {
            speakInternalLocked(engine, queued.text)
        }
    }

    private fun applyLanguage(engine: TextToSpeech) {
        val resolved = VoiceTts.resolveLocale(
            isSupported = { locale ->
                try {
                    val result = engine.isLanguageAvailable(locale)
                    result == TextToSpeech.LANG_AVAILABLE ||
                        result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                        result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                } catch (_: Exception) {
                    false
                }
            },
            voiceLangs = try {
                engine.voices?.mapNotNull { voice: Voice? ->
                    voice?.locale?.toLanguageTag()
                } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            },
        ) ?: return
        try {
            engine.setLanguage(resolved)
        } catch (_: Exception) {
        }
    }

    /** Caller holds [lifecycleLock] for the complete chunk sequence so stop cannot split it. */
    private fun speakInternalLocked(engine: TextToSpeech, text: String) {
        val chunks = try {
            VoiceTts.split(text, maxChunk)
        } catch (_: Exception) {
            return
        }
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle()
            try {
                engine.speak(chunk, mode, params, "${UUID.randomUUID()}")
            } catch (_: Exception) {
                return
            }
        }
    }
}
