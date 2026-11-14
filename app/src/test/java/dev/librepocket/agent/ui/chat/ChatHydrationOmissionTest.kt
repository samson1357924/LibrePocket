package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.HistoryWindowCap
import dev.librepocket.chat.TurnController
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class OmissionFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        seenRequests.add(request)
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

private class OmissionAllowPolicy : PolicyStore {
    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()
    override suspend fun evaluateFresh(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * B2 hydration omission visibility (pure JVM, all fake data).
 *
 * Resume a 150-turn dialogue (300 short replay rows): the model-context
 * prefix windows to [HistoryWindowCap.DEFAULT] (200) at construction and
 * again inside the first send's combined window, so the outgoing request
 * carries 200 rows while the UI replay keeps all 300. The provider-request
 * context omission total (init window + combined window, 100 + 1 = 101)
 * surfaces via [ChatViewModel.contextOmittedCount]; the DB-tail omission
 * ([ChatViewModel.historyOmittedCount]) stays 0 (300 rows < 2000) and the
 * send-time 100k-trim leg ([ChatViewModel.requestTrimmedCount]) stays 0
 * (tiny rows) — orthogonal counters, never summed.
 *
 * [controller_resumeLongDialogue_seedsInitWindowAndPublishesTotal] pins the
 * same numbers at the controller layer (construction seed = 100, post-send =
 * 101), and
 * [controller_windowAndSendTimeTrimBothBind_publishesFullOmissionTotal] pins
 * the send-time 100k-trim leg of the same identity (init window 19 +
 * combined window 1 + 100k trim 6 = 26).
 *
 * Fixed clock/zone pins the ephemeral time block; history rows stay tiny
 * (char legs never bind) and far below MODEL_HISTORY_MAX_CHARS_PER_MESSAGE
 * (no read-path truncation).
 */
class ChatHydrationOmissionTest {

    private fun openaiEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-01-15T12:00:00Z"),
        ZoneId.of("UTC"),
    )
    private val zoneSupplier: () -> ZoneId = { ZoneId.of("UTC") }

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val chatViewModels = ChatTestViewModelStore()
    private val chatMain = ChatTestMainDispatcher()

    @Before
    fun setUpMain() {
        chatMain.install()
    }

    @After
    fun tearDownMain() {
        try {
            chatMain.clearViewModels(chatViewModels)
        } finally {
            try {
                scopes.forEach { it.cancel() }
                runBlocking { scopes.mapNotNull { it.coroutineContext[kotlinx.coroutines.Job] }.joinAll() }
            } finally {
                try {
                    tmpDirs.forEach { it.deleteRecursively() }
                } finally {
                    chatMain.resetAndClose()
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        runBlocking { chatMain.run(block) }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("chat-omission-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "chat.preferences_pb") },
            ),
        )
    }

    private fun newFactory(fake: OmissionFakeProvider, transcripts: FakeSessionStore): ChatSessionFactory {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        return ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = fixedClock,
            userTimezone = "UTC",
            systemZone = zoneSupplier,
        )
    }

    private fun okHandler(): (ChatRequest) -> Flow<StreamEvent> = {
        flow {
            emit(StreamEvent.TextDelta(0, 0, "ok"))
            emit(StreamEvent.Done("stop"))
        }
    }

    private fun awaitTrue(
        timeoutMs: Long = 30_000,
        diagnostics: () -> String = { "" },
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) {
                val detail = diagnostics().takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                error("timed out waiting for condition$detail")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun open_resumeLongDialogue_publishesContextOmission() {
        val store = newStore()
        runBlocking { store.save(openaiEndpoint()) }
        val transcripts = FakeSessionStore()
        val sid = runBlocking {
            val id = transcripts.createSession("long chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
            // 150 turns = 300 tiny rows (< 2000 DB-tail scan, > 200 DEFAULT
            // window on both legs).
            for (i in 0 until 150) {
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "user", text = "u$i", createdAt = 1L),
                )
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "assistant", text = "a$i", createdAt = 1L),
                )
            }
            id
        }
        val fake = OmissionFakeProvider(okHandler())
        val vm = chatViewModels.own(ChatViewModel(store, newFactory(fake, transcripts), allowKeyReadPolicy()))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 300 }
        // Construction seed: 300 resumed rows windowed to the newest 200.
        awaitTrue(
            diagnostics = {
                "status=${vm.sessionState.value.status} messages=${vm.messages.value.size}" +
                    " omitted=${vm.contextOmittedCount.value} trimmed=${vm.requestTrimmedCount.value}" +
                    " dbTail=${vm.historyOmittedCount.value}"
            },
        ) { vm.contextOmittedCount.value == 100 }

        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue(
            diagnostics = {
                "status=${vm.sessionState.value.status} messages=${vm.messages.value.size}" +
                    " omitted=${vm.contextOmittedCount.value} trimmed=${vm.requestTrimmedCount.value}" +
                    " dbTail=${vm.historyOmittedCount.value}"
            },
        ) { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 302 }
        awaitTrue { vm.contextOmittedCount.value == 101 }

        // (a) provider sees the windowed request: 200 rows (199 resumed + live).
        val req = fake.seenRequests.single()
        assertEquals(200, req.messages.size)
        assertTrue(req.messages.last().text.startsWith("hi"))
        // (b) provider-request context omission: 100 init-window + 1 combined-window.
        assertEquals(101, vm.contextOmittedCount.value)
        // (c) UI replay keeps everything: 300 rows + user + assistant.
        assertEquals(302, vm.messages.value.size)
        // (d) orthogonal counters stay 0: DB-tail fits (300 < 2000), send-time
        // 100k trim never binds (tiny rows).
        assertEquals(0, vm.historyOmittedCount.value)
        assertEquals(0, vm.requestTrimmedCount.value)
    }

    /**
     * B1 pin: window leg + send-time 100k-char trim leg both bind.
     *
     * Fixture: 150 fixed-width 800-char rows (120,000 chars) under an explicit
     * HistoryWindowCap.Bounded(250, 105_000) — the DEFAULT 60k char leg can
     * never make the send-time 100k leg bind (post-window the request stays
     * ≤ ~60k + the ~98-char time block, far under MAX_REQUEST_CHARS), so a
     * custom cap in the (100k, 120k] band is required to exercise both legs.
     * Arithmetic (fixed clock 2026-01-15T12:00:00Z UTC, no sessionStart, so the
     * ephemeral time block is exactly 98 chars, suffixed as "\n\n" + block):
     *
     * 1. Construction window: count leg (150 ≤ 250) never binds; char leg
     *    keeps the newest floor(105_000 / 800) = 131 rows (131 × 800 =
     *    104,800 ≤ 105,000; 132 would be 105,600 > 105,000), dropping the
     *    19 oldest — construction-time omission = 19.
     * 2. send(500-char plain text): live = [user, 500 chars]. Combined =
     *    131 × 800 + 500 = 105,300 chars > 105,000; the combined window
     *    keeps the live row (500) + the newest 130 prefix rows
     *    (104,500 ≤ 105,000; one more would be 105,300), dropping the
     *    oldest prefix row — combined window contributes 1 (drop = 20 total).
     * 3. The time block is suffixed to the last user row AFTER the window,
     *    so base chars = 104,000 + 500 + 2 + 98 = 104,600 > 100,000: the
     *    send-time 100k head trim drops 6 oldest prefix rows (5 would leave
     *    100,600 > 100,000; 6 leave 99,800 ≤ 100,000). maxDropLimit (index
     *    of the live user row = 130) is not hit; snapHistoryToPairBoundary
     *    is a no-op (no tool rows kept/dropped).
     *
     * Therefore: contextOmission = initialHistory.size + live.size
     *   − finalMessages.size = 150 + 1 − 125 = 26
     *   = 19 (init window) + 1 (combined window) + 6 (100k trim) — the exact
     * assertEquals(26) below stands or falls with the 100k leg being counted
     * (a window-only count would report 20). The outgoing request's text sum
     * (99,800) must satisfy lastRequestChars ≤ MAX_REQUEST_CHARS and
     * requestTrimmedCount == 6 > 0.
     */
    @Test
    fun controller_windowAndSendTimeTrimBothBind_publishesFullOmissionTotal() {
        val history = (0 until 150).map { i ->
            val role = if (i % 2 == 0) "user" else "assistant"
            ChatMessage(role = role, text = i.toString().padStart(4, '0') + ":" + "v".repeat(795))
        }
        assertTrue("fixture rows must be fixed-width 800-char messages", history.all { it.text.length == 800 })
        val fake = OmissionFakeProvider(okHandler())
        var n = 0
        val controller = TurnController(
            provider = fake,
            policy = OmissionAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "omission-trim-${n++}" },
            initialHistory = history,
            // Custom cap in the (100k, 120k] band (count leg never binds):
            // admits rows past the 60k default so the send-time 100k leg also binds.
            historyCap = HistoryWindowCap.Bounded(250, 105_000),
        )
        try {
            // Construction seed: 150 rows -> newest 131 kept, 19 dropped.
            assertEquals(19, controller.contextOmission.value)
            runBlocking { controller.send("q".repeat(500)) }
            val req = fake.seenRequests.single()
            // (a) The outgoing request stays within the send-time cap.
            val totalChars = req.messages.sumOf { it.text.length }
            assertTrue(
                "request totalChars=$totalChars must stay within MAX_REQUEST_CHARS",
                totalChars <= TurnController.MAX_REQUEST_CHARS,
            )
            // (b) The 100k head trim bound: 6 rows dropped, observable.
            assertEquals(6, controller.requestTrimmedCount.value)
            // (c) The context omission total accounts for all three legs:
            // 19 (init window) + 1 (combined window) + 6 (100k trim) = 26 —
            // a window-only implementation would report 20.
            assertEquals(26, controller.contextOmission.value)
            assertEquals(125, req.messages.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun controller_resumeLongDialogue_seedsInitWindowAndPublishesTotal() {
        val history = (0 until 300).map { i ->
            val role = if (i % 2 == 0) "user" else "assistant"
            ChatMessage(role = role, text = "m$i")
        }
        val fake = OmissionFakeProvider(okHandler())
        var n = 0
        val controller = TurnController(
            provider = fake,
            policy = OmissionAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "omission-${n++}" },
            initialHistory = history,
            // B2: hydration travels DEFAULT (200); pin it explicitly here.
            historyCap = HistoryWindowCap.DEFAULT,
        )
        try {
            // Construction seed: 300 -> 200 windowed prefix.
            assertEquals(100, controller.contextOmission.value)
            runBlocking { controller.send("hi") }
            // Post-send: init window (100) + combined window (1); tiny rows,
            // send-time 100k trim contributes 0.
            assertEquals(101, controller.contextOmission.value)
            assertEquals(200, fake.seenRequests.single().messages.size)
        } finally {
            controller.close()
        }
    }
}
