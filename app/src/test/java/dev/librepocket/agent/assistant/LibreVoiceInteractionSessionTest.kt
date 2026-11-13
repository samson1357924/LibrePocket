package dev.librepocket.agent.assistant

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibreVoiceInteractionSessionTest {

    private class RecordingVoiceSession(
        context: Context,
        private val throwOnStart: Boolean = false,
    ) : LibreVoiceInteractionSession(context) {
        val uiEnabledCalls = mutableListOf<Boolean>()
        val startedIntents = mutableListOf<Intent>()
        var hideCalls = 0

        override fun setUiEnabled(enabled: Boolean) {
            uiEnabledCalls += enabled
        }

        override fun startVoiceActivity(intent: Intent?) {
            if (intent != null) {
                startedIntents += intent
            }
            if (throwOnStart) {
                throw RuntimeException("simulated startVoiceActivity failure")
            }
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
    }

    @Test
    fun onShow_whenStartVoiceActivityThrows_hidesFailClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context, throwOnStart = true)

        session.onShow(null, 0)

        assertEquals(1, session.startedIntents.size)
        assertEquals(1, session.hideCalls)
    }

    @Test
    fun onTaskFinished_hidesSessionOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = RecordingVoiceSession(context)

        session.onShow(null, 0)
        assertEquals(0, session.hideCalls)

        session.onTaskFinished(null, 42)

        assertEquals(1, session.hideCalls)
    }
}
