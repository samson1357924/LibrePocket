package dev.librepocket.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.librepocket.tool.DenyReason
import java.util.UUID

/**
 * S2 Android 系統 TTS 朗讀器。
 *
 * - 引擎：Android [TextToSpeech] API；實際語音引擎由裝置安裝／設定提供，其離線能力與資料處理方式各異；
 * - 佇列：首段 `QUEUE_FLUSH`，後續 `QUEUE_ADD`；
 * - 進度：掛載 [UtteranceProgressListener]（onStart/onDone 為 no-op；onError 轉送 [onError] 失敗通道）；
 * - 分段：[VoiceTts.split] 每段最多 4000 字；
 * - 語言：`zh-TW` → `zh-CN` → 第一個列出的 `zh` voice（見 [VoiceTts.resolveLocale]）。
 *
 * 初始化為非同步：[speak] 在成功回呼前只保留最新待播文字。初始化失敗時清除待播文字；本類別不提供重新初始化引擎的 API。
 *
 * 可觀察性（Stage2）：引擎可用性探測（[VoiceTts.hasEngine]，空引擎表視為初始化失敗，
 * fail-closed）與初始化／語言／朗讀／utterance 失敗一律經 [onError] 揭露，
 * 不再靜默吞錯。Listener 在鎖外呼叫；lifecycle 語義（epoch/pending/terminal
 * fence）與 Stage1 一致。
 */
class VoiceSpeaker(
    appContext: Context,
    private val maxChunk: Int = VoiceTts.MAX_CHUNK_CHARS,
    private val onError: (String) -> Unit = {},
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
            val initError: String? = synchronized(lifecycleLock) {
                val initializedEngine = tts
                if (initializedEngine == null) {
                    // TextToSpeech is allowed to call back from its constructor. Defer that result
                    // until the engine reference and listener setup are complete.
                    if (!initCallbackHandled && deferredInitStatus == null) {
                        deferredInitStatus = status
                    }
                    null
                } else {
                    handleInitLocked(initializedEngine, status)
                }
            }
            if (initError != null) emitError(initError)
        }
        val deferredError: String? = synchronized(lifecycleLock) {
            try {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        reportUtteranceError()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        reportUtteranceError()
                    }
                })
            } catch (_: Exception) {
            }
            tts = engine
            val callbackStatus = deferredInitStatus
            deferredInitStatus = null
            if (callbackStatus != null) handleInitLocked(engine, callbackStatus) else null
        }
        if (deferredError != null) emitError(deferredError)
    }

    /** 朗讀正文，交由 Android 系統 TTS；離線能力及資料處理取決於已安裝的語音引擎。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        val error: String? = synchronized(lifecycleLock) {
            if (terminal) return
            if (initializationFailed) {
                // 初始化已失敗：本次朗讀不會送引擎，經失敗通道揭露（不再靜默）。
                VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_INIT_FAILED)
            } else {
                val engine = tts ?: return
                if (!ready) {
                    pending = PendingSpeech(text, speechEpoch)
                    null
                } else {
                    speakInternalLocked(engine, text)
                }
            }
        }
        if (error != null) emitError(error)
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

    private fun handleInitLocked(engine: TextToSpeech, status: Int): String? {
        if (terminal || initCallbackHandled) return null
        initCallbackHandled = true
        if (status != TextToSpeech.SUCCESS) {
            initializationFailed = true
            pending = null
            speechEpoch++
            return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_INIT_FAILED)
        }

        // 引擎可用性探測：引擎表為空即無可用引擎，視為初始化失敗（fail-closed）。
        // 查詢未知（null／拋錯）維持原流程，不改變既有 lifecycle 語義。
        val noEngine = try {
            val engines = engine.engines
            engines != null && !VoiceTts.hasEngine(engines)
        } catch (_: Exception) {
            false
        }
        if (noEngine) {
            initializationFailed = true
            pending = null
            speechEpoch++
            return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_NO_ENGINE)
        }

        val langError = applyLanguage(engine)
        ready = true
        val queued = pending
        pending = null
        var speakError: String? = null
        if (queued != null && queued.epoch == speechEpoch) {
            speakError = speakInternalLocked(engine, queued.text)
        }
        return langError ?: speakError
    }

    /**
     * 語言回退套用；失敗回錯誤話術（呼叫方在鎖外經 [onError] 揭露），
     * 仍標 `ready`（lifecycle 語義不變：引擎可用，缺中文語料仍可送引擎）。
     */
    private fun applyLanguage(engine: TextToSpeech): String? {
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
        ) ?: return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LANG_UNAVAILABLE)
        val applied = try {
            engine.setLanguage(resolved)
        } catch (_: Exception) {
            return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LANG_UNAVAILABLE)
        }
        return when (applied) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
            -> null
            else -> VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LANG_UNAVAILABLE)
        }
    }

    /** Caller holds [lifecycleLock] for the complete chunk sequence so stop cannot split it. */
    private fun speakInternalLocked(engine: TextToSpeech, text: String): String? {
        val chunks = try {
            VoiceTts.split(text, maxChunk)
        } catch (_: Exception) {
            return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED)
        }
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle()
            val status = try {
                engine.speak(chunk, mode, params, "${UUID.randomUUID()}")
            } catch (_: Exception) {
                return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED)
            }
            if (status != TextToSpeech.SUCCESS) {
                return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED)
            }
        }
        return null
    }

    /** Utterance 失敗轉送失敗通道；terminal 後的遲到回呼不揭露（fence 語義不變）。 */
    private fun reportUtteranceError() {
        val suppressed = synchronized(lifecycleLock) { terminal }
        if (!suppressed) {
            emitError(VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED))
        }
    }

    /** 失敗通道出口（鎖外呼叫；listener 本體異常不回拋，避免壞掉引擎回呼執行緒）。 */
    private fun emitError(message: String) {
        try {
            onError(message)
        } catch (_: Exception) {
        }
    }
}
