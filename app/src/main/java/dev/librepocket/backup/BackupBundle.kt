package dev.librepocket.backup

import dev.librepocket.backup.BackupPolicy.BUNDLE_VERSION
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Closed event-kind set (mirrors SessionStore VALID_KINDS; re-declared so this
 *  package has no dependency on session internals). */
private val BACKUP_VALID_KINDS: Set<String> = setOf(
    "user", "assistant", "tool", "steer", "retry", "system",
)

/** One transcript event inside a backup bundle. */
data class BackupEvent(
    val seq: Long,
    val runId: String,
    val kind: String,
    val text: String,
    val imagesOmitted: Int = 0,
    val createdAt: Long,
)

/** One session (header + ordered events) inside a backup bundle. */
data class BackupSession(
    val sessionId: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val events: List<BackupEvent>,
    /** Mirrors SessionEntity.pinned (prune exemption); default false keeps old bundles decodable. */
    val pinned: Boolean = false,
)

/**
 * Local backup bundle (D07 minimal).
 *
 * - [sessions] carry transcripts; [prefs] carry NON-SECRET preferences only
 *   (theme, toggles — never provider keys).
 * - [keys] is the staging field for key material: [BackupCodec.encode] drops
 *   it unless the caller passes `includeKeys = true`. The default path therefore
 *   cannot serialize a key even if the caller staged one by mistake.
 * - [integrity] is a SHA-256 over the payload serialization, verified on decode;
 *   a restored bundle with a mismatch is rejected before anything is applied.
 *   NOTE: the serialization is insertion-ordered, not canonical JSON — the hash
 *   is a same-library self-check against corruption, not a cross-platform
 *   signature (an attacker who can rewrite the payload can recompute it).
 */
data class BackupBundle(
    val version: Int = BUNDLE_VERSION,
    val exportedAt: Long,
    val sessions: List<BackupSession>,
    val prefs: Map<String, String> = emptyMap(),
    val keys: Map<String, String> = emptyMap(),
)

/** Encode/decode for [BackupBundle] (tree API, no compiler plugin needed). */
object BackupCodec {

    fun encode(bundle: BackupBundle, includeKeys: Boolean = BackupPolicy.DEFAULT_INCLUDE_KEYS): String {
        require(bundle.version == BUNDLE_VERSION) { "unsupported bundle version: ${bundle.version}" }
        val keysOut = if (includeKeys) bundle.keys else emptyMap()
        val payload = buildJsonObject {
            put("version", bundle.version)
            put("exportedAt", bundle.exportedAt)
            put(
                "sessions",
                JsonArray(bundle.sessions.map { s ->
                    buildJsonObject {
                        put("sessionId", s.sessionId)
                        put("title", s.title)
                        put("model", s.model)
                        put("createdAt", s.createdAt)
                        put("updatedAt", s.updatedAt)
                        put("pinned", s.pinned)
                        put(
                            "events",
                            JsonArray(s.events.map { e ->
                                buildJsonObject {
                                    put("seq", e.seq)
                                    put("runId", e.runId)
                                    put("kind", e.kind)
                                    put("text", e.text)
                                    put("imagesOmitted", e.imagesOmitted)
                                    put("createdAt", e.createdAt)
                                }
                            }),
                        )
                    }
                }),
            )
            put(
                "prefs",
                buildJsonObject { for ((k, v) in bundle.prefs) put(k, v) },
            )
            put(
                "keys",
                buildJsonObject { for ((k, v) in keysOut) put(k, v) },
            )
        }
        val digest = sha256Hex(payload.toString())
        return buildJsonObject {
            put("payload", payload)
            put("integrity", digest)
        }.toString()
    }

    /** Decodes + verifies integrity; throws [IllegalArgumentException] on any fault. */
    fun decode(raw: String): BackupBundle {
        fun fail(why: String, cause: Throwable? = null): Nothing =
            throw IllegalArgumentException("backup decode failed: $why", cause)
        val root = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            fail("not JSON", e)
        }
        val payload = root["payload"]?.jsonObject ?: fail("missing payload")
        val integrity = root["integrity"]?.jsonPrimitive?.content ?: fail("missing integrity")
        if (sha256Hex(payload.toString()) != integrity) fail("integrity mismatch")
        if ((payload["version"]?.jsonPrimitive?.intOrNull) != BUNDLE_VERSION) {
            fail("unsupported version")
        }
        val exportedAt = payload["exportedAt"]?.jsonPrimitive?.longOrNull ?: fail("missing exportedAt")
        val sessions = try {
            payload["sessions"]?.jsonArray?.map { se ->
                val so = se.jsonObject
                fun str(key: String): String =
                    so[key]?.jsonPrimitive?.takeIf { it.isString }?.content ?: fail("session.$key")
                val events = so["events"]?.jsonArray?.map { ee ->
                    val eo = ee.jsonObject
                    fun estr(key: String): String =
                        eo[key]?.jsonPrimitive?.takeIf { it.isString }?.content ?: fail("event.$key")
                    val kind = estr("kind")
                    if (kind !in BACKUP_VALID_KINDS) fail("unknown event kind: $kind")
                    BackupEvent(
                        seq = eo["seq"]?.jsonPrimitive?.longOrNull ?: fail("event.seq"),
                        runId = estr("runId"),
                        kind = kind,
                        text = estr("text"),
                        imagesOmitted = eo["imagesOmitted"]?.jsonPrimitive?.intOrNull
                            ?: fail("event.imagesOmitted"),
                        createdAt = eo["createdAt"]?.jsonPrimitive?.longOrNull
                            ?: fail("event.createdAt"),
                    )
                } ?: fail("missing events")
                val seqs = events.map { it.seq }
                if (seqs != seqs.sorted() || seqs.toSet().size != seqs.size) fail("event seqs not ordered/unique")
                BackupSession(
                    sessionId = str("sessionId"),
                    title = str("title"),
                    model = str("model"),
                    createdAt = so["createdAt"]?.jsonPrimitive?.longOrNull ?: fail("session.createdAt"),
                    updatedAt = so["updatedAt"]?.jsonPrimitive?.longOrNull ?: fail("session.updatedAt"),
                    events = events,
                    pinned = so["pinned"]?.let {
                        it.jsonPrimitive.booleanOrNull ?: fail("session.pinned")
                    } ?: false,
                )
            } ?: fail("missing sessions")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            fail("malformed sessions", e)
        }
        val prefs = payload["prefs"]?.jsonObject?.entries?.associate { (k, v) ->
            k to (v.jsonPrimitive.takeIf { it.isString }?.content ?: fail("prefs.$k not a string"))
        } ?: emptyMap()
        val keys = payload["keys"]?.jsonObject?.entries?.associate { (k, v) ->
            k to (v.jsonPrimitive.takeIf { it.isString }?.content ?: fail("keys.$k not a string"))
        } ?: emptyMap()
        return BackupBundle(
            version = BUNDLE_VERSION,
            exportedAt = exportedAt,
            sessions = sessions,
            prefs = prefs,
            keys = keys,
        )
    }

    private fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
