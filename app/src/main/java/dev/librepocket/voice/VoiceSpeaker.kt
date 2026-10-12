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
 * - 進度：掛載 [UtteranceProgressListener]（onStart 為 no-op；onDone 經 [onDone]
 *   發成功信號（供 UI 清除 TTS 錯誤，見 `VoiceErrorState.onTtsSuccess`）；
 *   onError 轉送 [onError] 失敗通道）；
 *   掛載回傳 ERROR 或拋錯時經 [onError] 發出 LISTENER_FAILED 錯誤提醒（非同步朗讀失敗可能無法觀測），但不 fail-closed，引擎仍可用；
 * - 分段：[VoiceTts.split] 每段最多 4000 字；
 * - 語言：`zh-TW` → `zh-CN` → 第一個列出的 `zh` voice（見 [VoiceTts.resolveLocale]）。
 *
 * 初始化為非同步：[speak] 在成功回呼前只保留最新待播文字。初始化失敗時清除待播文字並記下原始 detail（NO_ENGINE／INIT_FAILED），後續 [speak] 沿用該 detail 揭露；本類別不提供重新初始化引擎的 API。
 *
 * 可觀察性（Stage2）：引擎可用性探測（[VoiceTts.hasEngine]，空引擎表視為初始化失敗，
 * fail-closed）與初始化／listener 掛載／朗讀／utterance 失敗一律經 [onError] 揭露，
 * 不再靜默吞錯。缺中文語料走非失敗的降級提醒（[VoiceTts.ttsLanguageWarningMessage]，
 * 不以「做不到」開頭，仍標 ready 繼續朗讀）；初始化時語言提醒與待播朗讀失敗
 * 會各別發出（警告在前、失敗在後），不互相覆蓋。Listener 在鎖外呼叫；lifecycle 語義（epoch/pending/terminal
 * fence）與 Stage1 一致；utterance 回呼以 utteranceId＋epoch 追蹤，
 * stop／shutdown 後的遲到回呼與重複同 id 終結不揭露。
 * Request 級成功語義：同 request 多 chunk 時，任一 chunk onError 即標記整輪失敗並照常揭露，
 * 後續同輪 onDone 一律抑制（錯誤不被成功清除）；全 chunk 無失敗終結時，末個 onDone 才發一次
 * [onDone]。chunk 終結（去重通過的 onError／發射條件滿足的 onDone）在鎖內即移除追蹤條目，
 * 重複終結一律抑制；emit 前重新進鎖複查 `!terminal && epoch == speechEpoch`，
 * 擋下 validation 與投遞之間插入的 stop／shutdown。
 */
class VoiceSpeaker(
    appContext: Context,
    private val maxChunk: Int = VoiceTts.MAX_CHUNK_CHARS,
    private val onError: (String) -> Unit = {},
    /**
     * utterance 完成信號：整 request 全 chunk 無失敗終結時恰發一次；
     * 任一失敗、遲到／未知 id、terminal 後一律不發（供 UI 清除 TTS 錯誤，見 `VoiceErrorState.onTtsSuccess`）。
     */
    private val onDone: () -> Unit = {},
) {

    private val context: Context = appContext.applicationContext
    private val lifecycleLock = Any()

    /** Engine is assigned only after the constructor returns (which may invoke its callback). */
    private var tts: TextToSpeech? = null
    private var deferredInitStatus: Int? = null
    private var initCallbackHandled = false
    private var initFailureDetail: String? = null
    private var ready = false
    private var terminal = false
    private var speechEpoch = 0L
    private var pending: PendingSpeech? = null

    /**
     * 已送引擎的 utterance 追蹤（utteranceId → 送出時的 [speechEpoch]）與
     * 已揭露集合（同 id 只報一次）。一律在 [lifecycleLock] 內讀寫。
     */
    private val utteranceEpochs = mutableMapOf<String, Long>()
    private val reportedUtteranceIds = mutableSetOf<String>()

    /**
     * 當輪 request 級狀態（[lifecycleLock] 內讀寫）：
     * - [activeRequestFailed]：任一 chunk 失敗（同步送入失敗或 utterance onError）即為 true；
     * - [activeRequestPending]：當輪已送引擎、尚未終結的 chunk 計數。
     * 新 speak（[speakInternalLocked] 整表清除處）重置兩者；stop／shutdown／初始化失敗
     * 清表時同步歸零，避免殘留計數洩入下輪。
     */
    private var activeRequestFailed = false
    private var activeRequestPending = 0

    /**
     * 測試用屏障鉤子：validation 通過後、emit 前複查前在鎖外執行呼叫端注入的動作
     * （如 `stop()`），用於確定性重現 validation→emit 窗口的交錯。生產路徑為 null；
     * 本體異常會被吞掉，避免壞掉引擎回呼執行緒。
     */
    @Volatile
    internal var preEmitHook: (() -> Unit)? = null

    private data class PendingSpeech(val text: String, val epoch: Long)

    init {
        val engine = TextToSpeech(context) { status ->
            val initErrors: List<String> = synchronized(lifecycleLock) {
                val initializedEngine = tts
                if (initializedEngine == null) {
                    // TextToSpeech is allowed to call back from its constructor. Defer that result
                    // until the engine reference and listener setup are complete.
                    if (!initCallbackHandled && deferredInitStatus == null) {
                        deferredInitStatus = status
                    }
                    emptyList()
                } else {
                    handleInitLocked(initializedEngine, status)
                }
            }
            initErrors.forEach { emitError(it) }
        }
        val listenerError: String?
        val deferredErrors: List<String>
        synchronized(lifecycleLock) {
            listenerError = try {
                val listenerStatus =
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            reportUtteranceDone(utteranceId)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            reportUtteranceError(utteranceId)
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            reportUtteranceError(utteranceId)
                        }
                    })
                if (listenerStatus == TextToSpeech.ERROR) {
                    VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LISTENER_FAILED)
                } else {
                    null
                }
            } catch (_: Exception) {
                VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LISTENER_FAILED)
            }
            tts = engine
            val callbackStatus = deferredInitStatus
            deferredInitStatus = null
            deferredErrors = if (callbackStatus != null) handleInitLocked(engine, callbackStatus) else emptyList()
        }
        if (listenerError != null) emitError(listenerError)
        deferredErrors.forEach { emitError(it) }
    }

    /** 朗讀正文，交由 Android 系統 TTS；離線能力及資料處理取決於已安裝的語音引擎。 */
    fun speak(text: String) {
        if (text.isBlank()) return
        val error: String? = synchronized(lifecycleLock) {
            if (terminal) return
            val failureDetail = initFailureDetail
            if (failureDetail != null) {
                // 初始化已失敗：本次朗讀不會送引擎，沿用原始 detail 揭露（不再靜默）。
                VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, failureDetail)
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
            // 舊 utterance 已被 QUEUE_FLUSH 語義或引擎 stop 丟棄：遲到錯誤不再揭露。
            val evicted = utteranceEpochs.keys.toSet()
            utteranceEpochs.clear()
            reportedUtteranceIds.removeAll(evicted)
            activeRequestFailed = false
            activeRequestPending = 0
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
            utteranceEpochs.clear()
            reportedUtteranceIds.clear()
            activeRequestFailed = false
            activeRequestPending = 0
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

    /**
     * 初始化回呼本體（呼叫方持有 [lifecycleLock]；回傳待揭露訊息，
     * 呼叫方在鎖外依序發出）。語言降級提醒與待播朗讀失敗各別回傳、
     * 互不覆蓋（警告在前、失敗在後）。
     */
    private fun handleInitLocked(engine: TextToSpeech, status: Int): List<String> {
        if (terminal || initCallbackHandled) return emptyList()
        initCallbackHandled = true
        if (status != TextToSpeech.SUCCESS) {
            initFailureDetail = VoiceTts.DETAIL_INIT_FAILED
            pending = null
            speechEpoch++
            activeRequestFailed = false
            activeRequestPending = 0
            return listOf(VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_INIT_FAILED))
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
            initFailureDetail = VoiceTts.DETAIL_NO_ENGINE
            pending = null
            speechEpoch++
            activeRequestFailed = false
            activeRequestPending = 0
            return listOf(VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_NO_ENGINE))
        }

        val langWarning = applyLanguage(engine)
        ready = true
        val queued = pending
        pending = null
        var speakError: String? = null
        if (queued != null && queued.epoch == speechEpoch) {
            speakError = speakInternalLocked(engine, queued.text)
        }
        return listOfNotNull(langWarning, speakError)
    }

    /**
     * 語言回退套用；缺中文語料回降級提醒（非播放失敗，呼叫方在鎖外經 [onError] 揭露），
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
        ) ?: return VoiceTts.ttsLanguageWarningMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LANG_UNAVAILABLE)
        val applied = try {
            engine.setLanguage(resolved)
        } catch (_: Exception) {
            return VoiceTts.ttsLanguageWarningMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LANG_UNAVAILABLE)
        }
        return when (applied) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
            -> null
            else -> VoiceTts.ttsLanguageWarningMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_LANG_UNAVAILABLE)
        }
    }

    /**
     * Caller holds [lifecycleLock] for the complete chunk sequence so stop cannot split it.
     *
     * 每段以實際傳給 `engine.speak` 的同一個 utteranceId 追蹤（utteranceId → 當前 epoch），
     * 供遲到的 utterance onError 在同一臨界區內校驗。首段 QUEUE_FLUSH 會取代舊 utterance，
     * 故先移除舊追蹤，使其遲到錯誤不再揭露，並重置當輪 request 級狀態
     * （[activeRequestFailed]／[activeRequestPending]）；新 speak 即新 epoch
     * （[speechEpoch]++），使舊輪 validation 通過後、emit 前插入新 speak 的
     * 交錯也被投遞時複查擋下。每段成功送入引擎即 pending++。
     * 送入失敗的段不留追蹤（引擎不會為它回呼），但標記整輪失敗，
     * 使同輪其他 chunk 的後續 onDone 被抑制（錯誤不被成功清除）。
     */
    private fun speakInternalLocked(engine: TextToSpeech, text: String): String? {
        val chunks = try {
            VoiceTts.split(text, maxChunk)
        } catch (_: Exception) {
            val evicted = utteranceEpochs.keys.toSet()
            utteranceEpochs.clear()
            reportedUtteranceIds.removeAll(evicted)
            activeRequestFailed = true
            activeRequestPending = 0
            return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED)
        }
        val evicted = utteranceEpochs.keys.toSet()
        utteranceEpochs.clear()
        reportedUtteranceIds.removeAll(evicted)
        speechEpoch++
        activeRequestFailed = false
        activeRequestPending = 0
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val params = Bundle()
            val utteranceId = "${UUID.randomUUID()}"
            utteranceEpochs[utteranceId] = speechEpoch
            val status = try {
                engine.speak(chunk, mode, params, utteranceId)
            } catch (_: Exception) {
                utteranceEpochs.remove(utteranceId)
                activeRequestFailed = true
                return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED)
            }
            if (status != TextToSpeech.SUCCESS) {
                utteranceEpochs.remove(utteranceId)
                activeRequestFailed = true
                return VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED)
            }
            activeRequestPending++
        }
        return null
    }

    /**
     * Utterance 失敗轉送失敗通道。同一臨界區內完成「id 存在＋epoch 相符＋未重複報錯」
     * 校驗、標記整輪失敗、pending-- 與移除追蹤條目，emit 在鎖外。terminal 後、
     * stop／shutdown 後（epoch 不符）、未知／空 id、同一 id 重複終結（條目已移除）
     * 的遲到回呼一律不揭露。emit 前重新進鎖複查 `!terminal && epoch == speechEpoch`，
     * 擋下 validation 與投遞之間插入的 stop／shutdown。
     */
    private fun reportUtteranceError(utteranceId: String?) {
        val epoch: Long? = synchronized(lifecycleLock) {
            if (terminal) {
                null
            } else if (utteranceId == null) {
                null
            } else {
                val stored = utteranceEpochs[utteranceId] ?: return@synchronized null
                if (stored != speechEpoch) {
                    null
                } else if (!reportedUtteranceIds.add(utteranceId)) {
                    null
                } else {
                    utteranceEpochs.remove(utteranceId)
                    reportedUtteranceIds.remove(utteranceId)
                    activeRequestFailed = true
                    if (activeRequestPending > 0) activeRequestPending--
                    stored
                }
            }
        }
        if (epoch == null) return
        runPreEmitHook()
        val shouldEmit = synchronized(lifecycleLock) { !terminal && epoch == speechEpoch }
        if (shouldEmit) {
            emitError(VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, VoiceTts.DETAIL_SPEAK_FAILED))
        }
    }

    /**
     * Utterance 完成轉送成功通道（request 級語義：整輪全 chunk 無失敗終結時，
     * 末個 onDone 才發一次 [onDone]；整輪已有失敗即抑制，不清除錯誤）。
     * 同一臨界區內完成「id 存在＋epoch 相符」校驗、pending-- 與移除追蹤條目，
     * emit 在鎖外。terminal 後、stop／shutdown 後（epoch 不符）、未知／空 id、
     * 同一 id 重複終結（條目已移除）的遲到回呼一律不發。emit 前複查 fence
     * 與 [reportUtteranceError] 相同。
     */
    private fun reportUtteranceDone(utteranceId: String?) {
        val decision: DoneDecision? = synchronized(lifecycleLock) {
            if (terminal) {
                null
            } else if (utteranceId == null) {
                null
            } else {
                val stored = utteranceEpochs[utteranceId] ?: return@synchronized null
                if (stored != speechEpoch) {
                    null
                } else {
                    utteranceEpochs.remove(utteranceId)
                    reportedUtteranceIds.remove(utteranceId)
                    if (activeRequestPending > 0) activeRequestPending--
                    DoneDecision(stored, emit = !activeRequestFailed && activeRequestPending == 0)
                }
            }
        }
        if (decision == null || !decision.emit) return
        runPreEmitHook()
        val shouldEmit = synchronized(lifecycleLock) { !terminal && decision.epoch == speechEpoch }
        if (shouldEmit) {
            emitDone()
        }
    }

    /** 鎖內判定的終結附帶 emit 許可（[epoch] 為回呼所屬輪次，供 emit 前複查）。 */
    private data class DoneDecision(val epoch: Long, val emit: Boolean)

    /** 執行測試屏障鉤子（鎖外；異常吞掉，避免壞掉引擎回呼執行緒）。 */
    private fun runPreEmitHook() {
        val hook = preEmitHook
        if (hook != null) {
            try {
                hook()
            } catch (_: Exception) {
            }
        }
    }

    /** 失敗通道出口（鎖外呼叫；listener 本體異常不回拋，避免壞掉引擎回呼執行緒）。 */
    private fun emitError(message: String) {
        try {
            onError(message)
        } catch (_: Exception) {
        }
    }

    /** 成功通道出口（語義同 [emitError]）。 */
    private fun emitDone() {
        try {
            onDone()
        } catch (_: Exception) {
        }
    }
}
