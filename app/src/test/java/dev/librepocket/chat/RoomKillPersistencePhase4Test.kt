package dev.librepocket.chat

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.isSwitchSnapshotTerminalMark
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
import dev.librepocket.session.TranscriptEventEntity
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
 * P1-4 Phase4: crash-like shutdown window (Room in-memory, not a process kill).
 *
 * 現況缺口: preCompensationDeathSeed為合成 (Fake上手工user+notice)、
 * reopen-twice皆Fake、TerminalAckCancelGap僅一條Room對照。
 *
 * Given: RoomSessionStore (in-memory Room) + 真TurnController跑半截
 *   (user已durable、assistant卡gate)。
 * When: hold cancellation in the provider until close has sealed the writer,
 * then release the host and reopen the same sid. This simulates the ordering,
 * not process death or persistence across a database/process restart.
 * Then (三維 + N/A):
 *   DB: INTERRUPTED恰一、family關閉、第二open零新增;
 *   JSONL: export and decode (isFinal/isPartial/parentRunId/attemptIndex; no import);
 *   reopen: backfill and second-open idempotence; repeated Room reads agree on runId/text/kind;
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

  private suspend fun cleanupController(c: TurnController, host: Job?, failure: Throwable?) {
    try {
      withContext(NonCancellable) {
        c.close(drainTimeoutMs = 0L)
        withTimeout(8_000) { host?.join() }
        check(c.flushTranscript(8_000L)) { "controller shutdown did not drain" }
      }
    } catch (cleanup: Throwable) {
      if (failure == null) throw cleanup
      failure.addSuppressed(cleanup)
    }
  }

  private suspend fun <T> withReopenedSession(session: ChatSession, block: suspend () -> T): T {
    var failure: Throwable? = null
    try {
      return block()
    } catch (t: Throwable) {
      failure = t
      throw t
    } finally {
      try {
        withContext(NonCancellable) {
          session.close()
          check(session.flushDurable(8_000)) { "reopened session shutdown did not drain" }
          withTimeout(8_000) {
            while (!session.isTranscriptWriterCompleted()) delay(15)
          }
        }
      } catch (cleanup: Throwable) {
        if (failure == null) throw cleanup
        failure.addSuppressed(cleanup)
      }
    }
  }

  @Test fun terminalMarkersRemainTerminalAcrossExportImportAndReopen() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val opened = LibrePocketDb.openInMemory(context)
    db = opened
    val store = RoomSessionStore(opened) { 1_700_000_000_000L }
    val sid = store.createSession("hi", "preset:openai@https://api.openai.com/gpt-4o-mini")
    val ids = listOf("private-user@example.com", "interrupted-id", "failure-id", "bearer syntheticToken123")
    val tails = listOf("cancelled", "interrupted", "failed: contact private-user@example.com", "cancelled")
    opened.runInTransaction {
      ids.forEachIndexed { i, id ->
        opened.sessionDao().insertEvent(TranscriptEventEntity(
          0, sid, (2 * i + 1).toLong(), id, "user", "question", false, 0, 1L, false, null, null, null,
        ))
        opened.sessionDao().insertEvent(TranscriptEventEntity(
          0, sid, (2 * i + 2).toLong(), id, "system", "turn $id ${tails[i]}", false, 0, 1L, false, null, null, null,
        ))
      }
      opened.sessionDao().insertEvent(TranscriptEventEntity(
        0, sid, 9L, "notice-id", "system", "turn different cancelled", false, 0, 1L, false, null, null, null,
      ))
    }
    val dir = Files.createTempDirectory("markers-jsonl").toFile()
    tmpDir = dir
    val dest = File(dir, "markers.jsonl")
    store.exportJsonl(sid, dest)
    val raw = dest.readText()
    assertTrue(ids.none { raw.contains(it) })
    val imported = store.importJsonl(dest)
    val events = store.loadEvents(imported, 0, 2000)
    val marks = events.filter { it.kind == "system" }
    assertEquals(5, marks.size)
    assertEquals("turn ${marks[0].runId} cancelled", marks[0].text)
    assertEquals("turn ${marks[1].runId} interrupted", marks[1].text)
    assertTrue(marks[2].text.startsWith("turn ${marks[2].runId} failed"))
    assertEquals("turn ${marks[3].runId} cancelled", marks[3].text)
    assertTrue(marks.take(4).all(::isSwitchSnapshotTerminalMark))
    assertEquals("turn different cancelled", marks[4].text)
    assertTrue(!isSwitchSnapshotTerminalMark(marks[4]))
    assertTrue(findDanglingRunIds(events).isEmpty())
    assertTrue(!isSwitchSnapshotTerminalMark(marks[0].copy(text = "turn different cancelled")))
    assertTrue(!isSwitchSnapshotTerminalMark(marks[0].copy(kind = "user")))
    val reopened = factory(store).open(endpoint(), imported)
    withReopenedSession(reopened.session) {
      assertEquals(events, store.loadEvents(imported, 0, 2000))
    }
  }

  @Test fun bearerMarkerBoundariesSurviveRawImportExportAndReopen() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val opened = LibrePocketDb.openInMemory(context)
    db = opened
    val store = RoomSessionStore(opened) { 1_700_000_000_000L }
    val ids = listOf("bearer", "bearer syntheticSecret123", "user@example.com")
    val tails = listOf("cancelled", "interrupted", "failed: bearer suffixSecret123")
    val dir = Files.createTempDirectory("bearer-markers").toFile()
    tmpDir = dir
    val source = File(dir, "source.jsonl")
    val rows = ids.flatMapIndexed { index, id ->
      listOf(
        TranscriptEvent(seq = (2 * index + 1).toLong(), sessionId = "source", runId = id,
          kind = "user", text = "question", createdAt = 1L),
        TranscriptEvent(seq = (2 * index + 2).toLong(), sessionId = "source", runId = id,
          kind = "system", text = "turn $id ${tails[index]}", createdAt = 1L),
      )
    }
    source.writeText(rows.joinToString("\n") { JsonlCodec.encode(it) } + "\n")
    val first = store.importJsonl(source)
    val importedRows = store.loadEvents(first, 0, 100)
    assertTrue(findDanglingRunIds(importedRows).isEmpty())
    assertTrue(importedRows.filter { it.kind == "system" }.all(::isSwitchSnapshotTerminalMark))
    val dest = File(dir, "export.jsonl")
    store.exportJsonl(first, dest)
    val raw = dest.readText()
    assertTrue(!raw.contains("syntheticSecret123"))
    assertTrue(!raw.contains("suffixSecret123"))
    assertTrue(!raw.contains("user@example.com"))
    val second = store.importJsonl(dest)
    val restored = store.loadEvents(second, 0, 100)
    assertTrue(findDanglingRunIds(restored).isEmpty())
    assertTrue(restored.filter { it.kind == "system" }.all(::isSwitchSnapshotTerminalMark))
    val reopened = factory(store).open(endpoint(), second)
    withReopenedSession(reopened.session) {
      assertEquals(restored, store.loadEvents(second, 0, 100))
    }
  }

  @Test fun roomNormalCancellation_persistsTerminalWithoutBackfill() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val opened = LibrePocketDb.openInMemory(context)
    db = opened
    val store = RoomSessionStore(opened) { 1_700_000_000_000L }
    val sid = store.createSession("hi", "preset:openai@https://api.openai.com/gpt-4o-mini")
    val entered = CompletableDeferred<Unit>()
    val gate = CompletableDeferred<Unit>()
    val provider = KillProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        entered.complete(Unit)
        gate.await()
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
      newId = { "control-${n.getAndIncrement()}" },
    )
    var host: Job? = null
    var failure: Throwable? = null
    try {
      val admission = c.startOrEnqueue("hi")
      assertTrue(admission is TurnStart.Started)
      host = (admission as TurnStart.Started).host
      withTimeout(8_000) { entered.await() }
      val before = store.loadEvents(sid, 0, 2000)
      val userRunId = before.single { it.kind == "user" }.runId
      assertEquals(listOf(userRunId), findDanglingRunIds(before))
      c.cancel()
      withTimeout(8_000) { host.join() }
      assertTrue("normal cancellation must drain", c.flushTranscript(8_000L))
      val after = store.loadEvents(sid, 0, 2000)
      assertEquals(1, after.count { it.kind == "assistant" && it.isFinal })
      assertTrue("normal cancellation must close the family: $after", findDanglingRunIds(after).isEmpty())
      val reopened = factory(store).open(endpoint(), sid)
      withReopenedSession(reopened.session) {
        assertEquals("reopen must not backfill a compensated turn", after, store.loadEvents(sid, 0, 2000))
      }
    } catch (t: Throwable) {
      failure = t
      throw t
    } finally {
      gate.complete(Unit)
      cleanupController(c, host, failure)
    }
  }

  @Test fun roomSealedShutdown_reopenMarksExactlyOneInterrupted_secondOpenZero() = runBlocking {
    // Given: 真Room + 真TurnController半截 (user durable、assistant卡gate)。
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val opened = LibrePocketDb.openInMemory(context)
    db = opened
    val store = RoomSessionStore(opened) { 1_700_000_000_000L }
    val sid = store.createSession("hi", "preset:openai@https://api.openai.com/gpt-4o-mini")
    val assistantGate = CompletableDeferred<Unit>()
    val assistantEntered = CountDownLatch(1)
    val cancellationEntered = CompletableDeferred<Unit>()
    val releaseCancellation = CompletableDeferred<Unit>()
    // Gate the assistant terminal by parking the provider stream (not the
    // store): user row lands durable, terminal never lands.
    val provider = KillProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        assistantEntered.countDown()
        try {
          assistantGate.await()
        } finally {
          withContext(NonCancellable) {
            cancellationEntered.complete(Unit)
            releaseCancellation.await()
          }
        }
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
    )
    var host: Job? = null
    var failure: Throwable? = null
    try {
      val admission = c.startOrEnqueue("hi")
      assertTrue("admission must start, was $admission", admission is TurnStart.Started)
      host = (admission as TurnStart.Started).host
      assertTrue("assistant stream never entered", assistantEntered.await(5, TimeUnit.SECONDS))
      // User row must already be durable in Room before the kill.
      withTimeout(8_000) {
        while (store.loadEvents(sid, 0, 10).none { it.kind == "user" }) {
          kotlinx.coroutines.delay(15)
        }
      }
      assertTrue(store.loadEvents(sid, 0, 10).none { it.kind == "assistant" })
      // The provider's non-cancellable cleanup holds the host before its
      // cancellation handler can attempt compensation. Zero drain seals the
      // writer without waiting for that host, not a literal process kill.
      c.close(drainTimeoutMs = 0L)
      withTimeout(8_000) { cancellationEntered.await() }
      assertTrue("shutdown must finish and seal the writer", c.flushTranscript(8_000L))
      assertTrue(c.isTranscriptSealed())
      val beforeRelease = store.loadEvents(sid, 0, 2000)
      val userRunId = beforeRelease.single { it.kind == "user" }.runId
      assertEquals(listOf(userRunId), findDanglingRunIds(beforeRelease))
      assertTrue("no terminal before host release: $beforeRelease", beforeRelease.none { it.kind == "assistant" || it.kind == "system" })
      releaseCancellation.complete(Unit)
      withTimeout(8_000) { host.join() }
      assertTrue("sealed transcript should drain", c.flushTranscript(8_000L))
      // No seam observes whether the host attempted compensation after sealing.
      val afterRelease = store.loadEvents(sid, 0, 2000)
      assertEquals("host release must not change Room after sealed shutdown", beforeRelease, afterRelease)
      assertEquals(listOf(userRunId), findDanglingRunIds(afterRelease))
    } catch (t: Throwable) {
      failure = t
      throw t
    } finally {
      releaseCancellation.complete(Unit)
      assistantGate.complete(Unit)
      cleanupController(c, host, failure)
    }

    // When: 新Factory.open同sid (真backfill)。
    val f = factory(store)
    val ep = endpoint()
    val sizeBefore = store.loadEvents(sid, 0, 2000).size
    val first = f.open(ep, sid)
    withReopenedSession(first.session) {
      // Then DB: INTERRUPTED恰一 + family關閉。
      val afterFirst = store.loadEvents(sid, 0, 2000)
      assertEquals(sizeBefore + 1, afterFirst.size)
      val marks = afterFirst.filter { it.kind == "system" && it.text.endsWith("interrupted") }
      assertEquals("kill must backfill exactly one INTERRUPTED, was $afterFirst", 1, marks.size)
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
      // Exported JSONL decodes with flags/linkage intact; no import is exercised.
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
        assertEquals(src.kind, back.kind)
        assertEquals(if (src.kind == "system") "turn ${back.runId} interrupted" else src.text, back.text)
        assertEquals(src.isFinal, back.isFinal)
        assertEquals(src.isPartial, back.isPartial)
        assertEquals(src.parentRunId == null, back.parentRunId == null)
        assertEquals(src.attemptIndex, back.attemptIndex)
      }
      val decoded = lines.mapIndexed { i, line -> JsonlCodec.decode(i + 1, line) }
      assertEquals(decoded.first { it.kind == "user" }.runId, decoded.first { it.kind == "system" }.runId)
      // A second Room read agrees on runId/text/kind; session hydration is not asserted.
      val reloaded = store.loadEvents(sid, 0, 2000)
      assertEquals(
        afterFirst.map { it.runId to it.text to it.kind },
        reloaded.map { it.runId to it.text to it.kind },
      )
    }
    // 第二open零新增。
    val sizeAfterFirst = store.loadEvents(sid, 0, 2000).size
    val second = f.open(ep, sid)
    withReopenedSession(second.session) {
      assertEquals(sizeAfterFirst, store.loadEvents(sid, 0, 2000).size)
      assertTrue(findDanglingRunIds(store.loadEvents(sid, 0, 2000)).isEmpty())
    }
  }
}
