package dev.librepocket.embedding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SemanticMemoryIndexTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun jsonEmbeddingResponse(
        embeddings: List<FloatArray>,
        model: String = "text-embedding-3-small",
    ): String {
        val dataArray = embeddings.mapIndexed { idx, vec ->
            val floats = vec.joinToString(",") { it.toString() }
            """{"object":"embedding","index":$idx,"embedding":[$floats]}"""
        }.joinToString(",")

        return """{
            "object": "list",
            "data": [$dataArray],
            "model": "$model",
            "usage": {
                "prompt_tokens": 10,
                "total_tokens": 10
            }
        }""".trimIndent()
    }

    private fun createTestClient(): OpenAiEmbeddingClient {
        return OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test-api-key".toCharArray() },
            allowTestLoopback = true,
        )
    }

    @Test
    fun addDirectAndSearchByVectorRankingAndTopK() {
        val index = SemanticMemoryIndex<String>()

        index.addDirect("doc1", "pure x", floatArrayOf(1.0f, 0.0f, 0.0f), "cat_x")
        index.addDirect("doc2", "pure y", floatArrayOf(0.0f, 1.0f, 0.0f), "cat_y")
        index.addDirect("doc3", "mostly x", floatArrayOf(0.8f, 0.6f, 0.0f), "cat_mix")
        index.addDirect("doc4", "half x half y", floatArrayOf(0.6f, 0.8f, 0.0f), "cat_mix2")

        val query = floatArrayOf(1.0f, 0.0f, 0.0f)

        // Top 2
        val top2 = index.searchByVector(query, k = 2)
        assertEquals(2, top2.size)
        assertEquals("doc1", top2[0].entry.id)
        assertEquals(1.0f, top2[0].score, 1e-5f)
        assertEquals("cat_x", top2[0].entry.metadata)

        assertEquals("doc3", top2[1].entry.id)
        assertEquals(0.8f, top2[1].score, 1e-5f)
        assertEquals("cat_mix", top2[1].entry.metadata)

        // Top 4 full ranking
        val top4 = index.searchByVector(query, k = 4)
        assertEquals(4, top4.size)
        assertEquals("doc1", top4[0].entry.id)
        assertEquals("doc3", top4[1].entry.id)
        assertEquals("doc4", top4[2].entry.id)
        assertEquals(0.6f, top4[2].score, 1e-5f)
        assertEquals("doc2", top4[3].entry.id)
        assertEquals(0.0f, top4[3].score, 1e-5f)
    }

    @Test
    fun clientAddAndSearchWithMockWebServer() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Map<String, String>>(client = client)

        // 1. Add "apple"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(1.0f, 0.0f, 0.0f))))
        )
        val entry1 = index.add("fruit_1", "apple", mapOf("type" to "fruit"))
        assertEquals("fruit_1", entry1.id)
        assertEquals("apple", entry1.text)
        assertEquals(1, index.size())

        val req1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req1)
        assertEquals("POST", req1!!.method)
        assertEquals("/v1/embeddings", req1.path)

        // 2. Add "banana"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.7071f, 0.7071f, 0.0f))))
        )
        index.add("fruit_2", "banana", mapOf("type" to "fruit"))
        assertEquals(2, index.size())

        // 3. Search "fresh fruits"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.9f, 0.1f, 0.0f))))
        )
        val results = index.search("fresh fruits", k = 2)
        assertEquals(2, results.size)
        // apple is closer to [0.9, 0.1, 0.0] than banana
        assertEquals("fruit_1", results[0].entry.id)
        assertEquals("fruit_2", results[1].entry.id)
        assertTrue(results[0].score > results[1].score)
        assertEquals("fruit", results[0].entry.metadata?.get("type"))
    }

    @Test
    fun clientAddAllBatchWithMockWebServer() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Int>(client = client)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    jsonEmbeddingResponse(
                        listOf(
                            floatArrayOf(1.0f, 0.0f),
                            floatArrayOf(0.0f, 1.0f),
                            floatArrayOf(0.7071f, 0.7071f),
                        )
                    )
                )
        )

        val items = listOf(
            Triple("item1", "text 1", 10),
            Triple("item2", "text 2", 20),
            Triple("item3", "text 3", 30),
        )

        val added = index.addAll(items)
        assertEquals(3, added.size)
        assertEquals(3, index.size())
        assertEquals(10, index.get("item1")?.metadata)
        assertEquals(20, index.get("item2")?.metadata)
        assertEquals(30, index.get("item3")?.metadata)

        // MockWebServer should have received exactly one batch request
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/v1/embeddings", request!!.path)
    }

    @Test
    fun addAllEmptyReturnsEmptyList() = runBlocking {
        val index = SemanticMemoryIndex<Unit>()
        val result = index.addAll(emptyList())
        assertTrue(result.isEmpty())
        assertEquals(0, index.size())
    }

    @Test
    fun minScoreThresholdFilter() {
        val index = SemanticMemoryIndex<Unit>()

        index.addDirect("v_high", "high", floatArrayOf(1.0f, 0.0f))
        index.addDirect("v_mid", "mid", floatArrayOf(0.7071f, 0.7071f))
        index.addDirect("v_zero", "zero", floatArrayOf(0.0f, 1.0f))
        index.addDirect("v_neg", "neg", floatArrayOf(-1.0f, 0.0f))

        val query = floatArrayOf(1.0f, 0.0f)

        // High threshold: only v_high
        val r1 = index.searchByVector(query, k = 10, minScore = 0.8f)
        assertEquals(1, r1.size)
        assertEquals("v_high", r1[0].entry.id)

        // Mid threshold: v_high and v_mid
        val r2 = index.searchByVector(query, k = 10, minScore = 0.5f)
        assertEquals(2, r2.size)
        assertEquals("v_high", r2[0].entry.id)
        assertEquals("v_mid", r2[1].entry.id)

        // Zero threshold (default): filters out v_neg
        val r3 = index.searchByVector(query, k = 10, minScore = 0.0f)
        assertEquals(3, r3.size)
        assertEquals("v_high", r3[0].entry.id)
        assertEquals("v_mid", r3[1].entry.id)
        assertEquals("v_zero", r3[2].entry.id)

        // Negative threshold: includes negative similarity
        val r4 = index.searchByVector(query, k = 10, minScore = -1.0f)
        assertEquals(4, r4.size)
        assertEquals("v_neg", r4[3].entry.id)

        // Impossible threshold
        val r5 = index.searchByVector(query, k = 10, minScore = 1.01f)
        assertTrue(r5.isEmpty())
    }

    @Test
    fun dimensionMismatchDefense() {
        val index = SemanticMemoryIndex<Unit>()

        assertNull(index.currentDimension)
        index.addDirect("id1", "sample 3D", floatArrayOf(1.0f, 2.0f, 3.0f))
        assertEquals(3, index.currentDimension)

        // Reject 2D vector
        val err2D = assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("id2", "sample 2D", floatArrayOf(1.0f, 2.0f))
        }
        assertTrue(err2D.message!!.contains("Vector dimension mismatch: expected 3, got 2"))

        // Reject 4D vector
        val err4D = assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("id3", "sample 4D", floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f))
        }
        assertTrue(err4D.message!!.contains("Vector dimension mismatch: expected 3, got 4"))

        // Reject query vector dimension mismatch
        val errQuery = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(floatArrayOf(1.0f, 2.0f))
        }
        assertTrue(errQuery.message!!.contains("Query vector dimension mismatch: expected 3, got 2"))

        // After clear(), dimension is unbound and can accept a different dimension
        index.clear()
        assertNull(index.currentDimension)
        index.addDirect("id_2d", "sample 2D", floatArrayOf(1.0f, 2.0f))
        assertEquals(2, index.currentDimension)

        // After removing all elements, dimension resets
        index.remove("id_2d")
        assertNull(index.currentDimension)
        index.addDirect("id_4d", "sample 4D", floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(4, index.currentDimension)
    }

    @Test
    fun maxEntriesCapacityDefense() {
        val index = SemanticMemoryIndex<Unit>(maxEntries = 2)

        index.addDirect("1", "one", floatArrayOf(1.0f, 0.0f))
        index.addDirect("2", "two", floatArrayOf(0.0f, 1.0f))
        assertEquals(2, index.size())

        // Adding 3rd item must be rejected (fail-closed)
        val err = assertThrows(IllegalStateException::class.java) {
            index.addDirect("3", "three", floatArrayOf(1.0f, 1.0f))
        }
        assertTrue(err.message!!.contains("limit of 2 reached"))
        assertEquals(2, index.size())

        // Updating existing id should be permitted even when at max capacity
        index.addDirect("2", "two_updated", floatArrayOf(0.7071f, 0.7071f))
        assertEquals(2, index.size())
        assertEquals("two_updated", index.get("2")?.text)

        // After removing one, can insert a new id
        assertTrue(index.remove("1"))
        assertEquals(1, index.size())
        index.addDirect("3", "three", floatArrayOf(1.0f, 1.0f))
        assertEquals(2, index.size())
        assertNotNull(index.get("3"))
    }

    @Test
    fun addAllBatchCapacityDefense() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = 2)

        index.addDirect("seed", "seed text", floatArrayOf(1.0f, 0.0f))
        assertEquals(1, index.size())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    jsonEmbeddingResponse(
                        listOf(
                            floatArrayOf(1.0f, 0.0f),
                            floatArrayOf(0.0f, 1.0f),
                        )
                    )
                )
        )

        val items = listOf(
            Triple("1", "one", null),
            Triple("2", "two", null),
        )

        // Should fail-closed without inserting partial entries
        val err = assertThrows(IllegalStateException::class.java) {
            runBlocking { index.addAll(items) }
        }
        assertTrue(err.message!!.contains("limit of 2 reached"))
        assertEquals(1, index.size())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun removeClearGetAndSize() {
        val index = SemanticMemoryIndex<String>()
        assertEquals(0, index.size())
        assertNull(index.get("item1"))

        index.addDirect("item1", "first", floatArrayOf(1f, 0f), "meta1")
        index.addDirect("item2", "second", floatArrayOf(0f, 1f), "meta2")
        assertEquals(2, index.size())

        val entry1 = index.get("item1")
        assertNotNull(entry1)
        assertEquals("item1", entry1!!.id)
        assertEquals("first", entry1.text)
        assertEquals("meta1", entry1.metadata)

        // Remove item1
        assertTrue(index.remove("item1"))
        assertFalse(index.remove("item1")) // second remove returns false
        assertEquals(1, index.size())
        assertNull(index.get("item1"))
        assertNotNull(index.get("item2"))

        // Clear
        index.clear()
        assertEquals(0, index.size())
        assertNull(index.get("item2"))
        assertNull(index.currentDimension)
    }

    @Test
    fun missingClientThrowsIllegalStateException() = runBlocking {
        val indexWithoutClient = SemanticMemoryIndex<Unit>(client = null)

        val e1 = assertThrows(IllegalStateException::class.java) {
            runBlocking { indexWithoutClient.add("id", "text") }
        }
        assertTrue(e1.message!!.contains("OpenAiEmbeddingClient is required"))

        val e2 = assertThrows(IllegalStateException::class.java) {
            runBlocking { indexWithoutClient.addAll(listOf(Triple("id", "text", null))) }
        }
        assertTrue(e2.message!!.contains("OpenAiEmbeddingClient is required"))

        val e3 = assertThrows(IllegalStateException::class.java) {
            runBlocking { indexWithoutClient.search("query") }
        }
        assertTrue(e3.message!!.contains("OpenAiEmbeddingClient is required"))

        // Direct operations work without client
        indexWithoutClient.addDirect("id", "text", floatArrayOf(1f, 2f))
        assertEquals(1, indexWithoutClient.size())
        val searchRes = indexWithoutClient.searchByVector(floatArrayOf(1f, 2f))
        assertEquals(1, searchRes.size)
    }

    @Test
    fun validationContractsDefense() {
        val index = SemanticMemoryIndex<Unit>()

        // Construction contracts
        assertEquals(2_000, SemanticMemoryIndex.DEFAULT_MAX_ENTRIES)
        assertEquals(10_000, SemanticMemoryIndex.MAX_ALLOWED_ENTRIES)
        assertEquals(SemanticMemoryIndex.MAX_ALLOWED_ENTRIES, SemanticMemoryIndex.MAX_ALLOWED_MAX_ENTRIES)

        assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = -5)
        }
        // 10,001 throws exception
        val errOverMax = assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = 10_001)
        }
        assertTrue(errOverMax.message!!.contains("maxEntries must be between 1 and 10000, got: 10001"))
        assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = Int.MAX_VALUE)
        }
        // 10,000 is legal upper bound
        val maxCapIndex = SemanticMemoryIndex<Unit>(maxEntries = 10_000)
        assertEquals(10_000, maxCapIndex.maxEntries)
        assertEquals(SemanticMemoryIndex.MAX_ALLOWED_ENTRIES, maxCapIndex.maxEntries)
        assertEquals(SemanticMemoryIndex.MAX_ALLOWED_ENTRIES, SemanticMemoryIndex.MAX_ALLOWED_MAX_ENTRIES)
        assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(model = "   ")
        }

        // Blank id
        assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("", "text", floatArrayOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("   ", "text", floatArrayOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.remove("  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.get("  ")
        }

        // Blank text
        assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("id", "", floatArrayOf(1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("id", "   ", floatArrayOf(1f))
        }

        // Empty embedding
        assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("id", "text", floatArrayOf())
        }

        // Search validation
        index.addDirect("id", "text", floatArrayOf(1f, 0f))
        assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(floatArrayOf(), k = 1)
        }
        val errK0 = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(floatArrayOf(1f, 0f), k = 0)
        }
        assertEquals("k must be in 1..100, was: 0", errK0.message)

        val errKNeg = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(floatArrayOf(1f, 0f), k = -1)
        }
        assertEquals("k must be in 1..100, was: -1", errKNeg.message)

        val errKOver = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(floatArrayOf(1f, 0f), k = 101)
        }
        assertEquals("k must be in 1..100, was: 101", errKOver.message)

        assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(floatArrayOf(1f, 0f), k = 1, minScore = Float.NaN)
        }
    }

    @Test
    fun defensiveCopyProtection() {
        val index = SemanticMemoryIndex<Unit>()
        val originalVec = floatArrayOf(1.0f, 2.0f, 3.0f)

        index.addDirect("doc", "sample text", originalVec)

        // Mutate original array
        originalVec[0] = 999.0f

        // Ensure index internal state is unaffected
        val retrieved = index.get("doc")!!
        assertEquals(1.0f, retrieved.embedding[0], 1e-5f)

        // Mutate retrieved array
        retrieved.embedding[0] = 888.0f

        // Ensure next retrieval is still unaffected
        val retrievedAgain = index.get("doc")!!
        assertEquals(1.0f, retrievedAgain.embedding[0], 1e-5f)
    }

    @Test
    fun emptyIndexSearchByVectorReturnsEmpty() {
        val index = SemanticMemoryIndex<Unit>()
        val results = index.searchByVector(floatArrayOf(1.0f, 2.0f, 3.0f), k = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun concurrencySafety() = runBlocking(Dispatchers.Default) {
        val index = SemanticMemoryIndex<Int>(maxEntries = 500)
        val numCoroutines = 20
        val operationsPerCoroutine = 100

        val jobs = (0 until numCoroutines).map { coroutineId ->
            async {
                for (step in 0 until operationsPerCoroutine) {
                    val id = "item_${coroutineId}_${step % 20}"
                    val vec = floatArrayOf((coroutineId + 1).toFloat(), (step + 1).toFloat())

                    when (step % 4) {
                        0 -> {
                            index.addDirect(id, "text $step", vec, step)
                        }
                        1 -> {
                            index.searchByVector(floatArrayOf(1.0f, 1.0f), k = 5)
                        }
                        2 -> {
                            index.get(id)
                        }
                        3 -> {
                            index.remove(id)
                        }
                    }
                }
            }
        }

        jobs.awaitAll()

        // Index should remain in a consistent state
        assertTrue(index.size() >= 0)
        assertTrue(index.size() <= 500)
    }

    @Test
    fun defensiveCopyReturnedObjectsProtection() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<String>(client = client)

        // 1. addDirect returned object defensive copy
        val directOriginal = floatArrayOf(1.0f, 2.0f)
        val directReturned = index.addDirect("doc1", "text 1", directOriginal, "meta1")

        // Mutate input array passed into addDirect
        directOriginal[0] = 999.0f
        assertEquals(1.0f, index.get("doc1")!!.embedding[0], 1e-5f)

        // Mutate returned object's embedding array
        directReturned.embedding[0] = 888.0f
        assertEquals(1.0f, index.get("doc1")!!.embedding[0], 1e-5f)

        // 2. addAll returned objects defensive copy
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(3.0f, 4.0f))))
        )
        val addedList = index.addAll(listOf(Triple("doc2", "text 2", "meta2")))
        assertEquals(1, addedList.size)
        val batchReturned = addedList[0]
        batchReturned.embedding[0] = 777.0f
        batchReturned.embedding[1] = 666.0f

        assertEquals(3.0f, index.get("doc2")!!.embedding[0], 1e-5f)
        assertEquals(4.0f, index.get("doc2")!!.embedding[1], 1e-5f)

        // 3. search / searchByVector returned objects defensive copy
        val searchResults = index.searchByVector(floatArrayOf(1.0f, 2.0f), k = 5)
        assertEquals(2, searchResults.size)
        for (res in searchResults) {
            res.entry.embedding[0] = -123.0f
            res.entry.embedding[1] = -456.0f
        }

        assertEquals(1.0f, index.get("doc1")!!.embedding[0], 1e-5f)
        assertEquals(2.0f, index.get("doc1")!!.embedding[1], 1e-5f)
        assertEquals(3.0f, index.get("doc2")!!.embedding[0], 1e-5f)
        assertEquals(4.0f, index.get("doc2")!!.embedding[1], 1e-5f)

        // 4. search (with client) returned objects defensive copy
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(1.0f, 2.0f))))
        )
        val clientSearchResults = index.search("query", k = 5)
        assertEquals(2, clientSearchResults.size)
        for (res in clientSearchResults) {
            res.entry.embedding[0] = -999.0f
        }

        assertEquals(1.0f, index.get("doc1")!!.embedding[0], 1e-5f)
        assertEquals(3.0f, index.get("doc2")!!.embedding[0], 1e-5f)

        // 5. get returned object defensive copy
        val retrieved = index.get("doc1")!!
        retrieved.embedding[0] = 555.0f
        assertEquals(1.0f, index.get("doc1")!!.embedding[0], 1e-5f)
    }

    @Test
    fun addAllCallerModifyingMutableListDuringSuspensionDoesNotPollute() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Int>(client = client)

        val requestReceived = CountDownLatch(1)
        val proceedResponse = CountDownLatch(1)

        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                requestReceived.countDown()
                proceedResponse.await(5, TimeUnit.SECONDS)
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        jsonEmbeddingResponse(
                            listOf(
                                floatArrayOf(1.0f, 0.0f),
                                floatArrayOf(0.0f, 1.0f),
                            )
                        )
                    )
            }
        }

        val mutableItems = mutableListOf(
            Triple("item1", "text 1", 10),
            Triple("item2", "text 2", 20),
        )

        val deferred = async(Dispatchers.IO) {
            index.addAll(mutableItems)
        }

        // Wait until addAll has sent the HTTP request and is suspended waiting for the response
        assertTrue(requestReceived.await(5, TimeUnit.SECONDS))

        // Mutate caller's original MutableList while addAll is suspended
        mutableItems.clear()
        mutableItems.add(Triple("tampered_item", "tampered text", 999))
        mutableItems.add(Triple("another_tampered", "another text", 888))

        // Let the mock response proceed
        proceedResponse.countDown()
        val results = deferred.await()

        // Verify that the operation completed with original snapshot items
        assertEquals(2, results.size)
        assertEquals("item1", results[0].id)
        assertEquals("item2", results[1].id)
        assertEquals(2, index.size())
        assertNotNull(index.get("item1"))
        assertNotNull(index.get("item2"))
        assertNull(index.get("tampered_item"))
        assertNull(index.get("another_tampered"))
    }

    @Test
    fun addAllPreCheckCapacityBeforeNetworkRequest() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = 2)

        // Seed 1 entry
        index.addDirect("existing", "text", floatArrayOf(1.0f, 0.0f))
        assertEquals(1, index.size())

        // 1 existing + 2 new = 3 > maxEntries 2
        val items = listOf(
            Triple("new1", "one", null),
            Triple("new2", "two", null),
        )

        val err = assertThrows(IllegalStateException::class.java) {
            runBlocking { index.addAll(items) }
        }
        assertTrue(err.message!!.contains("limit of 2 reached"))
        assertEquals(1, index.size())
        // Assert no network requests were sent
        assertEquals(0, server.requestCount)

        // Also test add() pre-check
        index.addDirect("existing2", "text2", floatArrayOf(0.0f, 1.0f))
        assertEquals(2, index.size())

        val errAdd = assertThrows(IllegalStateException::class.java) {
            runBlocking { index.add("new3", "three") }
        }
        assertTrue(errAdd.message!!.contains("limit of 2 reached"))
        assertEquals(2, index.size())
        assertEquals(0, server.requestCount)

        // Updating an existing entry when at capacity is allowed and issues network request
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.7071f, 0.7071f))))
        )
        val updated = index.add("existing", "updated text")
        assertEquals("existing", updated.id)
        assertEquals("updated text", updated.text)
        assertEquals(2, index.size())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun extremeDimensionAndTextLengthDefense() = runBlocking {
        val index = SemanticMemoryIndex<Unit>()

        assertEquals(3072, SemanticMemoryIndex.MAX_SUPPORTED_DIMENSION)

        // 1. Extreme dimension (3073 > 3072) rejected
        val overDim = FloatArray(3073) { 0.1f }
        val errDimDirect = assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("doc", "valid text", overDim)
        }
        assertTrue(errDimDirect.message!!.contains("exceeds maximum supported dimension of 3072"))

        val errDimSearch = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(overDim)
        }
        assertTrue(errDimSearch.message!!.contains("exceeds maximum supported dimension of 3072"))

        // Exact maximum dimension (3072) is accepted
        val exactDim = FloatArray(3072) { 0.01f }
        val addedExact = index.addDirect("doc_max_dim", "valid text", exactDim)
        assertEquals(3072, addedExact.embedding.size)
        assertEquals(3072, index.currentDimension)

        val searchExact = index.searchByVector(exactDim, k = 1)
        assertEquals(1, searchExact.size)
        assertEquals("doc_max_dim", searchExact[0].entry.id)

        // Reset index for text length tests
        val index2 = SemanticMemoryIndex<Unit>()

        // 2. Overlong text (> 2048) rejected
        assertEquals(2048, SemanticMemoryIndex.MAX_TEXT_CHARS)
        val overlongText = "a".repeat(SemanticMemoryIndex.MAX_TEXT_CHARS + 1)
        assertEquals(2049, overlongText.length)
        val validVec = floatArrayOf(1.0f, 0.0f)

        val errTextDirect = assertThrows(IllegalArgumentException::class.java) {
            index2.addDirect("doc", overlongText, validVec)
        }
        assertTrue(errTextDirect.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        val errTextAdd = assertThrows(IllegalStateException::class.java.let { _ -> IllegalArgumentException::class.java }) {
            runBlocking { index2.add("doc", overlongText) }
        }
        assertTrue(errTextAdd.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        val errTextAddAll = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index2.addAll(listOf(Triple("doc", overlongText, null))) }
        }
        assertTrue(errTextAddAll.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        val errTextSearch = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index2.search(overlongText) }
        }
        assertTrue(errTextSearch.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        // Exact maximum text length (2048) is accepted
        val exactText = "a".repeat(SemanticMemoryIndex.MAX_TEXT_CHARS)
        assertEquals(2048, exactText.length)
        val addedExactText = index2.addDirect("doc_max_text", exactText, validVec)
        assertEquals(SemanticMemoryIndex.MAX_TEXT_CHARS, addedExactText.text.length)
        assertEquals(2048, addedExactText.text.length)
        assertEquals(1, index2.size())
    }

    @Test
    fun concurrentCapacityContentionStrictlyEnforced() = runBlocking(Dispatchers.Default) {
        val maxCap = 25
        val index = SemanticMemoryIndex<Int>(maxEntries = maxCap)
        val totalCoroutines = 30
        val itemsPerCoroutine = 10

        val successCount = AtomicInteger(0)
        val capacityRejectedCount = AtomicInteger(0)

        val jobs = (0 until totalCoroutines).map { coroutineId ->
            async {
                for (i in 0 until itemsPerCoroutine) {
                    val id = "c_${coroutineId}_$i"
                    val vec = floatArrayOf(coroutineId.toFloat(), i.toFloat())
                    try {
                        index.addDirect(id, "text $i", vec, i)
                        successCount.incrementAndGet()
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("limit of $maxCap reached") == true) {
                            capacityRejectedCount.incrementAndGet()
                        } else {
                            throw e
                        }
                    }
                }
            }
        }

        jobs.awaitAll()

        // Capacity upper bound MUST be strictly respected
        assertEquals(maxCap, index.size())
        assertEquals(maxCap, successCount.get())
        assertEquals((totalCoroutines * itemsPerCoroutine) - maxCap, capacityRejectedCount.get())
    }

    @Test
    fun concurrentBatchCapacityContentionStrictlyEnforced() = runBlocking(Dispatchers.Default) {
        val maxCap = 10
        val client = createTestClient()
        val index = SemanticMemoryIndex<Int>(client = client, maxEntries = maxCap)

        // Dispatcher returns valid embeddings
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        jsonEmbeddingResponse(
                            listOf(
                                floatArrayOf(1.0f, 0.0f),
                                floatArrayOf(0.0f, 1.0f),
                            )
                        )
                    )
            }
        }

        // 10 concurrent coroutines each trying to add 2 unique items (total 20 items > capacity 10)
        val jobs = (0 until 10).map { cId ->
            async {
                val items = listOf(
                    Triple("batch_${cId}_1", "text 1", 1),
                    Triple("batch_${cId}_2", "text 2", 2),
                )
                try {
                    index.addAll(items)
                } catch (e: IllegalStateException) {
                    // Expected when capacity is reached
                }
            }
        }

        jobs.awaitAll()

        // Strict limit must be maintained
        assertTrue(index.size() <= maxCap)
        assertEquals(maxCap, index.size())
    }

    @Test
    fun addAllBatchSizeExceedsMaxEntriesThrowsIllegalArgumentException() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = 2)

        val items = listOf(
            Triple("1", "one", null),
            Triple("2", "two", null),
            Triple("3", "three", null),
        )

        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(items) }
        }
        assertTrue(err.message!!.contains("Batch size (3) exceeds maxEntries (2)"))
        assertEquals(0, index.size())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun addAllBatchWithExcessiveDuplicateIdsExceedingMaxEntriesIntercepted() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = 2)

        val items = (1..10).map {
            Triple("duplicate_id", "text $it", null)
        }

        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(items) }
        }
        assertTrue(err.message!!.contains("Batch size (10) exceeds maxEntries (2)"))
        assertEquals(0, index.size())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun addAllHugeIterableExceedingMaxEntriesInterruptedImmediatelyWithoutFullCopy() = runBlocking {
        val client = createTestClient()
        val maxCap = 3
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = maxCap)

        val iteratedCount = AtomicInteger(0)
        val hugeIterable = Iterable {
            iterator {
                for (i in 1..1_000_000) {
                    iteratedCount.incrementAndGet()
                    yield(Triple("doc_$i", "text $i", null))
                }
            }
        }

        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(hugeIterable) }
        }

        assertTrue(err.message!!.contains("Batch size exceeds maxEntries ($maxCap)"))
        // Verifies immediate bounded interruption: iterator must have yielded at most maxCap + 1 items,
        // and definitely never fully iterated or copied 1,000,000 items into memory.
        assertEquals(maxCap + 1, iteratedCount.get())
        assertEquals(0, index.size())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun addAllHugeSequenceExceedingMaxEntriesInterruptedImmediatelyWithoutFullCopy() = runBlocking {
        val client = createTestClient()
        val maxCap = 2
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = maxCap)

        val evaluatedCount = AtomicInteger(0)
        val hugeSequence = generateSequence(1) { it + 1 }
            .take(1_000_000)
            .onEach { evaluatedCount.incrementAndGet() }
            .map { Triple("id_$it", "content $it", null) }

        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(hugeSequence.asIterable()) }
        }

        assertTrue(err.message!!.contains("Batch size exceeds maxEntries ($maxCap)"))
        assertEquals(maxCap + 1, evaluatedCount.get())
        assertEquals(0, index.size())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun addAllHugeCollectionPreCheckFailsWithoutIteratingOrCopying() = runBlocking {
        val client = createTestClient()
        val maxCap = 2
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = maxCap)

        var iteratorCalled = false
        val hugeCollection = object : AbstractCollection<Triple<String, String, Unit?>>() {
            override val size: Int = 1_000_000

            override fun iterator(): Iterator<Triple<String, String, Unit?>> {
                iteratorCalled = true
                throw AssertionError("Iterator must not be called when Collection size exceeds maxEntries")
            }
        }

        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(hugeCollection) }
        }

        assertTrue(err.message!!.contains("Batch size (1000000) exceeds maxEntries (2)"))
        assertFalse(iteratorCalled)
        assertEquals(0, index.size())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun addAllDuplicateIdsWithinBatchDeduplicatedAndOnlyLatestEmbedded() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Int>(client = client, maxEntries = 5)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    jsonEmbeddingResponse(
                        listOf(
                            floatArrayOf(1.0f, 0.0f),
                            floatArrayOf(0.0f, 1.0f),
                        )
                    )
                )
        )

        val items = listOf(
            Triple("id1", "first text", 1),
            Triple("id2", "other text", 2),
            Triple("id1", "latest text", 3),
        )

        val result = index.addAll(items)

        // Only 2 entries returned for the 2 unique IDs
        assertEquals(2, result.size)
        assertEquals(2, index.size())

        val entry1 = index.get("id1")
        assertNotNull(entry1)
        assertEquals("latest text", entry1!!.text)
        assertEquals(3, entry1.metadata)

        val entry2 = index.get("id2")
        assertNotNull(entry2)
        assertEquals("other text", entry2!!.text)
        assertEquals(2, entry2.metadata)

        // Only one network request was sent to the server
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        val body = request!!.body.readUtf8()
        assertTrue(body.contains("latest text"))
        assertTrue(body.contains("other text"))
        assertFalse(body.contains("first text"))
    }

    @Test
    fun idAndModelLengthDefense() = runBlocking {
        // Model length validation
        assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(model = "m".repeat(SemanticMemoryIndex.MAX_MODEL_CHARS + 1))
        }
        val exactModelIndex = SemanticMemoryIndex<Unit>(model = "m".repeat(SemanticMemoryIndex.MAX_MODEL_CHARS))
        assertEquals(SemanticMemoryIndex.MAX_MODEL_CHARS, exactModelIndex.model.length)

        val index = SemanticMemoryIndex<Unit>()
        val overlongId = "i".repeat(SemanticMemoryIndex.MAX_ID_CHARS + 1)
        val exactId = "i".repeat(SemanticMemoryIndex.MAX_ID_CHARS)
        val validVec = floatArrayOf(1.0f, 0.0f)

        // addDirect
        val errDirect = assertThrows(IllegalArgumentException::class.java) {
            index.addDirect(overlongId, "text", validVec)
        }
        assertTrue(errDirect.message!!.contains("exceeds maximum allowed length of ${SemanticMemoryIndex.MAX_ID_CHARS}"))

        // add
        val errAdd = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.add(overlongId, "text") }
        }
        assertTrue(errAdd.message!!.contains("exceeds maximum allowed length of ${SemanticMemoryIndex.MAX_ID_CHARS}"))

        // addAll
        val errAddAll = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(listOf(Triple(overlongId, "text", null))) }
        }
        assertTrue(errAddAll.message!!.contains("exceeds maximum allowed length of ${SemanticMemoryIndex.MAX_ID_CHARS}"))

        // get
        val errGet = assertThrows(IllegalArgumentException::class.java) {
            index.get(overlongId)
        }
        assertTrue(errGet.message!!.contains("exceeds maximum allowed length of ${SemanticMemoryIndex.MAX_ID_CHARS}"))

        // remove
        val errRemove = assertThrows(IllegalArgumentException::class.java) {
            index.remove(overlongId)
        }
        assertTrue(errRemove.message!!.contains("exceeds maximum allowed length of ${SemanticMemoryIndex.MAX_ID_CHARS}"))

        // Exact ID length (256 chars) is accepted
        val added = index.addDirect(exactId, "text", validVec)
        assertEquals(exactId, added.id)
        assertNotNull(index.get(exactId))
        assertTrue(index.remove(exactId))
    }

    @Test
    fun maxAllowedEntriesCapacityBoundaryContracts() {
        assertEquals(10_000, SemanticMemoryIndex.MAX_ALLOWED_ENTRIES)
        assertEquals(2_000, SemanticMemoryIndex.DEFAULT_MAX_ENTRIES)

        // 10,000 is legal upper bound
        val legalIndex = SemanticMemoryIndex<Unit>(maxEntries = 10_000)
        assertEquals(10_000, legalIndex.maxEntries)
        assertEquals(SemanticMemoryIndex.MAX_ALLOWED_ENTRIES, legalIndex.maxEntries)

        // 10,001 throws IllegalArgumentException
        val err = assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = 10_001)
        }
        assertTrue(err.message!!.contains("maxEntries must be between 1 and 10000, got: 10001"))

        // SemanticMemoryIndex.MAX_ALLOWED_ENTRIES + 1 throws IllegalArgumentException
        val errOver = assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES + 1)
        }
        assertTrue(errOver.message!!.contains("maxEntries must be between 1 and 10000, got: 10001"))
    }

    @Test
    fun addAllAtMaxAllowedEntriesBoundaryWorksWithoutOverflow() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<String>(
            client = client,
            maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    jsonEmbeddingResponse(
                        listOf(
                            floatArrayOf(1.0f, 0.0f),
                            floatArrayOf(0.0f, 1.0f),
                        )
                    )
                )
        )

        val items = listOf(
            Triple("k1", "text 1", "meta1"),
            Triple("k2", "text 2", "meta2"),
        )

        val results = index.addAll(items)
        assertEquals(2, results.size)
        assertEquals(2, index.size())
        assertEquals("k1", results[0].id)
        assertEquals("k2", results[1].id)
        assertEquals("meta1", results[0].metadata)
        assertEquals("meta2", results[1].metadata)
    }

    @Test
    fun addAllNonCollectionAtMaxAllowedEntriesBoundaryWorksWithoutOverflow() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(
            client = client,
            maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    jsonEmbeddingResponse(
                        listOf(
                            floatArrayOf(0.5f, 0.5f),
                        )
                    )
                )
        )

        val items = sequenceOf(
            Triple("seq_1", "sequence text", null)
        ).asIterable()

        val results = index.addAll(items)
        assertEquals(1, results.size)
        assertEquals(1, index.size())
        assertEquals("seq_1", results[0].id)
    }

    @Test
    fun addAllEmptyAtMaxAllowedEntriesBoundaryReturnsEmptyList() = runBlocking {
        val index = SemanticMemoryIndex<Unit>(maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES)
        val emptyCollectionResult = index.addAll(emptyList())
        assertTrue(emptyCollectionResult.isEmpty())

        val emptyIterableResult = index.addAll(emptySequence<Triple<String, String, Unit?>>().asIterable())
        assertTrue(emptyIterableResult.isEmpty())
        assertEquals(0, index.size())
    }

    @Test
    fun addAllSequenceRespectsMaxEntriesWithoutExceedingInitialCapacity() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(client = client, maxEntries = 2)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    jsonEmbeddingResponse(
                        listOf(
                            floatArrayOf(1.0f, 0.0f),
                            floatArrayOf(0.0f, 1.0f),
                        )
                    )
                )
        )

        val items = sequenceOf(
            Triple("item1", "text 1", null),
            Triple("item2", "text 2", null),
        ).asIterable()

        val results = index.addAll(items)
        assertEquals(2, results.size)
        assertEquals(2, index.size())
    }

    @Test
    fun boundaryVerificationForMaxDimensionAndMaxEntries() {
        assertEquals(3072, SemanticMemoryIndex.MAX_SUPPORTED_DIMENSION)
        assertEquals(2_000, SemanticMemoryIndex.DEFAULT_MAX_ENTRIES)
        assertEquals(10_000, SemanticMemoryIndex.MAX_ALLOWED_ENTRIES)

        // 1. Capacity boundary: 10,000 legal, 10,001 rejected
        val legalCapIndex = SemanticMemoryIndex<Unit>(maxEntries = 10_000)
        assertEquals(10_000, legalCapIndex.maxEntries)

        val capEx = assertThrows(IllegalArgumentException::class.java) {
            SemanticMemoryIndex<Unit>(maxEntries = 10_001)
        }
        assertTrue(capEx.message!!.contains("maxEntries must be between 1 and 10000, got: 10001"))

        // 2. Dimension boundary: 3072 legal, 3073 rejected
        val index = SemanticMemoryIndex<Unit>()
        val legalVec = FloatArray(3072) { 0.05f }
        val added = index.addDirect("exact_dim", "some text", legalVec)
        assertEquals(3072, added.embedding.size)
        assertEquals(3072, index.currentDimension)

        val searchLegal = index.searchByVector(legalVec, k = 1)
        assertEquals(1, searchLegal.size)
        assertEquals("exact_dim", searchLegal[0].entry.id)

        val illegalVec = FloatArray(3073) { 0.05f }
        val addEx = assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("over_dim", "some text", illegalVec)
        }
        assertTrue(addEx.message!!.contains("Embedding dimension 3073 exceeds maximum supported dimension of 3072"))

        val searchEx = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(illegalVec)
        }
        assertTrue(searchEx.message!!.contains("Query vector dimension 3073 exceeds maximum supported dimension of 3072"))
    }

    @Test
    fun searchByVectorOnlyDefensivelyCopiesTopKResults() {
        val index = SemanticMemoryIndex<Unit>()
        val dim = 4
        // Insert 20 entries
        for (i in 1..20) {
            val vec = FloatArray(dim) { if (it == 0) i * 0.05f else 0.1f }
            index.addDirect("doc_$i", "text $i", vec)
        }
        assertEquals(20, index.size())

        val query = FloatArray(dim) { if (it == 0) 1.0f else 0.0f }
        val k = 3
        val topResults = index.searchByVector(queryVector = query, k = k, minScore = 0.0f)
        assertEquals(3, topResults.size)
        assertEquals("doc_20", topResults[0].entry.id)
        assertEquals("doc_19", topResults[1].entry.id)
        assertEquals("doc_18", topResults[2].entry.id)

        // Mutating returned top-k embeddings does not mutate internal index entries
        for (result in topResults) {
            result.entry.embedding[0] = -9999.0f
        }

        val storedDoc20 = index.get("doc_20")!!
        assertNotEquals(-9999.0f, storedDoc20.embedding[0])
        assertEquals(20 * 0.05f, storedDoc20.embedding[0], 1e-5f)

        // Non-top-k entry remains untouched
        val storedDoc1 = index.get("doc_1")!!
        assertEquals(1 * 0.05f, storedDoc1.embedding[0], 1e-5f)
    }

    @Test
    fun boundaryVerificationForMaxTextCharsAndSearchK() = runBlocking {
        assertEquals(2048, SemanticMemoryIndex.MAX_TEXT_CHARS)
        assertEquals(100, SemanticMemoryIndex.MAX_SEARCH_K)

        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(client = client)
        val vec = floatArrayOf(1.0f, 0.0f)

        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(1.0f, 0.0f))))
            }
        }

        // 1. Text boundaries: 2048 chars is legal, 2049 chars is rejected
        val text2048 = "x".repeat(2048)
        val text2049 = "x".repeat(2049)

        // addDirect: 2048 succeeds, 2049 fails
        val directEntry = index.addDirect("id_2048", text2048, vec)
        assertEquals(2048, directEntry.text.length)

        val errDirect2049 = assertThrows(IllegalArgumentException::class.java) {
            index.addDirect("id_2049", text2049, vec)
        }
        assertTrue(errDirect2049.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        // add: 2048 succeeds, 2049 fails before network request
        val addEntry = index.add("id_add_2048", text2048)
        assertEquals(2048, addEntry.text.length)

        val errAdd2049 = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.add("id_add_2049", text2049) }
        }
        assertTrue(errAdd2049.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        // addAll: 2048 succeeds, 2049 fails before network request
        val addAllEntries = index.addAll(listOf(Triple("id_all_2048", text2048, null)))
        assertEquals(1, addAllEntries.size)
        assertEquals(2048, addAllEntries[0].text.length)

        val errAddAll2049 = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(listOf(Triple("id_all_2049", text2049, null))) }
        }
        assertTrue(errAddAll2049.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        // search query text length: 2048 succeeds, 2049 fails before network request
        val search2048Results = index.search(query = text2048, k = 1)
        assertTrue(search2048Results.isNotEmpty())

        val errSearch2049 = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.search(query = text2049, k = 1) }
        }
        assertTrue(errSearch2049.message!!.contains("exceeds maximum allowed length of 2048 characters"))

        // 2. Search Top-K boundaries: 1..100 is legal, <= 0 and > 100 are rejected
        // searchByVector
        val resK1 = index.searchByVector(vec, k = 1)
        assertEquals(1, resK1.size)

        val resK100 = index.searchByVector(vec, k = 100)
        assertTrue(resK100.isNotEmpty())

        val errVecK0 = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(vec, k = 0)
        }
        assertEquals("k must be in 1..100, was: 0", errVecK0.message)

        val errVecKNeg = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(vec, k = -1)
        }
        assertEquals("k must be in 1..100, was: -1", errVecKNeg.message)

        val errVecK101 = assertThrows(IllegalArgumentException::class.java) {
            index.searchByVector(vec, k = 101)
        }
        assertEquals("k must be in 1..100, was: 101", errVecK101.message)

        // search
        val resSearchK1 = index.search("query", k = 1)
        assertEquals(1, resSearchK1.size)

        val resSearchK100 = index.search("query", k = 100)
        assertTrue(resSearchK100.isNotEmpty())

        val errSearchK0 = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.search("query", k = 0) }
        }
        assertEquals("k must be in 1..100, was: 0", errSearchK0.message)

        val errSearchKNeg = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.search("query", k = -1) }
        }
        assertEquals("k must be in 1..100, was: -1", errSearchKNeg.message)

        val errSearchK101 = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.search("query", k = 101) }
        }
        assertEquals("k must be in 1..100, was: 101", errSearchK101.message)
    }

    // --- Subagent B Blocker 2: MAX_BATCH_SIZE (100) Enforced Pre-check ---

    @Test
    fun maxBatchSizeConstantContract() {
        assertEquals(100, SemanticMemoryIndex.MAX_BATCH_SIZE)
    }

    @Test
    fun addAll101ItemsThrowsIllegalArgumentExceptionBeforeNetworkRequest() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(
            client = client,
            maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES
        )

        val items = (1..101).map {
            Triple("doc_$it", "text content $it", null)
        }

        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(items) }
        }

        assertTrue(
            "Error message should mention exceeding MAX_BATCH_SIZE",
            err.message!!.contains("Batch size (101) exceeds MAX_BATCH_SIZE (${SemanticMemoryIndex.MAX_BATCH_SIZE})")
        )
        assertEquals(0, index.size())
        assertEquals("Pre-check must fail before any network request is sent", 0, server.requestCount)
    }

    @Test
    fun addAllNonCollection101ItemsThrowsIllegalArgumentExceptionBeforeNetworkRequest() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(
            client = client,
            maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES
        )

        val iteratedCount = AtomicInteger(0)
        val itemsSequence = generateSequence(1) { it + 1 }
            .take(101)
            .onEach { iteratedCount.incrementAndGet() }
            .map { Triple("doc_$it", "text content $it", null) }

        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { index.addAll(itemsSequence.asIterable()) }
        }

        assertTrue(
            "Error message should mention exceeding MAX_BATCH_SIZE",
            err.message!!.contains("Batch size exceeds MAX_BATCH_SIZE (${SemanticMemoryIndex.MAX_BATCH_SIZE})")
        )
        assertEquals(101, iteratedCount.get())
        assertEquals(0, index.size())
        assertEquals("Pre-check must fail before any network request is sent", 0, server.requestCount)
    }

    @Test
    fun addAllExact100ItemsPassesBatchSizePreCheck() = runBlocking {
        val client = createTestClient()
        val index = SemanticMemoryIndex<Unit>(
            client = client,
            maxEntries = SemanticMemoryIndex.MAX_ALLOWED_ENTRIES
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f, 0.2f))))
        )

        // 100 items deduplicated to single ID to verify 100-item batch check succeeds
        val items = (1..100).map {
            Triple("single_id", "text content $it", null)
        }

        val result = index.addAll(items)
        assertEquals(1, result.size)
        assertEquals(1, index.size())
        assertEquals(1, server.requestCount)
    }
}
