package dev.librepocket.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ControlledTextToSpeechShadow::class])
class VoiceSpeakerLifecycleTest {

    @Before
    fun resetTextToSpeechShadow() {
        ControlledTextToSpeechShadow.resetControls()
        ShadowTextToSpeech.reset()
    }

    @Test
    fun stopBeforeInit_discardsPendingSpeechAndSpeakerCanResumeAfterInit() {
        val speaker = newSpeaker()
        speaker.speak("discard after stop")
        val shadow = currentTtsShadow()

        speaker.stop()
        initSuccessfully(shadow)

        assertTrue(shadow.spokenTextList.isEmpty())
        speaker.speak("resume after stop")
        assertEquals(listOf("resume after stop"), shadow.spokenTextList)
    }

    @Test
    fun repeatedShutdownIsTerminalAndLateInitCannotSpeak() {
        val speaker = newSpeaker()
        speaker.speak("discard after shutdown")
        val shadow = currentTtsShadow()

        speaker.shutdown()
        speaker.shutdown()
        initSuccessfully(shadow)
        speaker.speak("ignore after shutdown")

        assertEquals(emptyList<String>(), shadow.spokenTextList)
        assertEquals(1, shadow.stopCalls.get())
        assertEquals(1, shadow.shutdownCalls.get())
    }

    @Test
    fun synchronousInitCallbackDuringConstructionIsSafe() {
        ControlledTextToSpeechShadow.initSynchronously = true

        val speaker = newSpeaker()
        val shadow = currentTtsShadow()
        speaker.speak("ready during construction")

        assertEquals(listOf("ready during construction"), shadow.spokenTextList)
    }

    @Test
    fun delayedInitFlushesOnlyLatestPendingTextAndKeepsChunkQueueModes() {
        val speaker = VoiceSpeaker(ApplicationProvider.getApplicationContext<Context>(), maxChunk = 3)
        speaker.speak("old")
        speaker.speak("abcdefg")
        val shadow = currentTtsShadow()

        assertTrue(shadow.spokenTextList.isEmpty())
        initSuccessfully(shadow)

        assertEquals(listOf("abc", "def", "g"), shadow.spokenTextList)
        assertEquals(
            listOf(TextToSpeech.QUEUE_FLUSH, TextToSpeech.QUEUE_ADD, TextToSpeech.QUEUE_ADD),
            shadow.queueModes,
        )
    }

    @Test
    fun failedInitDropsPendingAndDoesNotBufferFurtherSpeech() {
        val speaker = newSpeaker()
        speaker.speak("pending before failure")
        val shadow = currentTtsShadow()

        requireNotNull(shadow.onInitListener).onInit(TextToSpeech.ERROR)
        speaker.speak("after failed initialization")

        assertTrue(shadow.spokenTextList.isEmpty())
    }

    @Test
    fun initFailure_isObservableAndLaterSpeakStaysObservable() {
        val errors = CopyOnWriteArrayList<String>()
        val speaker = newSpeaker(onError = { errors += it })
        speaker.speak("pending before failure")
        val shadow = currentTtsShadow()

        requireNotNull(shadow.onInitListener).onInit(TextToSpeech.ERROR)
        speaker.speak("after failed initialization")

        assertTrue(shadow.spokenTextList.isEmpty())
        assertEquals(2, errors.size)
        assertTrue(
            "errors=$errors",
            errors.all {
                it.contains(VoiceTts.DETAIL_INIT_FAILED) && it.contains("NO_PRIVILEGE")
            },
        )
    }

    @Test
    fun noEngine_isObservableAndFailClosed() {
        ControlledTextToSpeechShadow.forcedEngines = emptyList()
        val errors = CopyOnWriteArrayList<String>()
        val speaker = newSpeaker(onError = { errors += it })
        speaker.speak("pending before probe")
        val shadow = currentTtsShadow()

        initSuccessfully(shadow)
        speaker.speak("after no-engine failure")

        assertTrue(shadow.spokenTextList.isEmpty())
        assertTrue(
            "errors=$errors",
            errors.any {
                it.contains(VoiceTts.DETAIL_NO_ENGINE) && it.contains("NO_PRIVILEGE")
            },
        )
    }

    @Test
    fun speakReturnError_isObservable() {
        ControlledTextToSpeechShadow.failSpeak = true
        val errors = CopyOnWriteArrayList<String>()
        val speaker = newSpeaker(onError = { errors += it })
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)

        speaker.speak("hello")

        assertTrue(shadow.spokenTextList.isEmpty())
        assertTrue(
            "errors=$errors",
            errors.any {
                it.contains(VoiceTts.DETAIL_SPEAK_FAILED) && it.contains("NO_PRIVILEGE")
            },
        )
    }

    @Test
    fun speakException_isObservable() {
        ControlledTextToSpeechShadow.throwOnSpeak = true
        val errors = CopyOnWriteArrayList<String>()
        val speaker = newSpeaker(onError = { errors += it })
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)

        speaker.speak("hello")

        assertTrue(shadow.spokenTextList.isEmpty())
        assertTrue(
            "errors=$errors",
            errors.any {
                it.contains(VoiceTts.DETAIL_SPEAK_FAILED) && it.contains("NO_PRIVILEGE")
            },
        )
    }

    @Test
    fun utteranceError_isObservable() {
        val errors = CopyOnWriteArrayList<String>()
        val speaker = newSpeaker(onError = { errors += it })
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)

        val listener = requireNotNull(shadow.utteranceListener)
        listener.onError("u1", TextToSpeech.ERROR)
        @Suppress("DEPRECATION")
        listener.onError("u1")

        assertEquals(
            2,
            errors.count {
                it.contains(VoiceTts.DETAIL_SPEAK_FAILED) && it.contains("NO_PRIVILEGE")
            },
        )
        speaker.shutdown()
    }

    @Test
    fun langUnavailable_isObservableButSpeakerStaysReady() {
        // 預設 shadow 無中文語料時：揭露 LANG_UNAVAILABLE，但 ready 語義不變（仍可朗讀）。
        ControlledTextToSpeechShadow.failLanguageLookup = true
        val errors = CopyOnWriteArrayList<String>()
        val speaker = newSpeaker(onError = { errors += it })
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)

        speaker.speak("hi")

        assertEquals(listOf("hi"), shadow.spokenTextList)
        assertTrue(
            "errors=$errors",
            errors.any {
                it.contains(VoiceTts.DETAIL_LANG_UNAVAILABLE) && it.contains("NO_PRIVILEGE")
            },
        )
    }

    @Test
    fun initAlreadyInFlightCannotSpeakAfterStopReturns() {
        ControlledTextToSpeechShadow.pauseLanguageLookup = true
        ControlledTextToSpeechShadow.languageLookupEntered = CountDownLatch(1)
        ControlledTextToSpeechShadow.continueLanguageLookup = CountDownLatch(1)

        val speaker = newSpeaker()
        speaker.speak("in-flight pending speech")
        val shadow = currentTtsShadow()
        val callbackThread = Thread { initSuccessfully(shadow) }
        val stopStarted = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val stopThread = Thread {
            stopStarted.countDown()
            speaker.stop()
            shadow.events += "stop-return"
            stopReturned.countDown()
        }
        callbackThread.start()
        try {
            assertTrue(
                "init callback did not reach the controlled language lookup",
                ControlledTextToSpeechShadow.languageLookupEntered!!.await(5, TimeUnit.SECONDS),
            )
            stopThread.start()
            assertTrue(stopStarted.await(5, TimeUnit.SECONDS))
            awaitStopAttempt(shadow, stopThread)
        } finally {
            ControlledTextToSpeechShadow.continueLanguageLookup!!.countDown()
            callbackThread.join(5_000)
            if (stopThread.state != Thread.State.NEW) stopThread.join(5_000)
        }

        assertFalse("init callback thread did not finish", callbackThread.isAlive)
        assertFalse("stop thread did not finish", stopThread.isAlive)
        assertTrue("stop did not return", stopReturned.await(5, TimeUnit.SECONDS))

        val stopReturnIndex = shadow.events.indexOf("stop-return")
        assertTrue("stop-return event missing: ${shadow.events}", stopReturnIndex >= 0)
        val lateSpeakIndex = shadow.events.withIndex().firstOrNull {
            it.value.startsWith("speak:") && it.index > stopReturnIndex
        }?.index ?: -1
        assertEquals("speech was submitted after stop returned: ${shadow.events}", -1, lateSpeakIndex)
    }

    @Test
    fun stopCannotInterleaveInsideMultiChunkSubmission() {
        val speaker = VoiceSpeaker(ApplicationProvider.getApplicationContext<Context>(), maxChunk = 3)
        val shadow = currentTtsShadow()
        initSuccessfully(shadow)
        ControlledTextToSpeechShadow.pauseAfterFirstSpeak = true
        ControlledTextToSpeechShadow.firstSpeakEntered = CountDownLatch(1)
        ControlledTextToSpeechShadow.continueSpeaking = CountDownLatch(1)

        val speakThread = Thread { speaker.speak("abcdef") }
        val stopStarted = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val stopThread = Thread {
            stopStarted.countDown()
            speaker.stop()
            shadow.events += "stop-return"
            stopReturned.countDown()
        }
        speakThread.start()
        try {
            assertTrue(
                "first chunk was not submitted",
                ControlledTextToSpeechShadow.firstSpeakEntered!!.await(5, TimeUnit.SECONDS),
            )
            stopThread.start()
            assertTrue(stopStarted.await(5, TimeUnit.SECONDS))
            awaitStopAttempt(shadow, stopThread)
        } finally {
            ControlledTextToSpeechShadow.continueSpeaking!!.countDown()
            speakThread.join(5_000)
            if (stopThread.state != Thread.State.NEW) stopThread.join(5_000)
        }

        assertFalse("speak thread did not finish", speakThread.isAlive)
        assertFalse("stop thread did not finish", stopThread.isAlive)
        assertTrue("stop did not return", stopReturned.await(5, TimeUnit.SECONDS))

        val stopReturnIndex = shadow.events.indexOf("stop-return")
        assertTrue("stop-return event missing: ${shadow.events}", stopReturnIndex >= 0)
        val lateSpeakIndex = shadow.events.withIndex().firstOrNull {
            it.value.startsWith("speak:") && it.index > stopReturnIndex
        }?.index ?: -1
        assertEquals("a chunk was submitted after stop returned: ${shadow.events}", -1, lateSpeakIndex)
        assertEquals(listOf("abc", "def"), shadow.spokenTextList)
        assertEquals(listOf(TextToSpeech.QUEUE_FLUSH, TextToSpeech.QUEUE_ADD), shadow.queueModes)
    }

    /** Wait for engine stop or a coherent blocked-at-stop ThreadInfo snapshot. */
    private fun awaitStopAttempt(shadow: ControlledTextToSpeechShadow, stopThread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val threadMxBean = ManagementFactory.getThreadMXBean()
        while (true) {
            if (shadow.stopEntered.count == 0L) return
            val info = threadMxBean.getThreadInfo(stopThread.id, 1)
            val top = info?.stackTrace?.firstOrNull()
            val isVoiceSpeakerStop = top?.className == VoiceSpeaker::class.java.name &&
                (top.methodName == "stop" ||
                    (top.methodName.startsWith("\$\$robo\$\$") && top.methodName.endsWith("\$stop")))
            if (info?.threadState == Thread.State.BLOCKED && isVoiceSpeakerStop) return

            assertTrue("stop thread exited before an observable stop attempt", stopThread.isAlive)
            val remaining = deadline - System.nanoTime()
            assertTrue(
                "stop neither reached the engine nor blocked in VoiceSpeaker.stop within 2s",
                remaining > 0,
            )
            // Timed latch waits yield to the worker; the monotonic 2s deadline is
            // the failure guard. No Thread.sleep or arbitrary BLOCKED state is accepted.
            shadow.stopEntered.await(
                minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)),
                TimeUnit.NANOSECONDS,
            )
        }
    }

    private fun newSpeaker(onError: (String) -> Unit = {}): VoiceSpeaker =
        VoiceSpeaker(ApplicationProvider.getApplicationContext<Context>(), onError = onError)

    private fun currentTtsShadow(): ControlledTextToSpeechShadow {
        val engine = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        return Shadows.shadowOf(engine) as ControlledTextToSpeechShadow
    }

    private fun initSuccessfully(shadow: ControlledTextToSpeechShadow) {
        requireNotNull(shadow.onInitListener).onInit(TextToSpeech.SUCCESS)
    }
}

/** Controllable system-API shadow for deterministic init and lifecycle regressions. */
@Implements(TextToSpeech::class)
class ControlledTextToSpeechShadow : ShadowTextToSpeech() {

    val stopCalls = AtomicInteger()
    val shutdownCalls = AtomicInteger()
    val speakCalls = AtomicInteger()
    val stopEntered = CountDownLatch(1)
    val events = CopyOnWriteArrayList<String>()
    val queueModes = CopyOnWriteArrayList<Int>()

    @Volatile
    var utteranceListener: UtteranceProgressListener? = null

    @Implementation
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
        utteranceListener = listener
        return TextToSpeech.SUCCESS
    }

    @Implementation
    fun getEngines(): List<TextToSpeech.EngineInfo>? = forcedEngines

    @Implementation
    override fun __constructor__(
        context: Context,
        listener: TextToSpeech.OnInitListener,
        engine: String?,
        packageName: String?,
        useFallback: Boolean,
    ) {
        super.__constructor__(context, listener, engine, packageName, useFallback)
        if (initSynchronously) listener.onInit(TextToSpeech.SUCCESS)
    }

    @Implementation
    override fun isLanguageAvailable(locale: java.util.Locale): Int {
        if (pauseLanguageLookup) {
            languageLookupEntered?.countDown()
            continueLanguageLookup?.await(5, TimeUnit.SECONDS)
        }
        if (failLanguageLookup) return TextToSpeech.LANG_NOT_SUPPORTED
        return super.isLanguageAvailable(locale)
    }

    @Implementation
    override fun speak(
        text: CharSequence,
        queueMode: Int,
        params: Bundle,
        utteranceId: String?,
    ): Int {
        events += "speak:$text"
        queueModes += queueMode
        if (throwOnSpeak) throw RuntimeException("fake speak failure")
        if (failSpeak) return TextToSpeech.ERROR
        val result = super.speak(text, queueMode, params, utteranceId)
        if (pauseAfterFirstSpeak && speakCalls.incrementAndGet() == 1) {
            firstSpeakEntered?.countDown()
            continueSpeaking?.await(5, TimeUnit.SECONDS)
        }
        return result
    }

    @Implementation
    override fun stop(): Int {
        stopCalls.incrementAndGet()
        stopEntered.countDown()
        return super.stop()
    }

    @Implementation
    override fun shutdown() {
        shutdownCalls.incrementAndGet()
        super.shutdown()
    }

    companion object {
        @Volatile
        var initSynchronously = false

        @Volatile
        var pauseLanguageLookup = false

        @Volatile
        var pauseAfterFirstSpeak = false

        @Volatile
        var firstSpeakEntered: CountDownLatch? = null

        @Volatile
        var continueSpeaking: CountDownLatch? = null

        @Volatile
        var languageLookupEntered: CountDownLatch? = null

        @Volatile
        var continueLanguageLookup: CountDownLatch? = null

        @Volatile
        var failSpeak = false

        @Volatile
        var throwOnSpeak = false

        @Volatile
        var failLanguageLookup = false

        @Volatile
        var forcedEngines: List<TextToSpeech.EngineInfo>? = null

        fun resetControls() {
            initSynchronously = false
            pauseLanguageLookup = false
            pauseAfterFirstSpeak = false
            firstSpeakEntered = null
            continueSpeaking = null
            languageLookupEntered = null
            continueLanguageLookup = null
            failSpeak = false
            throwOnSpeak = false
            failLanguageLookup = false
            forcedEngines = null
        }
    }
}
