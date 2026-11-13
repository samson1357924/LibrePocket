package dev.librepocket.agent

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityEntryLifecycleTest {
    @Test
    fun consumedShareIsNotDispatchedAgainAfterActivityRecreation() {
        val originalLaunchIntent = shareIntent("summarize this")
        val first = launch(originalLaunchIntent)
        val savedState = Bundle()
        try {
            first.get().fakeDispatchPending()
            assertEquals(listOf("summarize this"), first.get().deliveries)
            first.saveInstanceState(savedState)
        } finally {
            destroy(first)
        }

        // Android may recreate with both the saved instance state and the old
        // launch Intent. The saved consumed state must take precedence.
        val recreated = recreate(originalLaunchIntent, savedState)
        try {
            assertNull("consumed launch share must not become pending again", recreated.get().pendingText)
            recreated.get().fakeDispatchPending()
            assertEquals(emptyList<String>(), recreated.get().deliveries)
        } finally {
            destroy(recreated)
        }
    }

    @Test
    fun pendingShareSurvivesRecreationAndTakesPrecedenceOverLaunchIntent() {
        val originalLaunchIntent = shareIntent("old launch share")
        val first = launch(originalLaunchIntent)
        val savedState = Bundle()
        try {
            first.get().deliverNewIntent(shareIntent("pending share"))
            assertEquals("pending share", first.get().pendingText)
            first.saveInstanceState(savedState)
        } finally {
            destroy(first)
        }

        val recreated = recreate(originalLaunchIntent, savedState)
        try {
            assertEquals("pending share", recreated.get().pendingText)
            recreated.get().fakeDispatchPending()
            assertEquals(listOf("pending share"), recreated.get().deliveries)
        } finally {
            destroy(recreated)
        }
    }

    @Test
    fun freshOnNewIntentWithSameTextIsDispatchedAgain() {
        val controller = launch(shareIntent("repeat me"))
        try {
            val activity = controller.get()
            activity.fakeDispatchPending()
            activity.deliverNewIntent(shareIntent("repeat me"))
            activity.fakeDispatchPending()

            assertEquals(listOf("repeat me", "repeat me"), activity.deliveries)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun invalidNewIntentDoesNotDiscardPendingShare() {
        val controller = launch(shareIntent("keep pending"))
        try {
            val activity = controller.get()
            activity.deliverNewIntent(Intent(Intent.ACTION_VIEW))

            assertEquals("keep pending", activity.pendingText)
            activity.fakeDispatchPending()
            assertEquals(listOf("keep pending"), activity.deliveries)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun assistVoiceFlag_setFromLaunchIntentAndConsumable() {
        val controller = launch(assistVoiceIntent())
        try {
            val activity = controller.get()
            assertEquals(true, activity.pendingVoice)
            activity.fakeConsumeVoice()
            assertEquals(false, activity.pendingVoice)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun assistVoiceFlag_absentForPlainAssistAndShare() {
        val plainAssist = launch(Intent(Intent.ACTION_ASSIST))
        try {
            assertEquals(false, plainAssist.get().pendingVoice)
        } finally {
            destroy(plainAssist)
        }
        val share = launch(shareIntent("hello"))
        try {
            assertEquals(false, share.get().pendingVoice)
        } finally {
            destroy(share)
        }
    }

    @Test
    fun pendingVoiceSurvivesRecreationAndConsumedStaysConsumed() {
        val first = launch(assistVoiceIntent())
        val savedState = Bundle()
        try {
            assertEquals(true, first.get().pendingVoice)
            first.saveInstanceState(savedState)
        } finally {
            destroy(first)
        }

        val recreated = recreate(assistVoiceIntent(), savedState)
        try {
            // 待消費在旋轉後保留（對應 pending share 語義）。
            assertEquals(true, recreated.get().pendingVoice)
            recreated.get().fakeConsumeVoice()
            assertEquals(false, recreated.get().pendingVoice)
        } finally {
            destroy(recreated)
        }
    }

    @Test
    fun consumedVoiceIsNotRedeliveredAfterRecreation() {
        val first = launch(assistVoiceIntent())
        val savedState = Bundle()
        try {
            first.get().fakeConsumeVoice()
            first.saveInstanceState(savedState)
        } finally {
            destroy(first)
        }

        // 已消費＋舊 launch Intent 同時出現時，已消費快照優先，不復活。
        val recreated = recreate(assistVoiceIntent(), savedState)
        try {
            assertEquals(false, recreated.get().pendingVoice)
        } finally {
            destroy(recreated)
        }
    }

    @Test
    fun nonVoiceNewIntentDoesNotClearPendingVoice() {
        val controller = launch(assistVoiceIntent())
        try {
            val activity = controller.get()
            activity.deliverNewIntent(Intent(Intent.ACTION_VIEW))
            assertEquals(true, activity.pendingVoice)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun savedInstanceStateWithoutAssistVoiceKeyFallsBackToLaunchIntent() {
        val savedState = Bundle()
        val recreated = recreate(assistVoiceIntent(), savedState)
        try {
            assertEquals(true, recreated.get().pendingVoice)
        } finally {
            destroy(recreated)
        }
    }

    @Test
    fun assistVoiceLaunchedFromHistoryDoesNotSetPendingVoice() {
        val historyIntent = assistVoiceIntent()
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val controller = launch(historyIntent)
        try {
            assertEquals(false, controller.get().pendingVoice)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun untrustedMainActivity_rejectsAssistVoiceIntent() {
        val controller = launch(assistVoiceIntent(), trustedVoiceSession = false)
        try {
            assertEquals(false, controller.get().pendingVoice)
        } finally {
            destroy(controller)
        }

        // Default MainActivity.isTrustedVoiceSession() relies on Activity.isVoiceInteractionRoot
        // (false when not launched by VoiceInteractionSession.startVoiceActivity).
        val defaultController = Robolectric.buildActivity(DefaultTrustFakeMainActivity::class.java, assistVoiceIntent()).setup()
        try {
            assertEquals(false, defaultController.get().isVoiceInteractionRoot)
            assertEquals(false, defaultController.get().pendingVoice)
        } finally {
            defaultController.pause().stop().destroy()
        }
    }

    @Test
    fun untrustedNewIntent_doesNotArmPendingVoice() {
        val controller = launch(Intent(Intent.ACTION_MAIN), trustedVoiceSession = false)
        try {
            val activity = controller.get()
            assertEquals(false, activity.pendingVoice)
            activity.deliverNewIntent(assistVoiceIntent())
            assertEquals(false, activity.pendingVoice)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun onNewIntent_assistVoiceAfterConsumed_doesNotRearmPendingVoice() {
        val controller = launch(assistVoiceIntent(), trustedVoiceSession = true)
        try {
            val activity = controller.get()
            assertEquals(true, activity.pendingVoice)
            activity.fakeConsumeVoice()
            assertEquals(false, activity.pendingVoice)

            // Voice auto-launch is not re-armed from onNewIntent deliveries.
            activity.deliverNewIntent(assistVoiceIntent())
            assertEquals(false, activity.pendingVoice)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun onNewIntent_externalShapedAssistVoiceToTrustedRoot_doesNotArmPendingVoice() {
        // Policy-level regression: even when the existing activity is a trusted
        // voice-root session, subsequent onNewIntent deliveries do not re-arm
        // assist voice because per-intent provenance is not verified here.
        val controller = launch(Intent(Intent.ACTION_MAIN), trustedVoiceSession = true)
        try {
            val activity = controller.get()
            assertEquals(false, activity.pendingVoice)
            activity.deliverNewIntent(assistVoiceIntent())
            assertEquals(false, activity.pendingVoice)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun restoreWithoutAssistVoiceKey_historyFlaggedLaunchIntent_doesNotSetPendingVoice() {
        val savedState = Bundle()
        val historyIntent = assistVoiceIntent()
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val recreated = recreate(historyIntent, savedState)
        try {
            assertEquals(false, recreated.get().pendingVoice)
        } finally {
            destroy(recreated)
        }
    }

    @Test
    fun restoreWithoutAssistVoiceKey_freshTrustedLaunchIntent_setsPendingVoice() {
        val savedState = Bundle()
        val recreated = recreate(assistVoiceIntent(), savedState)
        try {
            assertEquals(true, recreated.get().pendingVoice)
        } finally {
            destroy(recreated)
        }
    }

    @Test
    fun onNewIntent_plainAssistWithoutFlag_doesNotRearmOrClear() {
        val controller = launch(assistVoiceIntent(), trustedVoiceSession = true)
        try {
            val activity = controller.get()
            assertEquals(true, activity.pendingVoice)
            activity.deliverNewIntent(Intent(Intent.ACTION_ASSIST))
            assertEquals(true, activity.pendingVoice)

            activity.fakeConsumeVoice()
            assertEquals(false, activity.pendingVoice)
            activity.deliverNewIntent(Intent(Intent.ACTION_ASSIST))
            assertEquals(false, activity.pendingVoice)
        } finally {
            destroy(controller)
        }
    }

    @Test
    fun nonAssistIntentWithVoiceExtra_ignored() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_MAIN, Intent.ACTION_VIEW)) {
            val intent = Intent(action)
                .putExtra(dev.librepocket.agent.assistant.AssistantVoice.EXTRA_VOICE_INPUT, true)
            val controller = launch(intent, trustedVoiceSession = true)
            try {
                assertEquals("launch with $action must not arm pendingVoice", false, controller.get().pendingVoice)
            } finally {
                destroy(controller)
            }

            val warm = launch(Intent(Intent.ACTION_MAIN), trustedVoiceSession = true)
            try {
                warm.get().deliverNewIntent(intent)
                assertEquals("onNewIntent with $action must not arm pendingVoice", false, warm.get().pendingVoice)
            } finally {
                destroy(warm)
            }
        }
    }

    @Test
    fun malformedVoiceExtraTypes_ignored() {
        val stringExtra = Intent(Intent.ACTION_ASSIST)
            .putExtra(dev.librepocket.agent.assistant.AssistantVoice.EXTRA_VOICE_INPUT, "true")
        val intExtra = Intent(Intent.ACTION_ASSIST)
            .putExtra(dev.librepocket.agent.assistant.AssistantVoice.EXTRA_VOICE_INPUT, 1)
        for (malformed in listOf(stringExtra, intExtra)) {
            val controller = launch(malformed, trustedVoiceSession = true)
            try {
                assertEquals(false, controller.get().pendingVoice)
            } finally {
                destroy(controller)
            }

            val warm = launch(Intent(Intent.ACTION_MAIN), trustedVoiceSession = true)
            try {
                warm.get().deliverNewIntent(malformed)
                assertEquals(false, warm.get().pendingVoice)
            } finally {
                destroy(warm)
            }
        }
    }

    @Test
    fun savedInstanceStateWithAssistVoiceTrue_ignoredWhenUntrustedVoiceSession() {
        val first = launch(assistVoiceIntent(), trustedVoiceSession = true)
        val savedState = Bundle()
        try {
            assertEquals(true, first.get().pendingVoice)
            first.saveInstanceState(savedState)
        } finally {
            destroy(first)
        }

        val recreated = recreate(assistVoiceIntent(), savedState, trustedVoiceSession = false)
        try {
            assertEquals(false, recreated.get().pendingVoice)
        } finally {
            destroy(recreated)
        }
    }

    private fun launch(
        intent: Intent,
        trustedVoiceSession: Boolean = true,
    ): ActivityController<FakeDispatchMainActivity> =
        Robolectric.buildActivity(FakeDispatchMainActivity::class.java, intent)
            .apply { get().trustedVoiceSession = trustedVoiceSession }
            .setup()

    private fun recreate(
        originalLaunchIntent: Intent,
        savedState: Bundle,
        trustedVoiceSession: Boolean = true,
    ): ActivityController<FakeDispatchMainActivity> =
        Robolectric.buildActivity(FakeDispatchMainActivity::class.java, originalLaunchIntent)
            .apply { get().trustedVoiceSession = trustedVoiceSession }
            .create(savedState)
            .start()
            .resume()

    private fun destroy(controller: ActivityController<FakeDispatchMainActivity>) {
        controller.pause().stop().destroy()
    }

    private fun shareIntent(text: String) = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)

    private fun assistVoiceIntent() = Intent(Intent.ACTION_ASSIST)
        .putExtra(dev.librepocket.agent.assistant.AssistantVoice.EXTRA_VOICE_INPUT, true)
}

/** Captures MainActivity's existing acknowledgement callback without product composition. */
open class FakeDispatchMainActivity : MainActivity() {
    private lateinit var sharedText: () -> String?
    private lateinit var onSharedConsumed: () -> Unit
    private lateinit var voiceInputRequested: () -> Boolean
    private lateinit var onVoiceInputConsumed: () -> Unit
    val deliveries = mutableListOf<String>()
    var trustedVoiceSession: Boolean = true

    override fun isTrustedVoiceSession(): Boolean = trustedVoiceSession

    override fun installMainScreenContent(
        sharedText: () -> String?,
        onSharedConsumed: () -> Unit,
        voiceInputRequested: () -> Boolean,
        onVoiceInputConsumed: () -> Unit,
    ) {
        this.sharedText = sharedText
        this.onSharedConsumed = onSharedConsumed
        this.voiceInputRequested = voiceInputRequested
        this.onVoiceInputConsumed = onVoiceInputConsumed
    }

    val pendingText: String?
        get() = sharedText()

    val pendingVoice: Boolean
        get() = voiceInputRequested()

    fun deliverNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    fun fakeDispatchPending() {
        val text = pendingText ?: return
        deliveries += text
        onSharedConsumed()
    }

    fun fakeConsumeVoice() {
        onVoiceInputConsumed()
    }
}

/** Uses MainActivity's default isTrustedVoiceSession() (isVoiceInteractionRoot). */
class DefaultTrustFakeMainActivity : MainActivity() {
    private lateinit var voiceInputRequested: () -> Boolean

    override fun installMainScreenContent(
        sharedText: () -> String?,
        onSharedConsumed: () -> Unit,
        voiceInputRequested: () -> Boolean,
        onVoiceInputConsumed: () -> Unit,
    ) {
        this.voiceInputRequested = voiceInputRequested
    }

    val pendingVoice: Boolean
        get() = voiceInputRequested()
}
