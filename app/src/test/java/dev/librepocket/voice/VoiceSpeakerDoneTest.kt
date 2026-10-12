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

    @Test
    fun multiChunk_errorThenDone_errorRetainedAndDoneSuppressed() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val errors = CopyOnWriteArrayList<String>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            maxChunk = 3,
            onError = { errors += it },
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("abcdef")
        assertEquals(2, shadow.utteranceIds.size)
        val firstId = requireNotNull(shadow.utteranceIds[0])
        val secondId = requireNotNull(shadow.utteranceIds[1])

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onError(firstId, TextToSpeech.ERROR)
        listener.onDone(secondId)

        assertEquals(
            1,
            errors.count { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) },
        )
        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun multiChunk_doneThenError_errorEmittedAndDoneSuppressed() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val errors = CopyOnWriteArrayList<String>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            maxChunk = 3,
            onError = { errors += it },
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("abcdef")
        assertEquals(2, shadow.utteranceIds.size)
        val firstId = requireNotNull(shadow.utteranceIds[0])
        val secondId = requireNotNull(shadow.utteranceIds[1])

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onDone(secondId)
        listener.onError(firstId, TextToSpeech.ERROR)

        assertEquals(
            1,
            errors.count { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) },
        )
        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun multiChunk_allDone_emitsDoneExactlyOnce() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val errors = CopyOnWriteArrayList<String>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            maxChunk = 3,
            onError = { errors += it },
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("abcdef")
        assertEquals(2, shadow.utteranceIds.size)
        val firstId = requireNotNull(shadow.utteranceIds[0])
        val secondId = requireNotNull(shadow.utteranceIds[1])

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onDone(firstId)
        assertTrue(doneCalls.isEmpty())
        listener.onDone(secondId)

        assertEquals(1, doneCalls.size)
        assertTrue("errors=$errors", errors.none { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) })
        speaker.shutdown()
    }

    @Test
    fun sameId_errorThenDone_secondIsSuppressedAndErrorRetained() {
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

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onError(utteranceId, TextToSpeech.ERROR)
        listener.onDone(utteranceId)

        assertEquals(
            1,
            errors.count { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) },
        )
        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun sameId_doneThenError_secondIsSuppressed() {
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

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onDone(utteranceId)
        listener.onError(utteranceId, TextToSpeech.ERROR)

        assertEquals(1, doneCalls.size)
        assertTrue("errors=$errors", errors.none { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) })
        speaker.shutdown()
    }

    @Test
    fun duplicateDone_isSuppressedToExactlyOne() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("hello")
        val utteranceId = requireNotNull(shadow.utteranceIds.single())

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onDone(utteranceId)
        listener.onDone(utteranceId)

        assertEquals(1, doneCalls.size)
        speaker.shutdown()
    }

    @Test
    fun errorValidationThenStop_beforeEmit_isSuppressed() {
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

        speaker.preEmitHook = { speaker.stop() }
        try {
            requireNotNull(shadow.utteranceListener).onError(utteranceId, TextToSpeech.ERROR)
        } finally {
            speaker.preEmitHook = null
        }

        assertTrue("errors=$errors", errors.none { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) })
        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun doneValidationThenStop_beforeEmit_isSuppressed() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("hello")
        val utteranceId = requireNotNull(shadow.utteranceIds.single())

        speaker.preEmitHook = { speaker.stop() }
        try {
            requireNotNull(shadow.utteranceListener).onDone(utteranceId)
        } finally {
            speaker.preEmitHook = null
        }

        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun errorValidationThenNewSpeak_beforeEmit_isSuppressed() {
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

        speaker.preEmitHook = { speaker.speak("new request") }
        try {
            requireNotNull(shadow.utteranceListener).onError(utteranceId, TextToSpeech.ERROR)
        } finally {
            speaker.preEmitHook = null
        }

        assertTrue("errors=$errors", errors.none { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) })
        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun syncDoneReentrancy_twoChunks_emitsDoneExactlyOnceAfterBoth() {
        ControlledTextToSpeechShadow.callBackSynchronously = true
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val errors = CopyOnWriteArrayList<String>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            maxChunk = 3,
            onError = { errors += it },
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("abcdef")

        assertEquals(2, shadow.utteranceIds.size)
        assertEquals(1, doneCalls.size)
        assertTrue("errors=$errors", errors.none { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) })
        speaker.shutdown()
    }

    @Test
    fun syncErrorReentrancy_firstErrorSecondSuccess_errorRetainedAndDoneSuppressed() {
        ControlledTextToSpeechShadow.callBackSynchronously = true
        ControlledTextToSpeechShadow.syncErrorOnFirstSpeak = true
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val errors = CopyOnWriteArrayList<String>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            maxChunk = 3,
            onError = { errors += it },
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("abcdef")

        assertEquals(2, shadow.utteranceIds.size)
        assertEquals(
            1,
            errors.count { it.contains(VoiceTts.DETAIL_SPEAK_FAILED) },
        )
        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    @Test
    fun donePostCheckThenStop_beforeEmit_isSuppressed() {
        val doneCalls = CopyOnWriteArrayList<Unit>()
        val speaker = VoiceSpeaker(
            ApplicationProvider.getApplicationContext<Context>(),
            onDone = { doneCalls += Unit },
        )
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        speaker.speak("hello")
        val utteranceId = requireNotNull(shadow.utteranceIds.single())

        speaker.postCheckHook = { speaker.stop() }
        try {
            requireNotNull(shadow.utteranceListener).onDone(utteranceId)
        } finally {
            speaker.postCheckHook = null
        }

        assertTrue(doneCalls.isEmpty())
        speaker.shutdown()
    }

    private fun currentTtsShadow(): ControlledTextToSpeechShadow {
        val engine = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        return Shadows.shadowOf(engine) as ControlledTextToSpeechShadow
    }

    private fun initSuccessfully(shadow: ControlledTextToSpeechShadow) {
        requireNotNull(shadow.onInitListener).onInit(TextToSpeech.SUCCESS)
    }
}
