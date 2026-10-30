package dev.librepocket.embedding

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiEmbeddingClientTest {

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
        promptTokens: Int = 10,
        totalTokens: Int = 10,
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
                "prompt_tokens": $promptTokens,
                "total_tokens": $totalTokens
            }
        }""".trimIndent()
    }

    @Test
    fun singleEmbeddingSuccess() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f, 0.2f, 0.3f))))
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test-api-key".toCharArray() },
            allowTestLoopback = true,
        )

        val response = client.createEmbeddings(
            EmbeddingRequest(input = listOf("hello world"))
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/v1/embeddings", recorded.path)
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))

        val reqJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("text-embedding-3-small", reqJson["model"]?.jsonPrimitive?.content)
        assertEquals(listOf("hello world"), reqJson["input"]?.jsonArray?.map { it.jsonPrimitive.content })

        assertEquals("text-embedding-3-small", response.model)
        assertEquals(1, response.data.size)
        assertEquals(0, response.data[0].index)
        assertEquals(3, response.data[0].embedding.size)
        assertEquals(0.1f, response.data[0].embedding[0], 1e-5f)
        assertEquals(0.2f, response.data[0].embedding[1], 1e-5f)
        assertEquals(0.3f, response.data[0].embedding[2], 1e-5f)
        assertEquals(10, response.promptTokens)
        assertEquals(10, response.totalTokens)
    }

    @Test
    fun batchEmbeddingSuccess() = runBlocking {
        val vec1 = floatArrayOf(0.1f, 0.2f)
        val vec2 = floatArrayOf(0.3f, 0.4f)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(vec1, vec2)))
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/v1").toString(),
            apiKey = { "batch-key".toCharArray() },
            allowTestLoopback = true,
        )

        val response = client.createEmbeddings(
            EmbeddingRequest(input = listOf("first", "second"))
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("/v1/embeddings", recorded!!.path)

        val reqJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val inputs = reqJson["input"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("first", "second"), inputs)

        assertEquals(2, response.data.size)
        assertEquals(0, response.data[0].index)
        assertEquals(0.1f, response.data[0].embedding[0], 1e-5f)
        assertEquals(1, response.data[1].index)
        assertEquals(0.3f, response.data[1].embedding[0], 1e-5f)
    }

    @Test
    fun dimensionsParameterSentWhenPresent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(FloatArray(256) { 0.5f })))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.5f))))
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "dim-key".toCharArray() },
            allowTestLoopback = true,
        )

        // With dimensions = 256
        client.createEmbeddings(
            EmbeddingRequest(input = listOf("test"), dimensions = 256)
        )
        val rec1 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val json1 = Json.parseToJsonElement(rec1.body.readUtf8()).jsonObject
        assertEquals(256, json1["dimensions"]?.jsonPrimitive?.intOrNull)

        // Without dimensions (null)
        client.createEmbeddings(
            EmbeddingRequest(input = listOf("test"), dimensions = null)
        )
        val rec2 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val json2 = Json.parseToJsonElement(rec2.body.readUtf8()).jsonObject
        assertNull(json2["dimensions"])
    }

    @Test
    fun authorizationHeaderSent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f))))
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "my-secret-key-xyz".toCharArray() },
            allowTestLoopback = true,
        )

        client.createEmbeddings(EmbeddingRequest(input = listOf("text")))
        val rec = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer my-secret-key-xyz", rec.getHeader("Authorization"))
    }

    @Test
    fun endpointSecurityProductionRejectsThirdPartyAndLoopback() = runBlocking {
        // Disallowed third party in production
        val clientThirdParty = OpenAiEmbeddingClient(
            baseUrl = "https://api.evil.com",
            apiKey = { "key".toCharArray() },
            allowTestLoopback = false,
        )
        val exThirdParty = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                clientThirdParty.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }
        assertEquals(EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED, exThirdParty.errorCode)
        assertFalse(exThirdParty.retryable)

        // Disallowed loopback in production
        val clientLoopback = OpenAiEmbeddingClient(
            baseUrl = "http://127.0.0.1:8080",
            apiKey = { "key".toCharArray() },
            allowTestLoopback = false,
        )
        val exLoopback = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                clientLoopback.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }
        assertEquals(EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED, exLoopback.errorCode)

        val clientLocalhost = OpenAiEmbeddingClient(
            baseUrl = "http://localhost:8080",
            apiKey = { "key".toCharArray() },
            allowTestLoopback = false,
        )
        val exLocalhost = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                clientLocalhost.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }
        assertEquals(EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED, exLocalhost.errorCode)
    }

    @Test
    fun endpointSecurityTestIsolationAllowsLoopback() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f))))
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val resp = client.createEmbeddings(EmbeddingRequest(input = listOf("test")))
        assertEquals(1, resp.data.size)

        // However, third party is still rejected even with allowTestLoopback = true
        val clientThirdParty = OpenAiEmbeddingClient(
            baseUrl = "https://api.evil.com",
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                clientThirdParty.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }
        assertEquals(EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED, ex.errorCode)
    }

    @Test
    fun keyCharArrayPromptlyZeroedInMemory() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f))))
        )

        val rawKey = "sensitive-token-12345".toCharArray()
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { rawKey },
            allowTestLoopback = true,
        )

        client.createEmbeddings(EmbeddingRequest(input = listOf("test")))

        // Verify that rawKey in memory has been zeroed out
        assertTrue(rawKey.all { it == '\u0000' })
    }

    @Test
    fun keySupplierExceptionSafeMessageAndNoStackTraceLeak() = runBlocking {
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { throw RuntimeException("leaked-internal-database-password-XYZ") },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                client.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }

        assertEquals("Failed to obtain API key", ex.safeMessage)
        assertEquals(EmbeddingErrorCode.UNAUTHORIZED, ex.errorCode)
        assertFalse(ex.retryable)
        assertNull(ex.cause)
        assertFalse(ex.stackTraceToString().contains("leaked-internal-database-password-XYZ"))
    }

    @Test
    fun keySupplierCancellationExceptionCleanAndEmptyStackTrace() = runBlocking {
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { throw CancellationException("custom cancellation message") },
            allowTestLoopback = true,
        )

        val ce = assertThrows(CancellationException::class.java) {
            runBlocking {
                client.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }

        assertEquals("API key supplier cancelled", ce.message)
        assertTrue(ce.stackTrace.isEmpty())
    }

    @Test
    fun nonStandardKeyMaskingInErrors() = runBlocking {
        val customKey = "custom_non_std_token_998877"
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid key $customKey was rejected"}}""")
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { customKey.toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                client.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }

        assertEquals(EmbeddingErrorCode.UNAUTHORIZED, ex.errorCode)
        assertEquals(401, ex.statusCode)
        assertFalse("Safe message must not leak raw custom key", ex.safeMessage.contains(customKey))
        assertTrue("Safe message must contain redaction marker", ex.safeMessage.contains("⟦REDACTED:API_KEY⟧"))
    }

    @Test
    fun errorClassification401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(401, ex.statusCode)
        assertEquals(EmbeddingErrorCode.UNAUTHORIZED, ex.errorCode)
        assertFalse("401 must be non-retryable", ex.retryable)
    }

    @Test
    fun errorClassification429() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limit exceeded"))
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(429, ex.statusCode)
        assertEquals(EmbeddingErrorCode.RATE_LIMITED, ex.errorCode)
        assertTrue("429 must be retryable", ex.retryable)
    }

    @Test
    fun errorClassification500() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(500, ex.statusCode)
        assertEquals(EmbeddingErrorCode.SERVER_ERROR, ex.errorCode)
        assertFalse("500 must be non-retryable", ex.retryable)
    }

    @Test
    fun errorClassification503() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(503, ex.statusCode)
        assertEquals(EmbeddingErrorCode.SERVICE_UNAVAILABLE, ex.errorCode)
        assertTrue("503 must be retryable", ex.retryable)
    }

    @Test
    fun maxResponseBytesConstantContract() {
        assertEquals(5 * 1024 * 1024L, OpenAiEmbeddingClient.MAX_RESPONSE_BYTES)
    }

    @Test
    fun constructor_maxResponseBytesNonPositive_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiEmbeddingClient(
                apiKey = { "key".toCharArray() },
                maxResponseBytes = 0L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiEmbeddingClient(
                apiKey = { "key".toCharArray() },
                maxResponseBytes = -1L,
            )
        }
    }

    @Test
    fun responseTooLargeCancelledAndThrows() = runBlocking {
        // Enforce a small maxResponseBytes budget for test (128 bytes)
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
            maxResponseBytes = 128L,
        )

        // Server sends body larger than 128 bytes
        val largeBody = jsonEmbeddingResponse(List(10) { floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f) })
        assertTrue(largeBody.toByteArray().size > 128)

        server.enqueue(MockResponse().setResponseCode(200).setBody(largeBody))

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(EmbeddingErrorCode.RESPONSE_TOO_LARGE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun coroutineCancellationInterruptsOkHttpCall() = runBlocking {
        val requestReceived = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val cancelObserved = AtomicBoolean(false)

        val customClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelObserved.set(true)
                    callCancelled.countDown()
                }
            })
            .build()

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            httpClient = customClient,
            allowTestLoopback = true,
        )

        // Hang socket so execute waits for headers and stays blocked until cancel
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = launch(Dispatchers.IO) {
            try {
                client.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            } catch (_: CancellationException) {
                // Expected on cancellation
            }
        }

        // Wait until server receives request
        val req = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(req)

        // Cancel the coroutine
        job.cancelAndJoin()

        // Verify OkHttp call was cancelled
        val cancelled = withContext(Dispatchers.IO) {
            callCancelled.await(3, TimeUnit.SECONDS)
        }
        assertTrue("OkHttp Call.cancel must be invoked on coroutine cancellation", cancelled)
        assertTrue(cancelObserved.get())
    }

    @Test
    fun inputRedactionOption() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f))))
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f))))
        )

        val sensitiveText = "Please contact 0912-345-678 or write to test@example.com"

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
            redactInput = true,
        )

        // With redactInput = true
        client.createEmbeddings(EmbeddingRequest(input = listOf(sensitiveText)))
        val rec1 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body1 = rec1.body.readUtf8()
        assertFalse(body1.contains("0912-345-678"))
        assertFalse(body1.contains("test@example.com"))
        assertTrue(body1.contains("⟦REDACTED:PHONE⟧"))
        assertTrue(body1.contains("⟦REDACTED:EMAIL⟧"))

        // With redactInput = false
        client.createEmbeddings(EmbeddingRequest(input = listOf(sensitiveText)), redactInput = false)
        val rec2 = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body2 = rec2.body.readUtf8()
        assertTrue(body2.contains("0912-345-678"))
        assertTrue(body2.contains("test@example.com"))
    }

    @Test
    fun convenienceEmbedSingleText() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.42f, 0.84f))))
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val vec = client.embed("quick embed text")
        assertEquals(2, vec.size)
        assertEquals(0.42f, vec[0], 1e-5f)
        assertEquals(0.84f, vec[1], 1e-5f)
    }

    @Test
    fun missingOrEmptyApiKeyThrowsUnauthorized() = runBlocking {
        val clientNull = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { null },
            allowTestLoopback = true,
        )
        val exNull = assertThrows(EmbeddingException::class.java) {
            runBlocking { clientNull.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(EmbeddingErrorCode.UNAUTHORIZED, exNull.errorCode)
        assertEquals(401, exNull.statusCode)

        val clientEmpty = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { charArrayOf() },
            allowTestLoopback = true,
        )
        val exEmpty = assertThrows(EmbeddingException::class.java) {
            runBlocking { clientEmpty.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(EmbeddingErrorCode.UNAUTHORIZED, exEmpty.errorCode)
        assertEquals(401, exEmpty.statusCode)
    }

    @Test
    fun responseTooLargeContentLengthHeaderThrows() = runBlocking {
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
            maxResponseBytes = 100L,
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", 200)
                .setBody("x".repeat(200))
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(EmbeddingErrorCode.RESPONSE_TOO_LARGE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseItemsSortedByIndex() = runBlocking {
        val jsonOutOfOrder = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":1,"embedding":[0.2]},
                {"object":"embedding","index":0,"embedding":[0.1]}
            ],
            "model": "text-embedding-3-small",
            "usage": {"prompt_tokens": 2, "total_tokens": 2}
        }""".trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonOutOfOrder)
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val resp = client.createEmbeddings(EmbeddingRequest(input = listOf("a", "b")))
        assertEquals(2, resp.data.size)
        assertEquals(0, resp.data[0].index)
        assertEquals(0.1f, resp.data[0].embedding[0], 1e-5f)
        assertEquals(1, resp.data[1].index)
        assertEquals(0.2f, resp.data[1].embedding[0], 1e-5f)
    }

    @Test
    fun officialOpenAiEndpointAllowedInProduction() {
        val endpoint = OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com", allowTestLoopback = false)
        assertEquals("api.openai.com", endpoint.host)
        assertEquals("https", endpoint.scheme)

        val endpointWithV1 = OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com/v1", allowTestLoopback = false)
        assertEquals("api.openai.com", endpointWithV1.host)
    }

    @Test
    fun userParameterSentInRequestBody() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f))))
        )

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        client.createEmbeddings(EmbeddingRequest(input = listOf("text"), user = "user-abc-123"))
        val rec = server.takeRequest(5, TimeUnit.SECONDS)!!
        val json = Json.parseToJsonElement(rec.body.readUtf8()).jsonObject
        assertEquals("user-abc-123", json["user"]?.jsonPrimitive?.content)
    }

    // --- Reviewer Item 1: Parsing error safe shielding & leak defense ---

    @Test
    fun nonNumericFloatVectorShieldedAsInvalidResponseWithoutLeak() = runBlocking {
        val sensitiveSnippet = "secret_db_password_12345"
        val malformedResponse = """{
            "object": "list",
            "data": [
                {
                    "object": "embedding",
                    "index": 0,
                    "embedding": [0.1, "$sensitiveSnippet", 0.3]
                }
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(malformedResponse))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }

        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
        assertEquals("Failed to parse embedding response JSON", ex.safeMessage)
        assertNull("Cause must be null to avoid leaking unredacted server contents", ex.cause)
        assertFalse("Safe message must not leak sensitive content", ex.safeMessage.contains(sensitiveSnippet))
        assertFalse("Stack trace must not leak sensitive content", ex.stackTraceToString().contains(sensitiveSnippet))
    }

    @Test
    fun malformedJsonStructureShieldedAsInvalidResponseWithoutLeak() = runBlocking {
        val sensitiveDetail = "secret_internal_cluster_state"
        val malformedPayload = """{"malformed_structure": "$sensitiveDetail"}"""

        server.enqueue(MockResponse().setResponseCode(200).setBody(malformedPayload))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }

        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
        assertNull(ex.cause)
        assertFalse(ex.safeMessage.contains(sensitiveDetail))
        assertFalse(ex.stackTraceToString().contains(sensitiveDetail))
    }

    // --- Reviewer Item 2: Redact before truncate defense ---

    @Test
    fun keyAtChar195RedactedBeforeTruncationPreventingKeyPrefixLeak() = runBlocking {
        val customKey = "sk-custom-secret-key-9988776655"
        val padding = "A".repeat(195)
        val rawErrorBody = "${padding}${customKey}_extra_trailing_information"

        server.enqueue(MockResponse().setResponseCode(400).setBody(rawErrorBody))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { customKey.toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }

        assertEquals(EmbeddingErrorCode.INVALID_REQUEST, ex.errorCode)
        assertFalse("Key prefix must NOT be leaked", ex.safeMessage.contains(customKey.substring(0, 5)))
        assertFalse("Raw key must NOT be leaked", ex.safeMessage.contains(customKey))
        assertTrue("Redaction marker must be present", ex.safeMessage.contains("⟦REDACTED:API_KEY⟧"))
    }

    @Test
    fun safeSnippetRedactsKeyBeforeTruncating() {
        val customKey = "sk-custom-api-key-aabbcc"
        val padding = "P".repeat(195)
        val raw = "${padding}${customKey}_tail_data"

        val snippet = OpenAiEmbeddingClient.safeSnippet(raw, customKey, limit = 200)

        assertFalse("Must not contain key prefix", snippet.contains("sk-cu"))
        assertFalse("Must not contain full key", snippet.contains(customKey))
        assertTrue("Must contain full redaction marker", snippet.contains("⟦REDACTED:API_KEY⟧"))
    }

    // --- Reviewer Item 3: Official OpenAI endpoint strictly enforces HTTPS port 443 ---

    @Test
    fun officialEndpointStrictlyRejectsNon443Ports() {
        // Non-443 port in production must throw ENDPOINT_NOT_ALLOWED
        val ex8443Prod = assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com:8443", allowTestLoopback = false)
        }
        assertEquals(EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED, ex8443Prod.errorCode)

        // Non-443 port in test mode (allowTestLoopback = true) must also throw ENDPOINT_NOT_ALLOWED
        val ex8443Test = assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com:8443", allowTestLoopback = true)
        }
        assertEquals(EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED, ex8443Test.errorCode)

        // Port 80, 8080 must be rejected
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com:80", allowTestLoopback = false)
        }
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com:8080", allowTestLoopback = false)
        }

        // Implicit 443 and explicit 443 HTTPS must succeed
        val urlDefault = OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com", allowTestLoopback = false)
        assertEquals(443, urlDefault.port)

        val urlExplicit443 = OpenAiEmbeddingClient.validateEndpoint("https://api.openai.com:443", allowTestLoopback = false)
        assertEquals(443, urlExplicit443.port)
    }

    // --- Reviewer Item 5: Conservative and consistent retryable classification ---

    @Test
    fun retryableClassificationHttpCodes() {
        // Explicit transient errors: 429, 503, 504 are retryable
        assertEquals(true, OpenAiEmbeddingClient.classifyHttpError(429).second)
        assertEquals(true, OpenAiEmbeddingClient.classifyHttpError(503).second)
        assertEquals(true, OpenAiEmbeddingClient.classifyHttpError(504).second)

        // Server errors other than 503 and 504 are non-retryable
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(500).second)
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(501).second)
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(502).second)
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(505).second)
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(520).second)

        // 4xx errors are non-retryable
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(400).second)
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(401).second)
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(403).second)
        assertEquals(false, OpenAiEmbeddingClient.classifyHttpError(404).second)
    }

    @Test
    fun http501NonRetryableAnd504Retryable() = runBlocking {
        // 501 test
        server.enqueue(MockResponse().setResponseCode(501).setBody("Not Implemented"))
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val ex501 = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(501, ex501.statusCode)
        assertEquals(EmbeddingErrorCode.SERVER_ERROR, ex501.errorCode)
        assertFalse("501 must NOT be retryable", ex501.retryable)

        // 504 test
        server.enqueue(MockResponse().setResponseCode(504).setBody("Gateway Timeout"))
        val ex504 = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }
        assertEquals(504, ex504.statusCode)
        assertEquals(EmbeddingErrorCode.SERVER_ERROR, ex504.errorCode)
        assertTrue("504 must be retryable", ex504.retryable)
    }

    @Test
    fun retryableClassificationNetworkErrors() {
        // Timeouts are retryable
        val socketTimeout = SocketTimeoutException("Read timed out")
        assertTrue(OpenAiEmbeddingClient.classifyNetworkError(socketTimeout).second)

        val timeoutMsg = InterruptedIOException("failed: connect timed out")
        assertTrue(OpenAiEmbeddingClient.classifyNetworkError(timeoutMsg).second)

        val interruptedTimeout = InterruptedIOException("timeout")
        assertTrue(OpenAiEmbeddingClient.classifyNetworkError(interruptedTimeout).second)

        val wrappedTimeout = IOException("Network error", SocketTimeoutException())
        assertTrue(OpenAiEmbeddingClient.classifyNetworkError(wrappedTimeout).second)

        // Generic IOException with "timeout" in message is NOT retryable without explicit timeout exception
        val genericIoWithTimeout = IOException("failed: connect timed out")
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(genericIoWithTimeout).second)

        // TLS/SSL errors are NEVER retryable (even if outer wrapper says "connect timed out")
        val sslHandshake = SSLHandshakeException("Handshake failed")
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(sslHandshake).second)

        val sslEx = SSLException("Certificate verification failed")
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(sslEx).second)

        val wrappedSsl = IOException("Network wrapper", SSLHandshakeException("bad cert"))
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(wrappedSsl).second)

        val sslWithTimeoutMsg = SSLException("connect timed out")
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(sslWithTimeoutMsg).second)

        val wrappedSslWithTimeoutMsg = IOException("connect timed out", SSLException("handshake failed"))
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(wrappedSslWithTimeoutMsg).second)

        val wrappedSslHandshakeWithTimeoutMsg = IOException("failed: connect timed out", SSLHandshakeException("SSL handshake timed out"))
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(wrappedSslHandshakeWithTimeoutMsg).second)

        val wrappedCertWithTimeoutMsg = IOException("connect timed out", CertificateException("Certificate expired"))
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(wrappedCertWithTimeoutMsg).second)

        // Security errors (SecurityException) are NEVER retryable (even if wrapped in timeouts or having timeout messages)
        val securityExWithTimeoutMsg = IOException("connect timed out", SecurityException("Permission denied"))
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(securityExWithTimeoutMsg).second)

        val socketTimeoutWithSecurityCause = SocketTimeoutException("Read timed out").apply {
            initCause(SecurityException("android.permission.INTERNET denied"))
        }
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(socketTimeoutWithSecurityCause).second)

        val interruptedTimeoutWithSecurityCause = InterruptedIOException("timeout").apply {
            initCause(SecurityException("Permission denied"))
        }
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(interruptedTimeoutWithSecurityCause).second)

        val nestedSecurityWithTimeout = IOException(
            "connect timed out",
            IOException("transport error", SecurityException("Permission denied"))
        )
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(nestedSecurityWithTimeout).second)

        // Interrupted/cancelled are NOT retryable timeouts
        val threadInterrupted = InterruptedIOException("thread interrupted")
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(threadInterrupted).second)

        val interruptedWithCause = InterruptedIOException("timeout").apply { initCause(InterruptedException()) }
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(interruptedWithCause).second)

        val cancelledWithCause = InterruptedIOException("timeout").apply { initCause(CancellationException("cancelled")) }
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(cancelledWithCause).second)

        // Connection refused and others are NOT retryable
        val connRefused = ConnectException("Connection refused")
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(connRefused).second)

        val genericIo = IOException("Unexpected EOF")
        assertFalse(OpenAiEmbeddingClient.classifyNetworkError(genericIo).second)
    }

    @Test
    fun securityExceptionInCauseChainNeverRetryableEvenWithTimeout() {
        // Direct SecurityException with timeout in message
        val directSecWithTimeoutMsg = SecurityException("connect timed out")
        assertFalse(OpenAiEmbeddingClient.isTimeoutException(directSecWithTimeoutMsg))
        assertTrue(OpenAiEmbeddingClient.isSecurityOrSslException(directSecWithTimeoutMsg))

        // SecurityException wrapped in IOException with timeout message
        val ioWithTimeoutMsgAndSecurity = IOException("connect timed out", SecurityException("Permission denied"))
        val (code1, retryable1) = OpenAiEmbeddingClient.classifyNetworkError(ioWithTimeoutMsgAndSecurity)
        assertEquals(EmbeddingErrorCode.NETWORK_ERROR, code1)
        assertFalse("SecurityException cause must never be retryable", retryable1)

        // SecurityException wrapped in SocketTimeoutException
        val socketTimeoutWithSecurity = SocketTimeoutException("Read timed out").apply {
            initCause(SecurityException("android.permission.INTERNET denied"))
        }
        val (code2, retryable2) = OpenAiEmbeddingClient.classifyNetworkError(socketTimeoutWithSecurity)
        assertEquals(EmbeddingErrorCode.NETWORK_ERROR, code2)
        assertFalse("SocketTimeoutException wrapping SecurityException must not be retryable", retryable2)

        // SecurityException wrapped in InterruptedIOException with timeout message
        val interruptedWithSecurity = InterruptedIOException("timed out").apply {
            initCause(SecurityException("Security policy violation"))
        }
        val (code3, retryable3) = OpenAiEmbeddingClient.classifyNetworkError(interruptedWithSecurity)
        assertEquals(EmbeddingErrorCode.NETWORK_ERROR, code3)
        assertFalse("InterruptedIOException wrapping SecurityException must not be retryable", retryable3)

        // Multi-level nested cause chain containing SecurityException
        val deeplyNested = IOException(
            "connection timed out",
            IOException(
                "intermediate transport error",
                SocketTimeoutException("socket timeout").apply {
                    initCause(SecurityException("Operation not permitted"))
                }
            )
        )
        val (code4, retryable4) = OpenAiEmbeddingClient.classifyNetworkError(deeplyNested)
        assertEquals(EmbeddingErrorCode.NETWORK_ERROR, code4)
        assertFalse("Deeply nested SecurityException in cause chain must not be retryable", retryable4)

        // End-to-end: client.createEmbeddings when OkHttp call throws IOException wrapping SecurityException
        val mockClientIoSecurity = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("connect timed out", SecurityException("Permission denied"))
            }
            .build()

        val embeddingClientIo = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            httpClient = mockClientIoSecurity,
            allowTestLoopback = true,
        )

        val exIo = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                embeddingClientIo.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }
        assertEquals(EmbeddingErrorCode.NETWORK_ERROR, exIo.errorCode)
        assertFalse("EmbeddingException must not be retryable when cause chain has SecurityException", exIo.retryable)

        // End-to-end: client.createEmbeddings when OkHttp call throws direct SecurityException
        val mockClientDirectSecurity = OkHttpClient.Builder()
            .addInterceptor {
                throw SecurityException("Direct permission denied")
            }
            .build()

        val embeddingClientDirect = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            httpClient = mockClientDirectSecurity,
            allowTestLoopback = true,
        )

        val exDirect = assertThrows(EmbeddingException::class.java) {
            runBlocking {
                embeddingClientDirect.createEmbeddings(EmbeddingRequest(input = listOf("test")))
            }
        }
        assertEquals(EmbeddingErrorCode.NETWORK_ERROR, exDirect.errorCode)
        assertFalse("Direct SecurityException must not be retryable", exDirect.retryable)
    }

    // --- Reviewer Item 6: Strict validation of item count & index integrity ---

    @Test
    fun responseEmptyDataArrayThrowsInvalidResponse() = runBlocking {
        val emptyDataResponse = """{
            "object": "list",
            "data": [],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyDataResponse))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseItemCountMismatchThrowsInvalidResponse() = runBlocking {
        // Request expects 2 items, server returns 1
        val oneItemResponse = jsonEmbeddingResponse(listOf(floatArrayOf(0.1f)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(oneItemResponse))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1", "item2"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseDuplicateIndexThrowsInvalidResponse() = runBlocking {
        val duplicateIndexJson = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":0,"embedding":[0.1]},
                {"object":"embedding","index":0,"embedding":[0.2]}
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(duplicateIndexJson))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1", "item2"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseMissingOrOutOfBoundsIndexThrowsInvalidResponse() = runBlocking {
        // Missing index 0, contains index 1 and 2 for batch size 2
        val outOfBoundsJson = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":1,"embedding":[0.1]},
                {"object":"embedding","index":2,"embedding":[0.2]}
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(outOfBoundsJson))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1", "item2"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseEmptyEmbeddingVectorThrowsInvalidResponse() = runBlocking {
        val emptyVectorJson = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":0,"embedding":[]}
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyVectorJson))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseEmbeddingWithNanStringThrowsInvalidResponse() = runBlocking {
        val nanJson = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":0,"embedding":["NaN", 0.1]}
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(nanJson))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseEmbeddingWithInfinityStringThrowsInvalidResponse() = runBlocking {
        val infJson = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":0,"embedding":["Infinity", 0.1]}
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(infJson))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseEmbeddingWithOverflowNumberThrowsInvalidResponse() = runBlocking {
        val overflowJson = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":0,"embedding":[1e100, 0.1]}
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(overflowJson))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    @Test
    fun responseEmbeddingWithNegativeOverflowNumberThrowsInvalidResponse() = runBlocking {
        val negOverflowJson = """{
            "object": "list",
            "data": [
                {"object":"embedding","index":0,"embedding":[-1e100, 0.1]}
            ],
            "model": "text-embedding-3-small"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(negOverflowJson))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("item1"))) }
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
    }

    // --- Subagent A Blocker: Safe Cause & Zero Stack Trace Key Shielding ---

    @Test
    fun interceptorThrowsIOExceptionWithNonStandardKey_causeAndStackTraceDoNotLeakKey() = runBlocking {
        val nonStandardKey = "custom-secret-key-xyz-98765"
        val mockClient = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Network failure for token $nonStandardKey")
            }
            .build()

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { nonStandardKey.toCharArray() },
            httpClient = mockClient,
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }

        assertFalse("Exception message must not leak raw key", ex.message!!.contains(nonStandardKey))
        assertFalse("safeMessage must not leak raw key", ex.safeMessage.contains(nonStandardKey))
        assertTrue("safeMessage must contain redaction marker", ex.safeMessage.contains("⟦REDACTED:API_KEY⟧"))

        val cause = ex.cause
        assertNotNull("Cause must be preserved as safeCause", cause)
        assertTrue("Cause should be IOException", cause is IOException)
        assertFalse("Cause message must not leak raw key", cause!!.message!!.contains(nonStandardKey))
        assertTrue("Cause message must contain redaction marker", cause.message!!.contains("⟦REDACTED:API_KEY⟧"))
        assertFalse("Cause toString must not leak raw key", cause.toString().contains(nonStandardKey))
        assertTrue("Cause stackTrace must be empty", cause.stackTrace.isEmpty())

        val fullStackTrace = ex.stackTraceToString()
        assertFalse("Full stackTraceToString must not leak raw key", fullStackTrace.contains(nonStandardKey))
        assertTrue("Full stackTraceToString must contain redaction marker", fullStackTrace.contains("⟦REDACTED:API_KEY⟧"))
    }

    @Test
    fun interceptorThrowsSecurityExceptionWithNonStandardKey_causeAndStackTraceDoNotLeakKey() = runBlocking {
        val nonStandardKey = "custom-sec-secret-key-abc-12345"
        val mockClient = OkHttpClient.Builder()
            .addInterceptor {
                throw SecurityException("Security check failed for token: $nonStandardKey")
            }
            .build()

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { nonStandardKey.toCharArray() },
            httpClient = mockClient,
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }

        assertEquals(EmbeddingErrorCode.NETWORK_ERROR, ex.errorCode)
        assertFalse(ex.retryable)
        assertFalse("Exception message must not leak raw key", ex.message!!.contains(nonStandardKey))
        assertFalse("safeMessage must not leak raw key", ex.safeMessage.contains(nonStandardKey))
        assertTrue("safeMessage must contain redaction marker", ex.safeMessage.contains("⟦REDACTED"))

        val cause = ex.cause
        assertNotNull("Cause must be preserved as safeCause", cause)
        assertTrue("Cause should be SecurityException", cause is SecurityException)
        assertFalse("Cause message must not leak raw key", cause!!.message!!.contains(nonStandardKey))
        assertTrue("Cause message must contain redaction marker", cause.message!!.contains("⟦REDACTED"))
        assertFalse("Cause toString must not leak raw key", cause.toString().contains(nonStandardKey))
        assertTrue("Cause stackTrace must be empty", cause.stackTrace.isEmpty())

        val fullStackTrace = ex.stackTraceToString()
        assertFalse("Full stackTraceToString must not leak raw key", fullStackTrace.contains(nonStandardKey))
        assertTrue("Full stackTraceToString must contain redaction marker", fullStackTrace.contains("⟦REDACTED"))
    }

    // --- Subagent B Blocker 1: Maximum Supported Vector Dimension Validation ---

    @Test
    fun responseDimensionExceeding3072RejectedAsInvalidResponse() = runBlocking {
        val hugeDimension = 3073
        val vectorJson = (1..hugeDimension).joinToString(",") { "0.01" }
        val responseBody = """{
            "object": "list",
            "data": [
                {
                    "object": "embedding",
                    "index": 0,
                    "embedding": [$vectorJson]
                }
            ],
            "model": "text-embedding-3-large"
        }""".trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test-api-key".toCharArray() },
            allowTestLoopback = true,
        )

        val ex = assertThrows(EmbeddingException::class.java) {
            runBlocking { client.createEmbeddings(EmbeddingRequest(input = listOf("test"))) }
        }

        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertFalse(ex.retryable)
        assertTrue(
            "Message must mention dimension exceeds maximum allowed",
            ex.safeMessage.contains("Embedding dimension exceeds maximum allowed (${OpenAiEmbeddingClient.MAX_SUPPORTED_DIMENSION})")
        )
    }

    @Test
    fun parseResponseBodyJsonRejectsDimension3073() {
        val floats = (1..3073).joinToString(",") { "0.1" }
        val json = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[$floats]}],"model":"text-embedding-3-large"}"""

        val ex = assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = json,
                fallbackModel = "text-embedding-3-large",
                statusCode = 200,
                expectedInputSize = 1,
            )
        }

        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertEquals("Embedding dimension exceeds maximum allowed (3072)", ex.safeMessage)
    }

    @Test
    fun parseResponseBodyJsonAcceptsMaxDimension3072() {
        val floats = (1..3072).joinToString(",") { "0.1" }
        val json = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[$floats]}],"model":"text-embedding-3-large"}"""

        val response = OpenAiEmbeddingClient.parseResponseBodyJson(
            responseString = json,
            fallbackModel = "text-embedding-3-large",
            statusCode = 200,
            expectedInputSize = 1,
        )

        assertEquals(1, response.data.size)
        assertEquals(3072, response.data[0].embedding.size)
    }

    @Test
    fun parseResponseBodyJsonRejectsTrailingGarbageAfterRoot() {
        val validJson = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        val invalidWithGarbage = validJson + "garbage"

        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = invalidWithGarbage,
                fallbackModel = "text-embedding-3-small",
                statusCode = 200,
                expectedInputSize = 1,
            )
        }
    }

    @Test
    fun parseResponseBodyJsonRejectsTrailingCommaInArrayOrObject() {
        val trailingCommaInArray = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2,]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = trailingCommaInArray,
                fallbackModel = "text-embedding-3-small",
                statusCode = 200,
                expectedInputSize = 1,
            )
        }

        val missingCommaInArray = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = missingCommaInArray,
                fallbackModel = "text-embedding-3-small",
                statusCode = 200,
                expectedInputSize = 1,
            )
        }
    }

    @Test
    fun parseResponseBodyJsonRejectsMalformedSkippedValues() {
        val missingValueAfterColon = """{"extra":, "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = missingValueAfterColon,
                fallbackModel = "text-embedding-3-small",
                statusCode = 200,
                expectedInputSize = 1,
            )
        }

        val malformedSkippedObject = """{"extra":{"a":1 "b":2}, "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = malformedSkippedObject,
                fallbackModel = "text-embedding-3-small",
                statusCode = 200,
                expectedInputSize = 1,
            )
        }

        val trailingCommaInSkippedObject = """{"extra":{"a":1,}, "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = trailingCommaInSkippedObject,
                fallbackModel = "text-embedding-3-small",
                statusCode = 200,
                expectedInputSize = 1,
            )
        }
    }

    @Test
    fun parseResponseBodyJsonRejectsRfc8259Violations() {
        // Leading '+' in float array
        val leadingPlusInFloat = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[+0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(leadingPlusInFloat, "test", 200, 1)
        }

        // Leading '+' in skipped number
        val leadingPlusInSkipped = """{"extra":+123, "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(leadingPlusInSkipped, "test", 200, 1)
        }

        // Invalid escape sequence in string
        val invalidEscape = """{"extra":"\q", "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(invalidEscape, "test", 200, 1)
        }

        // Invalid unicode escape with non-hex char '+'
        val invalidUnicodePlus = """{"extra":"\u+123", "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(invalidUnicodePlus, "test", 200, 1)
        }

        // Unescaped control character in string
        val unescapedControl = "{\"extra\":\"\u0001\", \"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1, 0.2]}],\"model\":\"text-embedding-3-small\"}"
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(unescapedControl, "test", 200, 1)
        }

        // Leading zero in float
        val leadingZeroInFloat = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[01.5, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(leadingZeroInFloat, "test", 200, 1)
        }

        // Leading zero in integer index
        val leadingZeroInIndex = """{"object":"list","data":[{"object":"embedding","index":00,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(leadingZeroInIndex, "test", 200, 1)
        }

        // Leading zero in skipped value
        val leadingZeroInSkipped = """{"extra":01, "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(leadingZeroInSkipped, "test", 200, 1)
        }

        // Excessive nesting depth (> 64)
        val deepOpen = "{".repeat(70)
        val deepClose = "}".repeat(70)
        val excessiveNesting = """{"extra": $deepOpen"a": 1$deepClose, "object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(excessiveNesting, "test", 200, 1)
        }
    }

    @Test
    fun createEmbeddingsDefendsAgainstMutatingCallerListAfterConstruction() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonEmbeddingResponse(listOf(floatArrayOf(0.1f, 0.2f)))))
        val client = OpenAiEmbeddingClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )

        val mutableInput = mutableListOf("valid query")
        val request = EmbeddingRequest(input = mutableInput)
        // Caller mutates the list after constructing request to include blank items
        mutableInput.add("   ")

        // createEmbeddings must take defensive snapshot and fail-fast against blank items
        val err = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.createEmbeddings(request) }
        }
        assertTrue(err.message!!.contains("input items cannot be blank"))
        assertEquals("No network request should be sent when mutated list fails validation", 0, server.requestCount)
    }

    @Test
    fun parseResponseBodyJsonRejectsExpectedDimensionMismatch() {
        val json = """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1, 0.2]}],"model":"text-embedding-3-small"}"""
        val ex = assertThrows(EmbeddingException::class.java) {
            OpenAiEmbeddingClient.parseResponseBodyJson(
                responseString = json,
                fallbackModel = "text-embedding-3-small",
                statusCode = 200,
                expectedInputSize = 1,
                expectedDimensions = 256,
            )
        }
        assertEquals(EmbeddingErrorCode.INVALID_RESPONSE, ex.errorCode)
        assertTrue(ex.safeMessage.contains("Embedding dimension mismatch: expected 256, got 2"))
    }
}
