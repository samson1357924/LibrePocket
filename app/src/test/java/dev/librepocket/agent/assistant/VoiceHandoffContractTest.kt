package dev.librepocket.agent.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [VoiceHandoffContract.isExpired] — the single TTL/skew predicate shared by
 * every gate (delivery, consume, peek, clearExpired). The argument order is (issued, now);
 * swapping them inverts every boundary decision, so freeze the order here.
 */
class VoiceHandoffContractTest {

    private val now = 1_000_000_000L

    @Test
    fun `isExpired fresh at age zero`() {
        assertFalse(VoiceHandoffContract.isExpired(now, now))
    }

    @Test
    fun `isExpired fresh exactly at the TTL boundary`() {
        assertFalse(VoiceHandoffContract.isExpired(now - VoiceHandoffContract.RETRIGGER_TTL_MS, now))
    }

    @Test
    fun `isExpired expired one millisecond past the TTL boundary`() {
        assertTrue(VoiceHandoffContract.isExpired(now - VoiceHandoffContract.RETRIGGER_TTL_MS - 1, now))
    }

    @Test
    fun `isExpired skewed future entry is expired`() {
        assertTrue(VoiceHandoffContract.isExpired(now + 2_000L, now))
        assertTrue(VoiceHandoffContract.isExpired(now + 1, now))
    }

    @Test
    fun `isExpired corrupt-huge issued yields a negative age and fails closed`() {
        assertTrue(VoiceHandoffContract.isExpired(Long.MIN_VALUE, now))
        assertTrue(VoiceHandoffContract.isExpired(Long.MAX_VALUE, now))
    }

    @Test
    fun `isExpired wildcard sentinels keep their documented values`() {
        assertTrue(VoiceHandoffContract.WILDCARD_TASK_ID == Int.MIN_VALUE)
        assertTrue(VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID == Long.MIN_VALUE)
    }
}
