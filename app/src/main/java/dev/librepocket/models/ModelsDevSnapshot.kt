package dev.librepocket.models

import java.io.IOException
import java.io.InterruptedIOException
import dev.librepocket.provider.MiniJson
import dev.librepocket.provider.arr
import dev.librepocket.provider.bool
import dev.librepocket.provider.obj
import dev.librepocket.provider.string
import dev.librepocket.provider.validateBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * models.dev snapshot reader/fallback (P1 SPEC §1.2 / §10.3).
 *
 * Decouples "model list refresh" from app releases (ARCHITECTURE §12):
 * - [fetchSnapshotOutcome] pulls the directory through an injectable [fetcher]
 *   and reports remote versus bundled source with a stable fallback reason.
 *   Caller cancellation propagates and is never converted to fallback.
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

    enum class SnapshotSource { REMOTE_DIRECTORY, BUNDLED }

    enum class SnapshotFallbackReason {
        INVALID_URL,
        HTTP_STATUS,
        WIRE_BODY_TOO_LARGE,
        DECODED_BODY_TOO_LARGE,
        TRUNCATED_BODY,
        UNSUPPORTED_CONTENT_ENCODING,
        EMPTY_BODY,
        NETWORK_ERROR,
        TIMEOUT,
        INVALID_JSON,
        INVALID_SHAPE,
        EMPTY_DIRECTORY,
    }

    data class SnapshotFetchOutcome(
        val snapshot: Snapshot,
        val source: SnapshotSource,
        val fallbackReason: SnapshotFallbackReason? = null,
        val httpStatusCode: Int? = null,
    ) {
        init {
            require((source == SnapshotSource.REMOTE_DIRECTORY) == (fallbackReason == null))
            require((fallbackReason == SnapshotFallbackReason.HTTP_STATUS) == (httpStatusCode != null))
        }
    }

    /** Safe typed transport result; no exception text, URL, headers, or body is retained. */
    class FetchException(
        val reason: SnapshotFallbackReason,
        val httpStatusCode: Int? = null,
    ) : IOException("MODELS_DIRECTORY_${reason.name}") {
        init {
            require((reason == SnapshotFallbackReason.HTTP_STATUS) == (httpStatusCode != null))
        }
    }

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
     * Compatibility view of [fetchSnapshotOutcome] that returns only its
     * [Snapshot]. [fetcher] performs `GET(url)` and must not send Authorization.
     * Fetch and parse failures use [bundledSnapshot]; caller cancellation propagates.
     */
    suspend fun fetchSnapshot(
        url: String = DEFAULT_DIRECTORY_URL,
        nowMs: Long = System.currentTimeMillis(),
        fetcher: suspend (String) -> String,
    ): Snapshot = fetchSnapshotOutcome(url, nowMs, fetcher).snapshot

    /** Fetch and retain only safe, stable source/fallback metadata. */
    suspend fun fetchSnapshotOutcome(
        url: String = DEFAULT_DIRECTORY_URL,
        nowMs: Long = System.currentTimeMillis(),
        fetcher: suspend (String) -> String,
    ): SnapshotFetchOutcome {
        fun bundled(
            reason: SnapshotFallbackReason,
            statusCode: Int? = null,
        ) = SnapshotFetchOutcome(
            snapshot = bundledSnapshot(nowMs),
            source = SnapshotSource.BUNDLED,
            fallbackReason = reason,
            httpStatusCode = statusCode,
        )

        try {
            validateBaseUrl(url)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return bundled(SnapshotFallbackReason.INVALID_URL)
        }

        val body = try {
            fetcher(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchException) {
            currentCoroutineContext().ensureActive()
            return bundled(e.reason, e.httpStatusCode)
        } catch (e: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            return bundled(SnapshotFallbackReason.TIMEOUT)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return bundled(SnapshotFallbackReason.NETWORK_ERROR)
        }

        currentCoroutineContext().ensureActive()
        if (body.isEmpty()) {
            currentCoroutineContext().ensureActive()
            return bundled(SnapshotFallbackReason.EMPTY_BODY)
        }
        if (body.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES) {
            currentCoroutineContext().ensureActive()
            return bundled(SnapshotFallbackReason.DECODED_BODY_TOO_LARGE)
        }

        val parsed = try {
            parse(body, nowMs)
        } catch (e: SnapshotException) {
            currentCoroutineContext().ensureActive()
            return bundled(
                when (e.message) {
                    "MODELS_SNAPSHOT_SHAPE" -> SnapshotFallbackReason.INVALID_SHAPE
                    else -> SnapshotFallbackReason.INVALID_JSON
                },
            )
        }
        currentCoroutineContext().ensureActive()
        if (parsed.models.isEmpty()) return bundled(SnapshotFallbackReason.EMPTY_DIRECTORY)
        return SnapshotFetchOutcome(
            snapshot = parsed,
            source = SnapshotSource.REMOTE_DIRECTORY,
        )
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
