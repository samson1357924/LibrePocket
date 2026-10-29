package dev.librepocket.chat

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.JsonlCodec
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class KillProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow { emitAll(handler(request)) }
  override suspend fun listModels(): List<String> = emptyList()
}

private class KillPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * P1-4 Phase4: 真持久化kill (Room in-memory, 非合成).
 *
 * 現況缺口: preCompensationDeathSeed為合成 (Fake上手工user+notice)、
 * reopen-twice皆Fake、TerminalAckCancelGap僅一條Room對照。
 *
 * Given: RoomSessionStore (in-memory Room) + 真TurnController跑半截
 *   (user已durable、assistant卡gate)。
 * When: close(drainTimeout=0)模擬kill (不drain、不補償) → 新Factory.open同sid。
 * Then (三維 + N/A):
 *   DB: INTERRUPTED恰一、family關閉、第二open零新增;
 *   JSONL: export/import round-trip (isFinal/isPartial/parentRunId/attemptIndex);
 *   reopen: reload等價;
 *   UI hydrate與新session flushDurable未斷言 (由siblings覆蓋，本案僅pin kill backfill)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomKillPersistencePhase4Test {

  private var db: LibrePocketDb? = null
  private var tmpDir: File? = null

  @After fun tearDown() {
    try { db?.close() } finally {
      db = null
      tmpDir?.deleteRecursively()
      tmpDir = null
    }
  }

  private fun endpoint() = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProto.CHAT_COMPLETIONS,
    model = "gpt-4o-mini",
    apiKeyRef = "provider_key/preset:openai",
  )

  private fun factory(store: SessionStore): ChatSessionFactory = ChatSessionFactory(
    policy = allowKeyReadPolicy(),
    vaultSource = VaultSource {
      val v = EncryptedPrefsVault(InMemoryPrefs())
      v.putKey("preset:openai", "test-key-12345678".toCharArray())
      v
    },
    buildProvider = { _, _ -> KillProvider() },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = store
    },
  )

  @Test fun roomHalfTurnKill_reopenMarksExactlyOneInterrupted_secondOpenZero() = runBlocking {
    // Given: 真Room + 真TurnController半截 (user durable、assistant卡gate)。
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val opened = LibrePocketDb.openInMemory(context)
    db = opened
    val store = RoomSessionStore(opened) { 1_700_000_000_000L }
    val sid = store.createSession("hi", "preset:openai@https://api.openai.com/gpt-4o-mini")
    val assistantGate = CompletableDeferred<Unit>()
    val assistantEntered = CountDownLatch(1)
    // Gate the assistant terminal by parking the provider stream (not the
    // store): user row lands durable, terminal never lands.
    val provider = KillProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        assistantEntered.countDown()
        assistantGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = TurnController(
      provider = provider,
      policy = KillPolicy(),
      transcript = SessionTranscriptSink(store, sid),
      retryConfig = TurnRetryConfig(),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "kill-${n.incrementAndGet() - 1}" },
      cancelSettleTimeoutMs = 300L,
      cancelCompensationTimeoutMs = 0L,
    )
    try {
      val admission = c.startOrEnqueue("hi")
      assertTrue("admission must start, was $admission", admission is TurnStart.Started)
      val host = (admission as TurnStart.Started).host
      assertTrue("assistant stream never entered", assistantEntered.await(5, TimeUnit.SECONDS))
      // User row must already be durable in Room before the kill.
      withTimeout(8_000) {
        while (store.loadEvents(sid, 0, 10).none { it.kind == "user" }) {
          kotlinx.coroutines.delay(15)
        }
      }
      assertTrue(store.loadEvents(sid, 0, 10).none { it.kind == "assistant" })
      // When: kill — close with zero drain (no compensation, no backfill yet).
      c.close(drainTimeoutMs = 0L)
      host.cancel()
      assistantGate.complete(Unit)
      host.join()
      c.flushTranscript(2_000L)
    } finally {
      runCatching { assistantGate.complete(Unit) }
      runCatching { c.close() }
    }

    // When: 新Factory.open同sid (真backfill)。
    val f = factory(store)
    val ep = endpoint()
    val sizeBefore = store.loadEvents(sid, 0, 2000).size
    val first = f.open(ep, sid)
    try {
      // Then DB: INTERRUPTED恰一 + family關閉。
      val afterFirst = store.loadEvents(sid, 0, 2000)
      assertEquals(sizeBefore + 1, afterFirst.size)
      val marks = afterFirst.filter { it.kind == "system" && it.text.endsWith("interrupted") }
      assertEquals("kill must backfill exactly one INTERRUPTED, was $afterFirst", 1, marks.size)
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
      // JSONL round-trip (export → import preserves flags/linkage)。
      val dir = Files.createTempDirectory("p14-jsonl").toFile()
      tmpDir = dir
      val dest = File(dir, "kill.jsonl")
      store.exportJsonl(sid, dest)
      assertTrue(dest.exists() && dest.length() > 0)
      val lines = dest.readLines().filter { it.isNotBlank() }
      assertEquals(afterFirst.size, lines.size)
      for ((i, line) in lines.withIndex()) {
        val back = JsonlCodec.decode(i + 1, line)
        val src = afterFirst[i]
        assertEquals(src.runId, back.runId)
        assertEquals(src.kind, back.kind)
        assertEquals(src.text, back.text)
        assertEquals(src.isFinal, back.isFinal)
        assertEquals(src.isPartial, back.isPartial)
        assertEquals(src.parentRunId, back.parentRunId)
        assertEquals(src.attemptIndex, back.attemptIndex)
      }
      // Reopen reload等價 (DB reload equals)。
      val reloaded = store.loadEvents(sid, 0, 2000)
      assertEquals(
        afterFirst.map { it.runId to it.text to it.kind },
        reloaded.map { it.runId to it.text to it.kind },
      )
    } finally {
      first.session.close()
    }
    // 第二open零新增。
    val sizeAfterFirst = store.loadEvents(sid, 0, 2000).size
    val second = f.open(ep, sid)
    try {
      assertEquals(sizeAfterFirst, store.loadEvents(sid, 0, 2000).size)
      assertTrue(findDanglingRunIds(store.loadEvents(sid, 0, 2000)).isEmpty())
    } finally {
      second.session.close()
    }
  }
}
