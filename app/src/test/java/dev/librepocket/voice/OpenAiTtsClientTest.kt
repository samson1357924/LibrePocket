package dev.librepocket.voice

import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiTtsClientTest {

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

    @Test
    fun successfulSynthesize_sendsCorrectFieldsAndReceivesBinaryAudio() = runBlocking {
        val sampleAudio = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(sampleAudio)),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-api-key-123".toCharArray() },
        )

        val request = OpenAiTtsRequest(
            input = "Hello OpenAI TTS",
            model = "tts-1",
            voice = "alloy",
            responseFormat = "mp3",
            speed = 1.0f,
        )

        val result = client.synthesize(request)

        assertArrayEquals(sampleAudio, result.audioBytes)
        assertEquals("audio/mpeg", result.contentType)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("POST", recorded!!.method)
        assertEquals("/v1/audio/speech", recorded.path)
        assertEquals("Bearer test-api-key-123", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Content-Type")?.substringBefore(";"))

        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("tts-1", bodyJson["model"]?.jsonPrimitive?.content)
        assertEquals("alloy", bodyJson["voice"]?.jsonPrimitive?.content)
        assertEquals("Hello OpenAI TTS", bodyJson["input"]?.jsonPrimitive?.content)
        assertEquals("mp3", bodyJson["response_format"]?.jsonPrimitive?.content)
        assertEquals(1.0f, bodyJson["speed"]?.jsonPrimitive?.floatOrNull)
    }

    @Test
    fun successfulSynthesize_customVoiceAndFormat() = runBlocking {
        val sampleAudio = byteArrayOf(0x4F, 0x67, 0x67, 0x53) // OggS
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/opus")
                .setBody(Buffer().write(sampleAudio)),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "custom-key".toCharArray() },
        )

        val request = OpenAiTtsRequest(
            input = "Testing custom parameters",
            model = "tts-1-hd",
            voice = "echo",
            responseFormat = "opus",
            speed = 1.25f,
        )

        val result = client.synthesize(request)

        assertArrayEquals(sampleAudio, result.audioBytes)
        assertEquals("audio/opus", result.contentType)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val bodyJson = Json.parseToJsonElement(recorded!!.body.readUtf8()).jsonObject
        assertEquals("tts-1-hd", bodyJson["model"]?.jsonPrimitive?.content)
        assertEquals("echo", bodyJson["voice"]?.jsonPrimitive?.content)
        assertEquals("Testing custom parameters", bodyJson["input"]?.jsonPrimitive?.content)
        assertEquals("opus", bodyJson["response_format"]?.jsonPrimitive?.content)
        assertEquals(1.25f, bodyJson["speed"]?.jsonPrimitive?.floatOrNull)
    }

    @Test
    fun privacyRedaction_sensitiveInfoRedactedBeforeNetworkSend() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3))),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
        )

        val sensitiveInput = "Contact me at alice@example.com or 0912345678, secret sk-abcdef1234567890abcdef1234567890."
        val expectedRedacted = VoiceTools.redactForCloud(sensitiveInput)

        client.synthesize(OpenAiTtsRequest(input = sensitiveInput))

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val rawBody = recorded!!.body.readUtf8()

        assertFalse("Request body must not contain plain email", rawBody.contains("alice@example.com"))
        assertFalse("Request body must not contain plain phone number", rawBody.contains("0912345678"))
        assertFalse("Request body must not contain plain API key", rawBody.contains("sk-abcdef1234567890abcdef1234567890"))

        val bodyJson = Json.parseToJsonElement(rawBody).jsonObject
        val sentInput = bodyJson["input"]?.jsonPrimitive?.content
        assertEquals(expectedRedacted, sentInput)
    }

    @Test
    fun errorHandling_401Unauthorized() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "bad-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for 401")
        } catch (e: OpenAiTtsException) {
            assertEquals(401, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.UNAUTHORIZED, e.errorCode)
            assertFalse("401 must not be retryable", e.retryable)
            assertTrue("Safe message should contain error detail: ${e.safeMessage}", e.safeMessage.contains("Incorrect API key"))
        }
    }

    @Test
    fun errorHandling_401WithKeyLeakInErrorMessage_isRedacted() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Incorrect API key provided: sk-abcdef1234567890abcdef1234567890","type":"invalid_request_error"}}"""),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "bad-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException")
        } catch (e: OpenAiTtsException) {
            assertEquals(401, e.statusCode)
            assertFalse("Safe message must not leak key: ${e.safeMessage}", e.safeMessage.contains("sk-abcdef1234567890abcdef1234567890"))
            assertTrue("Safe message must contain redaction marker: ${e.safeMessage}", e.safeMessage.contains("REDACTED"))
        }
    }

    @Test
    fun errorHandling_403Forbidden() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Country or region not supported","type":"unsupported_region"}}"""),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "valid-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for 403")
        } catch (e: OpenAiTtsException) {
            assertEquals(403, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.FORBIDDEN, e.errorCode)
            assertFalse(e.retryable)
            assertTrue(e.safeMessage.contains("Country or region not supported"))
        }
    }

    @Test
    fun errorHandling_429RateLimit() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Rate limit reached for requests","type":"requests"}}"""),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "valid-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for 429")
        } catch (e: OpenAiTtsException) {
            assertEquals(429, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.RATE_LIMIT, e.errorCode)
            assertTrue("429 rate limit must be retryable", e.retryable)
            assertTrue(e.safeMessage.contains("Rate limit reached"))
        }
    }

    @Test
    fun errorHandling_500InternalError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"The server encountered an error processing your request","type":"server_error"}}"""),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "valid-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for 500")
        } catch (e: OpenAiTtsException) {
            assertEquals(500, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.SERVER_ERROR, e.errorCode)
            assertFalse("500 server error must not be retryable", e.retryable)
            assertTrue(e.safeMessage.contains("The server encountered an error"))
        }
    }

    @Test
    fun errorHandling_502HtmlError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>502 Bad Gateway</body></html>"),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "valid-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for 502")
        } catch (e: OpenAiTtsException) {
            assertEquals(502, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.SERVER_ERROR, e.errorCode)
            assertFalse("502 bad gateway must not be retryable", e.retryable)
            assertTrue(e.safeMessage.contains("502 Bad Gateway"))
        }
    }

    @Test
    fun missingKey_supplierReturnsNull() = runBlocking {
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { null },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException when apiKey is null")
        } catch (e: OpenAiTtsException) {
            assertNull("No HTTP call should be made", e.statusCode)
            assertEquals(OpenAiTtsErrorCode.MISSING_KEY, e.errorCode)
            assertFalse(e.retryable)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun missingKey_noSupplierConfigured() = runBlocking {
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = null,
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException when supplier is null")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.MISSING_KEY, e.errorCode)
            assertFalse(e.retryable)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun missingKey_blankKeyWipedAndThrows() = runBlocking {
        val keyChars = "   ".toCharArray()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { keyChars },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for blank key")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.MISSING_KEY, e.errorCode)
            assertTrue("Key array must be zeroed even if blank", keyChars.all { it == '\u0000' })
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun memoryKeyWiping_wipesKeyAfterSuccessfulCall() = runBlocking {
        val keyChars = "ephemeral-key-987".toCharArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3))),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { keyChars },
        )

        client.synthesize(OpenAiTtsRequest(input = "Wipe key test"))

        assertTrue("Key array must be overwritten with \\u0000 after success", keyChars.all { it == '\u0000' })
    }

    @Test
    fun memoryKeyWiping_wipesKeyAfterFailedCall() = runBlocking {
        val keyChars = "ephemeral-key-error".toCharArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Server failure"),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { keyChars },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Error wipe test"))
            fail("Expected exception")
        } catch (_: OpenAiTtsException) {
            // Expected
        }

        assertTrue("Key array must be overwritten with \\u0000 after error", keyChars.all { it == '\u0000' })
    }

    @Test
    fun coroutineCancellation_abortsOkHttpCallPromptly() = runBlocking {
        val canceledCount = AtomicInteger(0)
        val callCanceledLatch = CountDownLatch(1)
        val headersReceivedLatch = CountDownLatch(1)

        val listener = object : EventListener() {
            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                headersReceivedLatch.countDown()
            }

            override fun canceled(call: Call) {
                canceledCount.incrementAndGet()
                callCanceledLatch.countDown()
            }
        }

        val testHttpClient = OkHttpClient.Builder()
            .eventListener(listener)
            .build()

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            client = testHttpClient,
            apiKey = { "test-key".toCharArray() },
        )

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val deferred = async(Dispatchers.IO) {
            client.synthesize(OpenAiTtsRequest(input = "Cancel me"))
        }

        assertTrue("Request headers should reach server", headersReceivedLatch.await(5, TimeUnit.SECONDS))

        deferred.cancel()

        assertTrue("OkHttp Call must receive cancel()", callCanceledLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, canceledCount.get())

        try {
            deferred.await()
            fail("Deferred should have thrown CancellationException")
        } catch (e: CancellationException) {
            // Expected
        }
    }

    @Test
    fun inputValidation_exceeding4096Chars_failsClosed() = runBlocking {
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
        )

        val overlongInput = "A".repeat(4097)

        try {
            client.synthesize(OpenAiTtsRequest(input = overlongInput))
            fail("Expected OpenAiTtsException for input > 4096 chars")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertTrue(e.safeMessage.contains("4096"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun inputValidation_blankInput_failsClosed() = runBlocking {
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "   "))
            fail("Expected OpenAiTtsException for blank input")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun inputValidation_speedRange_failsClosed() = runBlocking {
        try {
            OpenAiTtsRequest(input = "Hello", speed = 0.1f)
            fail("Expected exception for speed < 0.25")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
        }

        try {
            OpenAiTtsRequest(input = "Hello", speed = 4.5f)
            fail("Expected exception for speed > 4.0")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
        }
    }

    @Test
    fun endpointResolution_handlesVariousBaseUrls() {
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://api.openai.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://api.openai.com/"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://api.openai.com/v1/"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://api.openai.com/v1/audio/speech"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://api.openai.com/v1/audio/speech/"),
        )
        assertEquals(
            "https://custom-proxy.internal/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://custom-proxy.internal/v1/audio/speech"),
        )
        assertEquals(
            "https://custom-proxy.internal/proxy/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://custom-proxy.internal/proxy/audio/speech"),
        )
        assertEquals(
            "https://custom-proxy.internal/proxy/v1/audio/speech",
            OpenAiTtsClient.resolveEndpoint("https://custom-proxy.internal/proxy"),
        )
    }

    @Test
    fun errorHandling_nonStandardKeyEchoedByServer_isRedacted() = runBlocking {
        val nonStandardKey = "my-custom-secret-key-12345"
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Invalid API key provided: $nonStandardKey","type":"invalid_request_error"}}"""),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { nonStandardKey.toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for 401 with echoed key")
        } catch (e: OpenAiTtsException) {
            assertEquals(401, e.statusCode)
            assertFalse("Safe message must not leak non-standard key", e.safeMessage.contains(nonStandardKey))
            assertFalse("Exception message must not leak non-standard key", e.message?.contains(nonStandardKey) == true)
            assertTrue("Safe message must contain redaction marker: ${e.safeMessage}", e.safeMessage.contains("⟦REDACTED:API_KEY⟧"))
            assertNull("No raw unredacted cause should be attached", e.cause)
        }
    }

    @Test
    fun keySupplier_throwsRuntimeException_wrappedSafelyWithoutLeakingSensitiveInfo() = runBlocking {
        val secretKey = "sk-abcdef1234567890abcdef1234567890"
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { throw RuntimeException("Keystore corrupted while reading secret: $secretKey") },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException when supplier throws")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.MISSING_KEY, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("e.message must not contain secret: ${e.message}", e.message?.contains(secretKey) == true)
            assertFalse("e.safeMessage must not contain secret: ${e.safeMessage}", e.safeMessage.contains(secretKey))
            assertFalse("e.cause?.message must not contain secret: ${e.cause?.message}", e.cause?.message?.contains(secretKey) == true)
            assertEquals("Key supplier failed to provide API key", e.safeMessage)
            assertEquals("Key supplier failed to provide API key", e.cause?.message)
            assertTrue("e.cause stackTrace must be empty", e.cause?.stackTrace?.isEmpty() == true)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun keySupplier_throwsRuntimeExceptionWithNonStandardKey_masksSensitiveDataCompletely() = runBlocking {
        val nonStandardKey = "my-custom-secret-key-12345"
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { throw RuntimeException("Cannot decrypt key $nonStandardKey from vault") },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException when supplier throws")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.MISSING_KEY, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("e.message must not contain non-standard key: ${e.message}", e.message?.contains(nonStandardKey) == true)
            assertFalse("e.safeMessage must not contain non-standard key: ${e.safeMessage}", e.safeMessage.contains(nonStandardKey))
            assertNotNull("e.cause must not be null", e.cause)
            assertFalse("e.cause?.message must not contain non-standard key: ${e.cause?.message}", e.cause?.message?.contains(nonStandardKey) == true)
            assertFalse("Raw exception message must not be leaked in safeMessage", e.safeMessage.contains("Cannot decrypt key"))
            assertFalse("Raw exception message must not be leaked in cause?.message", e.cause?.message?.contains("Cannot decrypt key") == true)
            assertEquals("Key supplier failed to provide API key", e.safeMessage)
            assertEquals("Key supplier failed to provide API key", e.cause?.message)
            assertTrue("e.cause stackTrace must be empty", e.cause?.stackTrace?.isEmpty() == true)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun keySupplier_throwsCustomExceptionWithSecretInClassNameAndStackTrace_masksCompletely() = runBlocking {
        val sensitiveSecret = "secret_in_stack_123"
        val customException = CustomSecretException_secret_in_stack_123(
            "Access denied to vault containing key $sensitiveSecret"
        ).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "dev.librepocket.vault.$sensitiveSecret.SecretVault",
                    "retrieveSecret_$sensitiveSecret",
                    "SecretVault_$sensitiveSecret.kt",
                    42,
                ),
            )
        }

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { throw customException },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException when supplier throws")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.MISSING_KEY, e.errorCode)
            assertFalse(e.retryable)

            // 1. Verify OpenAiTtsException fields and methods
            assertEquals("Key supplier failed to provide API key", e.safeMessage)
            assertEquals("Key supplier failed to provide API key", e.message)
            assertFalse(
                "e.message must not contain sensitive secret: ${e.message}",
                e.message?.contains(sensitiveSecret) == true,
            )
            assertFalse(
                "e.safeMessage must not contain sensitive secret: ${e.safeMessage}",
                e.safeMessage.contains(sensitiveSecret),
            )
            assertFalse(
                "e.toString() must not contain sensitive secret: $e",
                e.toString().contains(sensitiveSecret),
            )
            for (element in e.stackTrace) {
                assertFalse(
                    "e.stackTrace element must not contain sensitive secret: $element",
                    element.toString().contains(sensitiveSecret),
                )
            }

            // 2. Verify cause fields and methods
            val cause = checkNotNull(e.cause) { "e.cause must not be null" }
            assertEquals("Key supplier failed to provide API key", cause.message)
            assertFalse(
                "cause.message must not contain sensitive secret: ${cause.message}",
                cause.message?.contains(sensitiveSecret) == true,
            )
            assertFalse(
                "cause.toString() must not contain sensitive secret: $cause",
                cause.toString().contains(sensitiveSecret),
            )
            assertTrue(
                "cause.stackTrace must be empty to avoid leaking un-audited frames",
                cause.stackTrace.isEmpty(),
            )
            for (element in cause.stackTrace) {
                assertFalse(
                    "cause.stackTrace element must not contain sensitive secret: $element",
                    element.toString().contains(sensitiveSecret),
                )
            }

            // 3. Verify StringWriter printStackTrace outputs
            val swException = StringWriter()
            e.printStackTrace(PrintWriter(swException))
            val exceptionStackTrace = swException.toString()
            assertFalse(
                "OpenAiTtsException printStackTrace must not contain sensitive secret: $exceptionStackTrace",
                exceptionStackTrace.contains(sensitiveSecret),
            )

            val swCause = StringWriter()
            cause.printStackTrace(PrintWriter(swCause))
            val causeStackTrace = swCause.toString()
            assertFalse(
                "cause printStackTrace must not contain sensitive secret: $causeStackTrace",
                causeStackTrace.contains(sensitiveSecret),
            )

            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun sanitizeCause_clearsStackTraceEvenForDirectExceptionCause() {
        val sensitiveSecret = "secret_in_stack_123"
        val customException = CustomSecretException_secret_in_stack_123("Failed: $sensitiveSecret").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "dev.librepocket.vault.$sensitiveSecret.SecretVault",
                    "retrieveSecret_$sensitiveSecret",
                    "SecretVault_$sensitiveSecret.kt",
                    42,
                ),
            )
        }

        val sanitized = OpenAiTtsException.sanitizeCause(customException, activeKey = sensitiveSecret)
        assertNotNull(sanitized)
        assertTrue(
            "sanitized cause stackTrace must be empty",
            sanitized!!.stackTrace.isEmpty(),
        )
        assertFalse(
            "sanitized cause toString must not contain secret",
            sanitized.toString().contains(sensitiveSecret),
        )
        assertFalse(
            "sanitized cause message must not contain secret",
            sanitized.message?.contains(sensitiveSecret) == true,
        )

        val sw = StringWriter()
        sanitized.printStackTrace(PrintWriter(sw))
        assertFalse(
            "sanitized cause printStackTrace must not contain secret",
            sw.toString().contains(sensitiveSecret),
        )
    }

    @Test
    fun keySupplier_throwsCancellationException_sanitizesAndRethrowsCleanException() = runBlocking {
        val secretKey = "custom_secret_key_in_cancel"
        val customException = CancellationException(secretKey).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "dev.librepocket.SecretClass",
                    "secretMethodWith$secretKey",
                    "SecretSource.kt",
                    42,
                ),
            )
        }
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { throw customException },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("API key retrieval cancelled", e.message)
            assertFalse(
                "Caught CancellationException message must not contain secret",
                e.message?.contains(secretKey) == true,
            )
            assertFalse(
                "Caught CancellationException toString must not contain secret",
                e.toString().contains(secretKey),
            )
            assertTrue(
                "Caught CancellationException stackTrace must be empty",
                e.stackTrace.isEmpty(),
            )
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            assertFalse(
                "Caught CancellationException printStackTrace must not contain secret",
                sw.toString().contains(secretKey),
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun apiKeyOverride_throwsCancellationException_sanitizesAndRethrowsCleanException() = runBlocking {
        val secretKey = "custom_secret_key_in_override_cancel"
        val customException = CancellationException(secretKey).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "dev.librepocket.SecretClass",
                    "secretMethodWith$secretKey",
                    "SecretSource.kt",
                    42,
                ),
            )
        }
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "default-key".toCharArray() },
        )

        try {
            client.synthesize(
                OpenAiTtsRequest(input = "Hello"),
                apiKeyOverride = { throw customException },
            )
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("API key retrieval cancelled", e.message)
            assertFalse(
                "Caught CancellationException message must not contain secret",
                e.message?.contains(secretKey) == true,
            )
            assertTrue(
                "Caught CancellationException stackTrace must be empty",
                e.stackTrace.isEmpty(),
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun insecureEndpoint_plainHttpRemote_rejectedBeforeSending() = runBlocking {
        var supplierCalled = false
        val client = OpenAiTtsClient(
            baseUrl = "http://api.openai.com",
            apiKey = {
                supplierCalled = true
                "test-key".toCharArray()
            },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for insecure remote HTTP endpoint")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("Key supplier must not be called for insecure endpoint", supplierCalled)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun isLoopbackHost_strictlyValidatesLoopbackPatterns() {
        val loopbacks = listOf(
            "localhost",
            "LOCALHOST",
            "sub.localhost",
            "api.backend.localhost",
            "localhost.",
            "sub.localhost.",
            "127.0.0.1",
            "127.0.0.2",
            "127.1.0.1",
            "127.255.255.254",
            "127.255.255.255",
            "::1",
            "[::1]",
            "0:0:0:0:0:0:0:1",
            "[0:0:0:0:0:0:0:1]",
        )
        for (h in loopbacks) {
            assertTrue("Expected loopback host for: $h", OpenAiTtsClient.isLoopbackHost(h))
        }

        val nonLoopbacks = listOf(
            "127.attacker.example",
            "127.0.0.1.nip.io",
            "127.0.0.1.evil.com",
            "127.0.0.1.xip.io",
            "localhost.attacker.example",
            "evil.localhost.com",
            "evil-localhost",
            "localhostevil",
            ".localhost",
            "127.0.0.256",
            "127.0.0",
            "127.0.0.0.1",
            "128.0.0.1",
            "0.0.0.0",
            "192.168.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "::2",
            "fe80::1",
            "api.openai.com",
        )
        for (h in nonLoopbacks) {
            assertFalse("Expected non-loopback host for: $h", OpenAiTtsClient.isLoopbackHost(h))
        }
    }

    @Test
    fun insecureEndpoint_disguisedLoopbackDomains_rejectedBeforeSending() = runBlocking {
        val disguisedHosts = listOf(
            "http://127.attacker.example",
            "http://127.0.0.1.nip.io",
            "http://127.0.0.1.evil.com",
            "http://localhost.attacker.example",
            "http://evil.localhost.com",
            "http://127.0.0.256:8080",
            "http://192.168.1.1:8080",
        )

        for (endpoint in disguisedHosts) {
            var supplierCalled = false
            val client = OpenAiTtsClient(
                baseUrl = endpoint,
                apiKey = {
                    supplierCalled = true
                    "test-key".toCharArray()
                },
            )

            try {
                client.synthesize(OpenAiTtsRequest(input = "Hello"))
                fail("Expected OpenAiTtsException for disguised insecure host: $endpoint")
            } catch (e: OpenAiTtsException) {
                assertNull(e.statusCode)
                assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
                assertFalse(e.retryable)
                assertFalse("Key supplier must not be called for insecure endpoint: $endpoint", supplierCalled)
                assertTrue("Safe message should mention loopback requirement: ${e.safeMessage}", e.safeMessage.contains("loopback"))
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun productionDefault_thirdPartyHttpsEndpoint_rejectedWithInvalidInputBeforeSending() = runBlocking {
        var supplierCalled = false
        val client = OpenAiTtsClient(
            baseUrl = "https://thirdparty.example.com/v1",
            apiKey = {
                supplierCalled = true
                "test-key".toCharArray()
            },
            allowTestLoopback = false,
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello thirdparty"))
            fail("Expected OpenAiTtsException for thirdparty HTTPS endpoint under production default")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("Key supplier must not be called", supplierCalled)
            assertTrue("Safe message must mention allowed endpoint: ${e.safeMessage}", e.safeMessage.contains("api.openai.com"))
        }
    }

    @Test
    fun productionDefault_loopbackEndpoint_rejectedWithInvalidInputBeforeSending() = runBlocking {
        var supplierCalled = false
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = {
                supplierCalled = true
                "test-key".toCharArray()
            },
            allowTestLoopback = false,
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello loopback"))
            fail("Expected OpenAiTtsException for loopback endpoint under production default")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("Key supplier must not be called", supplierCalled)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun testIsolation_thirdPartyHttpsEndpoint_stillRejectedWithInvalidInputEvenWhenLoopbackAllowed() = runBlocking {
        var supplierCalled = false
        val client = OpenAiTtsClient(
            baseUrl = "https://thirdparty.example.com/v1",
            apiKey = {
                supplierCalled = true
                "test-key".toCharArray()
            },
            allowTestLoopback = true,
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello thirdparty with loopback flag"))
            fail("Expected OpenAiTtsException for thirdparty HTTPS endpoint even with allowTestLoopback = true")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("Key supplier must not be called", supplierCalled)
            assertTrue("Safe message must mention allowed endpoint: ${e.safeMessage}", e.safeMessage.contains("api.openai.com"))
        }
    }

    @Test
    fun validateEndpointUrl_strictRestrictionsEnforced() {
        // 官方 OpenAI 端點：不論旗標皆允許
        OpenAiTtsClient.validateEndpointUrl("https://api.openai.com/v1/audio/speech", allowTestLoopback = false)
        OpenAiTtsClient.validateEndpointUrl("https://api.openai.com/v1/audio/speech", allowTestLoopback = true)

        // 本機 loopback 端點：生產預設拋 INVALID_INPUT，測試隔離旗標放行
        try {
            OpenAiTtsClient.validateEndpointUrl("http://localhost:8080/v1/audio/speech", allowTestLoopback = false)
            fail("Expected OpenAiTtsException for loopback without test flag")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
        }
        OpenAiTtsClient.validateEndpointUrl("http://localhost:8080/v1/audio/speech", allowTestLoopback = true)
        OpenAiTtsClient.validateEndpointUrl("http://127.0.0.1:8080/v1/audio/speech", allowTestLoopback = true)

        // 第三方 HTTPS：不論旗標一律拋 INVALID_INPUT 拒絕
        try {
            OpenAiTtsClient.validateEndpointUrl("https://thirdparty.example.com/v1/audio/speech", allowTestLoopback = false)
            fail("Expected OpenAiTtsException for thirdparty HTTPS")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
        }
        try {
            OpenAiTtsClient.validateEndpointUrl("https://thirdparty.example.com/v1/audio/speech", allowTestLoopback = true)
            fail("Expected OpenAiTtsException for thirdparty HTTPS even with allowTestLoopback")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
        }
    }

    @Test
    fun responseAudio_exceedsMaxSizeDeclaredContentLength_cancelsCallAndThrowsResponseTooLarge() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setHeader("Content-Length", "200")
                .setBody(Buffer().write(ByteArray(200))),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
            maxAudioBytes = 100L,
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Large audio test"))
            fail("Expected OpenAiTtsException for exceeding max audio bytes")
        } catch (e: OpenAiTtsException) {
            assertEquals(200, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.RESPONSE_TOO_LARGE, e.errorCode)
            assertFalse(e.retryable)
            assertTrue("Safe message should mention limit: ${e.safeMessage}", e.safeMessage.contains("100"))
        }
    }

    @Test
    fun responseAudio_exceedsMaxSizeChunkedStream_cancelsCallAndThrowsResponseTooLarge() = runBlocking {
        val largeBuffer = Buffer().write(ByteArray(500))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setChunkedBody(largeBuffer, 50),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
            maxAudioBytes = 100L,
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Large chunked audio test"))
            fail("Expected OpenAiTtsException for exceeding chunked max audio bytes")
        } catch (e: OpenAiTtsException) {
            assertEquals(200, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.RESPONSE_TOO_LARGE, e.errorCode)
            assertFalse(e.retryable)
            assertTrue(e.safeMessage.contains("100"))
        }
    }

    @Test
    fun responseAudio_exactMaxSizeChunkedStream_successfullyReturnsAudio() = runBlocking {
        val sampleAudio = ByteArray(100) { it.toByte() }
        val streamBuffer = Buffer().write(sampleAudio)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setChunkedBody(streamBuffer, 25),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
            maxAudioBytes = 100L,
        )

        val result = client.synthesize(OpenAiTtsRequest(input = "Exact max size audio test"))

        assertArrayEquals(sampleAudio, result.audioBytes)
        assertEquals(100, result.audioBytes.size)
    }

    @Test
    fun responseAudio_oneByteOverMaxSizeChunkedStream_cancelsCallAndThrowsResponseTooLarge() = runBlocking {
        val streamBuffer = Buffer().write(ByteArray(101))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setChunkedBody(streamBuffer, 50),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
            maxAudioBytes = 100L,
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "One byte over max audio test"))
            fail("Expected OpenAiTtsException for exceeding chunked max audio bytes by 1 byte")
        } catch (e: OpenAiTtsException) {
            assertEquals(200, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.RESPONSE_TOO_LARGE, e.errorCode)
            assertFalse(e.retryable)
            assertTrue(e.safeMessage.contains("100"))
        }
    }

    @Test
    fun successfulSynthesize_withMaxAudioBytesLongMaxValue_readsNonEmptyAudioStreamWithoutOverflow() = runBlocking {
        val sampleAudio = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(sampleAudio)),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
            maxAudioBytes = Long.MAX_VALUE,
        )

        val result = client.synthesize(OpenAiTtsRequest(input = "Long.MAX_VALUE audio stream test"))

        assertArrayEquals(sampleAudio, result.audioBytes)
        assertEquals(sampleAudio.size, result.audioBytes.size)
        assertTrue(result.audioBytes.isNotEmpty())
        assertEquals("audio/mpeg", result.contentType)
    }

    @Test
    fun constructor_maxAudioBytesZero_throwsIllegalArgumentException() {
        try {
            OpenAiTtsClient(
                baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
                apiKey = { "test-key".toCharArray() },
                maxAudioBytes = 0L,
            )
            fail("Expected IllegalArgumentException for maxAudioBytes = 0")
        } catch (e: IllegalArgumentException) {
            assertEquals("maxAudioBytes must be positive, was: 0", e.message)
        }
    }

    @Test
    fun constructor_maxAudioBytesNegative_throwsIllegalArgumentException() {
        try {
            OpenAiTtsClient(
                baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
                apiKey = { "test-key".toCharArray() },
                maxAudioBytes = -1L,
            )
            fail("Expected IllegalArgumentException for maxAudioBytes = -1")
        } catch (e: IllegalArgumentException) {
            assertEquals("maxAudioBytes must be positive, was: -1", e.message)
        }
    }

    @Test
    fun retryableAccuracy_503And504AreRetryable_500And501AreNot() = runBlocking {
        val codesAndExpectedRetryable = listOf(
            429 to true,
            503 to true,
            504 to true,
            500 to false,
            501 to false,
            502 to false,
            400 to false,
            401 to false,
            403 to false,
            404 to false,
        )

        for ((code, expectedRetryable) in codesAndExpectedRetryable) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"message":"Error $code","type":"test_error"}}"""),
            )

            val client = OpenAiTtsClient(
                baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
                apiKey = { "test-key".toCharArray() },
            )

            try {
                client.synthesize(OpenAiTtsRequest(input = "Testing code $code"))
                fail("Expected OpenAiTtsException for $code")
            } catch (e: OpenAiTtsException) {
                assertEquals("Status code for $code", code, e.statusCode)
                assertEquals(
                    "Retryable flag for HTTP $code should be $expectedRetryable",
                    expectedRetryable,
                    e.retryable,
                )
            }
        }
    }

    @Test
    fun retryableAccuracy_connectionTimeoutIsRetryable_sslErrorIsNot() {
        val socketTimeout = java.net.SocketTimeoutException("Connect timed out")
        assertTrue("SocketTimeoutException must be retryable", OpenAiTtsClient.isConfirmedTimeout(socketTimeout))

        val interruptedTimeout = java.io.InterruptedIOException("timeout")
        assertTrue("InterruptedIOException with timeout must be retryable", OpenAiTtsClient.isConfirmedTimeout(interruptedTimeout))

        val sslError = javax.net.ssl.SSLException("SSL handshake aborted")
        assertFalse("SSLException must NOT be retryable", OpenAiTtsClient.isConfirmedTimeout(sslError))

        val connectError = java.net.ConnectException("Connection refused")
        assertFalse("ConnectException without timeout must NOT be retryable", OpenAiTtsClient.isConfirmedTimeout(connectError))
    }

    @Test
    fun memoryKeyWiping_wipesKeyImmediatelyBeforeNetworkDispatch() = runBlocking {
        val keyChars = "early-wipe-key-123".toCharArray()
        var keyWipedWhenRequestReceived = false

        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                keyWipedWhenRequestReceived = keyChars.all { it == '\u0000' }
                chain.proceed(chain.request())
            }
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3))),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            client = testClient,
            apiKey = { keyChars },
        )

        client.synthesize(OpenAiTtsRequest(input = "Early wipe test"))

        assertTrue(
            "keyChars must be wiped immediately after Request construction, before network dispatch",
            keyWipedWhenRequestReceived,
        )
        assertTrue("Key array must be all zeroes", keyChars.all { it == '\u0000' })
    }
}

private class CustomSecretException_secret_in_stack_123(message: String) : RuntimeException(message)
