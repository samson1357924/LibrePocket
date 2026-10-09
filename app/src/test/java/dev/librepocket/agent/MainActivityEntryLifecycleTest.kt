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
}

/** Captures MainActivity's existing acknowledgement callback without product composition. */
class FakeDispatchMainActivity : MainActivity() {
    private lateinit var sharedText: () -> String?
    private lateinit var onSharedConsumed: () -> Unit
    val deliveries = mutableListOf<String>()

    override fun installMainScreenContent(
        sharedText: () -> String?,
        onSharedConsumed: () -> Unit,
    ) {
        this.sharedText = sharedText
        this.onSharedConsumed = onSharedConsumed
    }

    val pendingText: String?
        get() = sharedText()

    fun deliverNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    fun fakeDispatchPending() {
        val text = pendingText ?: return
        deliveries += text
        onSharedConsumed()
    }
}
