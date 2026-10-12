package dev.librepocket.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * TTS 成功信號測試（#7，Robolectric，不釘原始碼字串）。
 *
 * `VoiceSpeaker.onDone` 只對「已送引擎＋當輪 epoch」的 utterance 發射；
 * 未知 id、stop／shutdown 後的遲到回呼一律壓住（與 onError 同 fence）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ControlledTextToSpeechShadow::class])
class VoiceSpeakerDoneTest {

    @Before
    fun resetTextToSpeechShadow() {
        ControlledTextToSpeechShadow.resetControls()
        ShadowTextToSpeech.reset()
    }

    @Test
    fun doneForTrackedUtterance_emitsSuccess() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val errors = CopyOnWriteArrayList<String>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onError = { errors += it },
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("hello")
        val utteranceId = requireNotNull(shadow.utteranceIds.single())

        requireNotNull(shadow.utteranceListener).onDone(utteranceId)

        assertEquals(1, doneCalls.size)
        // Robolectric 預設 shadow 無中文語料，init 照例發 LANG_UNAVAILABLE
        // 降級提醒（既有行為）；成功信號不斷言零錯誤，只斷言無朗讀失敗。
        assertTrue("errors=$errors", errors.none { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) })
        speaker.shutdown()
    }

    @Test
    fun doneUnknownId_isSuppressed() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onDone("never-submitted-id")
        listener.onDone(null)

        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun doneAfterStop_isSuppressed() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("hello")
        val utteranceId = requireNotNull(shadow.utteranceIds.single())

        speaker.stop()
        requireNotNull(shadow.utteranceListener).onDone(utteranceId)

        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun doneAfterShutdown_isSuppressed() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("hello")
        val utteranceId = requireNotNull(shadow.utteranceIds.single())

        speaker.shutdown()
        requireNotNull(shadow.utteranceListener).onDone(utteranceId)

        assertTrue(doneCalls.isEmpty())
    }

    private fun currentTtsShadow(): ControlledTextToSpeechShadow {
        val engine = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        return Shadows.shadowOf(engine) as ControlledTextToSpeechShadow
    }

    private fun initSuccessfully(shadow: ControlledTextToSpeechShadow) {
        requireNotNull(shadow.onInitListener).onInit(TextToSpeech.SUCCESS)
    }
}
