package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSessionImpl
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.TurnController
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class StuckAnsweringProvider : LlmProvider {
  override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    emit(StreamEvent.TextDelta(0, 0, "a-next"))
    emit(StreamEvent.Done("stop"))
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class StuckTimeoutNewSession : dev.librepocket.chat.ChatSession {
  private val state = kotlinx.coroutines.flow.MutableStateFlow(
    dev.librepocket.chat.ChatUiState(emptyList(), dev.librepocket.chat.ChatStatus.IDLE, 0, null),
  )
  override val uiState: kotlinx.coroutines.flow.StateFlow<dev.librepocket.chat.ChatUiState> = state
  val sent = CopyOnWriteArrayList<String>()
  override suspend fun send(text: String, images: List<dev.librepocket.chat.ChatImageRef>) { sent.add(text) }
  override suspend fun startTurn(text: String, images: List<dev.librepocket.chat.ChatImageRef>): kotlinx.coroutines.Job =
    kotlinx.coroutines.Job().apply { complete() }
  override suspend fun startOrEnqueue(
    text: String, images: List<dev.librepocket.chat.ChatImageRef>, opId: Long?,
  ): dev.librepocket.chat.TurnStart {
    sent.add(text)
    return dev.librepocket.chat.TurnStart.Started(kotlinx.coroutines.Job().apply { complete() })
  }
  override fun drainQueued(): List<dev.librepocket.chat.QueuedIntent> = emptyList()
  override fun cancel() = Unit
  override suspend fun flush(timeoutMs: Long): Boolean = true
  override fun steer(text: String) = Unit
  override fun close() = Unit
}

private class StuckTimeoutSessions(
  val transcripts: SessionStore,
  val oldId: String,
  val oldSession: dev.librepocket.chat.ChatSession,
  val newSession: StuckTimeoutNewSession,
) : ChatSessionProvider {
  val createdNewIds = CopyOnWriteArrayList<String>()
  override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
  override suspend fun storeOrNull(): SessionStore? = transcripts
  override suspend fun create(
    endpoint: EndpointConfig, title: String,
    initialHistory: List<dev.librepocket.provider.ChatMessage>,
    keyIsCurrent: suspend () -> Boolean,
  ): CreatedSession {
    val newId = transcripts.createSession(title.take(30), "${endpoint.providerId}@https://api.openai.com/${endpoint.model}")
    createdNewIds.add(newId)
    return CreatedSession(newId, newSession, endpoint.providerId, endpoint.model)
  }
  override suspend fun open(
    endpoint: EndpointConfig, sessionId: String, keyIsCurrent: suspend () -> Boolean,
  ): CreatedSession {
    check(sessionId == oldId) { "only old resume is used" }
    return CreatedSession(oldId, oldSession, endpoint.providerId, endpoint.model)
  }
  override suspend fun open(
    endpoint: EndpointConfig, sessionId: String, keyIsCurrent: suspend () -> Boolean,
    backfillInterrupted: Boolean,
  ): CreatedSession = open(endpoint, sessionId, keyIsCurrent)
}

/**
 * P1-3 Phase4: 真writer卡住時expiry到期仍defer.
 *
 * Phase1 ghost負控 (ChatSameIdReopenBackfillExpiryTest.expiryWhileWriterAlive +
 * R14 sealed-unfinished + ChatSessionFactorySwitchStreamingTest.realBarrierFork)
 * 已覆 writer-alive仍defer (fake store + 真barrier)。本檔補真持久化對照:
 * Room (in-memory) + 真TurnController卡在 admission-finalize barrier
 * (user已durable、terminal未落) 時, monotonic seam推進過期仍defer —
 * 不是Factory級backfill閘 (backfillInterrupted=false由VM settle決策),
 * 而是VM級 writer-liveness證明 (sealed-dead為false、alive為true)。
 *
 * Given: Room舊ledger + 真session卡barrier (hasUnsettledAdmission=true)。
 * When: monotonic seam推進過horizon + 同ID reopen。
 * Then: 仍defer (HISTORY_SNAPSHOT_INCOMPLETE、零INTERRUPTED、block續存)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WriterStuckExpiryPhase4Test {

  private val tmpDirs = ArrayList<File>()
  private val scopes = ArrayList<CoroutineScope>()
  private val chatViewModels = ChatTestViewModelStore()
  private val chatMain = ChatTestMainDispatcher()
  private var db: LibrePocketDb? = null

  @Before fun setUp() = chatMain.install()

  @After fun tearDown() {
    try {
      chatMain.clearViewModels(chatViewModels)
    } finally {
      try {
        cancelAndJoinChatTestScopes(scopes)
      } finally {
        try {
          db?.close()
        } finally {
          db = null
          try {
            tmpDirs.forEach { it.deleteRecursively() }
          } finally {
            chatMain.resetAndClose()
          }
        }
      }
    }
  }

  private fun onMain(block: () -> Unit) = runBlocking { chatMain.run(block) }

  private fun sampleEndpoint() = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = "gpt-4o-mini",
    apiKeyRef = "provider_key/preset:openai",
  )

  private fun newStore(): EndpointStore {
    val dir = Files.createTempDirectory("p13-stuck").toFile()
    tmpDirs.add(dir)
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    scopes.add(scope)
    return EndpointStore(
      PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "p13.preferences_pb") }),
    )
  }

  private fun awaitTrue(timeoutMs: Long = 10000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) error("timed out waiting for condition")
      Thread.sleep(10)
    }
  }

  private suspend fun awaitCond(timeoutMs: Long, what: String, cond: suspend () -> Boolean) {
    withTimeout(timeoutMs) { while (!cond()) delay(15) }
  }

  private fun controllerOf(impl: ChatSessionImpl): TurnController {
    val f = ChatSessionImpl::class.java.getDeclaredField("controller")
    f.isAccessible = true
    return f.get(impl) as TurnController
  }

  @Test fun roomWriterStuckOnBarrier_expiryStillDefers() = runBlocking {
    // Given: Room舊ledger (完成首turn) + 真session卡在finalize barrier。
    val store = newStore()
    runBlocking { store.save(sampleEndpoint()) }
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val roomDb = LibrePocketDb.openInMemory(ctx)
    db = roomDb
    val transcripts = RoomSessionStore(roomDb) { 1_700_000_000_000L }
    val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
    transcripts.appendEvent(TranscriptEvent(sessionId = oldId, runId = "r0", kind = "user", text = "q0", createdAt = 1L))
    transcripts.appendEvent(TranscriptEvent(sessionId = oldId, runId = "r0", kind = "assistant", text = "a0", createdAt = 1L))

    val ids = AtomicInteger(0)
    val oldSession = ChatSessionImpl(
      provider = StuckAnsweringProvider(),
      policy = InMemoryPolicyStore(),
      transcript = SessionTranscriptSink(transcripts, oldId),
      dispatcher = Dispatchers.Default,
      newId = { "p13-${ids.incrementAndGet() - 1}" },
    )
    val controller = controllerOf(oldSession)
    val enteredBarrier = CountDownLatch(1)
    val barrierGate = CompletableDeferred<Unit>()
    controller.beforeAdmissionFinalizeForTest = {
      enteredBarrier.countDown()
      barrierGate.await()
    }
    supervisorScope {
      try {
        val oldTurn = async(Dispatchers.Default) { oldSession.startOrEnqueue("q-cancel", opId = 913L) }
        assertTrue("old turn must park on barrier", enteredBarrier.await(5, TimeUnit.SECONDS))
        awaitCond(8_000, "barrier user row must be durable") {
          transcripts.loadEvents(oldId, 0, 100).any { it.kind == "user" && it.text == "q-cancel" }
        }
        assertTrue(oldSession.hasUnsettledAdmission())

        val newSession = StuckTimeoutNewSession()
        val sessions = StuckTimeoutSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 9_000_000L
        onMain {
          vm.backfillClockMs = { fakeNow }
          vm.backfillBlockHorizonMs = 500L
        }
        onMain { vm.openSession(oldId) }
        awaitCond(10_000, "VM must attach old ledger") { vm.currentSessionId.value == oldId }

        // When: 同ID reopen先park block (settle失敗→defer), 再推進seam過期。
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { vm.notice.value == "HISTORY_SNAPSHOT_INCOMPLETE" }
        // Then: 第一次defer零標記。
        assertTrue(transcripts.loadEvents(oldId, 0, 2000).none { it.text.endsWith("interrupted") })
        // When: seam推進過horizon, writer仍卡barrier (真writer未完成)。
        fakeNow += 600L
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { vm.notice.value == "HISTORY_SNAPSHOT_INCOMPLETE" }
        // Then: 仍defer (expiry不提升未證死亡writer), 零INTERRUPTED。
        // (block續存由notice + 無標記證明; writer-dead為false由下斷言。)
        assertTrue(
          "stuck Room writer must not gain INTERRUPTED on expiry",
          transcripts.loadEvents(oldId, 0, 2000).none { it.text.endsWith("interrupted") },
        )
        val deadM = ChatViewModel::class.java.getDeclaredMethod("isWriterSealedDead", dev.librepocket.chat.ChatSession::class.java)
        deadM.isAccessible = true
        assertTrue("stuck barrier writer must not read dead", !(deadM.invoke(vm, oldSession) as Boolean))
        val aliveM = ChatViewModel::class.java.getDeclaredMethod("isWriterAliveEvidence", dev.librepocket.chat.ChatSession::class.java)
        aliveM.isAccessible = true
        assertTrue("stuck barrier writer must read alive", aliveM.invoke(vm, oldSession) as Boolean)

        barrierGate.complete(Unit)
        runCatching { withTimeout(5_000) { oldTurn.await() } }
        withTimeout(5_000) { oldSession.flush() }
      } finally {
        barrierGate.complete(Unit)
        runCatching { oldSession.close() }
      }
    }
    Unit
  }
}
