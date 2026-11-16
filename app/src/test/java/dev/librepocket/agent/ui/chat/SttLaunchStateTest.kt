package dev.librepocket.agent.ui.chat

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
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
 * Activity recreation. Restore is fail-closed: any malformed bundle content must yield null
 * (so `rememberSaveable` falls back to [SttLaunchState.fresh]), never a half-trusted snapshot.
 */
class SttLaunchStateTest {

    private fun save(state: SttLaunchState): List<Any?> = saveSttLaunchState(state)

    private fun restore(saved: Any?): SttLaunchState? {
        @Suppress("UNCHECKED_CAST")
        return restoreSttLaunchState(saved as List<Any?>)
    }

    private val populated = SttLaunchState(
        slotBusy = listOf(true, false),
        slotGen = listOf(3L, 0L),
        admitGeneration = 3L,
        inFlightName = "INITIAL",
        admittedAtNanos = 123_456L,
    )

    @Test
    fun `fresh() shape`() {
        val fresh = SttLaunchState.fresh()
        assertEquals(listOf(false, false), fresh.slotBusy)
        assertEquals(listOf(0L, 0L), fresh.slotGen)
        assertEquals(0L, fresh.admitGeneration)
        assertNull(fresh.inFlightName)
        assertEquals(0L, fresh.admittedAtNanos)
    }

    @Test
    fun `saver round trip preserves all fields`() {
        val restored = restore(save(populated))
        assertEquals(populated, restored)
    }

    @Test
    fun `saver round trip preserves fresh state`() {
        val restored = restore(save(SttLaunchState.fresh()))
        assertEquals(SttLaunchState.fresh(), restored)
    }

    @Test
    fun `saver round trip preserves null inFlightName with non-zero watermark`() {
        val paused = SttLaunchState(
            slotBusy = listOf(false, true),
            slotGen = listOf(0L, 7L),
            admitGeneration = 7L,
            inFlightName = null,
            admittedAtNanos = 0L,
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
    fun `restore accepts zero generation and zero admittedAtNanos`() {
        val restored = restore(listOf(listOf(false, false), listOf(0L, 0L), 0L, null, 0L))
        assertEquals(SttLaunchState.fresh(), restored)
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
        )
        assertEquals(consistent, restore(save(consistent)))
        // Also consistent: slot0 holds gen 3 from an earlier admit, slot1 holds gen 5, watermark 5.
        val twoAdmits = SttLaunchState(
            slotBusy = listOf(true, true),
            slotGen = listOf(3L, 5L),
            admitGeneration = 5L,
            inFlightName = "USER",
            admittedAtNanos = 999_000L,
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
        assertNull(restore(listOf(listOf(false, false), listOf(0L, 0L), 0L, "INITIAL", 5L)))
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
        assertNull(restore(listOf(listOf(false, false), listOf(0L, 0L), 4L, "INITIAL", 5L)))
        // At least one busy slot satisfies the invariant.
        assertNotNull(restore(listOf(listOf(true, false), listOf(3L, 0L), 4L, "INITIAL", 5L)))
    }

    private val testSaverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun `ResetEpochSaver restore negative resets to zero, positive passes through, save round-trips`() {
        assertEquals(0, ResetEpochSaver.restore(-1)?.intValue)
        assertEquals(5, ResetEpochSaver.restore(5)?.intValue)
        val saved = with(ResetEpochSaver) { testSaverScope.save(mutableIntStateOf(7)) }
        assertEquals(7, saved)
    }

    @Test
    fun screenIdSaver_roundTrip_validUuidRestored() {
        val uuid = "123e4567-e89b-42d3-a456-426655440000"
        val saved = with(ScreenIdSaver) { testSaverScope.save(mutableStateOf(uuid)) }
        assertEquals(uuid, saved)
        val restored = ScreenIdSaver.restore(saved!!) ?: error("restored must not be null")
        assertEquals(uuid, restored.value)
    }

    @Test
    fun screenIdSaver_corruptRestore_yieldsFreshUnmatchableUuid() {
        listOf("garbage", 123, null).forEach { corrupt ->
            val restored = restoreScreenId(corrupt)
            assertEquals(36, restored.value.length)
            assertTrue(runCatching { UUID.fromString(restored.value) }.isSuccess)
            assertTrue(restored.value != "garbage")
        }
        // 同一顆腐化值經 saver 本身的 restore 入口也 fail-closed（非空值；null 沒入口但同走 restoreScreenId）。
        assertEquals(36, (ScreenIdSaver.restore(123) ?: error("restored must not be null")).value.length)
        // 36 字元但非合法 UUID 的字串同樣 fail-closed 發 fresh UUID。
        val invalid36 = "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"
        assertEquals(36, invalid36.length)
        val restored = restoreScreenId(invalid36)
        assertTrue(runCatching { UUID.fromString(restored.value) }.isSuccess)
        assertTrue(restored.value != invalid36)
    }

    @Test
    fun `isUnattributedVoiceResult true only for zero generation`() {
        assertTrue(isUnattributedVoiceResult(0L))
        assertFalse(isUnattributedVoiceResult(1L))
        assertFalse(isUnattributedVoiceResult(-1L))
        assertFalse(isUnattributedVoiceResult(Long.MAX_VALUE))
    }
}
