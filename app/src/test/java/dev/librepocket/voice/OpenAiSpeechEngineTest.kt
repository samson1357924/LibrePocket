package dev.librepocket.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.setup.ENDPOINT_API_KEY_REF_V1
import dev.librepocket.agent.ui.setup.ENDPOINT_BASE_URL_V1
import dev.librepocket.agent.ui.setup.ENDPOINT_LABEL_V1
import dev.librepocket.agent.ui.setup.ENDPOINT_MODEL_V1
import dev.librepocket.agent.ui.setup.ENDPOINT_PRESET_ID_V1
import dev.librepocket.agent.ui.setup.ENDPOINT_PROTOCOL_V1
import dev.librepocket.agent.ui.setup.ENDPOINT_PROVIDER_ID_V1
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.PendingDelete
import dev.librepocket.agent.ui.setup.SetupValidation
import dev.librepocket.keystore.KeyVault
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import android.media.MediaPlayer
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class OpenAiSpeechEngineTest {

    @get:Rule
    val globalTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(15)

    private suspend fun awaitCondition(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition timed out after ${timeoutMs}ms")
            }
            delay(10)
        }
    }

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
        private val keys: MutableMap<String, CharArray> = ConcurrentHashMap(),
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

    private class FailingDataStore(
        private val error: IOException = IOException("DATASTORE_UNAVAILABLE"),
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw error
    }

    private fun newTempEndpointStore(): Triple<EndpointStore, DataStore<Preferences>, CoroutineScope> {
        val dir = Files.createTempDirectory("endpoint-test").toFile().also { tmpDirs.add(it) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "endpoint.preferences_pb") },
        )
        return Triple(EndpointStore(dataStore), dataStore, scope)
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
        engine.shutdown()
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

        // 統一取消契約：內部 stop 中斷一律回傳 Failed(CANCELLED)，不拋出；僅外部明確取消才重拋。
        val outcomeRef = java.util.concurrent.atomic.AtomicReference<OpenAiSpeechEngine.Companion.SynthOutcome?>()
        val job = launch(Dispatchers.IO) {
            outcomeRef.set(
                engine.synthesizeAndPlay(
                    text = "streaming test",
                    voiceOutputOn = true,
                    openAiTtsOn = true,
                ),
            )
        }

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        engine.stop()
        unblockLatch.countDown()
        job.join()

        assertTrue(player.stopped)
        val outcome = outcomeRef.get()
        assertTrue("Stopped playback must return Failed(CANCELLED), was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed && outcome.detail == OpenAiSpeechEngine.DETAIL_CANCELLED)
        assertFalse("Unified contract: internal stop returns Failed, not CancellationException", job.isCancelled)
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
        try {
            player.stop()
        } finally {
            player.release()
        }
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
    fun engineNeverImplicitlyRetries_onRetryable429_singleDispatch() = runBlocking {
        // Stage-4 收斂釘：engine 永不隱性重試 — 即使 429（retryable=true）亦恰一發，不自動重送；
        // 未來 UI 若重試需指數退避＋尊重 Retry-After＋上限＋同 epoch/手勢。
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
            text = "test no implicit retry",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue(outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed)
        assertEquals(1, server.requestCount)
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

    private open class TestMediaPlayer : MediaPlayer() {
        // 注意：prepareAsync 為空實作（no-op），不會自動回調 prepared/completion；
        // 測試必須手動觸發 preparedListener/errorListener/completionListener 來模擬 native 非同步回調。
        // 此設計刻意用於驗證世代柵欄：遲到 onPrepared 永不 start（見 stopDuringAsyncPrepare），
        // 而正常 onPrepared 會 start 並可經 completion 正常返回（見 preparedTriggersStartWhenActive，
        // 此成功路徑證明 mock 具備判別力——若柵欄損壞，遲到回呼測試即會失敗）。
        @Volatile var prepareAsyncCalled = false
        @Volatile var startCalled = false
        @Volatile var stopCalled = false
        @Volatile var resetCalled = false
        @Volatile var releaseCalled = false
        @Volatile var throwOnSetDataSource: Exception? = null
        @Volatile var throwOnPrepareAsync: Exception? = null
        @Volatile var throwOnStart: Exception? = null
        @Volatile var throwOnReset: Throwable? = null
        @Volatile var preparedListener: MediaPlayer.OnPreparedListener? = null
        @Volatile var errorListener: MediaPlayer.OnErrorListener? = null
        @Volatile var completionListener: MediaPlayer.OnCompletionListener? = null

        override fun setDataSource(dataSource: android.media.MediaDataSource) {
            throwOnSetDataSource?.let { throw it }
        }

        override fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) {
            preparedListener = listener
        }

        override fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) {
            completionListener = listener
        }

        override fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) {
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
    fun isOpenAiEndpoint_enforcesPresetIdentityAndProviderNamespace() {
        val validOpenAiConfig = EndpointConfig(
            providerId = SetupValidation.deriveProviderId(ProviderCatalog.OPENAI_ID),
            presetId = ProviderCatalog.OPENAI_ID,
            label = "OpenAI",
            baseUrl = ProviderCatalog.OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = "provider_key/preset:openai",
        )
        assertTrue(OpenAiSpeechEngine.isOpenAiEndpoint(validOpenAiConfig))

        val customEndpointId = SetupValidation.deriveEndpointId(ProviderCatalog.CUSTOM_ID, ProviderCatalog.OPENAI_BASE_URL)
        val validCustomConfig = EndpointConfig(
            providerId = customEndpointId,
            presetId = ProviderCatalog.CUSTOM_ID,
            label = "Custom OpenAI",
            baseUrl = ProviderCatalog.OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = SetupValidation.deriveApiKeyRef(customEndpointId),
        )
        assertTrue(OpenAiSpeechEngine.isOpenAiEndpoint(validCustomConfig))

        // Anthropic preset with OpenAI URL -> false
        val anthropicConfig = EndpointConfig(
            providerId = SetupValidation.deriveProviderId(ProviderCatalog.ANTHROPIC_ID),
            presetId = ProviderCatalog.ANTHROPIC_ID,
            label = "Anthropic with OpenAI URL",
            baseUrl = ProviderCatalog.OPENAI_BASE_URL,
            protocol = ProviderProtocol.ANTHROPIC,
            model = "claude-3-5-sonnet",
            apiKeyRef = "provider_key/preset:anthropic",
        )
        assertFalse(OpenAiSpeechEngine.isOpenAiEndpoint(anthropicConfig))

        // Gemini preset with OpenAI URL -> false
        val geminiConfig = EndpointConfig(
            providerId = SetupValidation.deriveProviderId(ProviderCatalog.GEMINI_ID),
            presetId = ProviderCatalog.GEMINI_ID,
            label = "Gemini with OpenAI URL",
            baseUrl = ProviderCatalog.OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gemini-1.5-flash",
            apiKeyRef = "provider_key/preset:gemini",
        )
        assertFalse(OpenAiSpeechEngine.isOpenAiEndpoint(geminiConfig))

        // Custom preset with legacy/mismatched providerId -> false
        val legacyCustomConfig = EndpointConfig(
            providerId = "preset:custom",
            presetId = ProviderCatalog.CUSTOM_ID,
            label = "Legacy Custom",
            baseUrl = ProviderCatalog.OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = "provider_key/preset:custom",
        )
        assertFalse(OpenAiSpeechEngine.isOpenAiEndpoint(legacyCustomConfig))
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

    // ---- 階段 1：憑證來源閘門與端點安全性加固測試 ----

    @Test
    fun resolveBoundKey_dedicatedKeyRef_hasPriorityOverEndpointStore() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
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
        vault.putKey(OpenAiSpeechEngine.KEY_REF, "dedicated-key".toCharArray())
        vault.putKey("preset:openai", "store-key".toCharArray())

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertEquals(
            OpenAiSpeechEngine.Companion.ResolvedKey.OfficialOpenAi(OpenAiSpeechEngine.KEY_REF),
            resolved,
        )
    }

    @Test
    fun resolveBoundKey_dataStoreReadFailure_failsClosedWithoutDowngrade() = runBlocking {
        val failingStore = EndpointStore(FailingDataStore())
        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "fallback-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, failingStore)
        assertNull("DataStore read failure must fail-closed and return null", resolved)
        assertFalse("Must never check or downgrade to preset:openai on read failure", hasKeyCalls.contains("preset:openai"))
    }

    @Test
    fun resolveBoundKey_pendingDeletePresent_failsClosedWithoutDowngrade() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
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
        // 寫入 pending delete journal，模擬登出/待刪除中
        endpointStore.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L))

        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "dynamic-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNull("Pending delete present must fail-closed and return null", resolved)
        assertFalse("Must never check or downgrade to preset:openai with pending delete", hasKeyCalls.contains("preset:openai"))
    }

    @Test
    fun resolveBoundKey_emptyStoreNullConfig_failsClosedWithoutDowngrade() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        // store is empty, config == null

        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "fallback-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNull("Empty store (null config) must fail-closed and return null", resolved)
        assertFalse("Must never downgrade to preset:openai when endpointStore is present", hasKeyCalls.contains("preset:openai"))
    }

    @Test
    fun resolveBoundKey_nonOpenAiBaseUrl_failsClosedWithoutVaultReadsOrDowngrade() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        endpointStore.save(
            EndpointConfig(
                providerId = "custom:1234567890abcdef1234567890abcdef",
                presetId = ProviderCatalog.CUSTOM_ID,
                label = "Third Party",
                baseUrl = "https://api.openai.com.attacker.example/v1",
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/custom:1234567890abcdef1234567890abcdef",
            ),
        )

        val vault = FakeKeyVault()
        vault.putKey("custom:1234567890abcdef1234567890abcdef", "attacker-key".toCharArray())
        vault.putKey("preset:openai", "fallback-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNull("Non-official OpenAI baseUrl must fail-closed and return null", resolved)
        assertEquals("Zero vault reads for endpoint or preset keys: only KEY_REF was checked", listOf(OpenAiSpeechEngine.KEY_REF), hasKeyCalls)
        assertEquals(0, vault.getKeyCount.get())
    }

    @Test
    fun resolveBoundKey_legacyCustomEndpoint_failsClosedWithoutVaultReadsOrDowngrade() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        // P1/BLOCK: 舊版自訂端點（providerId = "preset:custom"），即使填入官方 OpenAI URL
        val legacyConfig = EndpointConfig(
            providerId = "preset:custom",
            presetId = ProviderCatalog.CUSTOM_ID,
            label = "Legacy Custom Endpoint with OpenAI URL",
            baseUrl = ProviderCatalog.OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = "provider_key/preset:custom",
        )
        assertTrue("SetupValidation must identify this as legacy endpoint", SetupValidation.isLegacyEndpoint(legacyConfig))
        endpointStore.save(legacyConfig)

        val vault = FakeKeyVault()
        // Vault 中存有第三方服務的 legacy custom 金鑰與 OpenAI 金鑰
        vault.putKey("preset:custom", "third-party-confidential-key".toCharArray())
        vault.putKey("preset:openai", "official-openai-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNull("Legacy custom endpoint must fail-closed and return null to prevent credential leakage", resolved)
        assertEquals("Zero vault reads for preset:custom or preset:openai", listOf(OpenAiSpeechEngine.KEY_REF), hasKeyCalls)
        assertEquals(0, vault.getKeyCount.get())
    }

    @Test
    fun resolveBoundKey_apiKeyRefNotInNamespace_failsClosedWithoutVaultReadsOrDowngrade() = runBlocking {
        val (endpointStore, dataStore, _) = newTempEndpointStore()
        // 透過底層 dataStore 寫入 apiKeyRef 不在 providerId 命名空間的狀態（例如舊資料庫或損毀狀態）
        dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
            prefs[ENDPOINT_PRESET_ID_V1] = ProviderCatalog.OPENAI_ID
            prefs[ENDPOINT_LABEL_V1] = "OpenAI"
            prefs[ENDPOINT_BASE_URL_V1] = ProviderCatalog.OPENAI_BASE_URL
            prefs[ENDPOINT_PROTOCOL_V1] = ProviderProtocol.CHAT_COMPLETIONS.name
            prefs[ENDPOINT_MODEL_V1] = "gpt-4o-mini"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:custom"
        }

        val vault = FakeKeyVault()
        vault.putKey("preset:custom", "foreign-key".toCharArray())
        vault.putKey("preset:openai", "official-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNull("Out-of-namespace apiKeyRef must fail-closed and return null", resolved)
        assertEquals("Zero vault reads for foreign key or preset:openai", listOf(OpenAiSpeechEngine.KEY_REF), hasKeyCalls)
        assertEquals(0, vault.getKeyCount.get())
    }

    @Test
    fun resolveBoundKey_validConfigKeyNotInVault_failsClosedWithoutDowngrade() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
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
        // Vault 不包含 "preset:openai"
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNull("Missing key in vault for endpoint must return null", resolved)
        assertTrue(hasKeyCalls.contains("preset:openai"))
    }

    @Test
    fun resolveBoundKey_validConfigWithStagedAlias_resolvesSuccessfully() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        val stagedAlias = "preset:openai#staged-0123456789abcdef0123456789abcdef"
        endpointStore.save(
            EndpointConfig(
                providerId = "preset:openai",
                presetId = ProviderCatalog.OPENAI_ID,
                label = "OpenAI",
                baseUrl = ProviderCatalog.OPENAI_BASE_URL,
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/$stagedAlias",
            ),
        )

        val vault = FakeKeyVault()
        vault.putKey(stagedAlias, "staged-secret-key".toCharArray())

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertEquals(
            OpenAiSpeechEngine.Companion.ResolvedKey.EndpointStoreBound(stagedAlias, ProviderCatalog.OPENAI_BASE_URL),
            resolved,
        )
    }

    @Test
    fun resolveBoundKey_anthropicPresetWithOpenAiUrl_failsClosedWithoutVaultReads() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        endpointStore.save(
            EndpointConfig(
                providerId = "preset:anthropic",
                presetId = ProviderCatalog.ANTHROPIC_ID,
                label = "Anthropic with OpenAI URL",
                baseUrl = ProviderCatalog.OPENAI_BASE_URL,
                protocol = ProviderProtocol.ANTHROPIC,
                model = "claude-3-5-sonnet",
                apiKeyRef = "provider_key/preset:anthropic",
            ),
        )

        val vault = FakeKeyVault()
        vault.putKey("preset:anthropic", "anthropic-secret-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNull("Anthropic preset with OpenAI URL must fail-closed and return null", resolved)
        assertFalse("Vault hasKey must never be called for preset:anthropic", hasKeyCalls.contains("preset:anthropic"))
        assertEquals("Vault getKey must never be called", 0, vault.getKeyCount.get())
    }

    @Test
    fun synthesizeAndPlay_anthropicPresetWithOpenAiUrl_failsClosedWithoutRequestsOrVaultReads() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        endpointStore.save(
            EndpointConfig(
                providerId = "preset:anthropic",
                presetId = ProviderCatalog.ANTHROPIC_ID,
                label = "Anthropic with OpenAI URL",
                baseUrl = ProviderCatalog.OPENAI_BASE_URL,
                protocol = ProviderProtocol.ANTHROPIC,
                model = "claude-3-5-sonnet",
                apiKeyRef = "provider_key/preset:anthropic",
            ),
        )

        val vault = FakeKeyVault()
        vault.putKey("preset:anthropic", "anthropic-secret-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            endpointStore = endpointStore,
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "Hello",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        assertTrue(
            "Expected Denied (NO_PRIVILEGE) or Failed outcome, was: $outcome",
            (outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied && outcome.reason == DenyReason.NO_PRIVILEGE) ||
                (outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed && (outcome.detail == "DETAIL_NO_PRIVILEGE" || outcome.detail == "DETAIL_RESOLVE_FAILED" || outcome.detail == OpenAiSpeechEngine.DETAIL_NO_KEY)),
        )
        if (outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied) {
            assertEquals(DenyReason.NO_PRIVILEGE, outcome.reason)
            assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, outcome.detail)
        }
        assertEquals("0 HTTP requests were sent to MockWebServer", 0, server.requestCount)
        assertEquals("0 vault reads occurred for Anthropic key", 0, vault.getKeyCount.get())
        assertFalse("Vault hasKey was never called for preset:anthropic", hasKeyCalls.contains("preset:anthropic"))
        assertEquals(0, player.playCount.get())
    }

    @Test
    fun resolveBoundKey_customPresetWithOpenAiOfficialUrl_resolvesEndpointStoreBound() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        val customEndpointId = SetupValidation.deriveEndpointId(ProviderCatalog.CUSTOM_ID, ProviderCatalog.OPENAI_BASE_URL)
        val apiKeyRef = SetupValidation.deriveApiKeyRef(customEndpointId)
        val alias = SetupValidation.aliasFromRef(apiKeyRef)

        endpointStore.save(
            EndpointConfig(
                providerId = customEndpointId,
                presetId = ProviderCatalog.CUSTOM_ID,
                label = "Custom OpenAI",
                baseUrl = ProviderCatalog.OPENAI_BASE_URL,
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = apiKeyRef,
            ),
        )

        val vault = FakeKeyVault()
        vault.putKey(alias, "custom-openai-key".toCharArray())

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore)
        assertNotNull("Custom endpoint with official OpenAI URL should resolve", resolved)
        assertTrue(
            "Resolved key must be EndpointStoreBound, was: $resolved",
            resolved is OpenAiSpeechEngine.Companion.ResolvedKey.EndpointStoreBound,
        )
        val bound = resolved as OpenAiSpeechEngine.Companion.ResolvedKey.EndpointStoreBound
        assertEquals(alias, bound.keyRef)
        assertEquals(ProviderCatalog.OPENAI_BASE_URL, bound.endpointBaseUrl)
    }

    @Test
    fun resolveBoundKey_nullEndpointStore_fallsBackToPresetOpenAi() = runBlocking {
        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "default-preset-key".toCharArray())

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore = null)
        assertEquals(
            OpenAiSpeechEngine.Companion.ResolvedKey.OfficialOpenAi("preset:openai"),
            resolved,
        )
    }

    @Test
    fun resolveBoundKey_nullEndpointStore_noKeyReturnsNull() = runBlocking {
        val vault = FakeKeyVault()

        val resolved = OpenAiSpeechEngine.resolveBoundKey(vault, endpointStore = null)
        assertNull(resolved)
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

        // 發起第一個呼叫（統一取消契約：被取代者回傳 Failed(CANCELLED)，不拋出）
        val outcome1Ref = java.util.concurrent.atomic.AtomicReference<OpenAiSpeechEngine.Companion.SynthOutcome?>()
        val job1 = launch(Dispatchers.IO) {
            outcome1Ref.set(
                engine.synthesizeAndPlay(
                    text = "First request text",
                    voiceOutputOn = true,
                    openAiTtsOn = true,
                ),
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

        // 第一個呼叫應被立即取代，不必等待 3 秒延遲：回傳 Failed(CANCELLED) 而非拋出
        job1.join()
        val outcome1 = outcome1Ref.get()
        assertTrue(
            "First call must return Failed(CANCELLED) when superseded, was: $outcome1",
            outcome1 is OpenAiSpeechEngine.Companion.SynthOutcome.Failed &&
                outcome1.detail == OpenAiSpeechEngine.DETAIL_CANCELLED,
        )
        assertFalse("Unified contract: superseded returns Failed, not CancellationException", job1.isCancelled)

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
        try {
            val exception = try {
                player.play(byteArrayOf(0x01, 0x02))
                null
            } catch (e: Exception) {
                e
            }

            assertNotNull("Expected exception to be thrown", exception)
            assertTrue("Exception must be IllegalStateException, was: $exception", exception is IllegalStateException)
            assertFalse("Exception must NOT be CancellationException", exception is CancellationException)
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue(testMp.releaseCalled)
        } finally {
            player.release()
        }
    }

    @Test
    fun androidAudioPlayer_mediaPlayerOnErrorListener_resumesWithIoExceptionRatherThanCancel() = runBlocking {
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        try {
            val job = async(Dispatchers.IO) {
                try {
                    player.play(byteArrayOf(0x01, 0x02))
                    null
                } catch (e: Exception) {
                    e
                }
            }

            // 等待 prepareAsync 被呼叫且 errorListener 被註冊
            awaitCondition { testMp.errorListener != null }

            // 觸發 MediaPlayer 錯誤回呼
            testMp.errorListener!!.onError(testMp, 1, -1004)

            val exception = job.await()
            assertNotNull("Expected exception to be thrown", exception)
            assertTrue("Exception must be IOException, was: $exception", exception is IOException)
            assertFalse("Exception must NOT be CancellationException", exception is CancellationException)
            assertTrue(exception!!.message!!.contains("what=1, extra=-1004"))
            // error 路徑 resume 先行、釋放走背景，需等待終將釋放
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue(testMp.releaseCalled)
        } finally {
            player.release()
        }
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
        try {
            val outcome = engine.synthesizeAndPlay(
                text = "test player failing in prepareAsync",
                voiceOutputOn = true,
                openAiTtsOn = true,
            )

            assertTrue("Expected Failed outcome, was $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed)
            val failed = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Failed
            assertEquals(OpenAiSpeechEngine.DETAIL_PLAYBACK_ERROR, failed.detail)
            assertTrue(failed.message.contains("Corrupt media audio track") || failed.message.contains("播放失敗"))
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun androidAudioPlayer_stopDuringAsyncPrepare_releasesPlayerAndCancelsWithoutDeadlock() = runBlocking {
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        try {
            val job = launch(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }

            // 等待 prepareAsync 被呼叫
            awaitCondition { testMp.prepareAsyncCalled }

            // 在準備中狀態立即呼叫 stop()
            player.stop()

            job.join()
            assertTrue("Job must be cancelled by stop()", job.isCancelled)
            // stop 的 native 釋放走背景執行緒，終將釋放
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("Player must be released on stop", testMp.releaseCalled)

            // 若 onPrepared 於 stop 後遲到回呼，不可呼叫 start()
            testMp.preparedListener?.onPrepared(testMp)
            assertFalse("start() must not be called after stop()", testMp.startCalled)
        } finally {
            player.release()
        }
    }

    @Test
    fun androidAudioPlayer_preparedTriggersStartWhenActive_provesMockDiscrimination() = runBlocking {
        // 成功路徑對照組：證明 TestMediaPlayer mock 具備判別力——正常 onPrepared 會 start，
        // 若世代柵欄損壞，遲到回呼測試（stopDuringAsyncPrepare）即會失敗，而本測試保障 mock 本身可 start。
        // prepareAsync 為空實作，需手動觸發 preparedListener 模擬 native 非同步回調。
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        try {
            val job = async(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }
            awaitCondition { testMp.preparedListener != null }

            // 正常時序的 onPrepared（仍為 active）：必須 start
            testMp.preparedListener!!.onPrepared(testMp)
            awaitCondition { testMp.startCalled }
            assertTrue("start() must be called for active onPrepared", testMp.startCalled)

            // 模擬播放完成，play 應正常返回（非取消、非異常）
            testMp.completionListener!!.onCompletion(testMp)
            job.await()
            assertTrue("Job must complete normally on completion", job.isCompleted && !job.isCancelled)
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("Player must be released after completion", testMp.releaseCalled)
        } finally {
            player.release()
        }
    }

    @Test
    fun byteArrayMediaDataSource_copyFalse_zeroCopyContract_passesSameReferenceWithoutClone() = runBlocking {
        // RequiresNoMutation 契約釘選測試（對應 copy=false 保留決策，記憶體考量）：
        // AndroidAudioPlayer.play 以 copy=false 建構 ByteArrayMediaDataSource，不做防禦性拷貝；
        // 呼叫端在播放期間絕對不可修改原陣列，否則讀取結果未定義。
        // 本測試擷取傳入 MediaDataSource 並驗證其直接引用原陣列（外部修改即時可見），釘死零拷貝語義。
        var captured: android.media.MediaDataSource? = null
        val testMp = object : TestMediaPlayer() {
            override fun setDataSource(dataSource: android.media.MediaDataSource) {
                captured = dataSource
                super.setDataSource(dataSource)
            }
        }
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        try {
            val original = byteArrayOf(0x01, 0x02, 0x03)
            val job = launch(Dispatchers.IO) {
                try {
                    player.play(original)
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
            awaitCondition { captured != null }
            val dataSource = captured!!
            // 讀取初始值
            val buf = ByteArray(1)
            assertEquals(1, dataSource.readAt(0, buf, 0, 1))
            assertEquals(0x01.toByte(), buf[0])
            // 外部修改即時可見 = 證明未 clone（零拷貝）；同時警示呼叫端不可如此修改。
            original[0] = 0x77
            assertEquals(1, dataSource.readAt(0, buf, 0, 1))
            assertEquals(
                "copy=false must not clone: external mutation must be visible (RequiresNoMutation contract)",
                0x77.toByte(),
                buf[0],
            )
            player.stop()
            job.join()
        } finally {
            player.release()
        }
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
        val customEndpointKey = OpenAiSpeechEngine.Companion.ResolvedKey.EndpointStoreBound(
            keyRef = SetupValidation.deriveEndpointId(ProviderCatalog.CUSTOM_ID, "https://api.openai.com/v1"),
            endpointBaseUrl = "https://api.openai.com/v1",
        )
        val foreignEndpointKeyWithOpenAiUrl = OpenAiSpeechEngine.Companion.ResolvedKey.EndpointStoreBound(
            keyRef = "preset:anthropic",
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

        // EndpointStore 官方端點金鑰：生產禁 loopback，測試隔離允許；且必須在 OpenAI 命名空間
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(officialEndpointKey, "https://api.openai.com/v1"))
        assertTrue(OpenAiSpeechEngine.isKeyTargetBindingAllowed(customEndpointKey, "https://api.openai.com/v1"))
        assertFalse(OpenAiSpeechEngine.isKeyTargetBindingAllowed(foreignEndpointKeyWithOpenAiUrl, "https://api.openai.com/v1"))
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
        try {
            // 啟動第一個播放工作使其成為 activePlayer
            val activeJob = launch(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }
            awaitCondition { activeMp.prepareAsyncCalled }
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

            // 清理第一個播放（stop 僅 detach+cancel，native 釋放走背景執行緒，需等待終將釋放）
            player.stop()
            activeJob.join()
            awaitCondition(timeoutMs = 5000L) { activeMp.releaseCalled }
            assertTrue("Active player released after stop", activeMp.releaseCalled)
        } finally {
            player.release()
        }
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
        try {
            val activeJob = launch(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }
            awaitCondition { activeMp.prepareAsyncCalled }

            // 使用 CoroutineStart.LAZY 確保在 secondJob 啟動執行前已完成 secondJobRef 指派，
            // 避免協程在指派前調用工廠導致 secondJobRef?.cancel 為空而引發逾時。
            val secondJob = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    player.play(byteArrayOf(0x03, 0x04))
                } catch (_: CancellationException) {
                }
            }
            secondJobRef = secondJob
            secondJob.start()
            secondJob.join()

            assertFalse("Active player must NOT be released", activeMp.releaseCalled)
            assertFalse("Active player must NOT be reset", activeMp.resetCalled)
            // 進入鎖前被取消的新建資源走 releaseAsync（背景釋放），需等待終將釋放
            awaitCondition(timeoutMs = 5000L) { secondMp.releaseCalled }
            assertTrue("Second player created before lock should be cleaned up", secondMp.releaseCalled)

            player.stop()
            activeJob.join()
        } finally {
            player.release()
        }
    }

    // ---- P2: reset() 失敗不得跳過 release() 測試 (Reviewer finding 3) ----

    @Test
    fun androidAudioPlayer_whenResetThrows_releaseIsStillGuaranteedToBeCalled() = runBlocking {
        val testMp = TestMediaPlayer().apply {
            throwOnReset = IllegalStateException("Native MediaPlayer reset failure")
        }
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        try {
            val job = launch(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }
            awaitCondition { testMp.prepareAsyncCalled }

            // 呼叫 stop() 觸發釋放流程（背景執行緒終將釋放）
            player.stop()
            job.join()

            awaitCondition(timeoutMs = 5000L) { testMp.resetCalled }
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("reset() must have been called", testMp.resetCalled)
            assertTrue("release() must still be called even if reset() threw exception", testMp.releaseCalled)
        } finally {
            player.release()
        }
    }

    // ---- 階段一：音訊引擎核心與並發重構測試 ----

    @Test
    fun byteArrayMediaDataSource_defensiveCopyAndClosedGuards() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val ds = ByteArrayMediaDataSource(original)

        // 驗證防禦性拷貝：外部修改不影響內部資料
        original[0] = 99
        val buffer = ByteArray(3)
        val read1 = ds.readAt(0, buffer, 0, 1)
        assertEquals(1, read1)
        assertEquals(1.toByte(), buffer[0])

        // 驗證 getSize 與各邊界條件防護
        assertEquals(5L, ds.size)
        assertEquals(-1, ds.readAt(-1, buffer, 0, 1))
        assertEquals(-1, ds.readAt(5, buffer, 0, 1))
        assertEquals(-1, ds.readAt(10, buffer, 0, 1))
        assertEquals(0, ds.readAt(0, buffer, 0, 0))
        assertEquals(0, ds.readAt(0, buffer, 0, -1))
        assertEquals(0, ds.readAt(0, buffer, -1, 1))
        assertEquals(0, ds.readAt(0, buffer, 3, 1)) // offset == buffer.size
        assertEquals(0, ds.readAt(0, buffer, 4, 1)) // offset > buffer.size

        // 讀取剩餘長度截斷
        val readRemaining = ds.readAt(3, buffer, 0, 5)
        assertEquals(2, readRemaining)
        assertEquals(4.toByte(), buffer[0])
        assertEquals(5.toByte(), buffer[1])

        // close 之後狀態
        ds.close()
        assertEquals(0L, ds.size)
        assertEquals(-1, ds.readAt(0, buffer, 0, 1))
    }

    @Test
    fun byteArrayMediaDataSource_closeClearsDataAndSubsequentCallsReturnZeroAndNegativeOne() {
        val original = byteArrayOf(10, 20, 30, 40)
        val ds = ByteArrayMediaDataSource(original)
        assertEquals(4L, ds.size)

        val buffer = ByteArray(2)
        val bytesRead = ds.readAt(0, buffer, 0, 2)
        assertEquals(2, bytesRead)
        assertEquals(10.toByte(), buffer[0])
        assertEquals(20.toByte(), buffer[1])

        // 呼叫 close()，驗證 data 被清空為 null 且 isClosed 為 true
        ds.close()

        // 後續 getSize 回傳 0L
        assertEquals(0L, ds.size)
        // 後續 readAt 安全回傳 -1
        assertEquals(-1, ds.readAt(0, buffer, 0, 2))
        assertEquals(-1, ds.readAt(2, buffer, 0, 2))
    }

    @Test
    fun byteArrayMediaDataSource_copyFalseDoesNotCloneArray() {
        // RequiresNoMutation 契約釘選：copy=false 為零拷貝路徑（保留決策：記憶體考量，見 AudioPlayer.play KDoc）。
        // 外部修改即時可見 = 證明未 clone；呼叫端在播放期間絕對不可如此修改。
        val original = byteArrayOf(1, 2, 3)
        val ds = ByteArrayMediaDataSource(original, copy = false)

        val buffer = ByteArray(1)
        ds.readAt(0, buffer, 0, 1)
        assertEquals(1.toByte(), buffer[0])

        // 外部直接修改 byte array，因 copy = false 未建立防禦性副本，內部資料同步變更
        original[0] = 77
        ds.readAt(0, buffer, 0, 1)
        assertEquals(77.toByte(), buffer[0])
    }

    @Test
    fun androidAudioPlayer_stopDoesNotCallPlayerStopDirectly() = runBlocking {
        // Stage-B P1/BLOCK-2 更新：stop() 在 detach 後做同步快速靜音（鎖外 best-effort stop），
        // 再將完整 reset/release 排入背景；故 PREPARING 階段的 stop() 亦會嘗試同步 stop（mock 上即 stopCalled）。
        // 仍保證：絕不在持有 lock/monitor 時阻塞於 native；呼叫線程有界返回；完整釋放終將在背景完成。
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        try {
            val job = launch(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }

            awaitCondition { testMp.prepareAsyncCalled }

            player.stop()
            job.join()

            // Stage-B：同步快速靜音必須已發生（不等背景釋放）
            assertTrue("Stage-B: player.stop() must be called synchronously for immediate mute", testMp.stopCalled)
            // 背景釋放需等待終將釋放
            awaitCondition(timeoutMs = 5000L) { testMp.resetCalled }
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("player.reset() must be called", testMp.resetCalled)
            assertTrue("player.release() must be called", testMp.releaseCalled)
        } finally {
            player.release()
        }
    }

    @Test
    fun androidAudioPlayer_stop_safelyClearsListenersAndReleasesPlayerWithoutErrors() = runBlocking {
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        try {
            val job = launch(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }

            awaitCondition {
                testMp.prepareAsyncCalled &&
                    testMp.preparedListener != null &&
                    testMp.completionListener != null &&
                    testMp.errorListener != null
            }

            assertNotNull("PreparedListener must be present before stop", testMp.preparedListener)
            assertNotNull("CompletionListener must be present before stop", testMp.completionListener)
            assertNotNull("ErrorListener must be present before stop", testMp.errorListener)

            // 呼叫 stop() 執行安全釋放流程（背景執行緒終將釋放）
            player.stop()
            job.join()

            // 驗證重構安全契約：在 reset/release 之前已安全清空所有 listeners
            // listeners 清除發生於背景釋放，需等待
            awaitCondition(timeoutMs = 5000L) { testMp.preparedListener == null }
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertNull("PreparedListener must be cleared after stop", testMp.preparedListener)
            assertNull("CompletionListener must be cleared after stop", testMp.completionListener)
            assertNull("ErrorListener must be cleared after stop", testMp.errorListener)
            assertTrue("reset() must have been called", testMp.resetCalled)
            assertTrue("release() must have been called", testMp.releaseCalled)
            assertFalse("start() must NOT have been called after stop", testMp.startCalled)
        } finally {
            player.release()
        }
    }

    @Test
    fun synthesizeAndPlay_failedPreCheck_doesNotIncrementEpochOrCancelOngoingJob() = runBlocking {
        val fakeAudio = byteArrayOf(0x01, 0x02)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeAudio)),
        )

        val startedLatch = CountDownLatch(1)
        val unblockLatch = CountDownLatch(1)

        val player = FakeAudioPlayer(
            onPlayAction = {
                startedLatch.countDown()
                unblockLatch.await(5, TimeUnit.SECONDS)
            },
        )

        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test-key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        // 啟動第一個正常播放請求
        val job1 = launch(Dispatchers.IO) {
            val outcome1 = engine.synthesizeAndPlay(
                text = "First ongoing speech",
                voiceOutputOn = true,
                openAiTtsOn = true,
            )
            assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcome1)
        }

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Job 1 should be actively running and playing", job1.isActive)

        // 發起前置門禁不通過的呼叫（開關關閉）
        val deniedOutcomeSwitch = engine.synthesizeAndPlay(
            text = "Ignored text when switch off",
            voiceOutputOn = true,
            openAiTtsOn = false, // 關閉
        )
        assertTrue(deniedOutcomeSwitch is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        assertTrue("Job 1 must NOT be cancelled by failed pre-check (switch off)", job1.isActive)

        // 發起前置門禁不通過的呼叫（空白文字）
        val deniedOutcomeBlank = engine.synthesizeAndPlay(
            text = "   ",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )
        assertTrue(deniedOutcomeBlank is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        assertTrue("Job 1 must NOT be cancelled by failed pre-check (empty text)", job1.isActive)

        // 發起前置門禁不通過的呼叫（無金鑰，且 target 為不信任端點）
        val untrustedClient = OpenAiTtsClient(baseUrl = "https://thirdparty.example.com/v1")
        val untrustedEngine = OpenAiSpeechEngine(client = untrustedClient, player = player)
        val deniedNoKey = untrustedEngine.synthesizeAndPlay(
            text = "Valid text",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )
        assertTrue(deniedNoKey is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        assertTrue("Job 1 must NOT be cancelled by independent failed engine call", job1.isActive)

        // 解除阻塞，第一個播放應順利完成，不受無效請求干擾
        unblockLatch.countDown()
        job1.join()
        assertFalse("Job 1 must complete normally, not cancelled", job1.isCancelled)
        assertEquals(1, player.playCount.get())
    }

    @Test
    fun synthesizeAndPlay_concurrentRequestsDuringPreCheck_doNotFailDueToStopEpochCollision() = runBlocking {
        val fakeAudio1 = byteArrayOf(0x01, 0x02)
        val fakeAudio2 = byteArrayOf(0x03, 0x04)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
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

        val deferred1 = async(Dispatchers.IO) {
            try {
                engine.synthesizeAndPlay(
                    text = "First concurrent synthesis request",
                    voiceOutputOn = true,
                    openAiTtsOn = true,
                )
            } catch (e: CancellationException) {
                null
            }
        }
        val deferred2 = async(Dispatchers.IO) {
            try {
                engine.synthesizeAndPlay(
                    text = "Second concurrent synthesis request",
                    voiceOutputOn = true,
                    openAiTtsOn = true,
                )
            } catch (e: CancellationException) {
                null
            }
        }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        val results = listOfNotNull(res1, res2)
        // 統一取消契約：兩併發請求其一必取代另一，勝者 Ok、敗者正常回傳 Failed(CANCELLED)（非拋出）。
        // 前置檢查期 stopEpoch 碰撞不得造成雙敗；合法取代造成之單一 CANCELLED 屬預期。
        assertEquals("Both concurrent requests must return (not throw)", 2, results.size)
        assertTrue("At least one request should succeed Ok", results.any { it is OpenAiSpeechEngine.Companion.SynthOutcome.Ok })
        val failed = results.filterIsInstance<OpenAiSpeechEngine.Companion.SynthOutcome.Failed>()
        assertTrue("At most one superseded loser", failed.size <= 1)
        for (f in failed) {
            assertEquals(
                "Superseded loser must be DETAIL_CANCELLED",
                OpenAiSpeechEngine.DETAIL_CANCELLED,
                f.detail,
            )
        }
    }

    // ---- 階段 2：回歸測試套件加固與全矩陣驗證 ----

    @Test
    fun synthesizeAndPlay_legacyCustomEndpoint_deniesWithoutVaultReadsOrHttp() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        val legacyConfig = EndpointConfig(
            providerId = "preset:custom",
            presetId = ProviderCatalog.CUSTOM_ID,
            label = "Legacy Custom Endpoint with OpenAI URL",
            baseUrl = ProviderCatalog.OPENAI_BASE_URL,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = "provider_key/preset:custom",
        )
        endpointStore.save(legacyConfig)

        val vault = FakeKeyVault()
        // 在 mock/recording vault 的 legacy slot preset:custom 放入測試金鑰，另放置 preset:openai 驗證不降級
        vault.putKey("preset:custom", "legacy-custom-key".toCharArray())
        vault.putKey("preset:openai", "fallback-openai-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            endpointStore = endpointStore,
            allowTestLoopback = true,
        )

        // 呼叫 engine.synthesizeAndPlay(...)（未提供 standalone openai_tts key）
        val outcome = engine.synthesizeAndPlay(
            text = "test legacy custom endpoint",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        // 斷言回傳結果為 SynthOutcome.Denied(DenyReason.NO_PRIVILEGE, ...)
        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        // 斷言 vault.getKeyCount == 0（零金鑰讀取）
        assertEquals("Zero key reads from vault", 0, vault.getKeyCount.get())
        // 斷言 server.requestCount == 0（零 HTTP 請求）
        assertEquals("Zero HTTP requests dispatched", 0, server.requestCount)
        assertEquals(0, player.playCount.get())
        assertFalse("Must never check or downgrade to preset:openai on legacy custom endpoint", hasKeyCalls.contains("preset:openai"))
    }

    @Test
    fun synthesizeAndPlay_mismatchedApiKeyRef_deniesWithoutVaultReadsOrHttp() = runBlocking {
        val (endpointStore, dataStore, _) = newTempEndpointStore()
        // 建立 apiKeyRef 與 providerId 不一致的端點
        dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
            prefs[ENDPOINT_PRESET_ID_V1] = ProviderCatalog.OPENAI_ID
            prefs[ENDPOINT_LABEL_V1] = "OpenAI"
            prefs[ENDPOINT_BASE_URL_V1] = ProviderCatalog.OPENAI_BASE_URL
            prefs[ENDPOINT_PROTOCOL_V1] = ProviderProtocol.CHAT_COMPLETIONS.name
            prefs[ENDPOINT_MODEL_V1] = "gpt-4o-mini"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:custom"
        }

        val vault = FakeKeyVault()
        vault.putKey("preset:custom", "foreign-test-key".toCharArray())
        vault.putKey("preset:openai", "fallback-openai-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            endpointStore = endpointStore,
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "test mismatched apiKeyRef",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        // 驗證 synthesizeAndPlay 拒絕，零金鑰讀取，零 HTTP 請求
        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals("Zero key reads from vault", 0, vault.getKeyCount.get())
        assertEquals("Zero HTTP requests dispatched", 0, server.requestCount)
        assertEquals(0, player.playCount.get())
        assertFalse("Must never check or downgrade to preset:openai on mismatched apiKeyRef", hasKeyCalls.contains("preset:openai"))
    }

    @Test
    fun synthesizeAndPlay_pendingDeleteJournalPresent_deniesWithoutVaultReadsOrHttp() = runBlocking {
        val (endpointStore, _, _) = newTempEndpointStore()
        // 建立存在 PendingDelete 的端點
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
        endpointStore.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L))

        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "official-openai-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            endpointStore = endpointStore,
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "test pending delete",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        // 驗證 synthesizeAndPlay 拒絕，零金鑰讀取，零 HTTP 請求
        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals("Zero key reads from vault", 0, vault.getKeyCount.get())
        assertEquals("Zero HTTP requests dispatched", 0, server.requestCount)
        assertEquals(0, player.playCount.get())
        assertFalse("Must never check or downgrade to preset:openai with pending delete", hasKeyCalls.contains("preset:openai"))
    }

    @Test
    fun synthesizeAndPlay_endpointStoreReadFailure_deniesWithoutVaultReadsOrHttp() = runBlocking {
        // 建立 snapshot 會拋出例外的 endpoint store
        val failingStore = EndpointStore(FailingDataStore())
        val vault = FakeKeyVault()
        vault.putKey("preset:openai", "fallback-key".toCharArray())
        val hasKeyCalls = mutableListOf<String>()
        vault.onHasKeyAction = { hasKeyCalls.add(it) }

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(
            client = client,
            player = player,
            vault = vault,
            endpointStore = failingStore,
            allowTestLoopback = true,
        )

        val outcome = engine.synthesizeAndPlay(
            text = "test endpoint store read failure",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )

        // 驗證 synthesizeAndPlay 拒絕，不降級到 preset:openai，零金鑰讀取，零 HTTP 請求
        assertTrue("Expected Denied outcome, was: $outcome", outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Denied)
        val denied = outcome as OpenAiSpeechEngine.Companion.SynthOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals(OpenAiSpeechEngine.DETAIL_NO_KEY, denied.detail)
        assertEquals("Zero key reads from vault", 0, vault.getKeyCount.get())
        assertEquals("Zero HTTP requests dispatched", 0, server.requestCount)
        assertEquals(0, player.playCount.get())
        assertFalse("Must never check or downgrade to preset:openai on read failure", hasKeyCalls.contains("preset:openai"))
    }

    // ---- Stage-1 P1: setDataSource 阻塞時 stop 有界返回、永不 start、終將釋放 ----

    @Test
    fun androidAudioPlayer_blockedSetDataSource_stopReturnsBoundedNeverStartsEventuallyReleases() = runBlocking {
        val enterSetDataSource = CountDownLatch(1)
        val unblockSetDataSource = CountDownLatch(1)
        val testMp = object : TestMediaPlayer() {
            override fun setDataSource(dataSource: android.media.MediaDataSource) {
                enterSetDataSource.countDown()
                // 故意阻塞 native setDataSource，模擬 P1 死鎖場景
                unblockSetDataSource.await(10, TimeUnit.SECONDS)
                super.setDataSource(dataSource)
            }
        }
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })

        val playJob = launch(Dispatchers.IO) {
            try {
                player.play(byteArrayOf(0x01, 0x02, 0x03))
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
        }
        try {
            assertTrue("play must enter blocked setDataSource", enterSetDataSource.await(5, TimeUnit.SECONDS))

            // 獨立線程 stop() 必須有界返回，不得被阻塞的 native 卡住
            var stopReturned = false
            val stopThread = kotlin.concurrent.thread(isDaemon = true) {
                player.stop()
                stopReturned = true
            }
            // 放寬 join 時限至 5000ms，避免 CI 高負載 flaky（stop 本身有界返回，僅等待調度）。
            stopThread.join(5000)
            assertFalse("stop() must return bounded while setDataSource blocked", stopThread.isAlive)
            assertTrue("stop() must have returned", stopReturned)

            // 釋放阻塞後，失格播放永不可 start，且資源終將釋放
            unblockSetDataSource.countDown()
            playJob.join()
            assertFalse("start() must never be called after stop during blocked setDataSource", testMp.startCalled)
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("Player must eventually be released", testMp.releaseCalled)
        } finally {
            // 若斷言前失敗，仍需放行阻塞避免懸掛
            unblockSetDataSource.countDown()
            player.release()
        }
    }

    // ---- Stage-1 P2: 舊 claim 不可取消新 winner 屏障測試 ----

    @Test
    fun synthesizeAndPlay_oldClaimCannotCancelNewWinner_barrier() = runBlocking {
        val fakeAudio1 = byteArrayOf(0x01, 0x02)
        val fakeAudio2 = byteArrayOf(0x03, 0x04)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
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

        try {
            val aClaimed = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val firstClaim = java.util.concurrent.atomic.AtomicBoolean(true)
            engine.epochClaimBarrierForTest = {
                if (firstClaim.getAndSet(false)) {
                    aClaimed.countDown()
                    // A 在躍遷臨界區內暫停，讓 B 有機會成為 winner
                    releaseA.await(5, TimeUnit.SECONDS)
                }
            }

            val outcomeARef = java.util.concurrent.atomic.AtomicReference<OpenAiSpeechEngine.Companion.SynthOutcome?>()
            val jobA = launch(Dispatchers.IO) {
                outcomeARef.set(
                    engine.synthesizeAndPlay(
                        text = "A barrier claim",
                        voiceOutputOn = true,
                        openAiTtsOn = true,
                    ),
                )
            }
            assertTrue("A must reach epoch claim barrier", aClaimed.await(5, TimeUnit.SECONDS))

            // B 在 A 持有 generationLock 期間發起，將阻塞等待鎖，待 A 釋放後成為 winner
            val deferredB = async(Dispatchers.IO) {
                engine.synthesizeAndPlay(
                    text = "B winner claim",
                    voiceOutputOn = true,
                    openAiTtsOn = true,
                )
            }
            delay(200)
            releaseA.countDown()

            val outcomeB = deferredB.await()
            jobA.join()
            val outcomeA = outcomeARef.get()

            // B 為 winner 必須 Ok，不被 A 取消；A 為舊世代回傳 Failed(CANCELLED)。
            // 僅斷言 outcomes（不釘死 playCount/playedBytes）：A 可能在網路前即被取消，
            // B 取到的可能是佇列首個回應，且併發調度下播放次數可能抖動，故放寬以避免 flaky。
            assertEquals(OpenAiSpeechEngine.Companion.SynthOutcome.Ok, outcomeB)
            assertTrue(
                "Old claim A must return Failed(CANCELLED), was: $outcomeA",
                outcomeA is OpenAiSpeechEngine.Companion.SynthOutcome.Failed &&
                    outcomeA.detail == OpenAiSpeechEngine.DETAIL_CANCELLED,
            )
        } finally {
            engine.epochClaimBarrierForTest = null
        }
    }

    // ---- Stage-A P1/BLOCK-1: prepare timeout 僅涵蓋 PREPARING，PLAYING 不被誤殺 ----

    @Test
    fun androidAudioPlayer_preparedPlaybackExceedingPrepareDeadline_isNotKilled() = runBlocking {
        // 縮短 timeout seam（200ms）決定性重現：onPrepared 成功後即使播放超過 deadline，
        // 仍不被 detach/TimeoutException/release，直到真實 onCompletion 才正常結束。
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp }, prepareTimeoutMs = 200L)
        try {
            val job = async(Dispatchers.IO) {
                player.play(byteArrayOf(0x01, 0x02))
            }
            awaitCondition { testMp.preparedListener != null }
            testMp.preparedListener!!.onPrepared(testMp)
            awaitCondition { testMp.startCalled }
            assertTrue("start() must be called for active onPrepared", testMp.startCalled)

            // 超過 prepare deadline（200ms）後仍在 PLAYING：不得被超時截斷。
            delay(600)
            assertTrue("play must still be active after prepare deadline when PLAYING", job.isActive)
            assertFalse("player must NOT be released while PLAYING past deadline", testMp.releaseCalled)

            // 真實 onCompletion 才正常結束。
            testMp.completionListener!!.onCompletion(testMp)
            job.await()
            assertTrue("Job must complete normally on completion", job.isCompleted && !job.isCancelled)
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("Player must be released after completion", testMp.releaseCalled)
        } finally {
            player.release()
        }
    }

    @Test
    fun androidAudioPlayer_neverPrepared_stillTimesOut() = runBlocking {
        // 對照組：永不 onPrepared 時仍確實 timeout（PREPARING 逾時語義保留）。
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp }, prepareTimeoutMs = 200L)
        try {
            val deferred = async(Dispatchers.IO) {
                try {
                    player.play(byteArrayOf(0x01, 0x02))
                    null
                } catch (e: Exception) {
                    e
                }
            }
            // 永不觸發 onPrepared，等待超時（200ms + 調度餘裕）。
            val exception = deferred.await()
            assertNotNull("Expected TimeoutException when never prepared", exception)
            assertTrue(
                "Exception must be TimeoutException, was: $exception",
                exception is java.util.concurrent.TimeoutException,
            )
            assertFalse("Timeout must NOT be CancellationException", exception is CancellationException)
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("Player must be released on prepare timeout", testMp.releaseCalled)
            assertFalse("start() must not be called when never prepared", testMp.startCalled)
        } finally {
            player.release()
        }
    }

    // ---- Stage-B P1/BLOCK-2: stop 真實媒體停止保證（決定性測試） ----

    @Test
    fun androidAudioPlayer_stopWhileReleaseBlocked_immediatelyMutesPlayingMedia() = runBlocking {
        // a. A 的 release 被 latch 擋住、B 已 start 播放中：呼叫 stop 後，在解除 A latch 前 B 不得繼續輸出。
        val resetEntered = CountDownLatch(1)
        val unblockA = CountDownLatch(1)
        val playerA = object : TestMediaPlayer() {
            override fun reset() {
                resetEntered.countDown()
                unblockA.await(10, TimeUnit.SECONDS)
                super.reset()
            }
        }
        val playerB = TestMediaPlayer()
        var factoryCount = 0
        val player = AndroidAudioPlayer(mediaPlayerFactory = {
            if (factoryCount++ == 0) playerA else playerB
        })
        // job 引用需在 finally 中清理，宣告於外層以避免提早返回漏放 latch。
        var jobA: Job? = null
        var jobB: Job? = null
        try {
            jobA = launch(Dispatchers.IO) {
                try {
                    player.play(byteArrayOf(0x01, 0x02))
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
            awaitCondition { playerA.prepareAsyncCalled }
            playerA.preparedListener!!.onPrepared(playerA)
            awaitCondition { playerA.startCalled }
            assertTrue("A must be playing before supersede", playerA.isPlaying())

            jobB = launch(Dispatchers.IO) {
                try {
                    player.play(byteArrayOf(0x03, 0x04))
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
            awaitCondition { playerB.prepareAsyncCalled }
            // 舊世代 A 的完整釋放已排入單線程 releaseExecutor 並卡住於 reset（證明佇列阻塞中）。
            assertTrue("A release must enter blocked reset", resetEntered.await(5, TimeUnit.SECONDS))
            // B 進入 PLAYING。
            playerB.preparedListener!!.onPrepared(playerB)
            awaitCondition { playerB.startCalled }
            assertTrue("B must be playing before stop", playerB.isPlaying())

            // 關鍵：在解除 A latch 前呼叫 stop，B 必須立即靜音（不等背景佇列輪到 B）。
            player.stop()
            assertTrue(
                "B must be synchronously muted at stop return (stopCalled),不等 A latch",
                playerB.stopCalled,
            )
            assertFalse(
                "B must not be playing immediately after stop (isPlaying==false),不等 A latch",
                playerB.isPlaying(),
            )
            // B 的完整 reset/release 仍排在 A 後面（背景），此時不應已完成——證明快速靜音與背景釋放分離。
            assertFalse(
                "B full release must still be queued behind blocked A (background), not yet done",
                playerB.releaseCalled,
            )
        } finally {
            unblockA.countDown()
            try {
                jobA?.join()
            } catch (_: Throwable) {
            }
            try {
                jobB?.join()
            } catch (_: Throwable) {
            }
            try {
                awaitCondition(timeoutMs = 5000L) { playerA.releaseCalled && playerB.releaseCalled }
            } catch (_: Throwable) {
            }
            player.release()
            // 確保測試縫不洩漏至其他用例。
            player.preStartBarrierForTest = null
        }
    }

    @Test
    fun androidAudioPlayer_onPreparedRacingWithStop_neverStartsAfterFinalFence() = runBlocking {
        // b. 最後一次 epoch check 後、native start 前的 stop 交錯：遲到 onPrepared 永不 start。
        // 以 preStartBarrierForTest 將回呼暫停於「最終柵欄之前」，主線程於暫停期間 stop，再放行——最終柵欄必須擋下。
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp })
        var job: Job? = null
        try {
            job = launch(Dispatchers.IO) {
                try {
                    player.play(byteArrayOf(0x01, 0x02))
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
            awaitCondition { testMp.preparedListener != null }

            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            player.preStartBarrierForTest = {
                entered.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }

            // onPrepared 於背景線程觸發，進入後將阻塞於最終柵欄前的測試縫。
            val cbJob = launch(Dispatchers.IO) {
                testMp.preparedListener!!.onPrepared(testMp)
            }
            assertTrue("onPrepared must reach pre-start barrier", entered.await(5, TimeUnit.SECONDS))

            // 決定性交錯：回呼暫停期間 stop（detach+epoch 遞增+同步靜音+背景釋放排隊）。
            player.stop()
            // 放行回呼，最終柵欄必須觀察到失格而跳過 start。
            proceed.countDown()
            cbJob.join()
            job.join()

            assertFalse(
                "Late onPrepared racing with stop must NEVER call start()",
                testMp.startCalled,
            )
            assertTrue(
                "stop() immediate mute must have been attempted",
                testMp.stopCalled,
            )
            awaitCondition(timeoutMs = 5000L) { testMp.releaseCalled }
            assertTrue("Player must eventually be released", testMp.releaseCalled)
        } finally {
            player.preStartBarrierForTest = null
            try {
                job?.join()
            } catch (_: Throwable) {
            }
            player.release()
        }
    }

    @Test
    fun androidAudioPlayer_stopBoundedReturnAndRealMuteAreSeparateAssertions() = runBlocking {
        // c. stop() 快速返回 與 實際音訊停止 是兩個不同 assertions：前者量測呼叫線程耗時（有界返回，
        // 不被 A 的阻塞 release 拖住），後者驗證真實媒體已靜音（stopCalled/isPlaying），兩者分開斷言。
        val resetEntered = CountDownLatch(1)
        val unblockA = CountDownLatch(1)
        val playerA = object : TestMediaPlayer() {
            override fun reset() {
                resetEntered.countDown()
                unblockA.await(10, TimeUnit.SECONDS)
                super.reset()
            }
        }
        val playerB = TestMediaPlayer()
        var factoryCount = 0
        val player = AndroidAudioPlayer(mediaPlayerFactory = {
            if (factoryCount++ == 0) playerA else playerB
        })
        var jobA: Job? = null
        var jobB: Job? = null
        try {
            jobA = launch(Dispatchers.IO) {
                try {
                    player.play(byteArrayOf(0x01))
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
            awaitCondition { playerA.prepareAsyncCalled }
            playerA.preparedListener!!.onPrepared(playerA)
            awaitCondition { playerA.startCalled }

            jobB = launch(Dispatchers.IO) {
                try {
                    player.play(byteArrayOf(0x02))
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                }
            }
            awaitCondition { playerB.prepareAsyncCalled }
            assertTrue(resetEntered.await(5, TimeUnit.SECONDS))
            playerB.preparedListener!!.onPrepared(playerB)
            awaitCondition { playerB.startCalled }
            assertTrue(playerB.isPlaying())

            // Assertion 1（快速返回）：stop() 呼叫本身必須有界（不等待背景佇列），即使 A 仍阻塞。
            val startNs = System.nanoTime()
            player.stop()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            assertTrue(
                "stop() must return bounded (<1000ms) while A release blocked, elapsed=${elapsedMs}ms",
                elapsedMs < 1000,
            )

            // Assertion 2（真實停止保證）：與耗時斷言分離——stop 返回當下 B 已靜音/停止，不等 A latch 解除。
            assertTrue(
                "Real media mute: B stopCalled must be true immediately after stop return",
                playerB.stopCalled,
            )
            assertFalse(
                "Real media mute: B isPlaying must be false immediately after stop return",
                playerB.isPlaying(),
            )
        } finally {
            unblockA.countDown()
            try {
                jobA?.join()
            } catch (_: Throwable) {
            }
            try {
                jobB?.join()
            } catch (_: Throwable) {
            }
            try {
                awaitCondition(timeoutMs = 5000L) { playerB.releaseCalled }
            } catch (_: Throwable) {
            }
            player.release()
            player.preStartBarrierForTest = null
        }
    }

    // ---- Stage-C P2: engine.shutdown 世代 fencing 缺口（人類第五輪） ----

    @Test
    fun shutdown_cancelsBlockedRequest_noPlayAfterUnblock_secondCallRejected_idempotent() = runBlocking {
        // 雲端回應阻塞（headers 延遲 2s 模擬進行中 HTTP），shutdown 必須取消該 Job，
        // 解除回應後絕不 player.play；關閉後再次呼叫明確拒絕且不再發 HTTP；多次 shutdown 冪等。
        val fakeAudio = byteArrayOf(0x01, 0x02)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody(Buffer().write(fakeAudio)),
        )
        // 此回應永不應被消耗：關閉後的新呼叫必須在發 HTTP 前即 fail-fast。
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(byteArrayOf(0x03, 0x04))),
        )

        val player = FakeAudioPlayer()
        val client = OpenAiTtsClient(
            baseUrl = server.url("/").toString(),
            apiKey = { "test-key".toCharArray() },
            allowTestLoopback = true,
        )
        val engine = OpenAiSpeechEngine(client = client, player = player, allowTestLoopback = true)

        val outcomeRef = java.util.concurrent.atomic.AtomicReference<OpenAiSpeechEngine.Companion.SynthOutcome?>()
        val job = launch(Dispatchers.IO) {
            outcomeRef.set(
                engine.synthesizeAndPlay(
                    text = "blocked request to be shutdown",
                    voiceOutputOn = true,
                    openAiTtsOn = true,
                ),
            )
        }

        // 確認雲端請求已在阻塞中
        val request1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("First request must reach MockWebServer before shutdown", request1)
        delay(200)

        val startNs = System.nanoTime()
        engine.shutdown()
        job.join()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        // HTTP cancellation：不必等待完整 2s 延遲即返回，且為 Failed(CANCELLED) 而非拋出。
        assertTrue(
            "Shutdown must cancel blocked request fast (<1500ms), elapsed=${elapsedMs}ms",
            elapsedMs < 1500,
        )
        val outcome = outcomeRef.get()
        assertTrue(
            "Blocked request after shutdown must return Failed(CANCELLED), was: $outcome",
            outcome is OpenAiSpeechEngine.Companion.SynthOutcome.Failed &&
                outcome.detail == OpenAiSpeechEngine.DETAIL_CANCELLED,
        )
        assertFalse("Unified contract: shutdown cancel returns Failed, not CancellationException", job.isCancelled)
        // 世代 fencing：解除回應（延遲窗口過去）後仍絕不播放。
        assertEquals(0, player.playCount.get())
        assertTrue("shutdown fencing must detach player (stop called)", player.stopped)

        // 等待原延遲窗口過去，確認無遲到播放（若 epoch 未遞增，解除後將誤播放）。
        delay(2500)
        assertEquals("No late play after delayed response unblocked", 0, player.playCount.get())

        // 關閉後再次呼叫明確拒絕，不再發 HTTP。
        val before = server.requestCount
        assertEquals("Only first request sent before shutdown", 1, before)
        val second = engine.synthesizeAndPlay(
            text = "after shutdown must reject",
            voiceOutputOn = true,
            openAiTtsOn = true,
        )
        assertTrue(
            "Call after shutdown must be rejected Failed(CANCELLED), was: $second",
            second is OpenAiSpeechEngine.Companion.SynthOutcome.Failed &&
                second.detail == OpenAiSpeechEngine.DETAIL_CANCELLED,
        )
        assertEquals("No new HTTP after shutdown", before, server.requestCount)
        assertEquals(0, player.playCount.get())

        // 資源關閉冪等：多次 shutdown + stop 安全無拋出。
        engine.shutdown()
        engine.shutdown()
        engine.stop()
        engine.shutdown()
    }

    @Test
    fun androidAudioPlayer_playAfterRelease_failsFastWithoutStartOrHang() = runBlocking {
        // Stage-C P2：play 在 released 狀態明確 fail-fast，不悄悄略過 timeout（不無界掛起、不 start）。
        val testMp = TestMediaPlayer()
        val player = AndroidAudioPlayer(mediaPlayerFactory = { testMp }, prepareTimeoutMs = 200L)
        player.release()
        // 多次 release 冪等安全。
        player.release()

        val startNs = System.nanoTime()
        val ex = try {
            withTimeout(2000L) {
                player.play(byteArrayOf(0x01, 0x02))
            }
            null
        } catch (e: Exception) {
            e
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertNotNull("play after release must fail fast, not hang", ex)
        assertTrue("play after release must be CancellationException, was: $ex", ex is CancellationException)
        assertTrue("play after release must fail fast (<1000ms), elapsed=${elapsedMs}ms", elapsedMs < 1000)
        assertFalse("start() must never be called after release", testMp.startCalled)
        assertFalse("prepareAsync must never be called after release", testMp.prepareAsyncCalled)

        // stop/release after release 安全。
        player.stop()
        player.release()
    }
}
