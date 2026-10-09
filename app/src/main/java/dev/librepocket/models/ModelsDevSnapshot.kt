package dev.librepocket.models

import dev.librepocket.provider.MiniJson
import dev.librepocket.provider.arr
import dev.librepocket.provider.bool
import dev.librepocket.provider.obj
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
 * - [mergeForProvider] adds only candidates belonging to an explicit directory
 *   provider identity, and exposes their wire IDs unchanged.
 *
 * The fetcher must not send any Authorization header (SPEC §10.3: reading the
 * directory carries no key); see [fetchSnapshot] docs for the assertion hook.
 */
object ModelsDevSnapshot {
    const val DEFAULT_DIRECTORY_URL = "https://models.dev/api.json"
    const val MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
    const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    data class ModelEntry(
        val providerId: String?,
        val wireId: String,
        val reasoning: Boolean = false,
        val toolCalls: Boolean = false,
    )

    data class Snapshot(val fetchedAt: Long, val models: List<ModelEntry>)

    /** Minimal bundled fallback so the model picker works fully offline. */
    fun bundledSnapshot(nowMs: Long = System.currentTimeMillis()): Snapshot = Snapshot(
        fetchedAt = nowMs,
        models = listOf(
            ModelEntry("openai", "gpt-4o-mini", reasoning = false, toolCalls = true),
            ModelEntry("anthropic", "claude-haiku-4-5", reasoning = false, toolCalls = true),
            ModelEntry("google", "gemini-2-flash", reasoning = true, toolCalls = true),
        ),
    )

    /**
     * Parse either the nested provider/model dictionary or the legacy flat
     * `{ "models": [ {"id": "provider/model", ...} ] }` / array forms.
     * For nested dictionaries, the model dictionary key is the verbatim wire
     * ID, even when it contains `/`. For legacy IDs only, the first slash
     * separates provider identity from wire ID. Malformed entries are skipped.
     */
    fun parse(body: String, nowMs: Long = System.currentTimeMillis()): Snapshot {
        val root = try {
            MiniJson.parse(body)
        } catch (e: MiniJson.MiniJsonException) {
            throw SnapshotException("MODELS_SNAPSHOT_PARSE", e)
        }

        val entries = when (root) {
            is MiniJson.JArr -> parseLegacyEntries(root.items)
            is MiniJson.JObj -> {
                // "models" present but not an array is a shape error, not a
                // provider directory (arr() alone cannot tell missing from
                // mistyped, which previously reinterpreted e.g.
                // {"models":{"models":{...}}} as provider "models").
                if ("models" in root.map && root.map["models"] !is MiniJson.JArr) {
                    throw SnapshotException("MODELS_SNAPSHOT_SHAPE")
                }
                val legacyItems = root.arr("models")
                if (legacyItems != null) {
                    parseLegacyEntries(legacyItems.items)
                } else {
                    parseProviderDirectory(root)
                }
            }
            else -> throw SnapshotException("MODELS_SNAPSHOT_SHAPE")
        }
        return Snapshot(nowMs, entries)
    }

    private fun parseLegacyEntries(items: List<MiniJson.J>): List<ModelEntry> {
        val entries = ArrayList<ModelEntry>()
        for (item in items) {
            val obj = item as? MiniJson.JObj ?: continue
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: continue
            val slash = id.indexOf('/')
            val isQualified = slash > 0 && slash < id.lastIndex
            val providerId = if (isQualified) id.substring(0, slash) else null
            val wireId = if (isQualified) id.substring(slash + 1) else id
            entries.add(modelEntry(providerId, wireId, obj))
        }
        return entries
    }

    private fun parseProviderDirectory(root: MiniJson.JObj): List<ModelEntry> {
        val entries = ArrayList<ModelEntry>()
        var hasProviderModelsObject = false
        for ((providerId, providerValue) in root.map) {
            val provider = providerValue as? MiniJson.JObj ?: continue
            val models = provider.obj("models") ?: continue
            hasProviderModelsObject = true
            for ((wireId, modelValue) in models.map) {
                val model = modelValue as? MiniJson.JObj ?: continue
                if (providerId.isBlank() || wireId.isBlank()) continue
                // Nested dictionary keys are already wire IDs; do not split or
                // infer them from metadata, including when they contain '/'.
                entries.add(modelEntry(providerId, wireId, model))
            }
        }
        if (!hasProviderModelsObject) throw SnapshotException("MODELS_SNAPSHOT_SHAPE")
        return entries
    }

    private fun modelEntry(providerId: String?, wireId: String, obj: MiniJson.JObj): ModelEntry = ModelEntry(
        providerId = providerId,
        wireId = wireId,
        reasoning = obj.bool("reasoning") == true,
        toolCalls = obj.bool("tool_call")
            ?: (obj.bool("tool_calls") == true || obj.bool("tools") == true),
    )

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

    /** Add matching provider wire IDs after live IDs, preserving stable order. */
    fun mergeForProvider(
        liveModelIds: List<String>,
        snapshot: Snapshot,
        providerId: String?,
    ): List<String> {
        val out = ArrayList<String>(liveModelIds)
        if (providerId == null) return out
        for (entry in snapshot.models) {
            if (entry.providerId == providerId && entry.wireId !in out) out.add(entry.wireId)
        }
        return out
    }

    fun isFresh(snapshot: Snapshot, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - snapshot.fetchedAt in 0..CACHE_TTL_MS

    class SnapshotException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
}
