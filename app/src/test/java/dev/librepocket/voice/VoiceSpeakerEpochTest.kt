package dev.librepocket.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * UI 事件 epoch 判定純函數測試（純 JVM，不依賴 Robolectric／Compose 測試框架）。
 *
 * [shouldApplyUiEvent] 為 ChatScreen Main 投遞 fence 的判定本體：
 * 事件 epoch 與 speaker 當代 [VoiceSpeaker.currentEpoch] 全等才應用，否則丟棄
 * （舊 request 事件排入 Main 後、執行前若有新 speak 使 epoch 前進，即被擋下）。
 */
class ShouldApplyUiEventTest {

    @Test
    fun sameEpoch_isApplied() {
        assertTrue(shouldApplyUiEvent(0L, 0L))
        assertTrue(shouldApplyUiEvent(3L, 3L))
    }

    @Test
    fun staleEpoch_isDiscarded() {
        assertFalse(shouldApplyUiEvent(1L, 2L))
        assertFalse(shouldApplyUiEvent(0L, 1L))
    }

    @Test
    fun futureEpoch_isDiscarded() {
        // 未來 epoch（跨實例錯配等）亦丟棄：fail-closed，只認全等。
        assertFalse(shouldApplyUiEvent(5L, 4L))
    }
}

/**
 * Main 排隊舊事件丟棄回歸測試（R5-2，Robolectric＋StandardTestDispatcher 確定性排序）。
 *
 * 重現：A 輪 utterance 回調已通過 speaker 層檢查、回調排入 Main（尚未執行），
 * drain 前 B 輪 speak 使 epoch 前進，後再 flush A 的 Main lambda → 舊事件被丟棄，
 * 不覆蓋新 request 的 UI。接線與 ChatScreen 同形（回調攜 epoch → Main 上
 * [shouldApplyUiEvent] 比對當代 epoch；repo 無 compose-test，故在此層鎖定）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ControlledTextToSpeechShadow::class])
class VoiceSpeakerEpochTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val uiScope = CoroutineScope(mainDispatcher + SupervisorJob())

    @Before
    fun setUp() {
        ControlledTextToSpeechShadow.resetControls()
        ShadowTextToSpeech.reset()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        uiScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun staleMainUiEvent_afterNewSpeak_isDiscarded() {
        val appliedErrors = CopyOnWriteArrayList<Pair<String, Long>>()
        val appliedDone = CopyOnWriteArrayList<Long>()
        var speaker: VoiceSpeaker? = null
        speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onError = { msg, epoch ->
                // 與 ChatScreen 接線同形：binder 回調攜 epoch，Main 上比對當代 epoch。
                val current = speaker
                uiScope.launch {
                    if (current == null || !shouldApplyUiEvent(epoch, current.currentEpoch)) return@launch
                    appliedErrors += msg to epoch
                }
            },
            onDone = { epoch ->
                val current = speaker
                uiScope.launch {
                    if (current == null || !shouldApplyUiEvent(epoch, current.currentEpoch)) return@launch
                    appliedDone += epoch
                }
            },
        )
        val active = requireNotNull(speaker)
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        // 初始化降級提醒（Robolectric 預設 shadow 無中文語料）為當代 epoch：先排空並清空，
        // 使後續斷言只看 A／B 兩輪事件；同時鎖定「新鮮事件照常應用」。
        mainDispatcher.scheduler.advanceUntilIdle()
        assertFalse("init events should apply while fresh: $appliedErrors", appliedErrors.isEmpty())
        appliedErrors.clear()

        // A 輪：送朗讀 → utterance 失敗 → speaker 層通過檢查 → 回調排入 Main（尚未執行）。
        active.speak("request A")
        val utteranceA = requireNotNull(shadow.utteranceIds.single())
        requireNotNull(shadow.utteranceListener).onError(utteranceA, TextToSpeech.ERROR)
        // B 輪在 A 的 Main lambda 執行前插入：epoch 前進。
        active.speak("request B")
        // drain 前 B speak 使 epoch 前進，後再 flush A 的 Main lambda。
        mainDispatcher.scheduler.advanceUntilIdle()

        // A 的舊錯誤被丟棄（不覆蓋新 request 的 UI），舊成功信號亦無。
        assertTrue("stale A event must be discarded: $appliedErrors", appliedErrors.isEmpty())
        assertTrue("stale done must be discarded: $appliedDone", appliedDone.isEmpty())

        // 新輪事件仍照常應用：B 輪失敗排入並執行，且攜當代 epoch。
        val utteranceB = requireNotNull(shadow.utteranceIds.last())
        requireNotNull(shadow.utteranceListener).onError(utteranceB, TextToSpeech.ERROR)
        mainDispatcher.scheduler.advanceUntilIdle()
        val speakFailures = appliedErrors.filter { it.first.contains(VoiceTts.DETAIL_SPEAK_FAILED) }
        assertEquals("applied=$appliedErrors", 1, speakFailures.size)
        assertEquals(active.currentEpoch, speakFailures.single().second)
        active.shutdown()
    }

    @Test
    fun staleMainUiEvent_afterRebuild_isDiscarded() {
        // R6-2 回歸：關→開重建撞號。A speak epoch e1→onError 排入 Main（未執行）
        // →dispose A→建 B→B speak（epoch 必 ≠ e1）→flush A 舊 lambda→斷言丟棄，
        // B 新事件正常應用。接線與 ChatScreen 同形（holder＋epoch-only 比對）。
        val appliedErrors = CopyOnWriteArrayList<Pair<String, Long>>()
        val holder = object { var speaker: VoiceSpeaker? = null }
        fun newWiredSpeaker(): VoiceSpeaker {
            val created = VoiceSpeaker(
                ApplicationProvider.getApplicationContext<Context>(),
                onError = { msg, epoch ->
                    uiScope.launch {
                        val current = holder.speaker
                        if (current == null || !shouldApplyUiEvent(epoch, current.currentEpoch)) return@launch
                        appliedErrors += msg to epoch
                    }
                },
                onDone = { _ -> },
            )
            holder.speaker = created
            return created
        }

        // A 輪：建→初始化→排空 init 事件→speak→utterance 失敗排入 Main（未執行）。
        val speakerA = newWiredSpeaker()
        val shadowA = currentTtsShadow()
        initSuccessfully(shadowA)
        mainDispatcher.scheduler.advanceUntilIdle()
        appliedErrors.clear()
        speakerA.speak("request A")
        val utteranceA = requireNotNull(shadowA.utteranceIds.single())
        val epochA = speakerA.currentEpoch
        requireNotNull(shadowA.utteranceListener).onError(utteranceA, TextToSpeech.ERROR)
        // A 的 Main lambda 尚在排隊：此時不 flush。

        // dispose A→建 B（holder 先 null 再指向 B，模擬關→開重建）。
        speakerA.shutdown()
        holder.speaker = null
        val speakerB = newWiredSpeaker()
        val shadowB = currentTtsShadow()
        initSuccessfully(shadowB)
        speakerB.speak("request B")
        val epochB = speakerB.currentEpoch
        // 全域單調：B 新 epoch 必 ≠ A 舊 epoch（舊碼同進程從 0 起會撞號）。
        assertTrue("rebuild epochs must differ: e1=$epochA e2=$epochB", epochA != epochB)

        // flush A 舊 lambda：epoch 不符（＋holder 已指 B），必須丟棄。
        mainDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            "stale A event (epoch=$epochA) must be discarded after rebuild: $appliedErrors",
            appliedErrors.none { it.second == epochA },
        )
        appliedErrors.clear()

        // B 新事件正常應用：B 輪失敗排入並執行，且攜當代 epoch。
        val utteranceB = requireNotNull(shadowB.utteranceIds.last())
        requireNotNull(shadowB.utteranceListener).onError(utteranceB, TextToSpeech.ERROR)
        mainDispatcher.scheduler.advanceUntilIdle()
        val speakFailures = appliedErrors.filter { it.first.contains(VoiceTts.DETAIL_SPEAK_FAILED) }
        assertEquals("applied=$appliedErrors", 1, speakFailures.size)
        assertEquals(speakerB.currentEpoch, speakFailures.single().second)
        speakerB.shutdown()
    }

    @Test
    fun freshMainUiEvent_withoutNewSpeak_isApplied() {
        val appliedErrors = CopyOnWriteArrayList<Pair<String, Long>>()
        var speaker: VoiceSpeaker? = null
        speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onError = { msg, epoch ->
                val current = speaker
                uiScope.launch {
                    if (current == null || !shouldApplyUiEvent(epoch, current.currentEpoch)) return@launch
                    appliedErrors += msg to epoch
                }
            },
            onDone = { _ -> },
        )
        val active = requireNotNull(speaker)
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        mainDispatcher.scheduler.advanceUntilIdle()
        appliedErrors.clear()

        active.speak("only request")
        val utteranceId = requireNotNull(shadow.utteranceIds.single())
        requireNotNull(shadow.utteranceListener).onError(utteranceId, TextToSpeech.ERROR)
        mainDispatcher.scheduler.advanceUntilIdle()

        // 無新 speak 推進 epoch：事件新鮮，照常應用。
        assertEquals("applied=$appliedErrors", 1, appliedErrors.size)
        assertEquals(active.currentEpoch, appliedErrors.single().second)
        active.shutdown()
    }

    private fun currentTtsShadow(): ControlledTextToSpeechShadow {
        val engine = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        return Shadows.shadowOf(engine) as ControlledTextToSpeechShadow
    }

    private fun initSuccessfully(shadow: ControlledTextToSpeechShadow) {
        requireNotNull(shadow.onInitListener).onInit(TextToSpeech.SUCCESS)
    }
}
