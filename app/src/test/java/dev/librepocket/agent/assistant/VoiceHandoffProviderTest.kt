package dev.librepocket.agent.assistant

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun publish(token: Long, targetTaskId: Int = 10, sessionInstanceId: Long = 7L, issuedUptimeMs: Long = SystemClock.uptimeMillis()) {
        assertTrue(
            VoiceHandoffProvider.publish(
                PendingVoiceRetrigger(
                    token = token,
                    targetTaskId = targetTaskId,
                    sessionInstanceId = sessionInstanceId,
                    issuedUptimeMs = issuedUptimeMs,
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
                    putLong(VoiceHandoffContract.KEY_ISSUED_UPTIME_MS, SystemClock.uptimeMillis())
                },
            )
            assertNull("token $badToken must be rejected", ack)
            assertFalse(peekBundle().containsKey(VoiceHandoffContract.KEY_TOKEN))
        }
    }

    @Test
    fun callPublish_withValidBundle_acksAndPeekRoundTrips() {
        val provider = newAttachedProvider()
        val issued = SystemClock.uptimeMillis() - 1234L

        val ack = provider.call(
            VoiceHandoffContract.METHOD_PUBLISH,
            null,
            Bundle().apply {
                putLong(VoiceHandoffContract.KEY_TOKEN, 4242L)
                putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, 10)
                putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 7L)
                putLong(VoiceHandoffContract.KEY_ISSUED_UPTIME_MS, issued)
            },
        )

        assertNotNull(ack)
        val peek = peekBundle()
        assertEquals(4242L, peek.getLong(VoiceHandoffContract.KEY_TOKEN))
        assertEquals(10, peek.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID))
        assertEquals(7L, peek.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID))
        assertEquals(issued, peek.getLong(VoiceHandoffContract.KEY_ISSUED_UPTIME_MS))
    }

    @Test
    fun peekExpired_returnsEmptyBundle() {
        val now = SystemClock.uptimeMillis()
        publish(950L, targetTaskId = 10, sessionInstanceId = 7L, issuedUptimeMs = now)

        val freshPeek = newAttachedProvider().call(VoiceHandoffContract.METHOD_PEEK, null, null)!!
        assertTrue(freshPeek.containsKey(VoiceHandoffContract.KEY_TOKEN))
        assertEquals(950L, freshPeek.getLong(VoiceHandoffContract.KEY_TOKEN))

        publish(951L, targetTaskId = 10, sessionInstanceId = 7L, issuedUptimeMs = SystemClock.uptimeMillis() - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)

        val expiredPeek = newAttachedProvider().call(VoiceHandoffContract.METHOD_PEEK, null, null)!!
        assertEquals(0L, expiredPeek.getLong(VoiceHandoffContract.KEY_TOKEN, 0L))
        assertFalse(expiredPeek.containsKey(VoiceHandoffContract.KEY_TOKEN))
        assertEquals(0, expiredPeek.keySet().size)
    }

    @Test
    fun consume_expiredBeyondTtl_returnsFalseAndDropsEntry() {
        val now = SystemClock.uptimeMillis()
        publish(900L, targetTaskId = 10, sessionInstanceId = 7L, issuedUptimeMs = now - VoiceHandoffContract.RETRIGGER_TTL_MS - 1)

        val ack = consumeBundle(900L, targetTaskId = 10, sessionInstanceId = 7L)

        assertEquals(false, ack.getBoolean("ok"))
        assertNull(VoiceHandoffProvider.pending.value)
    }

    @Test
    fun consume_ageExactlyTtl_returnsTrue() {
        val now = SystemClock.uptimeMillis()
        publish(901L, targetTaskId = 10, sessionInstanceId = 7L, issuedUptimeMs = now - VoiceHandoffContract.RETRIGGER_TTL_MS)

        assertTrue(VoiceHandoffProvider.consume(901L, 10, 7L))
    }

    @Test
    fun consume_negativeAge_returnsFalseAndDropsEntry() {
        val now = SystemClock.uptimeMillis()
        publish(902L, targetTaskId = 10, sessionInstanceId = 7L, issuedUptimeMs = now + 60_000L)

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
}
