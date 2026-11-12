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

    private fun launch(intent: Intent): ActivityController<FakeDispatchMainActivity> =
        Robolectric.buildActivity(FakeDispatchMainActivity::class.java, intent).setup()

    private fun recreate(
        originalLaunchIntent: Intent,
        savedState: Bundle,
    ): ActivityController<FakeDispatchMainActivity> =
        Robolectric.buildActivity(FakeDispatchMainActivity::class.java, originalLaunchIntent)
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
class FakeDispatchMainActivity : MainActivity() {
    private lateinit var sharedText: () -> String?
    private lateinit var onSharedConsumed: () -> Unit
    private lateinit var voiceInputRequested: () -> Boolean
    private lateinit var onVoiceInputConsumed: () -> Unit
    val deliveries = mutableListOf<String>()

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
