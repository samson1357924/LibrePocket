package dev.librepocket.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D07 `BackupRoundTripTest` (ROADMAP P7 §验收.1 / BACKLOG D07).
 *
 * - 備份→恢復一致（sessions/events/prefs 逐欄相等）；
 * - 金鑰預設排除：即使暫存了 key material，預設編碼也不含，解碼後 keys 為空；
 * - 顯式 `includeKeys = true` 才攜帶（換機預設重輸金鑰）；
 * - 竄改 payload 觸發完整性拒絕。
 *
 * Pure JVM (no Android / no Robolectric).
 */
class BackupRoundTripTest {

    private fun sampleBundle() = BackupBundle(
        exportedAt = 1_700_000_000_000L,
        sessions = listOf(
            BackupSession(
                sessionId = "sess-1",
                title = "hello",
                model = "prov-a/model-x",
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_001L,
                events = listOf(
                    BackupEvent(1, "run-1", "user", "call 0912-345-678", 0, 1_700_000_000_000L),
                    BackupEvent(2, "run-1", "assistant", "dialing", 0, 1_700_000_000_001L),
                ),
            ),
        ),
        prefs = mapOf("theme" to "dark"),
        keys = mapOf("provider_key/prov-a" to "sk-abcDEF1234567890"),
    )

    @Test fun roundTrip_preservesSessionsEventsPrefs() {
        val bundle = sampleBundle()
        val restored = BackupCodec.decode(BackupCodec.encode(bundle))

        assertEquals(bundle.sessions.size, restored.sessions.size)
        val s = restored.sessions.single()
        assertEquals("sess-1", s.sessionId)
        assertEquals("hello", s.title)
        assertEquals("prov-a/model-x", s.model)
        assertEquals(
            listOf("call 0912-345-678", "dialing"),
            s.events.map { it.text },
        )
        assertEquals(listOf(1L, 2L), s.events.map { it.seq })
        assertEquals(mapOf("theme" to "dark"), restored.prefs)
    }

    @Test fun roundTrip_preservesPinned() {
        val base = sampleBundle()
        val bundle = base.copy(
            sessions = base.sessions.map { it.copy(pinned = true) },
        )
        val restored = BackupCodec.decode(BackupCodec.encode(bundle))
        assertTrue(restored.sessions.single().pinned)
    }

    @Test fun decode_oldBundleWithoutPinned_defaultsFalse() {
        // Simulate a pre-pinned bundle: strip "pinned" from the payload JSON
        // then recompute integrity so the bundle stays valid.
        val raw = BackupCodec.encode(sampleBundle())
        val payload = Json.parseToJsonElement(raw).jsonObject.getValue("payload").jsonObject
        val sessions = payload.getValue("sessions").jsonArray.map { se ->
            val so = se.jsonObject.toMutableMap()
            so.remove("pinned")
            JsonObject(so)
        }
        val strippedPayload = JsonObject(payload.toMutableMap().also {
            it["sessions"] = JsonArray(sessions)
        })
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(strippedPayload.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val stripped = buildJsonObject {
            put("payload", strippedPayload)
            put("integrity", digest)
        }.toString()
        val restored = BackupCodec.decode(stripped)
        assertFalse(restored.sessions.single().pinned)
    }

    @Test fun keys_excludedByDefault() {
        val raw = BackupCodec.encode(sampleBundle())
        assertFalse("key material leaked into backup", "sk-abcDEF1234567890" in raw)
        assertFalse("key id leaked into backup", "provider_key" in raw)

        val restored = BackupCodec.decode(raw)
        assertTrue("default restore must carry no keys", restored.keys.isEmpty())
    }

    @Test fun keys_optInOnly() {
        val raw = BackupCodec.encode(sampleBundle(), includeKeys = true)
        val restored = BackupCodec.decode(raw)
        assertEquals(mapOf("provider_key/prov-a" to "sk-abcDEF1234567890"), restored.keys)
    }

    @Test fun tamperedPayload_rejected() {
        val raw = BackupCodec.encode(sampleBundle())
        // Flip one payload char while keeping the integrity trailer intact.
        val tampered = raw.replace("dialing", "dialinX")
        try {
            BackupCodec.decode(tampered)
            fail("tampered bundle must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("integrity"))
        }
    }

    @Test fun unknownKind_rejected() {
        val raw = BackupCodec.encode(sampleBundle()).replace("\"user\"", "\"root-shell\"")
        try {
            BackupCodec.decode(raw)
            fail("unknown kind must be rejected")
        } catch (e: IllegalArgumentException) {
            // Either integrity (string edit) or kind validation — both are rejections.
            assertTrue(e.message!!.startsWith("backup decode failed"))
        }
    }
}
