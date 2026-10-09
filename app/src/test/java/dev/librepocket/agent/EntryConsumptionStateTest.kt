package dev.librepocket.agent

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EntryConsumptionStateTest {
    @Test
    fun snapshotlessSavedStateFallsBackToLaunchIntent() {
        val restoredFromNull = EntryConsumptionState.restore(
            savedInstanceState = null,
            launchText = "legacy launch share",
        )
        val restoredFromEmptyBundle = EntryConsumptionState.restore(
            savedInstanceState = Bundle(),
            launchText = "legacy launch share",
        )

        assertEquals("legacy launch share", restoredFromNull.pendingText)
        assertEquals("legacy launch share", restoredFromEmptyBundle.pendingText)
    }

    @Test
    fun pendingSnapshotTakesPrecedenceOverStaleLaunchIntent() {
        val savedState = Bundle()
        EntryConsumptionState("new pending share").saveTo(savedState)

        val restored = EntryConsumptionState.restore(
            savedInstanceState = savedState,
            launchText = "stale launch share",
        )

        assertEquals("new pending share", restored.pendingText)
    }

    @Test
    fun consumedSnapshotSuppressesStaleLaunchIntent() {
        val savedState = Bundle()
        EntryConsumptionState("already sent").consume().saveTo(savedState)

        val restored = EntryConsumptionState.restore(
            savedInstanceState = savedState,
            launchText = "already sent",
        )

        assertNull(restored.pendingText)
    }

    @Test
    fun consumingIsIdempotent() {
        val consumed = EntryConsumptionState("one share").consume()

        assertEquals(consumed, consumed.consume())
        assertEquals(consumed, consumed.consume().consume())
    }
}
