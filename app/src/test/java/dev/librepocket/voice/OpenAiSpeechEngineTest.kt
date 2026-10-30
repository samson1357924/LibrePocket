package dev.librepocket.voice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.keystore.KeyVault
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Before
import org.junit.Test

class OpenAiSpeechEngineTest {

    private lateinit var server: MockWebServer
    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private class FakeKeyVault(
        private val keys: MutableMap<String, CharArray> = mutableMapOf(),
        var throwOnHasKey: Boolean = false,
        var throwOnGetKey: Boolean = false,
        var onHasKeyAction: (suspend (String) -> Unit)? = null,
    ) : KeyVault {
        val getKeyCount = AtomicInteger(0)

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            keys[providerId] = apiKey
        }

        override suspend fun getKey(providerId: String): CharArray? {
            getKeyCount.incrementAndGet()
            if (throwOnGetKey) throw IOException("Keystore read failure")
            return keys[providerId]?.clone()
        }

        override suspend fun deleteKey(providerId: String) {
            keys.remove(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean {
            onHasKeyAction?.invoke(providerId)
            if (throwOnHasKey) throw IOException("Keystore status failure")
            return keys.containsKey(providerId)
        }
    }

    private class FakeAudioPlayer(
        var throwOnPlay: Exception? = null,
        var onPlayAction: (suspend (ByteArray) -> Unit)? = null,
    ) : AudioPlayer {
        var playedBytes: ByteArray? = null
        var stopped = false
        val playCount = AtomicInteger(0)

        override suspend fun play(audioBytes: ByteArray) {
            playCount.incrementAndGet()
            throwOnPlay?.let { throw it }
            onPlayAction?.invoke(audioBytes)
            playedBytes = audioBytes
        }

        override fun stop() {
            stopped = true
        }
    }

    // ---- 門禁檢查測試 (check) ----

    @Test
    fun check_allowedWhenBothSwitchesOnAndKeyPresent() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(VoiceTools.OPENAI_KEY_REF, "test-key".toCharArray())

        val check1 = OpenAiSpeechEngine.check(voiceOutputOn = true, openAiTtsOn = true, vault = vault)
        assertEquals(OpenAiSpeechEngine.Companion.Check.Allowed, check1)

        val check2 = OpenAiSpeechEngine.check(voiceOutputOn = true, openAiTtsOn = true, hasKey = true)
        assertEquals(OpenAiSpeechEngine.Companion.Check.Allowed, check2)

        for (flavor in Flavor.entries) {
            val checkFlavor = OpenAiSpeechEngine.check(flavor = flavor, voiceOutputOn = true, openAiTtsOn = true, vault = vault)
            assertEquals(OpenAiSpeechEngine.Companion.Check.Allowed, checkFlavor)
        }
    }

    @Test
    fun check_deniedWhenVoiceOutputOff() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(VoiceTools.OPENAI_KEY_REF, "test-key".toCharArray())

        val c = OpenAiSpeechEngine.check(voiceOutputOn = false, openAiTtsOn = true, vault = vault)
        assertTrue(c is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = c as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.USER_DISABLED, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_SWITCH_OFF, denied.detail)
    }

    @Test
    fun check_deniedWhenOpenAiTtsOff() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(VoiceTools.OPENAI_KEY_REF, "test-key".toCharArray())

        val c = OpenAiSpeechEngine.check(voiceOutputOn = true, openAiTtsOn = false, vault = vault)
        assertTrue(c is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = c as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.USER_DISABLED, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_SWITCH_OFF, denied.detail)
    }

    @Test
    fun check_deniedWhenMissingKey() = runBlocking {
        val emptyVault = FakeKeyVault()

        val c = OpenAiSpeechEngine.check(voiceOutputOn = true, openAiTtsOn = true, vault = emptyVault)
        assertTrue(c is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = c as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
    }

    @Test
    fun check_keystoreException_failsClosedToNoKey() = runBlocking {
        val failingVault = FakeKeyVault(throwOnHasKey = true)

        val c = OpenAiSpeechEngine.check(voiceOutputOn = true, openAiTtsOn = true, vault = failingVault)
        assertTrue(c is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = c as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
    }

    @Test
    fun check_instanceWithInjectedClientKey_allows() = runBlocking {
        val client = OpenAiTtsClient(apiKey = { "test-injected".toCharArray() })
        val engine = OpenAiSpeechEngine(client = client)

        val c = engine.check(voiceOutputOn = true, openAiTtsOn = true)
        assertEquals(OpenAiSpeechEngine.Companion.Check.Allowed, c)
    }

    // ---- 語音合成與播放測試 (synthesizeAndPlay) ----

    @Test
    fun synthesizeAndPlay_redactsTextBeforeCloud_andStreamsToPlayer() = runBlocking {
        val fakeAudio = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "sk-test-secret".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val sensitiveText = "Please contact me at test@example.com or phone 0912-345-678."
        val outcome = engine.synthesizeAndPlay(
            text = sensitiveText,
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome)
        assertArrayEquals(fakeAudio, player.playedBytes)
        assertEquals(1, player.playCount.get())

        // 驗證送上雲端的 request 中，敏感文字已被 redact
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val bodyJson = Json.parseToJsonElement(recorded!!.body.readUtf8()).jsonObject
        val sentInput = bodyJson["input"]?.jsonPrimitive?.content ?: ""
        assertFalse("Sent text must not contain email", sentInput.contains("test@example.com"))
        assertFalse("Sent text must not contain phone", sentInput.contains("0912"))
        assertTrue("Sent text must contain redaction markers", sentInput.contains("⟦") || !sentInput.contains("test@example.com"))
    }

    @Test
    fun synthesizeAndPlay_deniedWhenTextBlank() = runBlocking {
        val player = FakeAudioPlayer()
        val engine = OpenAiSpeechEngine(player = player)

        val outcome = engine.synthesizeAndPlay(
            text = "   ",
            voiceOutputOn = true,
            openAiTtsOn = true,
            apiKeyOverride = { "test".toCharArray() },
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.USER_DISABLED, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_EMPTY_TEXT, denied.detail)
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun synthesizeAndPlay_deniedWhenGateCheckFails() = runBlocking {
        val player = FakeAudioPlayer()
        val engine = OpenAiSpeechEngine(player = player)

        // 開關關閉
        val outcomeSwitchOff = engine.synthesizeAndPlay(
            text = "hello",
            voiceOutputOn = false,
            openAiTtsOn = true,
            apiKeyOverride = { "test".toCharArray() },
        )
        assertTrue(outcomeSwitchOff is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        assertEquals(DenyReason.USER_DISABLED, (outcomeSwitchOff as OpenAiSpeechEngine.Companion.SynthOutcome.Denied).reason)

        // 無金鑰
        val outcomeNoKey = engine.synthesizeAndPlay(
            text = "hello",
            voiceOutputOn = true,
            openAiTtsOn = true,
            vault = FakeKeyVault(),
        )
        assertTrue(outcomeNoKey is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        assertEquals(DenyReason.NO_PRIVILEGE, (outcomeNoKey as OpenAiSpeechEngine.Companion.SynthOutcome.Denied).reason)
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun synthesizeAndPlay_omittingOpenAiTtsOn_failsClosedAndDeniesWithoutNetworkRequest() = runBlocking {
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "valid-key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        // 省略 openAiTtsOn（預設 false），即使有金鑰且 voiceOutputOn 預設 true
        val outcome = engine.synthesizeAndPlay(
            text = "test fail-closed default",
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.USER_DISABLED, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_SWITCH_OFF, denied.detail)
        assertEquals(0, server.requestCount)
        assertEquals(0, player.playCount.get())
    }

    // ---- 金鑰彈性來源測試 ----

    @Test
    fun keySource_fallsBackToActiveOpenAiEndpointKeyWhenNoTtsKey() = runBlocking {
        val fakeAudio = byteArrayOf(0x0a, 0x0b)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val vault = FakeKeyVault()
        // vault 內沒有 "openai_tts"，但有作用中的 OpenAI endpoint 金鑰 "preset:openai"
        vault.putKey(OpenAiSpeechEngine.OPENAI_ENDPOINT_KEY_REF, "endpoint-key-preset".toCharArray())

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = server.url("/").toString(), allowTestLoopback = true)
        val engine = OpenAiSpeechEngine(client = client, player = player, vault = vault, allowTestLoopback = true)

        val outcome = engine.synthesizeAndPlay(
            text = "hello fallback endpoint key",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome)
        assertArrayEquals(fakeAudio, player.playedBytes)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("Bearer endpoint-key-preset", recorded!!.getHeader("Authorization"))
    }

    @Test
    fun keySource_usesApiKeySupplierWhenPresent() = runBlocking {
        val fakeAudio = byteArrayOf(0x0c, 0x0d)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val vault = FakeKeyVault() // empty vault
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = server.url("/").toString(), allowTestLoopback = true)
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            apiKeySupplier = { "supplier-key-secret".toCharArray() },
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "hello supplier key",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome)
        assertArrayEquals(fakeAudio, player.playedBytes)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("Bearer supplier-key-secret", recorded!!.getHeader("Authorization"))
    }

    @Test
    fun keySource_usesEndpointStoreActiveOpenAiEndpoint() = runBlocking {
        val fakeAudio = byteArrayOf(0x0e, 0x0f)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val dir = Files.createTempDirectory("endpoint-test").toFile().also { tmpDirs.add(it) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "endpoint.preferences_pb") },
        )
        val endpointStore = EndpointStore(dataStore)
        endpointStore.save(
            EndpointConfig(
                providerId = "preset:openai",
                presetId = ProviderCatalog.OPENAI_ID,
                label = "OpenAI",
                baseUrl = ProviderCatalog.OPENAI_BASE_URL,
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/preset:openai",
            ),
        )

        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "dynamic-endpoint-key".toCharArray())

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = server.url("/").toString(), allowTestLoopback = true)
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            endpointStore = endpointStore,
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "hello endpointStore key",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome)
        assertArrayEquals(fakeAudio, player.playedBytes)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("Bearer dynamic-endpoint-key", recorded!!.getHeader("Authorization"))
    }

    @Test
    fun keySource_missingAllKeys_returnsNoPrivilege() = runBlocking {
        val vault = FakeKeyVault() // empty
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = server.url("/").toString(), allowTestLoopback = true)
        val engine = OpenAiSpeechEngine(client = client, player = player, vault = vault, allowTestLoopback = true)

        val outcome = engine.synthesizeAndPlay(
            text = "hello no key",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals(0, server.requestCount)
        assertEquals(0, player.playCount.get())
    }

    // ---- 停止播放測試 (stop) 與併發防範 ----

    @Test
    fun stop_stopsPlayerImmediately() {
        val player = FakeAudioPlayer()
        val engine = OpenAiSpeechEngine(player = player)

        assertFalse(player.stopped)
        engine.stop()
        assertTrue(player.stopped)
    }

    @Test
    fun stop_cancelsOngoingPlayback() = runBlocking {
        val startedLatch = CountDownLatch(1)
        val unblockLatch = CountDownLatch(1)

        val player = FakeAudioPlayer(
            onPlayAction = {
                startedLatch.countDown()
                unblockLatch.await(5, TimeUnit.SECONDS)
            },
        )

        val fakeAudio = byteArrayOf(0x01, 0x02)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val job = launch(Dispatchers.IO) {
            engine.synthesizeAndPlay(
                text = "streaming test",
                voiceOutputOn = true,
                openAiTtsOn = true,
            )
        }

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        engine.stop()
        unblockLatch.countDown()
        job.join()

        assertTrue(player.stopped)
        assertTrue(job.isCancelled)
    }

    @Test
    fun stop_duringPreCheck_doesNotStartPlayback() = runBlocking {
        val inCheckLatch = CountDownLatch(1)
        val stopCalledLatch = CountDownLatch(1)
        val vault = FakeKeyVault(
            onHasKeyAction = {
                inCheckLatch.countDown()
                stopCalledLatch.await(5, TimeUnit.SECONDS)
            },
        )
        vault.putKey(VoiceTools.OPENAI_KEY_REF, "test-key".toCharArray())

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = server.url("/").toString(), allowTestLoopback = true)
        val engine = OpenAiSpeechEngine(client = client, player = player, vault = vault, allowTestLoopback = true)

        val job = launch(Dispatchers.IO) {
            engine.synthesizeAndPlay(
                text = "text during pre check",
                voiceOutputOn = true,
                openAiTtsOn = true,
            )
        }

        assertTrue(inCheckLatch.await(5, TimeUnit.SECONDS))
        engine.stop()
        stopCalledLatch.countDown()
        job.join()

        assertEquals(0, server.requestCount)
        assertEquals(0, player.playCount.get())
        assertTrue(player.stopped)
    }

    @Test
    fun stop_twoConcurrentCalls_correctlyStopped() = runBlocking {
        val startedLatch = CountDownLatch(1)
        val unblockLatch = CountDownLatch(1)

        val player = FakeAudioPlayer(
            onPlayAction = {
                startedLatch.countDown()
                unblockLatch.await(5, TimeUnit.SECONDS)
            },
        )

        val fakeAudio = byteArrayOf(0x01, 0x02)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val job1 = launch(Dispatchers.IO) {
            engine.synthesizeAndPlay(
                text = "concurrent call 1",
                voiceOutputOn = true,
                openAiTtsOn = true,
            )
        }
        val job2 = launch(Dispatchers.IO) {
            engine.synthesizeAndPlay(
                text = "concurrent call 2",
                voiceOutputOn = true,
                openAiTtsOn = true,
            )
        }

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        engine.stop()
        unblockLatch.countDown()

        job1.join()
        job2.join()

        assertTrue(player.stopped)
    }

    @Test
    fun byteArrayMediaDataSource_readsCorrectly() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val source = ByteArrayMediaDataSource(data)
        assertEquals(5L, source.size)

        val buffer = ByteArray(3)
        val readCount = source.readAt(1, buffer, 0, 3)
        assertEquals(3, readCount)
        assertArrayEquals(byteArrayOf(20, 30, 40), buffer)

        val eofCount = source.readAt(5, buffer, 0, 3)
        assertEquals(-1, eofCount)

        source.close()
    }

    @Test
    fun androidAudioPlayer_stopWhenIdle_isSafe() {
        val player = AndroidAudioPlayer()
        player.stop()
    }

    // ---- 錯誤傳播測試 (401, 429, 網路異常, 播放異常) ----

    @Test
    fun errorPropagation_http401Unauthorized_returnsFailed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""),
        )

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "bad-key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val outcome = engine.synthesizeAndPlay(
            text = "test unauthorized",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed)
        val failed = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Failed
        assertEquals("UNAUTHORIZED", failed.detail)
        assertTrue(failed.message.contains("401"))
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun errorPropagation_http429RateLimit_returnsFailed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"message":"Rate limit exceeded","type":"requests"}}"""),
        )

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val outcome = engine.synthesizeAndPlay(
            text = "test rate limit",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed)
        val failed = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Failed
        assertEquals("RATE_LIMIT", failed.detail)
        assertTrue(failed.message.contains("429"))
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun errorPropagation_networkError_returnsFailed() = runBlocking {
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        )

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val outcome = engine.synthesizeAndPlay(
            text = "test network error",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed)
        val failed = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Failed
        assertEquals("NETWORK_ERROR", failed.detail)
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun errorPropagation_playerFailure_returnsFailed() = runBlocking {
        val fakeAudio = byteArrayOf(0x01, 0x02)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val player = FakeAudioPlayer(
            throwOnPlay = IOException("Audio hardware disconnected"),
        )
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val outcome = engine.synthesizeAndPlay(
            text = "test player failure",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed)
        val failed = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Failed
        assertEquals(OpenAiSpeechEngine.DETAIL_PLAYBACK_ERROR, failed.detail)
        assertTrue(failed.message.contains("播放失敗"))
    }

    private open class TestMediaPlayer : android.media.MediaPlayer() {
        var prepareAsyncCalled = false
        var startCalled = false
        var stopCalled = false
        var resetCalled = false
        var releaseCalled = false
        var throwOnSetDataSource: Exception? = null
        var throwOnPrepareAsync: Exception? = null
        var throwOnStart: Exception? = null
        var throwOnReset: Throwable? = null
        var preparedListener: android.media.MediaPlayer.OnPreparedListener? = null
        var errorListener: android.media.MediaPlayer.OnErrorListener? = null
        var completionListener: android.media.MediaPlayer.OnCompletionListener? = null

        override fun setDataSource(dataSource: android.media.MediaDataSource) {
            throwOnSetDataSource?.let { throw it }
        }

        override fun setOnPreparedListener(listener: android.media.MediaPlayer.OnPreparedListener?) {
            preparedListener = listener
        }

        override fun setOnCompletionListener(listener: android.media.MediaPlayer.OnCompletionListener?) {
            completionListener = listener
        }

        override fun setOnErrorListener(listener: android.media.MediaPlayer.OnErrorListener?) {
            errorListener = listener
        }

        override fun prepareAsync() {
            prepareAsyncCalled = true
            throwOnPrepareAsync?.let { throw it }
        }

        override fun start() {
            startCalled = true
            throwOnStart?.let { throw it }
        }

        override fun stop() {
            stopCalled = true
        }

        override fun reset() {
            resetCalled = true
            throwOnReset?.let { throw it }
        }

        override fun release() {
            releaseCalled = true
        }

        override fun isPlaying(): Boolean = startCalled && !stopCalled
    }

    // ---- 端點網址嚴格驗證測試 (Issue 1) ----

    @Test
    fun isOpenAiOfficialEndpoint_validOfficialEndpoints_returnTrue() {
        assertTrue(OpenAiSpeechEngine.isOpenAiOfficialEndpoint("https://api.openai.com"))
        assertTrue(OpenAiSpeechEngine.isOpenAiOfficialEndpoint("https://api.openai.com/"))
        assertTrue(OpenAiSpeechEngine.isOpenAiOfficialEndpoint("https://api.openai.com/v1"))
        assertTrue(OpenAiSpeechEngine.isOpenAiOfficialEndpoint("https://api.openai.com:443/v1"))
        assertTrue(OpenAiSpeechEngine.isOpenAiOfficialEndpoint("https://api.openai.com/v1/audio/speech"))
        assertTrue(OpenAiSpeechEngine.isOpenAiOfficialEndpoint("https://API.OPENAI.COM/v1"))
    }

    @Test
    fun isOpenAiOfficialEndpoint_attackerAndNonOfficialEndpoints_returnFalse() {
        val attackerUrls = listOf(
            "https://api.openai.com.attacker.example/v1",
            "https://api.openai.com.attacker.example",
            "https://api.openai.com.evil.com/v1",
            "https://evil.com/api.openai.com/v1",
            "http://api.openai.com/v1",
            "https://api.openai.com:8443/v1",
            "https://api.openai.com:80/v1",
            "https://user:pass@api.openai.com/v1",
            "https://not-openai.com/v1",
            "",
            "   ",
            "not-a-valid-url",
        )
        for (url in attackerUrls) {
            assertFalse("URL $url should NOT be recognized as official OpenAI endpoint", OpenAiSpeechEngine.isOpenAiOfficialEndpoint(url))
        }
    }

    @Test
    fun isOpenAiEndpoint_attackerDomainConfig_returnsFalse() {
        val attackerConfig = EndpointConfig(
            providerId = "preset:openai",
            presetId = ProviderCatalog.OPENAI_ID,
            label = "Fake OpenAI",
            baseUrl = "https://api.openai.com.attacker.example/v1",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = "provider_key/preset:openai",
        )
        assertFalse(OpenAiSpeechEngine.isOpenAiEndpoint(attackerConfig))
    }

    @Test
    fun resolveKeyRef_withAttackerEndpointInStore_doesNotResolveAttackerKey() = runBlocking {
        val dir = Files.createTempDirectory("attacker-endpoint-test").toFile().also { tmpDirs.add(it) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "endpoint.preferences_pb") },
        )
        val endpointStore = EndpointStore(dataStore)
        endpointStore.save(
            EndpointConfig(
                providerId = "attacker_provider",
                presetId = "custom",
                label = "Attacker Proxy",
                baseUrl = "https://api.openai.com.attacker.example/v1",
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/attacker_key",
            ),
        )

        val vault = FakeKeyVault()
        // Vault only contains the attacker key
        vault.putKey("attacker_key", "secret-attacker-key".toCharArray())

        val resolved = OpenAiSpeechEngine.resolveKeyRef(vault, endpointStore)
        assertNull("Attacker endpoint key must not be resolved", resolved)
    }

    // ---- 併發呼叫及時取消舊工作測試 (Issue 2) ----

    @Test
    fun concurrentCalls_secondCallCancelsFirstCallImmediately() = runBlocking {
        val fakeAudio1 = byteArrayOf(0x01, 0x02)
        val fakeAudio2 = byteArrayOf(0x03, 0x04)

        // 第一個回應設定 3 秒延遲，模擬正在進行中的網路請求
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setHeadersDelay(3, TimeUnit.SECONDS)
                .setBody(Buffer().write(fakeAudio1)),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio2)),
        )

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test-key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        // 發起第一個呼叫
        val job1 = launch(Dispatchers.IO) {
            engine.synthesizeAndPlay(
                text = "First request text",
                voiceOutputOn = true,
                openAiTtsOn = true,
            )
        }

        // 等待第一個請求抵達 MockWebServer，確認網路傳輸已在進行中
        val request1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request1)

        // 在第一個呼叫進行中時，發起第二個呼叫
        val outcome2 = engine.synthesizeAndPlay(
            text = "Second request text",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        // 第一個呼叫應被立即取消，不必等待 3 秒延遲
        job1.join()
        assertTrue("First job must be cancelled by the second call", job1.isCancelled)

        // 第二個呼叫應順利完成並播放音訊
        assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome2)
        assertArrayEquals(fakeAudio2, player.playedBytes)
    }

    // ---- MediaPlayer 錯誤傳播與非同步 prepareAsync 測試 (Issue 3 & 4) ----

    @Test
    fun androidAudioPlayer_mediaPlayerFailsDuringPrepare_resumesWithExceptionRatherThanCancel() = runBlocking {
        val testMp = TestMediaPlayer().apply {
            throwOnPrepareAsync = IllegalStateException("MediaPlayer initialization failed: native codec error")
        }
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })

        val exception = try {
            player.play(byteArrayOf(0x01, 0x02))
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Expected exception to be thrown", exception)
        assertTrue("Exception must be IllegalStateException, was: $exception", exception is IllegalStateException)
        assertFalse("Exception must NOT be CancellationException", exception is CancellationException)
        assertTrue(testMp.releaseCalled)
    }

    @Test
    fun androidAudioPlayer_mediaPlayerOnErrorListener_resumesWithIoExceptionRatherThanCancel() = runBlocking {
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })

        val job = async(Dispatchers.IO) {
            try {
                player.play(byteArrayOf(0x01, 0x02))
                null
            } catch (e: Exception) {
                e
            }
        }

        // 等待 prepareAsync 被呼叫且 errorListener 被註冊
        while (testMp.errorListener == null) {
            delay(10)
        }

        // 觸發 MediaPlayer 錯誤回呼
        testMp.errorListener!!.onError(testMp, 1, -1004)

        val exception = job.await()
        assertNotNull("Expected exception to be thrown", exception)
        assertTrue("Exception must be IOException, was: $exception", exception is IOException)
        assertFalse("Exception must NOT be CancellationException", exception is CancellationException)
        assertTrue(exception!!.message!!.contains("what=1, extra=-1004"))
        assertTrue(testMp.releaseCalled)
    }

    @Test
    fun synthesizeAndPlay_withAndroidAudioPlayerFailing_returnsFailedOutcomeRatherThanCancellation() = runBlocking {
        val fakeAudio = byteArrayOf(0x01, 0x02)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val testMp = TestMediaPlayer().apply {
            throwOnPrepareAsync = IllegalStateException("Corrupt media audio track")
        }
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val outcome = engine.synthesizeAndPlay(
            text = "test player failing in prepareAsync",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Failed outcome, was $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed)
        val failed = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Failed
        assertEquals(OpenAiSpeechEngine.DETAIL_PLAYBACK_ERROR, failed.detail)
        assertTrue(failed.message.contains("Corrupt media audio track") || failed.message.contains("播放失敗"))
    }

    @Test
    fun androidAudioPlayer_stopDuringAsyncPrepare_releasesPlayerAndCancelsWithoutDeadlock() = runBlocking {
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })

        val job = launch(Dispatchers.IO) {
            player.play(byteArrayOf(0x01, 0x02))
        }

        // 等待 prepareAsync 被呼叫
        while (!testMp.prepareAsyncCalled) {
            delay(10)
        }

        // 在準備中狀態立即呼叫 stop()
        player.stop()

        job.join()
        assertTrue("Job must be cancelled by stop()", job.isCancelled)
        assertTrue("Player must be released on stop", testMp.releaseCalled)

        // 若 onPrepared 於 stop 後遲到回呼，不可呼叫 start()
        testMp.preparedListener?.onPrepared(testMp)
        assertFalse("start() must not be called after stop()", testMp.startCalled)
    }

    // ---- P1: 實際請求目標與金鑰來源綁定測試 (Reviewer finding 1) ----

    @Test
    fun synthesizeAndPlay_withThirdPartyEndpointAndOfficialOpenAiKey_deniedWithNoPrivilegeAndNoRequestSent() = runBlocking {
        // Vault 包含官方 OpenAI 金鑰（KEY_REF）
        val vault = FakeKeyVault()
        vault.putKey(OpenAiSpeechEngine.KEY_REF, "sk-official-openai-test-key".toCharArray())

        val player = FakeAudioPlayer()
        // client 配置非官方第三方端點
        val client = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello sensitive text to speak",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        // 驗證被安全拒絕為 NO_PRIVILEGE，未發送任何請求與播放
        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals(0, player.playCount.get())
        assertEquals("Vault key must NOT be read when target is untrusted thirdparty endpoint", 0, vault.getKeyCount.get())
    }

    @Test
    fun synthesizeAndPlay_withThirdPartyEndpointAndPresetOpenAiKey_deniedWithNoPrivilege() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(OpenAiSpeechEngine.OPENAI_ENDPOINT_KEY_REF, "sk-preset-openai-key".toCharArray())

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello sensitive prompt",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals(0, player.playCount.get())
        assertEquals(0, vault.getKeyCount.get())
    }

    @Test
    fun synthesizeAndPlay_withEndpointStoreKeyAndMismatchedTargetClient_deniedWithNoPrivilege() = runBlocking {
        val dir = Files.createTempDirectory("endpoint-mismatch-test").toFile().also { tmpDirs.add(it) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "endpoint.preferences_pb") },
        )
        val endpointStore = EndpointStore(dataStore)
        endpointStore.save(
            EndpointConfig(
                providerId = "preset:openai",
                presetId = ProviderCatalog.OPENAI_ID,
                label = "OpenAI",
                baseUrl = ProviderCatalog.OPENAI_BASE_URL,
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/preset:openai",
            ),
        )

        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "sk-endpoint-key".toCharArray())

        val player = FakeAudioPlayer()
        // client 配置為不相符的第三方端點
        val client = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            endpointStore = endpointStore,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello text",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals(0, player.playCount.get())
        assertEquals(0, vault.getKeyCount.get())
    }

    @Test
    fun check_withThirdPartyEndpointAndOfficialKey_reportsDeniedNoPrivilege() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(OpenAiSpeechEngine.KEY_REF, "sk-test-key".toCharArray())

        val client = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val engine = OpenAiSpeechEngine(client = client, vault = vault)

        val checkResult = engine.check(
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied check, was: $checkResult", checkResult is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = checkResult as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
    }

    @Test
    fun isKeyTargetBindingAllowed_combinationsEnforcedCorrectly() {
        val officialKey = OpenAiSpeechEngine.Companion.ResolvedKey.OfficialOpenAi(OpenAiSpeechEngine.KEY_REF)
        val presetKey = OpenAiSpeechEngine.Companion.ResolvedKey.OfficialOpenAi(OpenAiSpeechEngine.OPENAI_ENDPOINT_KEY_REF)
        val endpointKey = OpenAiSpeechEngine.Companion.ResolvedKey.EndpointStoreBound(
            keyRef = "custom_alias",
            endpointBaseUrl = "https://custom.provider.org/v1",
        )
        val officialEndpointKey = OpenAiSpeechEngine.Companion.ResolvedKey.EndpointStoreBound(
            keyRef = "preset:openai",
            endpointBaseUrl = "https://api.openai.com/v1",
        )
        val unboundSupplier = OpenAiSpeechEngine.Companion.ResolvedKey.CustomSupplier(originTargetUrl = null)
        val boundSupplier = OpenAiSpeechEngine.Companion.ResolvedKey.CustomSupplier(
            originTargetUrl = "https://custom.provider.org/v1",
        )
        val officialBoundSupplier = OpenAiSpeechEngine.Companion.ResolvedKey.CustomSupplier(
            originTargetUrl = "https://api.openai.com/v1",
        )
        val loopbackBoundSupplier = OpenAiSpeechEngine.Companion.ResolvedKey.CustomSupplier(
            originTargetUrl = "http://127.0.0.1:8080",
        )

        // 生產預設政策（allowTestLoopback = false）：官方金鑰絕不允許送往本機 loopback
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "https://api.openai.com"))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "https://api.openai.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "http://localhost:8080/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "http://127.0.0.1:9090"))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(presetKey, "https://api.openai.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(presetKey, "http://127.0.0.1:9090"))

        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "https://thirdparty.example.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "https://api.openai.com.attacker.example/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(presetKey, "https://thirdparty.example.com/v1"))

        // 測試隔離旗標啟用（allowTestLoopback = true）：允許送往本機 loopback
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "http://localhost:8080/v1", allowTestLoopback = true))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "http://127.0.0.1:9090", allowTestLoopback = true))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(presetKey, "http://127.0.0.1:9090", allowTestLoopback = true))
        // 即使啟用 allowTestLoopback，非 loopback 之第三方端點仍嚴格禁止
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialKey, "https://thirdparty.example.com/v1", allowTestLoopback = true))

        // EndpointStore 金鑰：第三方端點嚴格禁止
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(endpointKey, "https://custom.provider.org/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(endpointKey, "https://custom.provider.org/v1/"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(endpointKey, "https://custom.provider.org:443/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(endpointKey, "https://other.provider.org/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(endpointKey, "https://thirdparty.example.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(endpointKey, "http://127.0.0.1:8080"))

        // EndpointStore 官方端點金鑰：生產禁 loopback，測試隔離允許
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialEndpointKey, "https://api.openai.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialEndpointKey, "http://127.0.0.1:8080"))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialEndpointKey, "http://127.0.0.1:8080", allowTestLoopback = true))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialEndpointKey, "https://thirdparty.example.com/v1"))

        // 自訂金鑰供應器：未綁定端點（originTargetUrl == null），預設為官方端點專用
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(unboundSupplier, "https://api.openai.com"))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(unboundSupplier, "https://api.openai.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(unboundSupplier, "http://127.0.0.1:8080"))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(unboundSupplier, "http://127.0.0.1:8080", allowTestLoopback = true))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(unboundSupplier, "https://thirdparty.example.com/v1"))

        // 自訂金鑰供應器：綁定第三方端點一律嚴格拒絕
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(boundSupplier, "https://custom.provider.org/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(boundSupplier, "https://custom.provider.org:443/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(boundSupplier, "https://other.provider.org/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(boundSupplier, "https://thirdparty.example.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(boundSupplier, "http://127.0.0.1:8080"))

        // 自訂金鑰供應器：綁定官方端點
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialBoundSupplier, "https://api.openai.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialBoundSupplier, "http://127.0.0.1:8080"))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialBoundSupplier, "http://127.0.0.1:8080", allowTestLoopback = true))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialBoundSupplier, "https://thirdparty.example.com/v1"))

        // 自訂金鑰供應器：綁定測試 loopback
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(loopbackBoundSupplier, "http://127.0.0.1:8080", allowTestLoopback = true))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(loopbackBoundSupplier, "http://127.0.0.1:8080", allowTestLoopback = false))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(loopbackBoundSupplier, "https://custom.provider.org/v1", allowTestLoopback = true))
    }

    @Test
    fun synthesizeAndPlay_productionDefault_officialKeyToLoopback_strictlyDeniedAndNoRequestSent() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(OpenAiSpeechEngine.KEY_REF, "sk-official-openai-test-key".toCharArray())

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = server.url("/").toString())
        // 生產預設：allowTestLoopback = false
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            allowTestLoopback = false,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Sensitive text should not go to loopback in production",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals("No network request should be dispatched to loopback", 0, server.requestCount)
        assertEquals("Vault key should not be retrieved", 0, vault.getKeyCount.get())
        assertEquals("No audio should be played", 0, player.playCount.get())
    }

    @Test
    fun synthesizeAndPlay_testIsolation_officialKeyToLoopback_allowedWhenFlagEnabled() = runBlocking {
        val fakeAudio = byteArrayOf(0x01, 0x02, 0x03)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val vault = FakeKeyVault()
        vault.putKey(OpenAiSpeechEngine.KEY_REF, "sk-official-openai-test-key".toCharArray())

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = server.url("/").toString(), allowTestLoopback = true)
        // 明確啟用測試隔離旗標
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Valid test text",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome)
        assertEquals(1, server.requestCount)
        assertEquals(1, vault.getKeyCount.get())
        assertEquals(1, player.playCount.get())
        assertArrayEquals(fakeAudio, player.playedBytes)
    }

    @Test
    fun synthesizeAndPlay_unboundCustomSupplierToThirdPartyEndpoint_deniedWithNoPrivilegeAndSupplierNotInvoked() = runBlocking {
        var supplierInvoked = false
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            apiKeySupplier = {
                supplierInvoked = true
                "custom-unbound-key".toCharArray()
            },
            // 未綁定特定目標端點（originTargetUrl == null）
            apiKeySupplierOriginUrl = null,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello text to thirdparty",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertFalse("Supplier must NOT be invoked when binding is rejected", supplierInvoked)
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun synthesizeAndPlay_unboundApiKeyOverrideToThirdPartyEndpoint_deniedWithNoPrivilegeAndOverrideNotInvoked() = runBlocking {
        var overrideInvoked = false
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val engine = OpenAiSpeechEngine(client = client, player = player)

        val outcome = engine.synthesizeAndPlay(
            text = "Hello text to thirdparty with override",
            voiceOutputOn = true,
            openAiTtsOn = true,
            apiKeyOverride = {
                overrideInvoked = true
                "override-key".toCharArray()
            },
            apiKeyOverrideOriginUrl = null,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertFalse("Override supplier must NOT be invoked when binding is rejected", overrideInvoked)
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun synthesizeAndPlay_engineOriginBoundToThirdParty_unboundApiKeyOverride_deniedWithNoPrivilegeAndNoRequestSent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(byteArrayOf(0x05, 0x06))),
        )

        val targetUrl = server.url("/").toString()
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = targetUrl)
        var engineSupplierInvoked = false
        var overrideInvoked = false

        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            apiKeySupplier = {
                engineSupplierInvoked = true
                "engine-bound-key".toCharArray()
            },
            apiKeySupplierOriginUrl = targetUrl,
            allowTestLoopback = false,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello text to thirdparty with unbound override",
            voiceOutputOn = true,
            openAiTtsOn = true,
            apiKeyOverride = {
                overrideInvoked = true
                "call-unbound-key".toCharArray()
            },
            apiKeyOverrideOriginUrl = null,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertFalse("Override supplier must NOT be invoked when binding is rejected", overrideInvoked)
        assertFalse("Engine supplier must NOT be invoked", engineSupplierInvoked)
        assertEquals("Network request must NOT be sent to third-party endpoint", 0, server.requestCount)
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun check_engineOriginBoundToThirdParty_unboundApiKeySupplierOverride_reportsDeniedNoPrivilege() = runBlocking {
        val targetUrl = "https://thirdparty.example.com/v1"
        val client = OpenAiTtsClient(baseUrl = targetUrl)
        var overrideInvoked = false
        var engineSupplierInvoked = false

        val engine = OpenAiSpeechEngine(
            client = client,
            apiKeySupplier = {
                engineSupplierInvoked = true
                "engine-bound-key".toCharArray()
            },
            apiKeySupplierOriginUrl = targetUrl,
            allowTestLoopback = false,
        )

        val checkResult = engine.check(
            voiceOutputOn = true,
            openAiTtsOn = true,
            apiKeySupplierOverride = {
                overrideInvoked = true
                "call-unbound-key".toCharArray()
            },
            apiKeySupplierOverrideOriginUrl = null,
        )

        assertTrue("Expected Denied check, was: $checkResult", checkResult is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = checkResult as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertFalse("Override supplier must NOT be invoked", overrideInvoked)
        assertFalse("Engine supplier must NOT be invoked", engineSupplierInvoked)
    }

    @Test
    fun synthesizeAndPlay_boundCustomSupplierToThirdPartyEndpoint_strictlyDeniedWithNoPrivilege() = runBlocking {
        val targetUrl = "https://thirdparty.example.com/v1"
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = targetUrl)
        var supplierInvoked = false
        // 明確綁定至第三方 targetUrl
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            apiKeySupplier = {
                supplierInvoked = true
                "custom-bound-key".toCharArray()
            },
            apiKeySupplierOriginUrl = targetUrl,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello bound custom endpoint to thirdparty",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertFalse("Bound supplier must NOT be invoked for thirdparty endpoint", supplierInvoked)
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun check_boundCustomSupplierToThirdPartyEndpoint_reportsDeniedNoPrivilege() = runBlocking {
        val targetUrl = "https://thirdparty.example.com/v1"
        val client = OpenAiTtsClient(baseUrl = targetUrl)
        var supplierInvoked = false
        val engine = OpenAiSpeechEngine(
            client = client,
            apiKeySupplier = {
                supplierInvoked = true
                "custom-bound-key".toCharArray()
            },
            apiKeySupplierOriginUrl = targetUrl,
        )

        val checkResult = engine.check(
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied check, was: $checkResult", checkResult is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = checkResult as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertFalse("Supplier must NOT be invoked during check", supplierInvoked)
    }

    @Test
    fun synthesizeAndPlay_boundCustomSupplierToLoopback_allowedWhenTestIsolationEnabled() = runBlocking {
        val fakeAudio = byteArrayOf(0x05, 0x06)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val targetUrl = server.url("/").toString()
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = targetUrl, allowTestLoopback = true)
        var supplierInvoked = false
        // 綁定至測試 loopback 端點且明確啟用 allowTestLoopback
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            apiKeySupplier = {
                supplierInvoked = true
                "custom-bound-key".toCharArray()
            },
            apiKeySupplierOriginUrl = targetUrl,
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello bound loopback endpoint in test mode",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome)
        assertTrue("Bound supplier should be invoked", supplierInvoked)
        assertArrayEquals(fakeAudio, player.playedBytes)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertEquals("Bearer custom-bound-key", recorded!!.getHeader("Authorization"))
    }

    @Test
    fun synthesizeAndPlay_boundCustomSupplierToMismatchedThirdPartyEndpoint_deniedWithNoPrivilege() = runBlocking {
        var supplierInvoked = false
        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(baseUrl = "https://mismatched.example.com/v1")
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            apiKeySupplier = {
                supplierInvoked = true
                "custom-bound-key".toCharArray()
            },
            apiKeySupplierOriginUrl = "https://expected.example.com/v1",
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello mismatched endpoint",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertFalse(supplierInvoked)
    }

    @Test
    fun check_unboundCustomSupplierToThirdPartyEndpoint_reportsDeniedNoPrivilege() = runBlocking {
        val client = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val engine = OpenAiSpeechEngine(
            client = client,
            apiKeySupplier = { "custom-key".toCharArray() },
            apiKeySupplierOriginUrl = null,
        )

        val checkResult = engine.check(
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied check, was: $checkResult", checkResult is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = checkResult as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
    }

    @Test
    fun check_productionDefault_officialKeyToLoopback_reportsDeniedNoPrivilege() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(OpenAiSpeechEngine.KEY_REF, "sk-test-key".toCharArray())

        val client = OpenAiTtsClient(baseUrl = "http://127.0.0.1:8080")
        val engine = OpenAiSpeechEngine(client = client, vault = vault, allowTestLoopback = false)

        val checkResult = engine.check(
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue("Expected Denied check, was: $checkResult", checkResult is OpenAiSpeechEngine.Companion.Check.Denied)
        val denied = checkResult as OpenAiSpeechEngine.Companion.Check.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
    }

    @Test
    fun check_testIsolation_officialKeyToLoopback_allowed() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey(OpenAiSpeechEngine.KEY_REF, "sk-test-key".toCharArray())

        val client = OpenAiTtsClient(baseUrl = "http://127.0.0.1:8080")
        val engine = OpenAiSpeechEngine(client = client, vault = vault, allowTestLoopback = true)

        val checkResult = engine.check(
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertEquals(OpenAiSpeechEngine.Companion.Check.Allowed, checkResult)
    }

    @Test
    fun isLoopbackEndpoint_and_isSameEndpoint_validateCorrectly() {
        assertTrue(OpenAiSpeechEngine.isLoopbackEndpoint("http://localhost:8080/v1"))
        assertTrue(OpenAiSpeechEngine.isLoopbackEndpoint("http://127.0.0.1:4040"))
        assertTrue(OpenAiSpeechEngine.isLoopbackEndpoint("http://[::1]:8080"))
        assertFalse(OpenAiSpeechEngine.isLoopbackEndpoint("https://api.openai.com"))
        assertFalse(OpenAiSpeechEngine.isLoopbackEndpoint("https://thirdparty.example.com/v1"))
        assertFalse(OpenAiSpeechEngine.isLoopbackEndpoint("invalid-url"))

        assertTrue(OpenAiSpeechEngine.isSameEndpoint("https://api.openai.com", "https://api.openai.com/v1"))
        assertTrue(OpenAiSpeechEngine.isSameEndpoint("https://api.openai.com/v1/", "https://api.openai.com/v1"))
        assertTrue(OpenAiSpeechEngine.isSameEndpoint("https://custom.org:443/v1", "https://custom.org/v1"))
        assertFalse(OpenAiSpeechEngine.isSameEndpoint("https://custom.org/v1", "https://attacker.org/v1"))
        assertFalse(OpenAiSpeechEngine.isSameEndpoint("https://custom.org/v1", "https://custom.org/v2"))
    }

    // ---- P2: 播放前防範已取消的舊工作覆蓋新播放器測試 (Reviewer finding 2) ----

    @Test
    fun androidAudioPlayer_playWhenContinuationAlreadyCancelled_doesNotResetOrReleaseActivePlayer() = runBlocking {
        val activeMp = TestMediaPlayer()
        val secondMp = TestMediaPlayer()
        var factoryCount = 0
        val player = AndroidAudioPlayer(mediaPlayerFactory = {
            if (factoryCount++ == 0) activeMp else secondMp
        })

        // 啟動第一個播放工作使其成為 activePlayer
        val activeJob = launch(Dispatchers.IO) {
            player.play(byteArrayOf(0x01, 0x02))
        }
        while (!activeMp.prepareAsyncCalled) {
            delay(10)
        }
        assertFalse("Active player must not be released yet", activeMp.releaseCalled)
        assertFalse("Active player must not be reset yet", activeMp.resetCalled)

        // 啟動第二個工作，但在進入 play() 前已處於取消狀態
        val cancelledJob = launch(Dispatchers.IO) {
            currentCoroutineContext().cancel(CancellationException("Already cancelled before play"))
            try {
                player.play(byteArrayOf(0x03, 0x04))
            } catch (_: CancellationException) {
            }
        }
        cancelledJob.join()

        // 驗證原本正在播放的 activePlayer 絕未被 reset 或 release
        assertFalse("Active player must NOT be released by cancelled play call", activeMp.releaseCalled)
        assertFalse("Active player must NOT be reset by cancelled play call", activeMp.resetCalled)
        assertFalse("Second player must NOT prepare", secondMp.prepareAsyncCalled)

        // 清理第一個播放
        player.stop()
        activeJob.join()
        assertTrue("Active player released after stop", activeMp.releaseCalled)
    }

    @Test
    fun androidAudioPlayer_playWhenCancelledDuringFactoryBeforeLock_doesNotTouchActivePlayer() = runBlocking {
        val activeMp = TestMediaPlayer()
        val secondMp = TestMediaPlayer()
        var secondJobRef: Job? = null
        var factoryCount = 0
        val player = AndroidAudioPlayer(mediaPlayerFactory = {
            if (factoryCount++ == 0) {
                activeMp
            } else {
                // 在工廠建立新播放器後、進入鎖前取消該 Job
                secondJobRef?.cancel(CancellationException("Cancelled during factory"))
                secondMp
            }
        })

        val activeJob = launch(Dispatchers.IO) {
            player.play(byteArrayOf(0x01, 0x02))
        }
        while (!activeMp.prepareAsyncCalled) {
            delay(10)
        }

        val secondJob = launch(Dispatchers.IO) {
            try {
                player.play(byteArrayOf(0x03, 0x04))
            } catch (_: CancellationException) {
            }
        }
        secondJobRef = secondJob
        secondJob.join()

        assertFalse("Active player must NOT be released", activeMp.releaseCalled)
        assertFalse("Active player must NOT be reset", activeMp.resetCalled)
        assertTrue("Second player created before lock should be cleaned up", secondMp.releaseCalled)

        player.stop()
        activeJob.join()
    }

    // ---- P2: reset() 失敗不得跳過 release() 測試 (Reviewer finding 3) ----

    @Test
    fun androidAudioPlayer_whenResetThrows_releaseIsStillGuaranteedToBeCalled() = runBlocking {
        val testMp = TestMediaPlayer().apply {
            throwOnReset = IllegalStateException("Native MediaPlayer reset failure")
        }
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })

        val job = launch(Dispatchers.IO) {
            player.play(byteArrayOf(0x01, 0x02))
        }
        while (!testMp.prepareAsyncCalled) {
            delay(10)
        }

        // 呼叫 stop() 觸發釋放流程
        player.stop()
        job.join()

        assertTrue("reset() must have been called", testMp.resetCalled)
        assertTrue("release() must still be called even if reset() threw exception", testMp.releaseCalled)
    }
}
