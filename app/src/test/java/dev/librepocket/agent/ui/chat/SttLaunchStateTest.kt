package dev.librepocket.agent.ui.chat

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SttLaunchState] / [SttLaunchStateSaver] / [isUnattributedVoiceResult]
 * (pure JVM, no Android).
 *
 * The saver restores the per-slot launch identity + arbiter watermark snapshot across
 * Activity recreation. [restoreSttLaunchState] is fail-closed: any malformed bundle content
 * must yield null; [SttLaunchStateSaver] wraps it and mints a fresh composite (new UUID)
 * instead of returning null — never a half-trusted snapshot, never a null-valued
 * MutableState at the `rememberSaveable` boundary.
 */
class SttLaunchStateTest {

    private fun save(state: SttLaunchState): List<Any?> = saveSttLaunchState(state)

    private fun restore(saved: Any?): SttLaunchState? {
        @Suppress("UNCHECKED_CAST")
        return restoreSttLaunchState(saved as List<Any?>)
    }

    private val TEST_SCREEN_ID = "123e4567-e89b-42d3-a456-426655440000"

    private val populated = SttLaunchState(
        slotBusy = listOf(true, false),
        slotGen = listOf(3L, 0L),
        admitGeneration = 3L,
        inFlightName = "INITIAL",
        admittedAtNanos = 123_456L,
        screenId = TEST_SCREEN_ID,
        resetEpoch = 3,
    )

    @Test
    fun `fresh() shape`() {
        val fresh = SttLaunchState.fresh()
        assertEquals(listOf(false, false), fresh.slotBusy)
        assertEquals(listOf(0L, 0L), fresh.slotGen)
        assertEquals(0L, fresh.admitGeneration)
        assertNull(fresh.inFlightName)
        assertEquals(0L, fresh.admittedAtNanos)
        assertEquals(0, fresh.resetEpoch)
        assertEquals(36, fresh.screenId.length)
        assertTrue(runCatching { UUID.fromString(fresh.screenId) }.isSuccess)
    }

    @Test
    fun `saver round trip preserves all fields`() {
        val saved = save(populated)
        assertEquals(7, saved.size)
        assertEquals(TEST_SCREEN_ID, saved[5])
        assertEquals(3, saved[6])
        val restored = restore(saved)
        assertEquals(populated, restored)
    }

    @Test
    fun `saver round trip preserves fresh state`() {
        val fresh = SttLaunchState.fresh()
        val restored = restore(save(fresh))
        assertEquals(fresh, restored)
    }

    @Test
    fun `saver round trip preserves null inFlightName with non-zero watermark`() {
        val paused = SttLaunchState(
            slotBusy = listOf(false, true),
            slotGen = listOf(0L, 7L),
            admitGeneration = 7L,
            inFlightName = null,
            admittedAtNanos = 0L,
            screenId = TEST_SCREEN_ID,
            resetEpoch = 2,
        )
        val restored = restore(save(paused))
        assertEquals(paused, restored)
    }

    @Test
    fun `restore returns null on wrong top level size`() {
        // Too few elements.
        assertNull(restore(save(populated).dropLast(1)))
        // Too many elements.
        assertNull(restore(save(populated) + 0L))
        // Not a list at all is rejected by the unchecked cast at the saver boundary; here we
        // cover the in-list size validation instead.
        assertNull(restore(emptyList<Any?>()))
    }

    @Test
    fun `restore returns null on malformed slotBusy`() {
        val base = save(populated)
        // Wrong size.
        assertNull(restore(base.toMutableList().also { it[0] = listOf(true) }))
        assertNull(restore(base.toMutableList().also { it[0] = listOf(true, false, true) }))
        // Non-Boolean element.
        assertNull(restore(base.toMutableList().also { it[0] = listOf(true, 1L) }))
        // Not a list.
        assertNull(restore(base.toMutableList().also { it[0] = "busy" }))
    }

    @Test
    fun `restore returns null on malformed slotGen`() {
        val base = save(populated)
        // Wrong size.
        assertNull(restore(base.toMutableList().also { it[1] = listOf(3L) }))
        assertNull(restore(base.toMutableList().also { it[1] = listOf(3L, 0L, 1L) }))
        // Non-Long element (explicit Any so Int stays Int and does not adapt to Long).
        assertNull(restore(base.toMutableList().also { it[1] = listOf<Any>(3, 0L) }))
        // Not a list.
        assertNull(restore(base.toMutableList().also { it[1] = 3L }))
    }

    @Test
    fun `restore returns null on negative or mistyped admitGeneration`() {
        val base = save(populated)
        assertNull(restore(base.toMutableList().also { it[2] = -1L }))
        assertNull(restore(base.toMutableList().also { it[2] = 3 })) // Int, not Long
        assertNull(restore(base.toMutableList().also { it[2] = "3" }))
    }

    @Test
    fun `restore returns null on negative or mistyped admittedAtNanos`() {
        val base = save(populated)
        assertNull(restore(base.toMutableList().also { it[4] = -1L }))
        assertNull(restore(base.toMutableList().also { it[4] = 123_456 })) // Int, not Long
        assertNull(restore(base.toMutableList().also { it[4] = "123456" }))
    }

    @Test
    fun `restore returns null on garbage or mistyped inFlightName`() {
        val base = save(populated)
        // Not a VoiceSttArmKind enum name.
        assertNull(restore(base.toMutableList().also { it[3] = "BOGUS" }))
        assertNull(restore(base.toMutableList().also { it[3] = "initial" })) // case-sensitive
        assertNull(restore(base.toMutableList().also { it[3] = "" }))
        // Non-String value.
        assertNull(restore(base.toMutableList().also { it[3] = 42 }))
        // All enum names restore fine.
        for (kind in VoiceSttArmKind.entries) {
            val ok = restore(base.toMutableList().also { it[3] = kind.name })
            assertEquals(kind.name, ok?.inFlightName)
        }
    }

    @Test
    fun `restore returns null on malformed screenId`() {
        val base = save(populated)
        // Wrong type.
        assertNull(restore(base.toMutableList().also { it[5] = 42 }))
        assertNull(restore(base.toMutableList().also { it[5] = null }))
        // Not 36 chars.
        assertNull(restore(base.toMutableList().also { it[5] = "" }))
        assertNull(restore(base.toMutableList().also { it[5] = "123e4567-e89b-42d3-a456-42665544000" }))
        assertNull(restore(base.toMutableList().also { it[5] = "123e4567-e89b-42d3-a456-4266554400000" }))
        // 36 chars but not a UUID (hex validation fails).
        assertNull(restore(base.toMutableList().also { it[5] = "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz" }))
    }

    @Test
    fun `restore returns null on malformed resetEpoch`() {
        val base = save(populated)
        assertNull(restore(base.toMutableList().also { it[6] = -1 }))
        assertNull(restore(base.toMutableList().also { it[6] = 3L })) // Long, not Int
        assertNull(restore(base.toMutableList().also { it[6] = "3" }))
        assertNull(restore(base.toMutableList().also { it[6] = null }))
    }

    @Test
    fun `restore accepts zero generation and zero admittedAtNanos`() {
        val restored = restore(
            listOf(listOf(false, false), listOf(0L, 0L), 0L, null, 0L, TEST_SCREEN_ID, 0),
        )
        assertEquals(
            SttLaunchState(
                slotBusy = listOf(false, false),
                slotGen = listOf(0L, 0L),
                admitGeneration = 0L,
                inFlightName = null,
                admittedAtNanos = 0L,
                screenId = TEST_SCREEN_ID,
                resetEpoch = 0,
            ),
            restored,
        )
    }

    @Test
    fun `restore accepts a consistent populated snapshot`() {
        // Realistic consistent mix: slot0 busy with the admit generation it was launched with
        // (gen 3), watermark at 3 (the admit that produced gen 3), in-flight arm paired with a
        // non-zero admit time. All cross-field invariants hold.
        val consistent = SttLaunchState(
            slotBusy = listOf(true, false),
            slotGen = listOf(3L, 0L),
            admitGeneration = 3L,
            inFlightName = "INITIAL",
            admittedAtNanos = 123_456L,
            screenId = TEST_SCREEN_ID,
            resetEpoch = 4,
        )
        assertEquals(consistent, restore(save(consistent)))
        // Also consistent: slot0 holds gen 3 from an earlier admit, slot1 holds gen 5, watermark 5.
        val twoAdmits = SttLaunchState(
            slotBusy = listOf(true, true),
            slotGen = listOf(3L, 5L),
            admitGeneration = 5L,
            inFlightName = "USER",
            admittedAtNanos = 999_000L,
            screenId = TEST_SCREEN_ID,
            resetEpoch = 5,
        )
        assertEquals(twoAdmits, restore(save(twoAdmits)))
    }

    @Test
    fun `restore returns null when a busy slot carries zero generation`() {
        val base = save(populated)
        // Both slots free of gen but marked busy.
        assertNull(restore(base.toMutableList().also { it[1] = listOf(0L, 0L) }))
        // Slot1 busy with gen 0; slot0 keeps gen 3 (isolation: rule fires on the busy slot).
        assertNull(restore(base.toMutableList().also {
            it[0] = listOf(false, true)
            it[1] = listOf(0L, 0L)
        }))
    }

    @Test
    fun `restore returns null when a free slot carries a generation`() {
        val base = save(populated)
        // Slot1 free but non-zero gen (production zeroes gen on release); gen 2 <= watermark 3,
        // so the watermark rule does not mask this rule.
        assertNull(restore(base.toMutableList().also { it[1] = listOf(3L, 2L) }))
    }

    @Test
    fun `restore returns null when inFlightName is set with zero watermark`() {
        // Isolate this rule: a fresh-shaped snapshot (all slots free) so the watermark>=gen
        // rule cannot mask it — only the inFlight-without-generation rule can fire.
        assertNull(restore(listOf(listOf(false, false), listOf(0L, 0L), 0L, "INITIAL", 5L, TEST_SCREEN_ID, 0)))
    }

    @Test
    fun `restore returns null when inFlightName is set with zero admittedAtNanos`() {
        val base = save(populated)
        assertNull(restore(base.toMutableList().also { it[4] = 0L }))
    }

    @Test
    fun `restore returns null when admitGeneration reaches Long MAX_VALUE`() {
        val base = save(populated)
        // Watermark at MAX_VALUE: the next admit would wrap to a negative generation.
        assertNull(restore(base.toMutableList().also { it[2] = Long.MAX_VALUE }))
    }

    @Test
    fun `restore returns null when a slot generation exceeds the watermark`() {
        val base = save(populated)
        // Slot0 busy with gen 4 > watermark 3 (watermark can never lag a minted generation).
        assertNull(restore(base.toMutableList().also { it[1] = listOf(4L, 0L) }))
        // Slot1 busy with gen 4 > watermark 3 as well; slot0 stays valid at gen 3.
        assertNull(restore(base.toMutableList().also {
            it[0] = listOf(true, true)
            it[1] = listOf(3L, 4L)
        }))
    }

    @Test
    fun `restore returns null when inFlightName is set but all slots are free`() {
        // Production invariant: a named in-flight arm must hold at least one reserved slot.
        assertNull(restore(listOf(listOf(false, false), listOf(0L, 0L), 4L, "INITIAL", 5L, TEST_SCREEN_ID, 0)))
        // At least one busy slot satisfies the invariant.
        assertNotNull(restore(listOf(listOf(true, false), listOf(3L, 0L), 4L, "INITIAL", 5L, TEST_SCREEN_ID, 0)))
    }

    @Test
    fun `saver fallback is non-null and fresh`() {
        // 腐化快照：slotBusy size==1 → inner restore 回 null → Saver 必須 fail-closed 回 fresh
        // composite（永不回 null；rememberSaveable 首讀即 NPE 的回歸釘住）。
        val corrupt = save(populated).toMutableList().also { it[0] = listOf(true) }
        val restored = SttLaunchStateSaver.restore(corrupt)
        assertNotNull("saver restore must fail closed to a fresh composite, never null", restored)
        assertEquals(listOf(false, false), restored!!.slotBusy)
        assertEquals(listOf(0L, 0L), restored.slotGen)
        assertEquals(0L, restored.admitGeneration)
        assertNull(restored.inFlightName)
        assertEquals(0L, restored.admittedAtNanos)
        assertEquals(0, restored.resetEpoch)
        assertEquals(36, restored.screenId.length)
        assertTrue(runCatching { UUID.fromString(restored.screenId) }.isSuccess)
        assertTrue(restored.screenId != populated.screenId)
    }

    @Test
    fun `legacy 5-element bundle falls back to fresh`() {
        // 舊 5 元素 bundle（issue 前格式）：inner restore 回 null → Saver 回 fresh composite。
        val legacy = listOf(listOf(true, false), listOf(3L, 0L), 3L, "INITIAL", 123_456L)
        val restored = SttLaunchStateSaver.restore(legacy)
        assertNotNull("saver restore must fail closed to a fresh composite, never null", restored)
        assertEquals(listOf(false, false), restored!!.slotBusy)
        assertEquals(listOf(0L, 0L), restored.slotGen)
        assertEquals(0L, restored.admitGeneration)
        assertNull(restored.inFlightName)
        assertEquals(0L, restored.admittedAtNanos)
        assertEquals(0, restored.resetEpoch)
        assertEquals(36, restored.screenId.length)
        assertTrue(runCatching { UUID.fromString(restored.screenId) }.isSuccess)
        assertTrue(restored.screenId != populated.screenId)
    }

    @Test
    fun `valid restore preserves identity`() {
        // GUARD：合法 snapshot 經 saver 還原仍原樣（composite atomicity 不改變合法路徑）。
        assertEquals(populated, SttLaunchStateSaver.restore(save(populated)))
    }

    @Test
    fun `isUnattributedVoiceResult true only for zero generation`() {
        assertTrue(isUnattributedVoiceResult(0L))
        assertFalse(isUnattributedVoiceResult(1L))
        assertFalse(isUnattributedVoiceResult(-1L))
        assertFalse(isUnattributedVoiceResult(Long.MAX_VALUE))
    }
}
