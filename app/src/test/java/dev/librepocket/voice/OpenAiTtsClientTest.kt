package dev.librepocket.voice

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.net.Proxy
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
import okhttp3.Authenticator
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OpenAiTtsClientTest {

    @get:Rule
    val globalTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(15)

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

        val sensitiveInput = "Contact me at alice@example.com or 0912345678, secret sk-abcDEF1234567890."
        val expectedRedacted = VoiceTools.redactForCloud(sensitiveInput)

        client.synthesize(OpenAiTtsRequest(input = sensitiveInput))

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val rawBody = recorded!!.body.readUtf8()

        assertFalse("Request body must not contain plain email", rawBody.contains("alice@example.com"))
        assertFalse("Request body must not contain plain phone number", rawBody.contains("0912345678"))
        assertFalse("Request body must not contain plain API key", rawBody.contains("sk-abcDEF1234567890"))

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
                .setBody("""{"error":{"message":"Incorrect API key provided: sk-testSECRET1234567890","type":"invalid_request_error"}}"""),
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
            assertFalse("Safe message must not leak key: ${e.safeMessage}", e.safeMessage.contains("sk-testSECRET1234567890"))
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
        val wipeTargetChars = "   ".toCharArray()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { wipeTargetChars },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for blank key")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.MISSING_KEY, e.errorCode)
            assertTrue("Key array must be zeroed even if blank", wipeTargetChars.all { it == '\u0000' })
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun memoryKeyWiping_wipesKeyAfterSuccessfulCall() = runBlocking {
        val wipeTargetChars = "ephemeral-wipe-buffer".toCharArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3))),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { wipeTargetChars },
        )

        client.synthesize(OpenAiTtsRequest(input = "Wipe key test"))

        assertTrue("Key array must be overwritten with \\u0000 after success", wipeTargetChars.all { it == '\u0000' })
    }

    @Test
    fun memoryKeyWiping_wipesKeyAfterFailedCall() = runBlocking {
        val wipeTargetChars = "ephemeral-wipe-buffer".toCharArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Server failure"),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { wipeTargetChars },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Error wipe test"))
            fail("Expected exception")
        } catch (_: OpenAiTtsException) {
            // Expected
        }

        assertTrue("Key array must be overwritten with \\u0000 after error", wipeTargetChars.all { it == '\u0000' })
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
        val secretKey = "sk-abc123XYZ4567890"
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
    fun cancellationDuringNetworkExecution_sanitizesCauseAndDoesNotLeakSecret() = runBlocking {
        val secretKey = "sk-super-secret-network-key-98765"
        val requestReceivedLatch = CountDownLatch(1)
        val callCanceledLatch = CountDownLatch(1)
        val testHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                requestReceivedLatch.countDown()
                while (!chain.call().isCanceled()) {
                    Thread.sleep(10)
                }
                callCanceledLatch.countDown()
                throw IOException("Network failed while handling secret: $secretKey")
            }
            .build()

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            client = testHttpClient,
            apiKey = { secretKey.toCharArray() },
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))

        var caughtException: CancellationException? = null
        val job = launch(Dispatchers.IO) {
            try {
                client.synthesize(OpenAiTtsRequest(input = "Hello"))
            } catch (e: CancellationException) {
                caughtException = e
            }
        }

        assertTrue("Network interceptor should be reached", requestReceivedLatch.await(5, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("OkHttp call must receive cancel", callCanceledLatch.await(5, TimeUnit.SECONDS))
        job.join()

        assertNotNull("Expected CancellationException to be caught", caughtException)
        val e = caughtException!!
        assertFalse("CancellationException message must not contain secret", e.message?.contains(secretKey) == true)
        assertFalse("CancellationException toString must not contain secret", e.toString().contains(secretKey))

        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        assertFalse("CancellationException printStackTrace must not contain secret", sw.toString().contains(secretKey))

        val cause = e.cause
        if (cause != null) {
            assertFalse("Cause message must not contain secret: ${cause.message}", cause.message?.contains(secretKey) == true)
            assertFalse("Cause toString must not contain secret: $cause", cause.toString().contains(secretKey))
            val causeSw = StringWriter()
            cause.printStackTrace(PrintWriter(causeSw))
            assertFalse("Cause printStackTrace must not contain secret", causeSw.toString().contains(secretKey))
        }

        // Direct assertion on network exception cause sanitization during cancellation
        val rawNetworkException = IOException("Network failed while handling secret: $secretKey")
        val sanitizedCause = OpenAiTtsException.sanitizeCause(rawNetworkException, secretKey)
        assertNotNull(sanitizedCause)
        assertTrue("Sanitized cause stackTrace must be empty", sanitizedCause!!.stackTrace.isEmpty())
        assertFalse("Sanitized cause message must not contain secret: ${sanitizedCause.message}", sanitizedCause.message?.contains(secretKey) == true)
        assertFalse("Sanitized cause toString must not contain secret: $sanitizedCause", sanitizedCause.toString().contains(secretKey))
        assertTrue("Sanitized cause message must contain redacted marker: ${sanitizedCause.message}", sanitizedCause.message?.contains("⟦REDACTED") == true)
        val sanitizedSw = StringWriter()
        sanitizedCause.printStackTrace(PrintWriter(sanitizedSw))
        assertFalse("Sanitized cause printStackTrace must not contain secret", sanitizedSw.toString().contains(secretKey))
    }

    @Test
    fun headerConstruction_withInvalidKeyChars_throwsSanitizedExceptionAndDoesNotLeakKey() = runBlocking {
        val secretKey = "sk-super-secret-key-12345\ninvalid-newline"
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { secretKey.toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for invalid key chars in header")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertEquals("Failed to construct Authorization header: invalid characters in API key", e.safeMessage)
            assertFalse("Exception message must not contain secret key: ${e.message}", e.message?.contains("sk-super-secret-key-12345") == true)
            assertFalse("Exception safeMessage must not contain secret key: ${e.safeMessage}", e.safeMessage.contains("sk-super-secret-key-12345"))
            assertFalse("Exception toString must not contain secret key: $e", e.toString().contains("sk-super-secret-key-12345"))

            val cause = e.cause
            assertNotNull("Cause must be present", cause)
            assertFalse("Cause message must not contain secret key: ${cause?.message}", cause?.message?.contains("sk-super-secret-key-12345") == true)
            assertFalse("Cause toString must not contain secret key: $cause", cause?.toString()?.contains("sk-super-secret-key-12345") == true)
            assertTrue("Cause stackTrace must be empty", cause!!.stackTrace.isEmpty())

            val sw = StringWriter()
            cause.printStackTrace(PrintWriter(sw))
            assertFalse("Cause printStackTrace must not contain secret key", sw.toString().contains("sk-super-secret-key-12345"))

            assertEquals("Zero HTTP requests should be dispatched", 0, server.requestCount)
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
        // Strict allow-list (F7): ONLY the "localhost" name itself, 127.0.0.0/8, and ::1.
        // "*.localhost" subdomains are NOT loopback (wildcard DNS can escape the sandbox).
        val loopbacks = listOf(
            "localhost",
            "LOCALHOST",
            "localhost.",
            "LOCALHOST.",
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
            "sub.localhost",
            "api.backend.localhost",
            "sub.localhost.",
            "SUB.LOCALHOST",
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
    fun constructor_maxAudioBytesAboveHardCap_throwsIllegalArgumentException() {
        // F4: Long.MAX_VALUE is no longer an accepted bound; the hard cap is 64MB.
        try {
            OpenAiTtsClient(
                baseUrl = server.url("/").toString(),
                allowTestLoopback = true,
                apiKey = { "test-key".toCharArray() },
                maxAudioBytes = Long.MAX_VALUE,
            )
            fail("Expected IllegalArgumentException for maxAudioBytes = Long.MAX_VALUE")
        } catch (e: IllegalArgumentException) {
            assertEquals(
                "maxAudioBytes must not exceed ${OpenAiTtsClient.MAX_SUPPORTED_AUDIO_BYTES}, was: ${Long.MAX_VALUE}",
                e.message,
            )
        }
        try {
            OpenAiTtsClient(
                baseUrl = server.url("/").toString(),
                allowTestLoopback = true,
                apiKey = { "test-key".toCharArray() },
                maxAudioBytes = OpenAiTtsClient.MAX_SUPPORTED_AUDIO_BYTES + 1,
            )
            fail("Expected IllegalArgumentException for maxAudioBytes above hard cap")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must not exceed"))
        }
    }

    @Test
    fun successfulSynthesize_withMaxSupportedAudioBytes_readsNonEmptyAudioStreamWithoutOverflow() = runBlocking {
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
            maxAudioBytes = OpenAiTtsClient.MAX_SUPPORTED_AUDIO_BYTES,
        )

        val result = client.synthesize(OpenAiTtsRequest(input = "Hard-cap audio stream test"))

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
        val wipeTargetChars = "ephemeral-wipe-buffer".toCharArray()
        var keyWipedWhenRequestReceived = false

        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                keyWipedWhenRequestReceived = wipeTargetChars.all { it == '\u0000' }
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
            apiKey = { wipeTargetChars },
        )

        client.synthesize(OpenAiTtsRequest(input = "Early wipe test"))

        assertTrue(
            "wipeTargetChars must be wiped immediately after Request construction, before network dispatch",
            keyWipedWhenRequestReceived,
        )
        assertTrue("Key array must be all zeroes", wipeTargetChars.all { it == '\u0000' })
    }

    @Test
    fun request_toString_masksInputLengthOnly() {
        val sensitiveInput = "Highly sensitive user confidential medical note"
        val req = OpenAiTtsRequest(input = sensitiveInput, voice = "alloy")
        val str = req.toString()
        assertFalse("toString must not contain raw input text", str.contains(sensitiveInput))
        assertTrue("toString must contain input=[length=${sensitiveInput.length}]", str.contains("input=[length=${sensitiveInput.length}]"))
        assertTrue("toString must contain model", str.contains("model=tts-1"))
        assertTrue("toString must contain voice", str.contains("voice=alloy"))
    }

    @Test
    fun request_supportedVoices_validation() {
        for (voice in OpenAiTtsModels.SUPPORTED_VOICES) {
            val req = OpenAiTtsRequest(input = "test", voice = voice)
            assertEquals(voice, req.voice)
        }

        try {
            OpenAiTtsRequest(input = "test", voice = "   ")
            fail("Expected OpenAiTtsException for blank voice")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertTrue(e.safeMessage.contains("Voice must not be blank"))
        }

        // 未列在推薦清單但非空白的語音在客戶端允許通過，由伺服器端做最終校驗（避免 client drift）
        val unlistedVoice = "unsupported-voice-123"
        val req = OpenAiTtsRequest(input = "test", voice = unlistedVoice)
        assertEquals(unlistedVoice, req.voice)
    }

    @Test
    fun request_supportedResponseFormats_validation() {
        for (format in OpenAiTtsModels.SUPPORTED_RESPONSE_FORMATS) {
            val req = OpenAiTtsRequest(input = "test", responseFormat = format)
            assertEquals(format, req.responseFormat)
        }

        try {
            OpenAiTtsRequest(input = "test", responseFormat = "   ")
            fail("Expected OpenAiTtsException for blank format")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertTrue(e.safeMessage.contains("Response format must not be blank"))
        }

        // 未列在推薦清單但非空白的格式在客戶端允許通過，由伺服器端做最終校驗
        val unlistedFormat = "ogg"
        val req = OpenAiTtsRequest(input = "test", responseFormat = unlistedFormat)
        assertEquals(unlistedFormat, req.responseFormat)
    }

    @Test
    fun validateEndpointUrl_rejectsQueryParamsAndFragments() {
        try {
            OpenAiTtsClient.validateEndpointUrl("https://api.openai.com/v1/audio/speech?query=param", allowTestLoopback = false)
            fail("Expected OpenAiTtsException for query in official endpoint")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertTrue(e.safeMessage.contains("query parameters or fragments"))
        }

        try {
            OpenAiTtsClient.validateEndpointUrl("https://api.openai.com/v1/audio/speech#section", allowTestLoopback = false)
            fail("Expected OpenAiTtsException for fragment in official endpoint")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertTrue(e.safeMessage.contains("query parameters or fragments"))
        }

        try {
            OpenAiTtsClient.validateEndpointUrl("http://localhost:8080/v1/audio/speech?param=test", allowTestLoopback = true)
            fail("Expected OpenAiTtsException for query in loopback endpoint")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertTrue(e.safeMessage.contains("query parameters or fragments"))
        }

        try {
            OpenAiTtsClient.validateEndpointUrl("http://127.0.0.1:8080/v1/audio/speech#frag", allowTestLoopback = true)
            fail("Expected OpenAiTtsException for fragment in loopback endpoint")
        } catch (e: OpenAiTtsException) {
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertTrue(e.safeMessage.contains("query parameters or fragments"))
        }
    }

    @Test
    fun errorHandling_packetFragmentation_readsCompleteErrorBody() = runBlocking {
        val errorJson = """{"error":{"message":"Rate limit exceeded for model tts-1","type":"rate_limit_error"}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .throttleBody(8, 20, TimeUnit.MILLISECONDS)
                .setBody(errorJson),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "ephemeral-wipe-buffer".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException")
        } catch (e: OpenAiTtsException) {
            assertEquals(429, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.RATE_LIMIT, e.errorCode)
            assertTrue(e.retryable)
            assertTrue(
                "Safe message should contain fully parsed message despite throttling: ${e.safeMessage}",
                e.safeMessage.contains("Rate limit exceeded for model tts-1"),
            )
        }
    }

    @Test
    fun responseAudio_emptyBody200_rejectedAsUnexpected() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer()),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Empty body test"))
            fail("Expected OpenAiTtsException for empty 200 audio body")
        } catch (e: OpenAiTtsException) {
            assertEquals(200, e.statusCode)
            assertEquals(OpenAiTtsErrorCode.UNEXPECTED, e.errorCode)
            assertFalse(e.retryable)
            assertTrue("Safe message should mention empty body: ${e.safeMessage}", e.safeMessage.contains("empty", ignoreCase = true))
        }
    }

    @Test
    fun errorHandling_404And410_appendSunsetMigrationHint() = runBlocking {
        for (code in listOf(404, 410)) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"message":"Model not found","type":"not_found"}}"""),
            )

            val client = OpenAiTtsClient(
                baseUrl = server.url("/").toString(),
                allowTestLoopback = true,
                apiKey = { "test-key".toCharArray() },
            )

            try {
                client.synthesize(OpenAiTtsRequest(input = "Sunset hint test $code"))
                fail("Expected OpenAiTtsException for $code")
            } catch (e: OpenAiTtsException) {
                assertEquals(code, e.statusCode)
                assertFalse(e.retryable)
                assertTrue(
                    "Safe message must carry sunset date: ${e.safeMessage}",
                    e.safeMessage.contains(OpenAiTtsClient.SUNSET_DATE),
                )
                assertTrue(
                    "Safe message must carry migration target: ${e.safeMessage}",
                    e.safeMessage.contains(OpenAiTtsClient.MIGRATION_TARGET),
                )
            }
        }
    }

    @Test
    fun errorHandling_jsonMessageWithControlChars_isSingleLineAndBounded() = runBlocking {
        // \n stays a JSON escape inside triple-quoted Kotlin (no escape processing),
        // so the parsed server message contains real newlines — a log-forging attempt.
        val rawBody = """{"error":{"message":"Line one\nLine two   evil   spacing ${"x".repeat(500)}","type":"bad"}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(rawBody),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "test-key".toCharArray() },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Log forging test"))
            fail("Expected OpenAiTtsException for 400")
        } catch (e: OpenAiTtsException) {
            assertEquals(400, e.statusCode)
            assertFalse("Safe message must not contain raw newline: ${e.safeMessage}", e.safeMessage.contains('\n'))
            assertFalse("Safe message must not contain raw CR: ${e.safeMessage}", e.safeMessage.contains('\r'))
            assertTrue(
                "Safe message must be bounded to prefix + 200 chars (was ${e.safeMessage.length}): ${e.safeMessage}",
                e.safeMessage.length <= "HTTP 400: ".length + 200,
            )
        }
    }

    @Test
    fun constructor_defaultAllowTestLoopback_isFalse_productionSafe() {
        // F1 pin: production default must never admit loopback; base URL single-sourced.
        val client = OpenAiTtsClient(apiKey = { "test-key".toCharArray() })
        assertFalse("Production default must not admit loopback", client.allowTestLoopback)
        assertEquals("https://api.openai.com", client.baseUrl)
        assertEquals(OpenAiTtsModels.DEFAULT_BASE_URL, OpenAiTtsClient.DEFAULT_BASE_URL)
        assertEquals(OpenAiTtsModels.SPEECH_ENDPOINT_PATH, OpenAiTtsClient.SPEECH_ENDPOINT_PATH)
        assertEquals("/v1/audio/speech", OpenAiTtsClient.SPEECH_ENDPOINT_PATH)
        assertEquals(
            OpenAiTtsModels.MAX_SUPPORTED_AUDIO_BYTES,
            OpenAiTtsClient.MAX_SUPPORTED_AUDIO_BYTES,
        )
    }

    @Test
    fun httpClient_stripsAuthenticatorAndProxy_fromInjectedClient() {
        // F3 pin: even a hostile injected client must not survive into execution with an
        // authenticator (cross-host key attachment) or proxy (traffic observation).
        val injected = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
            .authenticator { _, _ -> null }
            .followRedirects(true)
            .build()

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            client = injected,
            apiKey = { "test-key".toCharArray() },
        )

        assertSame(Authenticator.NONE, client.httpClient.authenticator)
        assertEquals(Proxy.NO_PROXY, client.httpClient.proxy)
        assertFalse("Redirects must stay disabled", client.httpClient.followRedirects)
        assertFalse("SSL redirects must stay disabled", client.httpClient.followSslRedirects)
        assertFalse("Retry on connection failure must stay disabled", client.httpClient.retryOnConnectionFailure)
    }

    @Test
    fun validateEndpointUrl_rejectsNonHttpSchemes_evenForLoopback() {
        for (url in listOf(
            "ftp://localhost:8080/v1/audio/speech",
            "gopher://127.0.0.1:8080/v1/audio/speech",
            "file:///v1/audio/speech",
        )) {
            try {
                OpenAiTtsClient.validateEndpointUrl(url, allowTestLoopback = true)
                fail("Expected OpenAiTtsException for non-http(s) scheme: $url")
            } catch (e: OpenAiTtsException) {
                assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            }
        }
    }

    @Test
    fun validateEndpointUrl_rejectsOfficialHostWithNonCanonicalPath() {
        for (url in listOf(
            "https://api.openai.com/evil",
            "https://api.openai.com/evil/v1/audio/speech",
            "https://api.openai.com/v1/audio/speech/extra",
        )) {
            try {
                OpenAiTtsClient.validateEndpointUrl(url, allowTestLoopback = false)
                fail("Expected OpenAiTtsException for non-canonical official path: $url")
            } catch (e: OpenAiTtsException) {
                assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            }
        }
        // Canonical resolved endpoints stay admissible on both branches.
        OpenAiTtsClient.validateEndpointUrl("https://api.openai.com/v1/audio/speech", allowTestLoopback = false)
        OpenAiTtsClient.validateEndpointUrl("http://localhost:8080/v1/audio/speech", allowTestLoopback = true)
        OpenAiTtsClient.validateEndpointUrl("https://127.0.0.1:8080/v1/audio/speech", allowTestLoopback = true)
    }

    @Test
    fun insecureEndpoint_officialHostWithEvilPath_rejectedBeforeSending() = runBlocking {
        var supplierCalled = false
        val client = OpenAiTtsClient(
            baseUrl = "https://api.openai.com/evil",
            apiKey = {
                supplierCalled = true
                "test-key".toCharArray()
            },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for official host with evil path")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("Key supplier must not be called for evil-path endpoint", supplierCalled)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun insecureEndpoint_loopbackSubdomain_rejectedEvenWithFlag() = runBlocking {
        var supplierCalled = false
        val client = OpenAiTtsClient(
            baseUrl = "http://sub.localhost:8080",
            allowTestLoopback = true,
            apiKey = {
                supplierCalled = true
                "test-key".toCharArray()
            },
        )

        try {
            client.synthesize(OpenAiTtsRequest(input = "Hello"))
            fail("Expected OpenAiTtsException for *.localhost even with allowTestLoopback = true")
        } catch (e: OpenAiTtsException) {
            assertNull(e.statusCode)
            assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
            assertFalse(e.retryable)
            assertFalse("Key supplier must not be called for *.localhost", supplierCalled)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun synthesize_paddedKey_trimmedToSingleBearerValue() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3))),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
            apiKey = { "  padded-key-123  ".toCharArray() },
        )

        val result = client.synthesize(OpenAiTtsRequest(input = "Hello"))

        assertArrayEquals(byteArrayOf(1, 2, 3), result.audioBytes)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("Bearer padded-key-123", recorded!!.getHeader("Authorization"))
    }

    @Test
    fun inputValidation_speedNaNAndInfinite_rejected() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            try {
                OpenAiTtsRequest(input = "Hello", speed = bad)
                fail("Expected OpenAiTtsException for speed=$bad")
            } catch (e: OpenAiTtsException) {
                assertEquals(OpenAiTtsErrorCode.INVALID_INPUT, e.errorCode)
                assertFalse(e.retryable)
            }
        }
    }
}

private class CustomSecretException_secret_in_stack_123(message: String) : RuntimeException(message)
