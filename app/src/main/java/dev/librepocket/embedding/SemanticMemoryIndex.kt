package dev.librepocket.embedding

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * An indexed entry containing an identifier, text, embedding vector, and optional metadata.
 */
data class SemanticEntry<T>(
    val id: String,
    val text: String,
    val embedding: FloatArray,
    val metadata: T? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SemanticEntry<*>
        if (id != other.id) return false
        if (text != other.text) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (metadata != other.metadata) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (metadata?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "SemanticEntry(id=$id, text=$text, embedding=<${embedding.size} dimensions>, metadata=$metadata)"
}

/**
 * Result of a semantic search operation containing the matching entry and its cosine similarity score.
 */
data class SemanticSearchResult<T>(
    val entry: SemanticEntry<T>,
    val score: Float,
)

private class ScoredEntry<T>(
    val entry: SemanticEntry<T>,
    val score: Float,
)

/**
 * Lightweight, thread-safe in-memory semantic memory index and retrieval module.
 *
 * Combines [OpenAiEmbeddingClient] and [VectorMath] to provide bounded semantic memory
 * indexing, cosine similarity ranking, and defensive fail-closed validation.
 *
 * ### Memory Safety & Mobile Capacity Budget Model
 * - **Triple-Dimension Memory Bounds**:
 *   - Memory consumption is strictly bounded across three dimensions:
 *     1. Entry count: [maxEntries] (bounded by [MAX_ALLOWED_ENTRIES] = 10,000).
 *     2. Embedding dimension: [MAX_SUPPORTED_DIMENSION] (3072, e.g. `text-embedding-3-large`).
 *     3. Text length: [MAX_TEXT_CHARS] (2048 characters, standard semantic chunk / snippet size ~500–1000 tokens).
 *   - In addition, search retrieval is bounded by [MAX_SEARCH_K] (100) to prevent excessive transient allocations.
 * - **Memory Budget Estimation Model**:
 *   - *Per-entry footprint*:
 *     - Embedding vector payload: 1,536 floats * 4 bytes = 6,144 bytes (~6 KiB) or 3,072 floats * 4 bytes = 12,288 bytes (~12 KiB) + JVM array overhead.
 *     - [SemanticEntry] wrapper & [LinkedHashMap] entry node overhead: ~64–128 bytes.
 *     - Managed ID and text snippets: bounded by [MAX_ID_CHARS] = 256 and [MAX_TEXT_CHARS] = 2048.
 *   - *Default configuration (2,000 entries, 1536 dimensions, average 500 characters)*:
 *     - Heap footprint is approximately 18–25 MiB, well-suited for standard mobile environments.
 *   - *Worst-case / extreme boundary limit (10,000 entries, 3072 dimensions, max 2048 characters)*:
 *     - Total index-managed static retained index storage has an estimated upper bound (actual heap usage varies by runtime and object layout) of approx. 160 MiB
 *       (~12 KiB vector + ~4 KiB text + ID & wrapper overhead ≈ 16.5 KiB per entry * 10,000 entries).
 *     - Note: This estimated ~160 MiB retained memory may exceed constrained Android heaps (<64 MiB / <128 MiB);
 *       callers must configure [maxEntries] defensively based on their application's heap budget rather than assuming zero risk.
 *   - *Retained Storage vs. Transient Operation Peaks (Ephemeral Allocations)*:
 *     - The ~160 MiB figure strictly represents **static retained index storage** (persisting in-memory entries).
 *     - Callers must not equate static retained storage with total peak memory during operations. Batch operations
 *       (such as large-batch [addAll]) incur ephemeral allocations during execution: input snapshot lists, API response vectors,
 *       defensive array copies, and returned entry lists. Temporary operational heap peaks can reach 2x–3x the retained data size
 *       before unreferenced objects are garbage collected.
 *     - To constrain transient batch payloads, [addAll] strictly enforces [MAX_BATCH_SIZE] = 100 items per call
 *       (under 1.2 MiB for 3072-dimensional raw vectors).
 * - **Dimension Boundary**:
 *   - [MAX_SUPPORTED_DIMENSION] is 3072 (matching `text-embedding-3-large`, the maximum official OpenAI embedding dimension;
 *     `text-embedding-3-small` is 1536), eliminating extreme workloads from 8192-dimensional vectors.
 * - **Managed Memory Boundary**:
 *   - Memory bounds strictly cover index-managed data: text, embedding float arrays, and ID strings.
 *   - Caller-supplied generic [metadata] objects ([T]) are held by reference; their heap footprint
 *     remains the responsibility of the caller.
 *
 * @param client Optional [OpenAiEmbeddingClient]. If null, only vector-direct operations are supported.
 * @param maxEntries Upper bound on the number of entries (entry count capacity / 項目筆數上限).
 *                   Must be between 1 and [MAX_ALLOWED_ENTRIES] (default: [DEFAULT_MAX_ENTRIES] = 2,000,
 *                   maximum allowed: [MAX_ALLOWED_ENTRIES] = 10,000).
 *                   Combined with [MAX_SUPPORTED_DIMENSION] (3072), [MAX_TEXT_CHARS] (2048), and [MAX_ID_CHARS] (256),
 *                   strictly bounds index-managed memory consumption.
 * @param model Model identifier used when invoking [client] for text embeddings (max length [MAX_MODEL_CHARS]).
 */
class SemanticMemoryIndex<T>(
    private val client: OpenAiEmbeddingClient? = null,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val model: String = DEFAULT_MODEL,
) {
    init {
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) {
            "maxEntries must be between 1 and $MAX_ALLOWED_ENTRIES, got: $maxEntries"
        }
        require(model.isNotBlank()) { "model cannot be blank" }
        require(model.length <= MAX_MODEL_CHARS) {
            "model length ${model.length} exceeds maximum allowed length of $MAX_MODEL_CHARS characters"
        }
    }

    private val rwLock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, SemanticEntry<T>>()

    @Volatile
    private var dimension: Int? = null

    /**
     * Currently bound embedding dimension, or null if the index is empty.
     */
    val currentDimension: Int?
        get() = rwLock.read { dimension }

    /**
     * Embeds the given [text] via [client] and stores it in the index.
     *
     * Pre-checks capacity under read lock before sending network requests to prevent wasted API quota.
     *
     * @throws IllegalStateException if [client] is not provided or capacity limit is reached.
     * @throws IllegalArgumentException if [id] or [text] is blank, [id] exceeds [MAX_ID_CHARS],
     *                                  [text] exceeds [MAX_TEXT_CHARS], or vector dimension mismatches.
     */
    suspend fun add(id: String, text: String, metadata: T? = null): SemanticEntry<T> {
        require(id.isNotBlank()) { "id cannot be blank" }
        require(id.length <= MAX_ID_CHARS) {
            "id length ${id.length} exceeds maximum allowed length of $MAX_ID_CHARS characters"
        }
        require(text.isNotBlank()) { "text cannot be blank" }
        require(text.length <= MAX_TEXT_CHARS) {
            "text length ${text.length} exceeds maximum allowed length of $MAX_TEXT_CHARS characters"
        }

        val nonNullClient = client
            ?: throw IllegalStateException("OpenAiEmbeddingClient is required for text embedding operations")

        rwLock.read {
            if (!entries.containsKey(id) && entries.size >= maxEntries) {
                throw IllegalStateException("Index entry limit of $maxEntries reached")
            }
        }

        val embedding = nonNullClient.embed(text = text, model = model)
        return addDirect(id = id, text = text, embedding = embedding, metadata = metadata)
    }

    /**
     * Embeds multiple [items] in batches via [client] and inserts them into the index atomically.
     *
     * Validates all items, checks batch size against [maxEntries] and [MAX_BATCH_SIZE], and deduplicates duplicate IDs
     * within the batch (retaining the latest item for each ID) to prevent wasted embedding API quota.
     * Takes an immutable bounded snapshot of [items] at the beginning of the call.
     * Pre-checks capacity under read lock before sending any network request to avoid wasted API calls.
     * Double-checks capacity under write lock during final insertion to guard against concurrent writes.
     *
     * Note: Executing batch operations incurs ephemeral memory allocations (input snapshots,
     * API response vectors, and returned lists). Batch size is strictly hard-bounded by [MAX_BATCH_SIZE]
     * (100 items, ~1.2 MiB for 3072-dim raw vector payloads) to bound batch allocations.
     *
     * @param items Iterable of (id, text, metadata) triples.
     * @throws IllegalStateException if [client] is not provided or adding items would exceed [maxEntries].
     * @throws IllegalArgumentException if batch size exceeds [maxEntries] or [MAX_BATCH_SIZE], any id or text is blank,
     *                                  [id] exceeds [MAX_ID_CHARS], [text] exceeds [MAX_TEXT_CHARS],
     *                                  or vector dimensions mismatch.
     */
    suspend fun addAll(items: Iterable<Triple<String, String, T?>>): List<SemanticEntry<T>> {
        val maxBatch = minOf(maxEntries, MAX_BATCH_SIZE)
        if (items is Collection<*>) {
            require(items.size <= maxEntries) {
                "Batch size (${items.size}) exceeds maxEntries ($maxEntries)"
            }
            require(items.size <= MAX_BATCH_SIZE) {
                "Batch size (${items.size}) exceeds MAX_BATCH_SIZE ($MAX_BATCH_SIZE)"
            }
            if (items.isEmpty()) return emptyList()
        }

        val targetCap = if (items is Collection<*>) minOf(items.size, maxBatch) else minOf(16, maxBatch)
        val stableItems = ArrayList<Triple<String, String, T?>>(targetCap)
        for (item in items) {
            if (stableItems.size >= maxBatch) {
                if (maxEntries < MAX_BATCH_SIZE) {
                    throw IllegalArgumentException("Batch size exceeds maxEntries ($maxEntries)")
                } else {
                    throw IllegalArgumentException("Batch size exceeds MAX_BATCH_SIZE ($MAX_BATCH_SIZE)")
                }
            }
            stableItems.add(item)
        }
        if (stableItems.isEmpty()) return emptyList()

        require(stableItems.size <= minOf(maxEntries, MAX_BATCH_SIZE)) {
            "Batch size (${stableItems.size}) exceeds MAX_BATCH_SIZE ($MAX_BATCH_SIZE)"
        }

        for ((id, text, _) in stableItems) {
            require(id.isNotBlank()) { "id cannot be blank" }
            require(id.length <= MAX_ID_CHARS) {
                "id length ${id.length} exceeds maximum allowed length of $MAX_ID_CHARS characters"
            }
            require(text.isNotBlank()) { "text cannot be blank" }
            require(text.length <= MAX_TEXT_CHARS) {
                "text length ${text.length} exceeds maximum allowed length of $MAX_TEXT_CHARS characters"
            }
        }

        val convergedItems = stableItems.associateBy { it.first }.values.toList()

        val nonNullClient = client
            ?: throw IllegalStateException("OpenAiEmbeddingClient is required for text embedding operations")

        val uniqueNewIds = convergedItems.map { it.first }.toSet()
        rwLock.read {
            val newCount = uniqueNewIds.count { !entries.containsKey(it) }
            if (entries.size + newCount > maxEntries) {
                throw IllegalStateException(
                    "Index entry limit of $maxEntries reached: cannot add $newCount new entries to existing ${entries.size}"
                )
            }
        }

        val allEmbeddings = ArrayList<FloatArray>(convergedItems.size)
        for (chunk in convergedItems.chunked(EmbeddingRequest.MAX_BATCH_SIZE)) {
            val response = nonNullClient.createEmbeddings(
                EmbeddingRequest(
                    input = chunk.map { it.second },
                    model = model,
                )
            )
            val sorted = response.data.sortedBy { it.index }
            for (item in sorted) {
                allEmbeddings.add(item.embedding)
            }
        }

        return rwLock.write {
            val currentNewCount = uniqueNewIds.count { !entries.containsKey(it) }
            if (entries.size + currentNewCount > maxEntries) {
                throw IllegalStateException(
                    "Index entry limit of $maxEntries reached: cannot add $currentNewCount new entries to existing ${entries.size}"
                )
            }

            var targetDim = dimension
            for (emb in allEmbeddings) {
                require(emb.isNotEmpty()) { "embedding cannot be empty" }
                require(emb.size <= MAX_SUPPORTED_DIMENSION) {
                    "Embedding dimension ${emb.size} exceeds maximum supported dimension of $MAX_SUPPORTED_DIMENSION"
                }
                if (targetDim == null) {
                    targetDim = emb.size
                } else if (targetDim != emb.size) {
                    throw IllegalArgumentException("Vector dimension mismatch: expected $targetDim, got ${emb.size}")
                }
            }

            dimension = targetDim
            convergedItems.mapIndexed { index, (id, text, meta) ->
                val emb = allEmbeddings[index]
                val entry = SemanticEntry(id, text, emb.copyOf(), meta)
                entries[id] = entry
                entry.copy(embedding = entry.embedding.copyOf())
            }
        }
    }

    /**
     * Inserts a precomputed vector directly into the index without calling [client].
     *
     * @throws IllegalStateException if capacity limit is reached for a new entry.
     * @throws IllegalArgumentException if [id] or [text] is blank, [id] exceeds [MAX_ID_CHARS],
     *                                  [text] exceeds [MAX_TEXT_CHARS], [embedding] is empty
     *                                  or exceeds [MAX_SUPPORTED_DIMENSION], or dimension mismatches.
     */
    fun addDirect(id: String, text: String, embedding: FloatArray, metadata: T? = null): SemanticEntry<T> {
        require(id.isNotBlank()) { "id cannot be blank" }
        require(id.length <= MAX_ID_CHARS) {
            "id length ${id.length} exceeds maximum allowed length of $MAX_ID_CHARS characters"
        }
        require(text.isNotBlank()) { "text cannot be blank" }
        require(text.length <= MAX_TEXT_CHARS) {
            "text length ${text.length} exceeds maximum allowed length of $MAX_TEXT_CHARS characters"
        }
        require(embedding.isNotEmpty()) { "embedding cannot be empty" }
        require(embedding.size <= MAX_SUPPORTED_DIMENSION) {
            "Embedding dimension ${embedding.size} exceeds maximum supported dimension of $MAX_SUPPORTED_DIMENSION"
        }

        return rwLock.write {
            if (!entries.containsKey(id) && entries.size >= maxEntries) {
                throw IllegalStateException("Index entry limit of $maxEntries reached")
            }

            val curDim = dimension
            if (curDim != null && embedding.size != curDim) {
                throw IllegalArgumentException("Vector dimension mismatch: expected $curDim, got ${embedding.size}")
            }

            if (curDim == null) {
                dimension = embedding.size
            }

            val entry = SemanticEntry(id, text, embedding.copyOf(), metadata)
            entries[id] = entry
            entry.copy(embedding = entry.embedding.copyOf())
        }
    }

    /**
     * Searches the index by textual query using [client] to generate the query embedding.
     *
     * @param query Non-blank search query.
     * @param k Maximum number of top results to return (must be in 1..[MAX_SEARCH_K], default: 5).
     * @param minScore Minimum cosine similarity threshold (default: 0.0f).
     * @throws IllegalStateException if [client] is not provided.
     * @throws IllegalArgumentException if [query] is blank, [query] exceeds [MAX_TEXT_CHARS],
     *                                  [k] is not in 1..[MAX_SEARCH_K], or [minScore] is NaN.
     */
    suspend fun search(query: String, k: Int = 5, minScore: Float = 0.0f): List<SemanticSearchResult<T>> {
        require(query.isNotBlank()) { "query cannot be blank" }
        require(query.length <= MAX_TEXT_CHARS) {
            "query length ${query.length} exceeds maximum allowed length of $MAX_TEXT_CHARS characters"
        }
        require(k in 1..MAX_SEARCH_K) { "k must be in 1..$MAX_SEARCH_K, was: $k" }
        require(!minScore.isNaN()) { "minScore cannot be NaN" }

        val nonNullClient = client
            ?: throw IllegalStateException("OpenAiEmbeddingClient is required for text search operations")

        val queryVector = nonNullClient.embed(text = query, model = model)
        return searchByVector(queryVector = queryVector, k = k, minScore = minScore)
    }

    /**
     * Searches the index using a raw [queryVector].
     *
     * Evaluates cosine similarity under a read lock without cloning vector payloads during ranking,
     * defensively copying only the final top-[k] entries to prevent transient memory spikes.
     *
     * @param queryVector Raw query embedding vector.
     * @param k Maximum number of top results to return (must be in 1..[MAX_SEARCH_K], default: 5).
     * @param minScore Minimum cosine similarity threshold (default: 0.0f).
     * @throws IllegalArgumentException if [queryVector] is empty, exceeds [MAX_SUPPORTED_DIMENSION],
     *                                  [k] is not in 1..[MAX_SEARCH_K], [minScore] is NaN, or dimensions mismatch.
     */
    fun searchByVector(queryVector: FloatArray, k: Int = 5, minScore: Float = 0.0f): List<SemanticSearchResult<T>> {
        require(queryVector.isNotEmpty()) { "queryVector cannot be empty" }
        require(queryVector.size <= MAX_SUPPORTED_DIMENSION) {
            "Query vector dimension ${queryVector.size} exceeds maximum supported dimension of $MAX_SUPPORTED_DIMENSION"
        }
        require(k in 1..MAX_SEARCH_K) { "k must be in 1..$MAX_SEARCH_K, was: $k" }
        require(!minScore.isNaN()) { "minScore cannot be NaN" }

        return rwLock.read {
            if (entries.isEmpty()) return emptyList()

            val curDim = dimension
            if (curDim != null && queryVector.size != curDim) {
                throw IllegalArgumentException("Query vector dimension mismatch: expected $curDim, got ${queryVector.size}")
            }

            val candidates = ArrayList<ScoredEntry<T>>()
            for (entry in entries.values) {
                val score = VectorMath.cosineSimilarity(queryVector, entry.embedding)
                if (score >= minScore) {
                    candidates.add(ScoredEntry(entry, score))
                }
            }

            candidates.sortByDescending { it.score }
            val count = minOf(k, candidates.size)
            val results = ArrayList<SemanticSearchResult<T>>(count)
            for (i in 0 until count) {
                val scored = candidates[i]
                results.add(
                    SemanticSearchResult(
                        entry = scored.entry.copy(embedding = scored.entry.embedding.copyOf()),
                        score = scored.score,
                    )
                )
            }
            results
        }
    }

    /**
     * Removes the entry with [id]. If the index becomes empty, dimension binding is reset.
     *
     * @return true if an entry was removed, false otherwise.
     * @throws IllegalArgumentException if [id] is blank or exceeds [MAX_ID_CHARS].
     */
    fun remove(id: String): Boolean {
        require(id.isNotBlank()) { "id cannot be blank" }
        require(id.length <= MAX_ID_CHARS) {
            "id length ${id.length} exceeds maximum allowed length of $MAX_ID_CHARS characters"
        }
        return rwLock.write {
            val removed = entries.remove(id) != null
            if (entries.isEmpty()) {
                dimension = null
            }
            removed
        }
    }

    /**
     * Clears all entries and resets the dimension binding.
     */
    fun clear() {
        rwLock.write {
            entries.clear()
            dimension = null
        }
    }

    /**
     * Returns the current number of indexed entries.
     */
    fun size(): Int = rwLock.read { entries.size }

    /**
     * Retrieves the entry by [id], or null if not found.
     *
     * @throws IllegalArgumentException if [id] is blank or exceeds [MAX_ID_CHARS].
     */
    fun get(id: String): SemanticEntry<T>? {
        require(id.isNotBlank()) { "id cannot be blank" }
        require(id.length <= MAX_ID_CHARS) {
            "id length ${id.length} exceeds maximum allowed length of $MAX_ID_CHARS characters"
        }
        return rwLock.read {
            entries[id]?.let { it.copy(embedding = it.embedding.copyOf()) }
        }
    }

    companion object {
        /**
         * Default entry count capacity (2,000 entries).
         * Under default conditions (1536 dimensions, avg. 500 chars), consumes approx. 18–25 MiB,
         * well-suited for standard mobile devices.
         */
        const val DEFAULT_MAX_ENTRIES = 2_000

        /**
         * Hard upper bound on entry count capacity (10,000 entries) tailored to mobile memory constraints.
         * Under extreme boundary conditions (3072 dimensions, 2048 chars), consumes approx. 160 MiB.
         */
        const val MAX_ALLOWED_ENTRIES = 10_000

        /**
         * Backwards-compatible alias for [MAX_ALLOWED_ENTRIES].
         */
        const val MAX_ALLOWED_MAX_ENTRIES = MAX_ALLOWED_ENTRIES

        const val DEFAULT_MODEL = "text-embedding-3-small"

        /**
         * Maximum supported embedding dimension (3072).
         * Matches the maximum official OpenAI embedding dimension (`text-embedding-3-large`).
         */
        const val MAX_SUPPORTED_DIMENSION = 3072

        /**
         * Maximum allowed batch size for a single [addAll] operation (100 entries).
         * Hard-capping batch writes to 100 items bounds transient raw vector payloads (~1.2 MiB
         * for 100 items * 3072 dims * 4 bytes).
         */
        const val MAX_BATCH_SIZE = 100

        /**
         * Maximum allowed characters for indexed text and search queries (2,048 chars).
         * Tailored for semantic memory chunks / snippets (~500–1000 tokens), preventing excessive heap consumption.
         */
        const val MAX_TEXT_CHARS = 2048

        /**
         * Maximum allowed top-K retrieval limit (100) to prevent excessive result copying and allocations during search.
         */
        const val MAX_SEARCH_K = 100

        const val MAX_ID_CHARS = 256
        const val MAX_MODEL_CHARS = 128
    }
}
