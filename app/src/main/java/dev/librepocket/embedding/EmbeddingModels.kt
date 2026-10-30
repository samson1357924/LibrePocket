package dev.librepocket.embedding

import kotlin.math.sqrt

/**
 * OpenAI Embeddings API request structure.
 *
 * Enforces validation contracts:
 * - [input] must not be empty.
 * - [input] must not exceed [MAX_BATCH_SIZE] items.
 * - No item in [input] may be blank (empty or whitespace only).
 * - [dimensions], if specified, must be a strictly positive integer (> 0).
 */
data class EmbeddingRequest(
    val input: List<String>,
    val model: String = DEFAULT_MODEL,
    val dimensions: Int? = null,
    val user: String? = null,
) {
    init {
        require(input.isNotEmpty()) { "input cannot be empty" }
        require(input.size <= MAX_BATCH_SIZE) { "batch size ${input.size} exceeds maximum limit of $MAX_BATCH_SIZE" }
        require(input.none { it.isBlank() }) { "input items cannot be blank" }
        require(dimensions == null || dimensions > 0) { "dimensions must be positive" }
    }

    companion object {
        const val DEFAULT_MODEL = "text-embedding-3-small"
        const val MAX_BATCH_SIZE = 2048
    }
}

/**
 * Single embedding vector result for an input item.
 */
data class EmbeddingItem(
    val index: Int,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingItem
        if (index != other.index) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + embedding.contentHashCode()
        return result
    }

    override fun toString(): String =
        "EmbeddingItem(index=$index, embedding=<${embedding.size} dimensions>)"
}

/**
 * Structured OpenAI Embeddings response.
 */
data class EmbeddingResponse(
    val data: List<EmbeddingItem>,
    val model: String,
    val promptTokens: Int,
    val totalTokens: Int,
)

/**
 * Normalized embedding error codes.
 */
enum class EmbeddingErrorCode {
    INVALID_REQUEST,
    UNAUTHORIZED,
    RATE_LIMITED,
    SERVER_ERROR,
    SERVICE_UNAVAILABLE,
    RESPONSE_TOO_LARGE,
    ENDPOINT_NOT_ALLOWED,
    INVALID_RESPONSE,
    NETWORK_ERROR,
}

/**
 * Domain exception for embeddings operations with safe, redacted messages.
 */
class EmbeddingException(
    val statusCode: Int? = null,
    val errorCode: EmbeddingErrorCode,
    val retryable: Boolean,
    val safeMessage: String,
    cause: Throwable? = null,
) : RuntimeException(safeMessage, cause) {
    override fun toString(): String =
        "EmbeddingException(statusCode=$statusCode, errorCode=$errorCode, retryable=$retryable, message=$safeMessage)"
}

/**
 * Pure Kotlin vector math utilities with defense against dimension mismatch,
 * zero vectors, NaN/Inf, and floating point overflow.
 */
object VectorMath {

    /**
     * Calculates the dot product of two vectors [a] and [b].
     * Throws [IllegalArgumentException] if dimensions do not match.
     *
     * Defenses:
     * - Returns 0.0f if either vector contains NaN or Infinity, or is empty.
     * - Clamps to [-Float.MAX_VALUE, Float.MAX_VALUE] to safely prevent overflow and guarantee a finite result.
     */
    fun dotProduct(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimensions must match: ${a.size} != ${b.size}" }
        if (a.isEmpty()) return 0.0f
        var sum = 0.0
        for (i in a.indices) {
            val va = a[i]
            val vb = b[i]
            if (va.isNaN() || va.isInfinite() || vb.isNaN() || vb.isInfinite()) {
                return 0.0f
            }
            sum += va.toDouble() * vb.toDouble()
        }
        if (sum.isNaN()) return 0.0f
        if (sum > Float.MAX_VALUE.toDouble()) return Float.MAX_VALUE
        if (sum < -Float.MAX_VALUE.toDouble()) return -Float.MAX_VALUE
        val res = sum.toFloat()
        return if (res.isFinite()) res else 0.0f
    }

    /**
     * Calculates the L2 (Euclidean) norm of vector [a].
     *
     * Defenses:
     * - Returns 0.0f if the vector is empty, a zero vector, or contains NaN/Infinity.
     * - Uses scaled accumulation (dividing by maxVal) to prevent overflow during squaring.
     * - Clamps to [Float.MAX_VALUE] if the mathematically scaled norm exceeds the maximum finite Float.
     */
    fun l2Norm(a: FloatArray): Float {
        if (a.isEmpty()) return 0.0f
        var maxVal = 0.0
        for (x in a) {
            if (x.isNaN() || x.isInfinite()) return 0.0f
            val abs = kotlin.math.abs(x.toDouble())
            if (abs > maxVal) maxVal = abs
        }
        if (maxVal == 0.0) return 0.0f

        var sumSq = 0.0
        for (x in a) {
            val scaled = x.toDouble() / maxVal
            sumSq += scaled * scaled
        }
        val norm = maxVal * sqrt(sumSq)
        if (norm.isNaN() || norm.isInfinite()) return 0.0f
        if (norm > Float.MAX_VALUE.toDouble()) return Float.MAX_VALUE
        val res = norm.toFloat()
        return if (res.isFinite()) res else 0.0f
    }

    /**
     * Computes the cosine similarity between vectors [a] and [b].
     *
     * Defenses:
     * - Throws [IllegalArgumentException] on dimension mismatch.
     * - Returns 0.0f if either vector has norm 0 (zero vector) or is empty.
     * - Returns 0.0f if either vector or the calculation contains NaN or Infinity.
     * - Uses scaled accumulation to prevent overflow even with [Float.MAX_VALUE].
     * - Clamps the final similarity to the range `[-1.0f, 1.0f]`.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimensions must match: ${a.size} != ${b.size}" }
        if (a.isEmpty()) return 0.0f

        var maxA = 0.0
        var maxB = 0.0
        for (i in a.indices) {
            val va = a[i]
            val vb = b[i]
            if (va.isNaN() || va.isInfinite() || vb.isNaN() || vb.isInfinite()) {
                return 0.0f
            }
            val absA = kotlin.math.abs(va.toDouble())
            val absB = kotlin.math.abs(vb.toDouble())
            if (absA > maxA) maxA = absA
            if (absB > maxB) maxB = absB
        }

        if (maxA == 0.0 || maxB == 0.0) {
            return 0.0f
        }

        var dot = 0.0
        var normASq = 0.0
        var normBSq = 0.0

        for (i in a.indices) {
            val sa = a[i].toDouble() / maxA
            val sb = b[i].toDouble() / maxB
            dot += sa * sb
            normASq += sa * sa
            normBSq += sb * sb
        }

        val normA = sqrt(normASq)
        val normB = sqrt(normBSq)
        val denominator = normA * normB

        if (denominator == 0.0 || denominator.isNaN() || denominator.isInfinite()) {
            return 0.0f
        }

        val sim = (dot / denominator).toFloat()
        if (sim.isNaN() || sim.isInfinite()) {
            return 0.0f
        }

        return sim.coerceIn(-1.0f, 1.0f)
    }

    /**
     * Returns the top [k] candidates sorted by cosine similarity to [query] in descending order.
     * Returns an empty list if [k] <= 0 or [candidates] is empty.
     */
    fun <T> topK(
        query: FloatArray,
        candidates: List<Pair<T, FloatArray>>,
        k: Int,
    ): List<Pair<T, Float>> {
        if (k <= 0 || candidates.isEmpty()) return emptyList()
        return candidates
            .map { (item, vec) -> item to cosineSimilarity(query, vec) }
            .sortedByDescending { it.second }
            .take(k)
    }
}
