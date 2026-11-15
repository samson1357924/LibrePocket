package dev.librepocket.agent.assistant

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceHandoffProviderTest {

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

    private fun newAttachedProvider(): VoiceHandoffProvider =
        VoiceHandoffProvider().apply {
            attachInfo(appContext, ProviderInfo().apply { authority = "${appContext.packageName}.voice.handoff" })
        }

    private fun publish(token: Long, targetTaskId: Int = 10, sessionInstanceId: Long = 7L, issuedElapsedRealtimeMs: Long = SystemClock.elapsedRealtime()) {
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

    private fun consumeBundle(token: Long, targetTaskId: Int, sessionInstanceId: Long): Bundle {
        val provider = newAttachedProvider()
        return provider.call(
            VoiceHandoffContract.METHOD_CONSUME,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, token)
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, targetTaskId)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, sessionInstanceId)
            },
        )!!
    }

    private fun peekBundle(): Bundle {
        val provider = newAttachedProvider()
        return provider.call(VoiceHandoffContract.METHOD_PEEK, null, null)!!
    }

    @Test
    fun consume_withWrongTargetTaskId_failsAndEntrySurvives() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)

        val ack = consumeBundle(777L, targetTaskId = 11, sessionInstanceId = 7L)

        assertEquals(false, ack.getBoolean("ok"))
        val peek = peekBundle()
        assertEquals(777L, peek.getLong(VoiceHandoffContract.KEY_TOKEN))
    }

    @Test
    fun consume_withWrongSessionInstanceId_failsAndEntrySurvives() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)

        val ack = consumeBundle(777L, targetTaskId = 10, sessionInstanceId = 99L)

        assertEquals(false, ack.getBoolean("ok"))
        val peek = peekBundle()
        assertEquals(777L, peek.getLong(VoiceHandoffContract.KEY_TOKEN))
    }

    @Test
    fun consume_withMatchingIdentity_succeedsOnceThenFails() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)

        assertTrue(VoiceHandoffProvider.consume(777L, 10, 7L))
        assertFalse(VoiceHandoffProvider.consume(777L, 10, 7L))
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun callPublish_withNonPositiveToken_returnsNullAndStoresNothing() {
        val provider = newAttachedProvider()
        for (badToken in listOf(0L, -1L)) {
            VoiceHandoffProvider.resetForTest()
            val ack = provider.call(
                VoiceHandoffContract.METHOD_PUBLISH,
                null,
                Bundle().apply {
                    putLong(VoiceHandoffContract.KEY_TOKEN, badToken)
                    putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, 10)
                    putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
                    putLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS, SystemClock.elapsedRealtime())
                },
            )
            assertNull("token $badToken must be rejected", ack)
            assertFalse(peekBundle().containsKey(VoiceHandoffContract.KEY_TOKEN))
        }
    }

    // M-2 (Stage 3): the provider ignores the caller-supplied issued value and stamps the
    // stored entry with its own clock; the caller cannot forward-anchor the TTL.
    @Test
    fun callPublish_ignoresCallerIssued_storesProviderClockStamp() {
        val provider = newAttachedProvider()
        val bogusIssued = 123L

        val before = SystemClock.elapsedRealtime()
        val ack = provider.call(
            VoiceHandoffContract.METHOD_PUBLISH,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, 4242L)
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, 10)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
                putLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS, bogusIssued)
            },
        )
        val after = SystemClock.elapsedRealtime()

        assertNotNull(ack)
        val peek = peekBundle()
        assertEquals(4242L, peek.getLong(VoiceHandoffContract.KEY_TOKEN))
        assertEquals(10, peek.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID))
        assertEquals(7L, peek.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID))
        val storedIssued = peek.getLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS)
        assertTrue("stored issued must be the provider's clock stamp, not the caller's bogus value", storedIssued in before..after)
        assertNotEquals(bogusIssued, storedIssued)
    }

    @Test
    fun peekExpired_returnsEmptyBundle() {
        val now = SystemClock.elapsedRealtime()
        publish(950L, targetTaskId = 10, sessionInstanceId = 7L, issuedElapsedRealtimeMs = now)

        val freshPeek = newAttachedProvider().call(VoiceHandoffContract.METHOD_PEEK, null, null)!!
        assertTrue(freshPeek.containsKey(VoiceHandoffContract.KEY_TOKEN))
        assertEquals(950L, freshPeek.getLong(VoiceHandoffContract.KEY_TOKEN))

        publish(951L, targetTaskId = 10, sessionInstanceId = 7L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)

        val expiredPeek = newAttachedProvider().call(VoiceHandoffContract.METHOD_PEEK, null, null)!!
        assertEquals(0L, expiredPeek.getLong(VoiceHandoffContract.KEY_TOKEN, 0L))
        assertFalse(expiredPeek.containsKey(VoiceHandoffContract.KEY_TOKEN))
        assertEquals(0, expiredPeek.keySet().size)
    }

    @Test
    fun consume_expiredBeyondTtl_returnsFalseAndDropsEntry() {
        val now = SystemClock.elapsedRealtime()
        publish(900L, targetTaskId = 10, sessionInstanceId = 7L, issuedElapsedRealtimeMs = now - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)

        val ack = consumeBundle(900L, targetTaskId = 10, sessionInstanceId = 7L)

        assertEquals(false, ack.getBoolean("ok"))
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun consume_ageExactlyTtl_returnsTrue() {
        val now = SystemClock.elapsedRealtime()
        publish(901L, targetTaskId = 10, sessionInstanceId = 7L, issuedElapsedRealtimeMs = now - VoiceHandoffContract.RETRIGGER_TTL_MS)

        assertTrue(VoiceHandoffProvider.consume(901L, 10, 7L))
    }

    @Test
    fun consume_negativeAge_returnsFalseAndDropsEntry() {
        val now = SystemClock.elapsedRealtime()
        publish(902L, targetTaskId = 10, sessionInstanceId = 7L, issuedElapsedRealtimeMs = now + 60_000L)

        assertFalse(VoiceHandoffProvider.consume(902L, 10, 7L))
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun clearMatching_withStaleToken_keepsNewerEntry() {
        publish(111L, targetTaskId = 10, sessionInstanceId = 7L)
        assertTrue(VoiceHandoffProvider.consume(111L, 10, 7L))
        publish(222L, targetTaskId = 10, sessionInstanceId = 7L)

        VoiceHandoffProvider.clearMatching(10, 7L, token = 111L)
        assertEquals(222L, VoiceHandoffProvider.pending.value?.token)

        VoiceHandoffProvider.clearMatching(10, 7L, token = 222L)
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun clearIfToken_onlyClearsMatchingToken() {
        publish(333L, targetTaskId = 10, sessionInstanceId = 7L)

        VoiceHandoffProvider.clearIfToken(444L)
        assertEquals(333L, VoiceHandoffProvider.pending.value?.token)

        VoiceHandoffProvider.clearIfToken(333L)
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun clearIfToken_withZeroToken_isNoOp() {
        publish(555L, targetTaskId = 10, sessionInstanceId = 7L)

        VoiceHandoffProvider.clearIfToken(0L)

        assertEquals(555L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun clearMatching_wildcardTask_clearsAnyTaskWithMatchingSid() {
        publish(801L, targetTaskId = 10, sessionInstanceId = 7L)
        VoiceHandoffProvider.clearMatching(Int.MIN_VALUE, 7L)
        assertNull(VoiceHandoffProvider.pending.value)

        publish(802L, targetTaskId = 99, sessionInstanceId = 7L)
        VoiceHandoffProvider.clearMatching(Int.MIN_VALUE, 99L)
        assertEquals(802L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun clearMatching_wildcardSessionInstance_clearsMatchingTaskIgnoringSid() {
        publish(803L, targetTaskId = 10, sessionInstanceId = 7L)
        VoiceHandoffProvider.clearMatching(10, Long.MIN_VALUE)
        assertNull(VoiceHandoffProvider.pending.value)

        publish(804L, targetTaskId = 11, sessionInstanceId = 7L)
        VoiceHandoffProvider.clearMatching(10, Long.MIN_VALUE)
        assertEquals(804L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun clearMatching_wildcardWithMismatchedToken_stillRefuses() {
        publish(805L, targetTaskId = 10, sessionInstanceId = 7L)
        VoiceHandoffProvider.clearMatching(Int.MIN_VALUE, 7L, token = 999L)
        assertEquals(805L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun nextToken_survivesResetWithMonotonicSeed() {
        val first = VoiceHandoffProvider.nextToken()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        VoiceHandoffProvider.resetForTest()
        val second = VoiceHandoffProvider.nextToken()
        assertTrue(second > first)
    }

    @Test
    fun isCallerAllowed_nullCaller_isAllowed() {
        assertTrue(isCallerAllowed(null, appContext.packageName))
    }

    @Test
    fun isCallerAllowed_samePackage_isAllowed() {
        assertTrue(isCallerAllowed(appContext.packageName, appContext.packageName))
    }

    @Test
    fun isCallerAllowed_foreignPackage_isRejected() {
        assertFalse(isCallerAllowed("com.example.other.sameuid", appContext.packageName))
    }

    @Test
    fun call_foreignPackage_returnsNull() {
        publish(888L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()
        val shadow = Shadows.shadowOf(provider)
        shadow.setCallingPackage("com.example.other.sameuid")
        try {
            assertNull("foreign same-UID caller must be rejected", provider.call(VoiceHandoffContract.METHOD_PEEK, null, null))
            assertNull(
                "foreign same-UID caller must be rejected",
                provider.call(
                    VoiceHandoffContract.METHOD_CONSUME,
                    null,
                    Bundle().apply {
                        putLong(VoiceHandoffContract.KEY_TOKEN, 888L)
                        putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, 10)
                        putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
                    },
                ),
            )
            assertEquals("rejected call must not consume", 888L, VoiceHandoffProvider.pending.value?.token)
        } finally {
            shadow.setCallingPackage(null)
        }
    }

    @Test
    fun callClear_withTokenOnly_clearsThatTokenNotLaterOne() {
        val provider = newAttachedProvider()
        publish(666L, targetTaskId = 10, sessionInstanceId = 7L)
        provider.call(
            VoiceHandoffContract.METHOD_CLEAR,
            null,
            Bundle().apply { putLong(VoiceHandoffContract.KEY_TOKEN, 666L) },
        )
        assertNull(VoiceHandoffProvider.pending.value)

        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)
        provider.call(
            VoiceHandoffContract.METHOD_CLEAR,
            null,
            Bundle().apply { putLong(VoiceHandoffContract.KEY_TOKEN, 666L) },
        )
        assertEquals(777L, VoiceHandoffProvider.pending.value?.token)
    }

    // ---- Stage 3, provider-enforced binding at the IPC boundary ----

    @Test
    fun consumeIpc_withoutIdentityKeys_returnsOkFalseAndPreservesEntry() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        val ack = provider.call(
            VoiceHandoffContract.METHOD_CONSUME,
            null,
            Bundle().apply { putLong(VoiceHandoffContract.KEY_TOKEN, 777L) },
        )!!

        assertEquals(false, ack.getBoolean(VoiceHandoffContract.KEY_OK))
        assertEquals("token-only consume must not remove the entry", 777L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun consumeIpc_withExplicitNullIdentity_returnsOkFalseAndPreservesEntry() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        // Explicit null value under an identity key: containsKey passes, the typed read
        // must fail closed (sentinel default / throw) — never consume.
        val ack = provider.call(
            VoiceHandoffContract.METHOD_CONSUME,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, 777L)
                putString(VoiceHandoffContract.KEY_TARGET_TASK_ID, null)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
            },
        )

        assertTrue("explicit-null identity must be rejected", ack == null || ack.getBoolean(VoiceHandoffContract.KEY_OK) == false)
        assertEquals(777L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun consumeIpc_withSentinelIdentity_returnsOkFalseAndPreservesEntry() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        val ack = provider.call(
            VoiceHandoffContract.METHOD_CONSUME,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, 777L)
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, Int.MIN_VALUE)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, Long.MIN_VALUE)
            },
        )!!

        assertEquals(false, ack.getBoolean(VoiceHandoffContract.KEY_OK))
        assertEquals("sentinel identity must be rejected", 777L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun publishIpc_withUndeliverableIdentity_returnsNullAndStoresNothing() {
        val provider = newAttachedProvider()

        // Sentinel task axis: refused at the boundary, slot stays free.
        val ack1 = provider.call(
            VoiceHandoffContract.METHOD_PUBLISH,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, 991L)
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, Int.MIN_VALUE)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
                putLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS, SystemClock.elapsedRealtime())
            },
        )
        assertNull(ack1)
        assertNull(VoiceHandoffProvider.pending.value)

        // Zero sid axis: likewise refused.
        val ack2 = provider.call(
            VoiceHandoffContract.METHOD_PUBLISH,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, 992L)
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, 10)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 0L)
                putLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS, SystemClock.elapsedRealtime())
            },
        )
        assertNull(ack2)
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun consumeIpc_withFullIdentity_returnsOkTrueAndRemovesEntry() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        val ack = provider.call(
            VoiceHandoffContract.METHOD_CONSUME,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, 777L)
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, 10)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
            },
        )!!

        assertEquals(true, ack.getBoolean(VoiceHandoffContract.KEY_OK))
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun consumeIpc_withNullExtras_returnsOkFalseAndPreservesEntry() {
        publish(777L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        val ack = provider.call(VoiceHandoffContract.METHOD_CONSUME, null, null)!!

        assertEquals(false, ack.getBoolean(VoiceHandoffContract.KEY_OK))
        assertEquals(777L, VoiceHandoffProvider.pending.value?.token)
    }

    // L-1: a wrong-typed value must never produce a crash or consume the entry. On-device,
    // Bundle.getLong(...) with a String value throws ClassCastException, which call()'s
    // envelope catch converts to null; Robolectric's Bundle is lenient (returns the default), so
    // the exact outcome may differ between test runtimes and devices — wrong-typed reads may
    // either throw (converted to null by the envelope catch) or surface the lenient default
    // (sentinel gate → ok=false). Either way the entry must survive; it never crashes or consumes.
    @Test
    fun call_withMalformedExtras_rejectsWithoutCrashAndPreservesEntry() {
        publish(888L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        // putString into a Long-typed key.
        val ack = provider.call(
            VoiceHandoffContract.METHOD_CONSUME,
            null,
            Bundle().apply {
                putString(VoiceHandoffContract.KEY_TOKEN, "not-a-long")
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, 10)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
            },
        )

        // Robolectric: wrong-typed reads return the default (token=0 -> rejected ok=false).
        // Device: ClassCastException -> caught -> call() returns null. Both are valid; neither crashes.
        assertTrue("malformed envelope must be rejected (null) or carry ok=false", ack == null || ack.getBoolean(VoiceHandoffContract.KEY_OK) == false)
        assertEquals(888L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun callClear_withEmptyExtras_preservesEntry() {
        publish(888L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        provider.call(VoiceHandoffContract.METHOD_CLEAR, null, Bundle())
        assertEquals(888L, VoiceHandoffProvider.pending.value?.token)

        // arg set but extras empty: still a no-op.
        provider.call(VoiceHandoffContract.METHOD_CLEAR, "some-arg", null)
        assertEquals(888L, VoiceHandoffProvider.pending.value?.token)

        // extras with neither token nor identity keys: still a no-op.
        provider.call(VoiceHandoffContract.METHOD_CLEAR, null, Bundle().apply { putString("unrelated", "x") })
        assertEquals(888L, VoiceHandoffProvider.pending.value?.token)

        // arg + extras == null together: bare path is not reachable over IPC.
        provider.call(VoiceHandoffContract.METHOD_CLEAR, null, null)
        assertEquals(888L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun callClearExpired_onFreshEntry_preservesIt_thenClearsAfterTtl() {
        publish(888L, targetTaskId = 10, sessionInstanceId = 7L)
        val provider = newAttachedProvider()

        provider.call(VoiceHandoffContract.METHOD_CLEAR_EXPIRED, null, null)
        assertEquals("unexpired entry must survive clearExpired", 888L, VoiceHandoffProvider.pending.value?.token)

        ShadowSystemClock.advanceBy(Duration.ofMillis(VoiceHandoffContract.RETRIGGER_TTL_MS + 1))
        provider.call(VoiceHandoffContract.METHOD_CLEAR_EXPIRED, null, null)
        assertNull("expired entry must be dropped by clearExpired", VoiceHandoffProvider.pending.value)
    }

    @Test
    fun callClearExpired_onNegativeAgeEntry_clearsIt() {
        publish(889L, targetTaskId = 10, sessionInstanceId = 7L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() + 60_000L)
        val provider = newAttachedProvider()

        provider.call(VoiceHandoffContract.METHOD_CLEAR_EXPIRED, null, null)

        assertNull("negative-age (skewed) entry must be dropped by clearExpired", VoiceHandoffProvider.pending.value)
    }

    @Test
    fun clearExpired_companion_dropsOnlyExpiredOrSkewedEntries() {
        publish(890L, targetTaskId = 10, sessionInstanceId = 7L, issuedElapsedRealtimeMs = SystemClock.elapsedRealtime() - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)
        VoiceHandoffProvider.clearExpired()
        assertNull(VoiceHandoffProvider.pending.value)

        publish(891L, targetTaskId = 10, sessionInstanceId = 7L)
        VoiceHandoffProvider.clearExpired()
        assertEquals(891L, VoiceHandoffProvider.pending.value?.token)
    }

    @Test
    fun isCallerAllowed_uidMismatch_isRejectedEvenForSamePackage() {
        assertFalse(isCallerAllowed(appContext.packageName, appContext.packageName, 10001, 10002))
    }

    @Test
    fun isCallerAllowed_sameUidSamePackage_isAllowed() {
        assertTrue(isCallerAllowed(appContext.packageName, appContext.packageName, 10001, 10001))
    }

    @Test
    fun isCallerAllowed_uidMismatch_dominatesNullPackage() {
        assertFalse(isCallerAllowed(null, appContext.packageName, 10001, 10002))
    }

    @Test
    fun isCallerAllowed_sameUidDifferentPackage_isRejected() {
        assertFalse(isCallerAllowed("com.example.other", appContext.packageName, 10001, 10001))
    }

    @Test
    fun isCallerAllowed_absentUids_keepPurePackageSemantics() {
        assertTrue(isCallerAllowed(null, appContext.packageName))
        assertTrue(isCallerAllowed(appContext.packageName, appContext.packageName))
        assertFalse(isCallerAllowed("com.example.other", appContext.packageName))
    }
}
