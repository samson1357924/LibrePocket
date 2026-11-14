package dev.librepocket.agent.assistant

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibreVoiceInteractionSessionTest {

    @Before
    fun setUp() {
        AssistantVoiceHandoff.clear()
        VoiceHandoffProvider.resetForTest()
        ShadowSystemClock.reset()
    }

    @After
    fun tearDown() {
        AssistantVoiceHandoff.clear()
        VoiceHandoffProvider.resetForTest()
        ShadowSystemClock.reset()
    }

    private class RecordingVoiceSession(
        context: Context,
        private val throwOnStart: Boolean = false,
    ) : LibreVoiceInteractionSession(context) {
        val uiEnabledCalls = mutableListOf<Boolean>()
        val startedIntents = mutableListOf<Intent>()
        val startedTaskIds = mutableListOf<Int>()
        val keepAwakeCalls = mutableListOf<Boolean>()
        var hideCalls = 0
        var throwOnKeepAwakeOnce: Boolean = false

        override fun setUiEnabled(enabled: Boolean) {
            uiEnabledCalls += enabled
        }

        override fun setKeepAwake(keepAwake: Boolean) {
            keepAwakeCalls += keepAwake
            if (throwOnKeepAwakeOnce) {
                throwOnKeepAwakeOnce = false
                throw RuntimeException("simulated keepAwake failure")
            }
        }

        override fun startVoiceActivity(intent: Intent?) {
            if (intent != null) {
                startedIntents += intent
            }
            if (throwOnStart) {
                throw RuntimeException("simulated startVoiceActivity failure")
            }
        }

        override fun onTaskStarted(intent: Intent?, taskId: Int) {
            startedTaskIds += taskId
            super.onTaskStarted(intent, taskId)
        }

        override fun hide() {
            hideCalls++
        }
    }

    @Test
    fun onPrepareShow_disablesUi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onPrepareShow(null, 0)

        assertEquals(listOf(false), session.uiEnabledCalls)
    }

    @Test
    fun onShow_startsExplicitMainActivityWithAssistVoiceFlag_withoutImmediateHide() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)

        assertEquals(1, session.startedIntents.size)
        val intent = session.startedIntents.single()
        assertEquals(Intent.ACTION_ASSIST, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(AssistantVoice.isVoiceInputRequested(intent))
        // Successful start must keep the session alive for the delegated voice task/activity flow.
        assertEquals(0, session.hideCalls)
        // Each successful start re-acquires the default voice wake lock; release it for long-lived C2 task.
        assertTrue(session.keepAwakeCalls.contains(false))
        assertEquals(false, session.keepAwakeCalls.last())
    }

    @Test
    fun keepAwakeFailureAfterSuccessfulStart_doesNotHideSessionOrLoseTaskBinding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)
        session.throwOnKeepAwakeOnce = true
        try {
            // setKeepAwake(false) after a successful start throws; the success-path runCatching
            // must swallow it — no exception escapes, the voice task stays alive and bound.
            session.onShow(null, 0)

            assertEquals(1, session.startedIntents.size)
            assertEquals(0, session.hideCalls)
            assertTrue(session.keepAwakeCalls.contains(false))
        } finally {
            session.throwOnKeepAwakeOnce = false
        }
    }

    @Test
    fun onShow_whenStartVoiceActivityThrows_hidesFailClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context, throwOnStart = true)

        session.onShow(null, 0)

        assertEquals(1, session.startedIntents.size)
        assertEquals(1, session.hideCalls)
        // Best-effort convergence: failed start path must not leave wake lock held.
        assertTrue(session.keepAwakeCalls.contains(false))
    }

    @Test
    fun onTaskFinished_hidesSessionOnceForActiveTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        session.onTaskStarted(null, 42)
        assertEquals(0, session.hideCalls)

        session.onTaskFinished(null, 42)

        assertEquals(1, session.hideCalls)
        // Idempotent convergence on task completion.
        assertTrue(session.keepAwakeCalls.contains(false))
    }

    @Test
    fun duplicateOnShow_whileActiveTaskStarted_isCoalesced_andActiveTaskCanFinish() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        session.onTaskStarted(null, 10)

        // Immediate duplicate dispatch within debounce window: coalesce (no retrigger handoff).
        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        assertEquals(0, session.hideCalls)
        assertEquals(0L, AssistantVoiceHandoff.peek(context))

        // Non-active callbacks must not break the active request.
        session.onTaskFinished(null, 999)
        assertEquals(0, session.hideCalls)

        session.onTaskFinished(null, 10)
        assertEquals(1, session.hideCalls)
    }

    @Test
    fun newOnShow_whileActiveTaskStarted_requestsRetriggerHandoffWithoutKillingTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        session.onTaskStarted(null, 10)
        AssistantVoiceHandoff.clear(context)

        // Later real gesture (after debounce window): accept retrigger via cross-process handoff,
        // keep active task alive, and do NOT create/start a new voice task (F1/F2).
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        val pending = AssistantVoiceHandoff.currentPending(context)
        assertNotEquals(null, pending)
        assertNotEquals(0L, pending!!.token)
        assertEquals(10, pending.targetTaskId)
        assertEquals(0, session.hideCalls)

        // Simulate UI consuming retrigger handoff; active task continues.
        assertTrue(AssistantVoiceHandoff.consume(pending.token, context))
        session.onTaskFinished(null, 10)
        assertEquals(1, session.hideCalls)
    }

    @Test
    fun duplicateOnShow_whileLaunchPending_isIgnored_untilTaskStarts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)

        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        assertEquals(0, session.hideCalls)

        session.onTaskStarted(null, 10)
        session.onTaskFinished(null, 10)
        assertEquals(1, session.hideCalls)
    }

    @Test
    fun onTaskFinished_forNonActiveTask_isIgnored() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        session.onTaskStarted(null, 10)

        session.onTaskFinished(null, 999)
        assertEquals(0, session.hideCalls)

        session.onTaskFinished(null, 10)
        assertEquals(1, session.hideCalls)
    }

    @Test
    fun interleaving_immediateDuplicateCoalesced_andExtraTaskStartCannotKillActive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0) // A
        session.onTaskStarted(null, 10)
        session.onShow(null, 0) // immediate duplicate/coalesced, must not retrigger or start new task
        assertEquals(1, session.startedIntents.size)
        assertEquals(0L, AssistantVoiceHandoff.peek(context))

        // If an extra task start arrives anyway it must not become active or finish session.
        session.onTaskStarted(null, 11)
        assertEquals(listOf(10, 11), session.startedTaskIds)
        session.onTaskFinished(null, 11)
        assertEquals(0, session.hideCalls)

        session.onTaskFinished(null, 10)
        assertEquals(1, session.hideCalls)
    }

    @Test
    fun laterOnShow_whileActiveTask_requestsRetriggerAndDoesNotLetOldFinishKillActiveRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0) // A
        session.onTaskStarted(null, 10)
        AssistantVoiceHandoff.clear(context)

        // B: later real gesture while same voice task/activity remains active.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size) // no new voice task/start
        val pending = AssistantVoiceHandoff.currentPending(context)
        assertNotEquals(null, pending)
        assertEquals(10, pending!!.targetTaskId)
        assertEquals(0, session.hideCalls)

        // Extra task callbacks must not take over or end the active request.
        session.onTaskStarted(null, 11)
        session.onTaskFinished(null, 11)
        assertEquals(0, session.hideCalls)

        // Active task (A/10) finishing ends the session once.
        session.onTaskFinished(null, 10)
        assertEquals(1, session.hideCalls)
        assertEquals(0L, AssistantVoiceHandoff.peek(context))
    }

    @Test
    fun retriggerWhileHandoffPending_isReplacedByLaterRealGesture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        session.onTaskStarted(null, 10)
        AssistantVoiceHandoff.clear(context)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        val token = AssistantVoiceHandoff.peek(context)
        assertNotEquals(0L, token)

        // Later real gesture replaces unconsumed token so requests are not dropped.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        val replaced = AssistantVoiceHandoff.peek(context)
        assertNotEquals(0L, replaced)
        assertNotEquals(token, replaced)
    }

    @Test
    fun retriggerPublishesHandoff_andSubsequentRealGestureIsNotDropped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)
        session.onShow(null, 0)
        session.onTaskStarted(null, 10)
        AssistantVoiceHandoff.clear(context)

        // Later real gesture: retrigger accepted, no new voice task started.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        val first = AssistantVoiceHandoff.currentPending(context)
        assertNotEquals(null, first)
        assertEquals(10, first!!.targetTaskId)
        assertEquals(0, session.hideCalls)

        // Another real gesture after debounce window must still be accepted (replaced token), not dropped.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        val second = AssistantVoiceHandoff.currentPending(context)
        assertNotEquals(null, second)
        assertNotEquals(first.token, second!!.token)
        assertEquals(0, session.hideCalls)

        session.onTaskFinished(null, 10)
        assertEquals(1, session.hideCalls)
    }


    @Test
    fun startFailure_clearsPendingSoLaterOnShowCanStartAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context, throwOnStart = true)

        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        assertEquals(1, session.hideCalls)

        // Simulate next cycle after failure cleanup/hide.
        session.onHide()
        session.onShow(null, 0)
        // throwOnStart still true, but proves a new launch attempt was made (not blocked as pending).
        assertEquals(2, session.startedIntents.size)
        assertEquals(2, session.hideCalls)
    }

    @Test
    fun afterTaskCompletes_newOnShowCanStartNewTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        session.onTaskStarted(null, 10)
        session.onTaskFinished(null, 10)
        assertEquals(1, session.startedIntents.size)
        assertEquals(1, session.hideCalls)

        // Framework/session hide transition may occur; next show must be able to start fresh.
        session.onHide()
        session.onShow(null, 0)
        assertEquals(2, session.startedIntents.size)
        // Each accepted start must release the re-acquired default voice wake lock.
        assertEquals(false, session.keepAwakeCalls.last())

        session.onTaskStarted(null, 20)
        session.onTaskFinished(null, 20)
        assertEquals(2, session.hideCalls)
        assertTrue(session.keepAwakeCalls.contains(false))
    }

    @Test
    fun retriggerPublishFailure_resetsDebounceSoImmediateRetryIsAccepted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)
        val defaultPublisher = session.retriggerPublisher
        var publisherCalls = 0
        session.retriggerPublisher = { _, _, _ ->
            publisherCalls++
            0L // simulated IPC/publish failure
        }
        try {
            session.onShow(null, 0)
            session.onTaskStarted(null, 10)

            ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
            session.onShow(null, 0)
            assertEquals(1, publisherCalls)

            // A failed publish must not stick the debounce: the very next gesture retries immediately.
            session.onShow(null, 0)
            assertEquals(2, publisherCalls)
            assertEquals(0, session.hideCalls)
        } finally {
            session.retriggerPublisher = defaultPublisher
        }
    }

    @Test
    fun retriggerPublishSuccess_appliesDebounceWithBoundary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)
        val defaultPublisher = session.retriggerPublisher
        var publisherCalls = 0
        session.retriggerPublisher = { _, _, _ ->
            publisherCalls++
            4242L // publish accepted with a real token
        }
        try {
            session.onShow(null, 0)
            session.onTaskStarted(null, 10)

            ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
            session.onShow(null, 0)
            assertEquals(1, publisherCalls)

            // Inside the 350ms duplicate window: coalesced, no publish.
            session.onShow(null, 0)
            assertEquals(1, publisherCalls)

            ShadowSystemClock.advanceBy(Duration.ofMillis(349))
            session.onShow(null, 0)
            assertEquals(1, publisherCalls)

            ShadowSystemClock.advanceBy(Duration.ofMillis(1)) // exactly 350ms since accepted retrigger
            session.onShow(null, 0)
            assertEquals(2, publisherCalls)
        } finally {
            session.retriggerPublisher = defaultPublisher
        }
    }

    @Test
    fun onHide_thenOnShow_startsFreshCycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        val firstSid = session.startedIntents.single()
            .getLongExtra(AssistantVoice.EXTRA_SESSION_INSTANCE_ID, 0L)
        assertNotEquals(0L, firstSid)

        session.onHide()
        session.onShow(null, 0)

        assertEquals(2, session.startedIntents.size)
        assertEquals(0, session.hideCalls)
        val secondSid = session.startedIntents[1]
            .getLongExtra(AssistantVoice.EXTRA_SESSION_INSTANCE_ID, 0L)
        assertTrue("fresh cycle must mint a new session instance id", secondSid > firstSid)
    }

    @Test
    fun onTaskFinished_twice_hidesOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        session.onTaskStarted(null, 42)
        session.onTaskFinished(null, 42)
        session.onTaskFinished(null, 42)

        assertEquals(1, session.hideCalls)
        assertEquals(false, session.keepAwakeCalls.last())
    }

    @Test
    fun onHide_afterTaskFinished_noDoubleFinish() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        session.onTaskStarted(null, 42)
        session.onTaskFinished(null, 42)
        session.onHide()

        assertEquals(1, session.hideCalls)
    }

    @Test
    fun onTaskFinished_whileLaunchPending_finishesCurrentRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        assertEquals(1, session.startedIntents.size)
        assertEquals(0, session.hideCalls)

        // Finish arrives before onTaskStarted bound taskId: treat as completion of current request.
        session.onTaskFinished(null, 77)
        assertEquals(1, session.hideCalls)

        // Late start/finish for that request must not re-hide; new cycle should work.
        session.onTaskStarted(null, 77)
        session.onTaskFinished(null, 77)
        assertEquals(1, session.hideCalls)

        session.onHide()
        session.onShow(null, 0)
        assertEquals(2, session.startedIntents.size)
    }
}
