package dev.librepocket.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingModelsTest {

    @Test
    fun embeddingRequestValidInput() {
        val req = EmbeddingRequest(
            input = listOf("hello world", "foo bar"),
            model = "text-embedding-3-small",
            dimensions = 512,
            user = "user_123",
        )
        assertEquals(2, req.input.size)
        assertEquals("text-embedding-3-small", req.model)
        assertEquals(512, req.dimensions)
        assertEquals("user_123", req.user)
    }

    @Test
    fun embeddingRequestDefaultModel() {
        val req = EmbeddingRequest(input = listOf("test"))
        assertEquals("text-embedding-3-small", req.model)
        assertEquals(null, req.dimensions)
        assertEquals(null, req.user)
    }

    @Test
    fun embeddingRequestEmptyInputThrows() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = emptyList())
        }
        assertTrue(ex.message!!.contains("input cannot be empty"))
    }

    @Test
    fun embeddingRequestBlankInputThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf(""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("   "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("hello", "   \t\n  "))
        }
    }

    @Test
    fun embeddingRequestInvalidDimensionsThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("valid"), dimensions = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("valid"), dimensions = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("valid"), dimensions = -512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("valid"), dimensions = 3073)
        }
    }

    @Test
    fun embeddingRequestMaxDimensionsValidation() {
        assertEquals(3072, EmbeddingRequest.MAX_DIMENSIONS)

        // Valid boundary: exactly MAX_DIMENSIONS (3072)
        val reqMax = EmbeddingRequest(input = listOf("valid"), dimensions = EmbeddingRequest.MAX_DIMENSIONS)
        assertEquals(3072, reqMax.dimensions)

        // Invalid: MAX_DIMENSIONS + 1 (3073)
        val exOver = assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("valid"), dimensions = EmbeddingRequest.MAX_DIMENSIONS + 1)
        }
        assertEquals(
            "dimensions must be positive and <= 3072, got: 3073",
            exOver.message
        )

        // Invalid: 0
        val exZero = assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("valid"), dimensions = 0)
        }
        assertEquals(
            "dimensions must be positive and <= 3072, got: 0",
            exZero.message
        )

        // Invalid: negative
        val exNeg = assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = listOf("valid"), dimensions = -1)
        }
        assertEquals(
            "dimensions must be positive and <= 3072, got: -1",
            exNeg.message
        )
    }

    @Test
    fun embeddingRequestBatchSizeLimit() {
        assertEquals(100, EmbeddingRequest.MAX_BATCH_SIZE)

        // Max batch size of 100 should succeed
        val validBatch = List(100) { "item $it" }
        val req = EmbeddingRequest(input = validBatch)
        assertEquals(100, req.input.size)

        // Exceeding 100 should throw
        val overBatch = List(101) { "item $it" }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            EmbeddingRequest(input = overBatch)
        }
        assertTrue(ex.message!!.contains("exceeds maximum limit"))
    }

    @Test
    fun batchSizeAndDimensionsTiedToResponseBudgetContract() {
        // Mathematical boundary contract tying MAX_BATCH_SIZE * MAX_DIMENSIONS to the 5 MiB response budget:
        val maxBatch = EmbeddingRequest.MAX_BATCH_SIZE
        val maxDims = EmbeddingRequest.MAX_DIMENSIONS
        assertEquals(100, maxBatch)
        assertEquals(3072, maxDims)

        val totalFloats = maxBatch * maxDims // 307,200
        assertEquals(307_200, totalFloats)

        // Worst-case float JSON length: sign + '0.' + 8 decimals + comma + space <= 14 bytes
        // Even with a pessimistic 16 bytes/float + envelope overhead:
        val estimatedWorstCaseBytes = (totalFloats * 16L) + 8192L // 4,923,392 bytes
        val responseCap = 5 * 1024 * 1024L // 5,242,880 bytes

        assertTrue(
            "Max batch * max dimensions under worst-case float encoding must fit under 5 MiB cap",
            estimatedWorstCaseBytes < responseCap
        )

        // Conversely, prove why the former 2048 batch size was mathematically impossible:
        // Even for text-embedding-3-small (1536 dims), 2048 * 1536 = 3,145,728 floats.
        // Even in the impossible theoretical minimum of 2 bytes per float ("0,"),
        // 3,145,728 * 2 = 6,291,456 bytes > 5 MiB.
        val oldBatchSmallFloats = 2048L * 1536L
        val minPossibleOldPayloadBytes = oldBatchSmallFloats * 2L
        assertTrue(minPossibleOldPayloadBytes > responseCap)
    }

    @Test
    fun embeddingItemEqualsAndHashCode() {
        val item1 = EmbeddingItem(0, floatArrayOf(0.1f, 0.2f))
        val item2 = EmbeddingItem(0, floatArrayOf(0.1f, 0.2f))
        val item3 = EmbeddingItem(1, floatArrayOf(0.1f, 0.2f))
        val item4 = EmbeddingItem(0, floatArrayOf(0.1f, 0.3f))

        assertEquals(item1, item2)
        assertEquals(item1.hashCode(), item2.hashCode())
        assertNotEquals(item1, item3)
        assertNotEquals(item1, item4)
        assertTrue(item1.toString().contains("2 dimensions"))
    }

    @Test
    fun embeddingRequestToStringRedactsInputAndUser() {
        val sensitiveInput1 = "super-secret user query about passwords"
        val sensitiveInput2 = "confidential medical report data"
        val sensitiveUser = "user-id-sensitive-999"

        val req = EmbeddingRequest(
            input = listOf(sensitiveInput1, sensitiveInput2),
            model = "text-embedding-3-small",
            dimensions = 512,
            user = sensitiveUser,
        )

        val str = req.toString()
        assertFalse("Raw input item 1 must not be leaked in toString", str.contains(sensitiveInput1))
        assertFalse("Raw input item 2 must not be leaked in toString", str.contains(sensitiveInput2))
        assertFalse("Raw user id must not be leaked in toString", str.contains(sensitiveUser))

        assertEquals("EmbeddingRequest(input=<2 items>, model=text-embedding-3-small, dimensions=512, user=<redacted>)", str)

        val reqNoUser = EmbeddingRequest(
            input = listOf("single-input"),
            model = "text-embedding-3-small",
            dimensions = null,
            user = null,
        )
        val strNoUser = reqNoUser.toString()
        assertFalse(strNoUser.contains("single-input"))
        assertEquals("EmbeddingRequest(input=<1 items>, model=text-embedding-3-small, dimensions=null, user=null)", strNoUser)
    }

    @Test
    fun vectorMathDotProduct() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(4.0f, 5.0f, 6.0f)
        val dot = VectorMath.dotProduct(a, b)
        assertEquals(32.0f, dot, 1e-5f)

        // Negative values
        val c = floatArrayOf(1.0f, -2.0f)
        val d = floatArrayOf(-3.0f, 4.0f)
        assertEquals(-11.0f, VectorMath.dotProduct(c, d), 1e-5f)

        // Empty arrays
        assertEquals(0.0f, VectorMath.dotProduct(floatArrayOf(), floatArrayOf()), 0f)
    }

    @Test
    fun vectorMathDotProductDimensionMismatchThrows() {
        val a = floatArrayOf(1.0f, 2.0f)
        val b = floatArrayOf(1.0f, 2.0f, 3.0f)
        assertThrows(IllegalArgumentException::class.java) {
            VectorMath.dotProduct(a, b)
        }
    }

    @Test
    fun vectorMathL2Norm() {
        val v = floatArrayOf(3.0f, 4.0f)
        assertEquals(5.0f, VectorMath.l2Norm(v), 1e-5f)

        val zero = floatArrayOf(0.0f, 0.0f, 0.0f)
        assertEquals(0.0f, VectorMath.l2Norm(zero), 0.0f)

        val v3d = floatArrayOf(1.0f, 2.0f, 2.0f)
        assertEquals(3.0f, VectorMath.l2Norm(v3d), 1e-5f)
    }

    @Test
    fun vectorMathDotProductFloatMaxValueNoOverflow() {
        val a = floatArrayOf(Float.MAX_VALUE)
        val b = floatArrayOf(Float.MAX_VALUE)
        val dot = VectorMath.dotProduct(a, b)
        assertTrue("Dot product must be finite", dot.isFinite())
        assertEquals(Float.MAX_VALUE, dot, 0.0f)

        val negB = floatArrayOf(-Float.MAX_VALUE)
        val dotNeg = VectorMath.dotProduct(a, negB)
        assertTrue("Negative dot product must be finite", dotNeg.isFinite())
        assertEquals(-Float.MAX_VALUE, dotNeg, 0.0f)

        val multiA = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        val multiB = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        val dotMulti = VectorMath.dotProduct(multiA, multiB)
        assertTrue("Multi-element dot product must be finite", dotMulti.isFinite())
        assertEquals(Float.MAX_VALUE, dotMulti, 0.0f)
    }

    @Test
    fun vectorMathDotProductNanAndInfDefense() {
        val normal = floatArrayOf(1.0f, 2.0f)
        val nanVec = floatArrayOf(Float.NaN, 2.0f)
        val posInf = floatArrayOf(Float.POSITIVE_INFINITY, 2.0f)
        val negInf = floatArrayOf(1.0f, Float.NEGATIVE_INFINITY)

        assertEquals(0.0f, VectorMath.dotProduct(nanVec, normal), 0.0f)
        assertEquals(0.0f, VectorMath.dotProduct(normal, nanVec), 0.0f)
        assertEquals(0.0f, VectorMath.dotProduct(posInf, normal), 0.0f)
        assertEquals(0.0f, VectorMath.dotProduct(normal, negInf), 0.0f)
    }

    @Test
    fun vectorMathL2NormFloatMaxValueNoOverflow() {
        val v = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        val norm = VectorMath.l2Norm(v)
        assertTrue("L2 norm must be finite", norm.isFinite())
        assertEquals(Float.MAX_VALUE, norm, 0.0f)

        val half = Float.MAX_VALUE * 0.5f
        val vHalf = floatArrayOf(half, 0.0f)
        val normHalf = VectorMath.l2Norm(vHalf)
        assertTrue("L2 norm must be finite", normHalf.isFinite())
        assertEquals(half, normHalf, 1e20f)
    }

    @Test
    fun vectorMathL2NormNanAndInfDefense() {
        val nanVec = floatArrayOf(Float.NaN, 1.0f)
        val posInf = floatArrayOf(Float.POSITIVE_INFINITY, 1.0f)
        val negInf = floatArrayOf(1.0f, Float.NEGATIVE_INFINITY)

        assertEquals(0.0f, VectorMath.l2Norm(nanVec), 0.0f)
        assertEquals(0.0f, VectorMath.l2Norm(posInf), 0.0f)
        assertEquals(0.0f, VectorMath.l2Norm(negInf), 0.0f)
    }

    @Test
    fun vectorMathCosineSimilarityOrthogonal() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun vectorMathCosineSimilarityParallel() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(2.0f, 4.0f, 6.0f)
        assertEquals(1.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun vectorMathCosineSimilarityAntiParallel() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(-1.0f, 0.0f)
        assertEquals(-1.0f, VectorMath.cosineSimilarity(a, b), 1e-5f)
    }

    @Test
    fun vectorMathCosineSimilarityZeroVectorDefense() {
        val zero = floatArrayOf(0.0f, 0.0f)
        val normal = floatArrayOf(1.0f, 2.0f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(zero, normal), 0.0f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(normal, zero), 0.0f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(zero, zero), 0.0f)
    }

    @Test
    fun vectorMathCosineSimilarityDimensionMismatchThrows() {
        val a = floatArrayOf(1.0f, 2.0f)
        val b = floatArrayOf(1.0f, 2.0f, 3.0f)
        assertThrows(IllegalArgumentException::class.java) {
            VectorMath.cosineSimilarity(a, b)
        }
    }

    @Test
    fun vectorMathCosineSimilarityNanAndInfDefense() {
        val nanVec = floatArrayOf(Float.NaN, 1.0f)
        val normal = floatArrayOf(1.0f, 2.0f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(nanVec, normal), 0.0f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(normal, nanVec), 0.0f)

        val posInfVec = floatArrayOf(Float.POSITIVE_INFINITY, 1.0f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(posInfVec, normal), 0.0f)

        val negInfVec = floatArrayOf(1.0f, Float.NEGATIVE_INFINITY)
        assertEquals(0.0f, VectorMath.cosineSimilarity(normal, negInfVec), 0.0f)
    }

    @Test
    fun vectorMathCosineSimilarityClamping() {
        // Values identical to themselves must never exceed 1.0f
        val a = floatArrayOf(1.23456f, 7.89012f, 3.45678f)
        val sim = VectorMath.cosineSimilarity(a, a)
        assertTrue("Similarity $sim should be <= 1.0f", sim <= 1.0f)
        assertTrue("Similarity $sim should be >= -1.0f", sim >= -1.0f)
        assertEquals(1.0f, sim, 1e-5f)
    }

    @Test
    fun vectorMathCosineSimilarityNegativeZeroNormalized() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(0.0f, -1.0f)
        val sim = VectorMath.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.0f)
        assertEquals(0, java.lang.Float.floatToRawIntBits(sim))
        assertEquals(Float.POSITIVE_INFINITY, 1.0f / sim, 0.0f)

        // Also test with explicit negative zero components
        val c = floatArrayOf(0.0f, 1.0f)
        val d = floatArrayOf(-0.0f, 0.0f)
        val sim2 = VectorMath.cosineSimilarity(c, d)
        assertEquals(0.0f, sim2, 0.0f)
        assertEquals(0, java.lang.Float.floatToRawIntBits(sim2))
        assertEquals(Float.POSITIVE_INFINITY, 1.0f / sim2, 0.0f)
    }

    @Test
    fun vectorMathTopK() {
        val query = floatArrayOf(1.0f, 0.0f)
        val candidates = listOf(
            "exact" to floatArrayOf(1.0f, 0.0f),
            "close" to floatArrayOf(0.70710677f, 0.70710677f),
            "orthogonal" to floatArrayOf(0.0f, 1.0f),
            "opposite" to floatArrayOf(-1.0f, 0.0f),
        )

        val top2 = VectorMath.topK(query, candidates, 2)
        assertEquals(2, top2.size)
        assertEquals("exact", top2[0].first)
        assertEquals(1.0f, top2[0].second, 1e-5f)
        assertEquals("close", top2[1].first)
        assertEquals(0.70710677f, top2[1].second, 1e-4f)

        val topAll = VectorMath.topK(query, candidates, 10)
        assertEquals(4, topAll.size)
        assertEquals("exact", topAll[0].first)
        assertEquals("close", topAll[1].first)
        assertEquals("orthogonal", topAll[2].first)
        assertEquals("opposite", topAll[3].first)

        // k <= 0 or empty candidates
        assertEquals(emptyList<Pair<String, Float>>(), VectorMath.topK(query, candidates, 0))
        assertEquals(emptyList<Pair<String, Float>>(), VectorMath.topK(query, candidates, -1))
        assertEquals(emptyList<Pair<String, Float>>(), VectorMath.topK(query, emptyList<Pair<String, FloatArray>>(), 3))
    }

    @Test
    fun vectorMathCosineSimilarityFloatMaxValueNoOverflow() {
        val a = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        val simSame = VectorMath.cosineSimilarity(a, b)
        assertEquals("Identical Float.MAX_VALUE vectors must have similarity 1.0f", 1.0f, simSame, 1e-5f)

        val opposite = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE)
        val simOpposite = VectorMath.cosineSimilarity(a, opposite)
        assertEquals("Opposite Float.MAX_VALUE vectors must have similarity -1.0f", -1.0f, simOpposite, 1e-5f)

        val orthogonalA = floatArrayOf(Float.MAX_VALUE, 0.0f)
        val orthogonalB = floatArrayOf(0.0f, Float.MAX_VALUE)
        val simOrthogonal = VectorMath.cosineSimilarity(orthogonalA, orthogonalB)
        assertEquals("Orthogonal Float.MAX_VALUE vectors must have similarity 0.0f", 0.0f, simOrthogonal, 1e-5f)

        val denormA = floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE)
        val denormB = floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE)
        val simDenorm = VectorMath.cosineSimilarity(denormA, denormB)
        assertEquals("Identical Float.MIN_VALUE vectors must have similarity 1.0f", 1.0f, simDenorm, 1e-5f)
    }

    @Test
    fun vectorMathTopKNumericalStabilityWithExtremeValues() {
        val query = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        val candidates = listOf(
            "parallel" to floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE),
            "orthogonal" to floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE),
            "nan" to floatArrayOf(Float.NaN, Float.MAX_VALUE),
            "inf" to floatArrayOf(Float.POSITIVE_INFINITY, 1.0f),
            "opposite" to floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE),
        )

        val results = VectorMath.topK(query, candidates, 5)
        assertEquals(5, results.size)
        assertEquals("parallel", results[0].first)
        assertEquals(1.0f, results[0].second, 1e-5f)

        // NaN and Inf candidates are safely assigned 0.0f similarity
        val nanResult = results.find { it.first == "nan" }
        val infResult = results.find { it.first == "inf" }
        assertEquals(0.0f, nanResult?.second ?: -999f, 0.0f)
        assertEquals(0.0f, infResult?.second ?: -999f, 0.0f)

        // Opposite candidate has -1.0f
        assertEquals("opposite", results.last().first)
        assertEquals(-1.0f, results.last().second, 1e-5f)
    }
}
