package dev.librepocket.models

import dev.librepocket.provider.MiniJson
import dev.librepocket.provider.arr
import dev.librepocket.provider.bool
import dev.librepocket.provider.string
import dev.librepocket.provider.validateBaseUrl

/**
 * models.dev snapshot reader/cache/fallback (P1 SPEC §1.2 / §10.3).
 *
 * Decouples "model list refresh" from app releases (ARCHITECTURE §12):
 * - [fetchSnapshot] pulls the upstream directory over plain HTTPS with an
 *   injectable [fetcher] (no Android, no OkHttp dependency here; the caller
 *   supplies transport). Non-https URLs, oversize bodies and parse failures
 *   all fall back to [bundledSnapshot].
 * - [merge] unions a live provider listing with snapshot candidates.
 *
 * The fetcher must not send any Authorization header (SPEC §10.3: reading the
 * directory carries no key); see [fetchSnapshot] docs for the assertion hook.
 */
object ModelsDevSnapshot {
    const val DEFAULT_DIRECTORY_URL = "https://models.dev/api.json"
    const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
    const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    data class ModelEntry(
        val id: String, // "provider/model"
        val reasoning: Boolean = false,
        val toolCalls: Boolean = false,
    )

    data class Snapshot(val fetchedAt: Long, val models: List<ModelEntry>)

    /** Minimal bundled fallback so the model picker works fully offline. */
    fun bundledSnapshot(nowMs: Long = System.currentTimeMillis()): Snapshot = Snapshot(
        fetchedAt = nowMs,
        models = listOf(
            ModelEntry("openai/gpt-4o-mini", reasoning = false, toolCalls = true),
            ModelEntry("anthropic/claude-haiku-4-5", reasoning = false, toolCalls = true),
            ModelEntry("google/gemini-2-flash", reasoning = true, toolCalls = true),
        ),
    )

    /**
     * Parse a models.dev directory body. Accepts the upstream shape
     * `{ "models": [ {"id": "...", "reasoning": bool, ...} ] }` plus a flat
     * array fallback. Malformed entries are skipped, never fatal.
     */
    fun parse(body: String, nowMs: Long = System.currentTimeMillis()): Snapshot {
        val root = try {
            MiniJson.parse(body)
        } catch (e: MiniJson.MiniJsonException) {
            throw SnapshotException("MODELS_SNAPSHOT_PARSE", e)
        }
        val items: List<MiniJson.J> = when (root) {
            is MiniJson.JObj -> root.arr("models")?.items ?: throw SnapshotException("MODELS_SNAPSHOT_SHAPE")
            is MiniJson.JArr -> root.items
            else -> throw SnapshotException("MODELS_SNAPSHOT_SHAPE")
        }
        val entries = ArrayList<ModelEntry>()
        for (item in items) {
            val obj = item as? MiniJson.JObj ?: continue
            val id = obj.string("id") ?: continue
            entries.add(
                ModelEntry(
                    id = id,
                    reasoning = obj.bool("reasoning") == true,
                    toolCalls = obj.bool("tool_calls") == true || obj.bool("tools") == true,
                ),
            )
        }
        return Snapshot(nowMs, entries)
    }

    /**
     * Fetch with fallback. [fetcher] performs `GET(url)` and returns the body;
     * it must send no Authorization header (asserted by tests at the call
     * site). Any failure -> [bundledSnapshot].
     */
    suspend fun fetchSnapshot(
        url: String = DEFAULT_DIRECTORY_URL,
        nowMs: Long = System.currentTimeMillis(),
        fetcher: suspend (String) -> String,
    ): Snapshot {
        try {
            validateBaseUrl(url)
            val body = fetcher(url)
            if (body.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES) {
                return bundledSnapshot(nowMs)
            }
            val parsed = parse(body, nowMs)
            if (parsed.models.isEmpty()) return bundledSnapshot(nowMs)
            return parsed
        } catch (_: Exception) {
            return bundledSnapshot(nowMs)
        }
    }

    /** Union live ids with snapshot candidates (live first, snapshot order kept). */
    fun merge(liveModelIds: List<String>, snapshot: Snapshot): List<String> {
        val out = ArrayList<String>(liveModelIds)
        for (entry in snapshot.models) {
            if (!out.contains(entry.id)) out.add(entry.id)
        }
        return out
    }

    fun isFresh(snapshot: Snapshot, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - snapshot.fetchedAt in 0..CACHE_TTL_MS

    class SnapshotException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
}
