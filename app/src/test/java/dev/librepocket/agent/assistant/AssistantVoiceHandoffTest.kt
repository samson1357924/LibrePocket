package dev.librepocket.agent.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.Routes
import dev.librepocket.agent.ui.shouldDeliverVoiceRetrigger
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantVoiceHandoffTest {

    private val appContext: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        VoiceHandoffProvider.resetForTest()
        ShadowSystemClock.reset()
    }

    @After
    fun tearDown() {
        VoiceHandoffProvider.resetForTest()
        ShadowSystemClock.reset()
    }

    /** Swaps the manifest-declared handoff provider for [replacement] (probe: replacement wins). */
    private fun swapHandoffProvider(replacement: ContentProvider) {
        val info = ProviderInfo().apply { authority = authority() }
        replacement.attachInfo(appContext, info)
        ShadowContentResolver.registerProviderInternal(authority(), replacement)
    }

    /** Restores a fresh real provider after [swapHandoffProvider] (registration persists per sandbox). */
    private fun restoreManifestProvider() {
        val fresh = VoiceHandoffProvider()
        val info = ProviderInfo().apply { authority = authority() }
        fresh.attachInfo(appContext, info)
        ShadowContentResolver.registerProviderInternal(authority(), fresh)
    }

    private fun authority(): String = "${appContext.packageName}.voice.handoff"

    /** Provider stub whose call() returns null for METHOD_PUBLISH: simulates a publish with no ack. */
    private class NullAckProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            if (method == VoiceHandoffContract.METHOD_PUBLISH) null else throw RuntimeException("unexpected call: $method")

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    /** Provider stub that records every (method, extras) call instead of handling it. */
    private class CountingProvider : ContentProvider() {
        val calls = mutableListOf<Pair<String, Bundle?>>()

        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            calls += method to extras
            return null
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    /** Provider stub whose call() throws for every method: simulates an IPC/publish failure. */
    private class ThrowingProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            throw RuntimeException("boom from $method")

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun seedPending(token: Long, issuedElapsedRealtimeMs: Long, targetTaskId: Int = 10, sessionInstanceId: Long = 7L) {
        assertTrue(
            VoiceHandoffProvider.publish(
                PendingVoiceRetrigger(
                    token = token,
                    targetTaskId = targetTaskId,
                    sessionInstanceId = sessionInstanceId,
                    issuedElapsedRealtimeMs = issuedElapsedRealtimeMs,
                ),
            ),
        )
    }

    @Test
    fun clear_singleAxisTaskIdentity_wildcardSidAxisMatchesAndClears_ipcBundleCarriesTaskKeyOnly() {
        seedPending(token = 970L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)

        // Local-only assertion: single task axis, wildcard sid → matches the seeded entry.
        AssistantVoiceHandoff.clear(context = null, targetTaskId = 10, sessionInstanceId = VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID)
        assertNull("single-axis identity must wildcard-match the other axis", VoiceHandoffProvider.pending.value)

        // Mismatched task axis: entry preserved.
        seedPending(token = 971L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)
        AssistantVoiceHandoff.clear(context = null, targetTaskId = 11, sessionInstanceId = VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID)
        assertEquals(971L, VoiceHandoffProvider.pending.value?.token)

        // Wire shape: exactly the task key is put on the bundle; the wildcard sid axis is omitted.
        VoiceHandoffProvider.resetForTest()
        val counting = CountingProvider()
        swapHandoffProvider(counting)
        try {
            AssistantVoiceHandoff.clear(context = appContext, targetTaskId = 10, sessionInstanceId = VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID)
            val clearCall = counting.calls.last { it.first == VoiceHandoffContract.METHOD_CLEAR }
            assertEquals(10, clearCall.second?.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID))
            assertFalse(clearCall.second!!.containsKey(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID))
        } finally {
            restoreManifestProvider()
        }
    }

    @Test
    fun requestRetrigger_success_returnsNonzeroToken_andPublishes() {
        val token = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)

        assertNotEquals(0L, token)
        val pending = AssistantVoiceHandoff.currentPending(appContext)
        assertNotNull("successful publish must be visible to the facade", pending)
        assertEquals(token, pending?.token)
        assertEquals(10, pending?.targetTaskId)
        assertEquals(7L, pending?.sessionInstanceId)
    }

    @Test
    fun requestRetrigger_failure_returnsZero_andLeavesSameCycleOlderToken() {
        // Pre-seed an older pending entry from the same session cycle (10/7L, different token);
        // the failure cleanup must not wipe it.
        seedPending(token = 999L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)

        // Probe-verified: registering a replacement provider under the manifest authority replaces
        // dispatch, so this models "IPC blew up" deterministically for the publish call.
        swapHandoffProvider(ThrowingProvider())
        try {
            val token = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)

            assertEquals(0L, token)
            assertEquals("token-matched cleanup must not wipe an older pending token from the same session cycle", 999L, VoiceHandoffProvider.pending.value?.token)
            assertEquals(999L, AssistantVoiceHandoff.currentPending(appContext)?.token)
        } finally {
            restoreManifestProvider()
        }
    }

    @Test
    fun requestRetrigger_nullAck_returnsZero_andLeavesSameCycleOlderToken() {
        // Pre-seed an older pending entry from the same session cycle (10/7L, different token);
        // the null-ack cleanup must not wipe it.
        seedPending(token = 999L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)

        // Probe-verified: registering a replacement provider under the manifest authority replaces
        // dispatch, so this models "publish with no ack" deterministically for the publish call.
        swapHandoffProvider(NullAckProvider())
        try {
            val token = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)

            assertEquals(0L, token)
            assertEquals("token-matched cleanup must not wipe an older pending token from the same session cycle", 999L, VoiceHandoffProvider.pending.value?.token)
            assertEquals(999L, AssistantVoiceHandoff.currentPending(appContext)?.token)
        } finally {
            restoreManifestProvider()
        }
    }

    @Test
    fun currentPending_filtersExpiredAndFutureEntries() {
        val now = SystemClock.elapsedRealtime()

        // Expired (older than RETRIGGER_TTL_MS).
        seedPending(token = 1L, issuedElapsedRealtimeMs = now - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)
        assertNull(AssistantVoiceHandoff.currentPending(appContext))

        // Future-dated (clock skew / bogus timestamp): negative age, treated as absent.
        VoiceHandoffProvider.resetForTest()
        seedPending(token = 2L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() + 5_000L)
        assertNull(AssistantVoiceHandoff.currentPending(appContext))

        // Fresh entry within TTL: visible.
        VoiceHandoffProvider.resetForTest()
        seedPending(token = 3L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime())
        assertEquals(3L, AssistantVoiceHandoff.currentPending(appContext)?.token)
    }

    @Test
    fun consume_withMatchingAndMismatchedIdentity() {
        val token = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)
        assertNotEquals(0L, token)

        // Wrong taskId: rejected, entry survives.
        assertFalse(AssistantVoiceHandoff.consume(token, appContext, targetTaskId = 11, sessionInstanceId = 7L))
        assertEquals(token, AssistantVoiceHandoff.currentPending(appContext)?.token)

        // Matching identity: consumed.
        assertTrue(AssistantVoiceHandoff.consume(token, appContext, targetTaskId = 10, sessionInstanceId = 7L))
        assertNull(AssistantVoiceHandoff.currentPending(appContext))

        // Already consumed: false.
        assertFalse(AssistantVoiceHandoff.consume(token, appContext, targetTaskId = 10, sessionInstanceId = 7L))
    }

    // Note on the spec's literal "consume(T1) == true after publishing T1 then T2": impossible with a
    // single pending slot (T2 overwrites T1). The pinned property here is that stale-token
    // consume/clear never touches the newer entry; the "consuming a token succeeds exactly once"
    // half is pinned in the second half of this test.
    @Test
    fun consumeAndClear_areTokenMatched_staleTokensNeverHitNewerEntry() {
        // Single pending slot: publishing T2 overwrites T1 (same target/sid).
        val t1 = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)
        val t2 = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)
        assertNotEquals(0L, t1)
        assertNotEquals(0L, t2)
        assertNotEquals(t1, t2)
        assertEquals(t2, AssistantVoiceHandoff.currentPending(appContext)?.token)

        // Consuming the stale token T1 fails and must not hit T2.
        assertFalse(AssistantVoiceHandoff.consume(t1, appContext, targetTaskId = 10, sessionInstanceId = 7L))
        assertEquals(t2, AssistantVoiceHandoff.currentPending(appContext)?.token)

        // Clearing with the stale token T1 leaves T2 intact.
        AssistantVoiceHandoff.clear(context = appContext, targetTaskId = 10, sessionInstanceId = 7L, token = t1)
        assertEquals(t2, AssistantVoiceHandoff.currentPending(appContext)?.token)

        // Clearing with the matching token removes T2.
        AssistantVoiceHandoff.clear(context = appContext, targetTaskId = 10, sessionInstanceId = 7L, token = t2)
        assertNull(AssistantVoiceHandoff.currentPending(appContext))

        // Second half: consuming T1 succeeds once while it is current, then a clear with the
        // already-consumed T1 still must not hit the newer T2.
        VoiceHandoffProvider.resetForTest()
        val c1 = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)
        assertTrue(AssistantVoiceHandoff.consume(c1, appContext, targetTaskId = 10, sessionInstanceId = 7L))
        val c2 = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)
        assertEquals(c2, AssistantVoiceHandoff.currentPending(appContext)?.token)
        AssistantVoiceHandoff.clear(context = appContext, targetTaskId = 10, sessionInstanceId = 7L, token = c1)
        assertEquals(c2, AssistantVoiceHandoff.currentPending(appContext)?.token)
        AssistantVoiceHandoff.clear(context = appContext, targetTaskId = 10, sessionInstanceId = 7L, token = c2)
        assertNull(AssistantVoiceHandoff.currentPending(appContext))
    }

    @Test
    fun successfulConsume_makesNoIpcCalls() {
        // Pins "zero IPC of any method during a successful local consume" — stronger than the
        // minimum (post-CAS METHOD_CLEAR). This is an intentional lock, not an accident: if a
        // future change adds any provider call to the success path, this test must fail.
        // Pre-seed a fresh pending entry (companion publish is in-process, no IPC).
        seedPending(token = 999L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)

        val counting = CountingProvider()
        swapHandoffProvider(counting)
        try {
            assertTrue(AssistantVoiceHandoff.consume(999L, appContext, 10, 7L))
            assertTrue("successful in-process consume must not perform any provider IPC: ${counting.calls}", counting.calls.isEmpty())
        } finally {
            restoreManifestProvider()
        }
    }

    @Test
    fun currentPending_ageExactlyTtl_returnsEntry() {
        seedPending(token = 555L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - VoiceHandoffContract.RETRIGGER_TTL_MS)
        val pending = AssistantVoiceHandoff.currentPending(appContext)
        assertNotNull("entry aged exactly TTL is still valid", pending)
        assertEquals(555L, pending?.token)

        VoiceHandoffProvider.resetForTest()
        seedPending(token = 556L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)
        assertNull("entry aged TTL+1 is expired", AssistantVoiceHandoff.currentPending(appContext))
    }

    @Test
    fun consume_zeroToken_returnsFalse() {
        assertFalse(AssistantVoiceHandoff.consume(0L, appContext, 10, 7L))
        // Provider arm: seed a live entry first; a zero-token consume must fail without touching it.
        assertTrue(
            VoiceHandoffProvider.publish(
                PendingVoiceRetrigger(555L, 10, 7L, SystemClock.elapsedRealtime()),
            ),
        )
        assertFalse(VoiceHandoffProvider.consume(0L, 10, 7L))
        assertEquals(555L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun consume_withSeededExpiredEntry_returnsFalseAndDropsEntry() {
        val now = SystemClock.elapsedRealtime()
        seedPending(token = 777L, issuedElapsedRealtimeMs = now - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)

        assertFalse(AssistantVoiceHandoff.consume(777L, appContext, targetTaskId = 10, sessionInstanceId = 7L))
        assertNull("expired entry must be dropped on consume attempt", VoiceHandoffProvider.pending.value)
    }

    // ---- Stage 3: bare clear is local-only; clearExpired; provider-stamped publish ----

    @Test
    fun clear_noArg_clearsLocalPending_andIssuesNoIpc() {
        seedPending(token = 999L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)

        val counting = CountingProvider()
        swapHandoffProvider(counting)
        try {
            AssistantVoiceHandoff.clear(appContext)
            assertNull("bare clear must still clear the local slot", VoiceHandoffProvider.pending.value)
            assertTrue("bare clear must not issue any provider IPC: ${counting.calls}", counting.calls.isEmpty())
        } finally {
            restoreManifestProvider()
        }
    }

    @Test
    fun clear_withToken_stillIssuesIpc() {
        seedPending(token = 999L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)

        val counting = CountingProvider()
        swapHandoffProvider(counting)
        try {
            AssistantVoiceHandoff.clear(appContext, token = 999L)
            assertEquals(listOf(VoiceHandoffContract.METHOD_CLEAR), counting.calls.map { it.first })
        } finally {
            restoreManifestProvider()
        }
    }

    @Test
    fun clearExpired_localExpiredEntry_isCleared_freshEntry_isPreserved() {
        val now = SystemClock.elapsedRealtime()

        // Expired entry: cleared.
        seedPending(token = 1L, issuedElapsedRealtimeMs = now - VoiceHandoffContract.RETRIGGER_TTL_MS - 1, targetTaskId = 10, sessionInstanceId = 7L)
        AssistantVoiceHandoff.clearExpired()
        assertNull("expired entry must be cleared by clearExpired", VoiceHandoffProvider.pending.value)

        // Fresh entry: preserved.
        seedPending(token = 2L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)
        AssistantVoiceHandoff.clearExpired()
        assertEquals("fresh entry must be preserved by clearExpired", 2L, VoiceHandoffProvider.pending.value?.token)

        // Negative-age (skewed) entry: cleared.
        VoiceHandoffProvider.resetForTest()
        seedPending(token = 3L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() + 60_000L, targetTaskId = 10, sessionInstanceId = 7L)
        AssistantVoiceHandoff.clearExpired()
        assertNull("negative-age entry must be cleared by clearExpired", VoiceHandoffProvider.pending.value)
    }

    @Test
    fun clearExpired_withContext_sendsOnlyClearExpiredIpc_andSwallowsFailures() {
        seedPending(token = 4L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)

        val counting = CountingProvider()
        swapHandoffProvider(counting)
        try {
            AssistantVoiceHandoff.clearExpired(appContext)
            assertEquals(listOf(VoiceHandoffContract.METHOD_CLEAR_EXPIRED), counting.calls.map { it.first })
            assertNull("clearExpired must not send extras", counting.calls.first().second)
        } finally {
            restoreManifestProvider()
        }

        // IPC blowing up must not escape.
        swapHandoffProvider(ThrowingProvider())
        try {
            AssistantVoiceHandoff.clearExpired(appContext)
        } finally {
            restoreManifestProvider()
        }
    }

    // Smoke check only: the caller also mints with elapsedRealtime BEFORE the provider stamps,
    // so this cannot distinguish the two stamps. The M-2 regression pin lives in
    // VoiceHandoffProviderTest.callPublish_ignoresCallerIssued_storesProviderClockStamp.
    @Test
    fun requestRetrigger_publishedEntry_isProviderClockStamped() {
        val before = SystemClock.elapsedRealtime()
        val token = AssistantVoiceHandoff.requestRetrigger(appContext, targetTaskId = 10, sessionInstanceId = 7L)
        val after = SystemClock.elapsedRealtime()

        assertNotEquals(0L, token)
        val pending = VoiceHandoffProvider.pending.value
        assertNotNull(pending)
        assertTrue(
            "provider must stamp the stored entry with its own clock (age ≈ 0)",
            pending!!.issuedElapsedRealtimeMs in before..after,
        )
    }

    // F3: the retrigger TTL is measured on monotonic elapsed realtime, which includes deep
    // sleep, while uptimeMillis does not. An over-TTL entry must fail every expiry gate after
    // the device slept — even though only 100 ms of awake time elapsed — and a fresh entry must
    // survive the same 100 ms awake advance (control). Deltas are asserted instead of absolute
    // clock values so the test does not depend on the shadow clock's base offset.
    @Test
    fun deepSleep_expiresEntryOnAllGates_onlyAwakeTimeCountsTowardUptime() {
        // Control: fresh entry + 100 ms of awake advance is still deliverable on the gate.
        VoiceHandoffProvider.resetForTest()
        seedPending(token = 4321L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        val controlPending = VoiceHandoffProvider.pending.value
        assertEquals(true, shouldDeliverVoiceRetrigger(
            pending = controlPending,
            identity = VoiceTaskIdentity(10, 7L),
            currentRoute = Routes.CHAT,
            hasEndpoint = true,
            navLoading = false,
            viewedSessionId = null,
            drawerState = DrawerState(initialValue = DrawerValue.Closed, confirmStateChange = { true }),
        ))
        assertTrue(AssistantVoiceHandoff.consume(4321L, appContext, targetTaskId = 10, sessionInstanceId = 7L))

        // Deep sleep: 30 minutes of wall/realtime pass while uptime only advances by the 100 ms
        // of awake time. Token TTL (10 s) is blown for elapsed realtime but not for uptime.
        VoiceHandoffProvider.resetForTest()
        val uptimeBefore = SystemClock.uptimeMillis()
        val realtimeBefore = SystemClock.elapsedRealtime()
        seedPending(token = 9876L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(), targetTaskId = 10, sessionInstanceId = 7L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(30))
        assertEquals("uptime must only advance while awake", 100L, SystemClock.uptimeMillis() - uptimeBefore)
        assertEquals(
            "elapsed realtime must include the deep-sleep interval",
            100L + Duration.ofMinutes(30).toMillis(),
            SystemClock.elapsedRealtime() - realtimeBefore,
        )

        // Every expiry gate agrees the entry is expired. currentPending/peek run BEFORE consume so
        // they exercise their own TTL filters against the still-present entry; the delivery gate is
        // evaluated against the entry snapshot (the provider keeps it until consume drops it).
        val pendingSnapshot = VoiceHandoffProvider.pending.value
        assertNotNull("entry must still be present after deep sleep (deep-sleep gate, not absence)", pendingSnapshot)
        assertFalse(
            "expired entry must not be deliverable",
            shouldDeliverVoiceRetrigger(
                pending = pendingSnapshot,
                identity = VoiceTaskIdentity(10, 7L),
                currentRoute = Routes.CHAT,
                hasEndpoint = true,
                navLoading = false,
                viewedSessionId = null,
                drawerState = DrawerState(initialValue = DrawerValue.Closed, confirmStateChange = { true }),
            ),
        )
        assertNull("currentPending must filter the deep-sleep-expired entry", AssistantVoiceHandoff.currentPending(appContext))
        assertEquals("peek must report the deep-sleep-expired entry as absent", 0L, AssistantVoiceHandoff.peek(appContext))
        assertFalse("consume must reject the deep-sleep-expired entry", AssistantVoiceHandoff.consume(9876L, targetTaskId = 10, sessionInstanceId = 7L))
        assertNull("expired entry must be dropped on consume attempt", VoiceHandoffProvider.pending.value)
    }
}
