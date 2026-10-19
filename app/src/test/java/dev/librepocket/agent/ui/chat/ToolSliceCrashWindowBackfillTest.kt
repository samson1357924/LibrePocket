package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.router.SafeToolSlice
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

private class CrashReopenProvider : LlmProvider {
  val requests = AtomicInteger(0)
  override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    requests.incrementAndGet()
    emitAll(emptyFlow())
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class CrashReopenBackend : LspTools.Backend {
  val invocations = AtomicInteger(0)
  override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
    invocations.incrementAndGet()
    return emptyList()
  }
  override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
}

/**
 * Phase-0 review F3(e): the tool-round crash window pin from
 * [SafeToolSlice.execute]'s durability contract — turn-level exec-once, not
 * process-level exec-once.
 *
 * Given: a ledger the previous process left mid-tool-round — user row +
 * attempt partial (isFinal=0) carrying the inline `[tool:...]` record and the
 * executed `[tool-result:...]` marker, then the process DIED before the
 * continuation/final settle (no final assistant row, no system mark).
 * When: the factory re-opens the same session (same provider origin).
 * Then: the reopen backfill stamps EXACTLY ONE `INTERRUPTED` system mark for
 * the family (Stage F: the attempt partial never closes a family), nothing
 * re-executes — zero backend invocations — and no provider request rides the
 * crash (no tool round resumes on reopen). A second open is idempotent.
 *
 * The ledger shape mirrors what [TurnController] persists for one attempt:
 * marker rows live INLINE in the assistant partial text; the durable audit
 * keeps raw call ids — settling them is the turn engine's job and it must
 * never re-run on a kill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolSliceCrashWindowBackfillTest {

  private fun endpoint() = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = "gpt-4o-mini",
    apiKeyRef = "provider_key/preset:openai",
  )

  private fun factory(
    store: SessionStore,
    backend: CrashReopenBackend,
    provider: CrashReopenProvider,
  ): ChatSessionFactory {
    val policy = allowKeyReadPolicy()
    runBlocking { policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20)) }
    return ChatSessionFactory(
      policy = policy,
      vaultSource = VaultSource {
        val v = EncryptedPrefsVault(InMemoryPrefs())
        v.putKey("preset:openai", "test-key-12345678".toCharArray())
        v
      },
      buildProvider = { _, _ -> provider },
      sessionStores = object : SessionStoreSource {
        override suspend fun store() = store
      },
      toolSlice = SafeToolSlice(
        ctxSupplier = { ProjectionContext(userSwitches = mapOf("lsp" to true)) },
        policy = policy,
        backend = backend,
      ),
    )
  }

  private fun rows(store: FakeSessionStore, sid: String) =
    store.events.filter { it.sessionId == sid }.sortedBy { it.seq }

  @Test fun toolRoundCrashReopenStampsOneInterruptedWithoutReExecution() = runBlocking {
    val store = FakeSessionStore()
    val sid = store.createSession("t-crash", "preset:openai@https://api.openai.com/gpt-4o-mini")

    // Crash window: user row + tool-round attempt partial with the ToolDone
    // record and the executed result marker — then the kill (no final row).
    val backend = CrashReopenBackend()
    val provider = CrashReopenProvider()
    store.appendEvent(
      TranscriptEvent(sessionId = sid, runId = "L-crash", kind = "user", text = "查 Foo 符號", createdAt = 1L),
    )
    val resultJson =
      """{"denied":false,"tool":"lsp.symbols","callId":"call-room-1","query":"Foo",""" +
      """"symbols":[{"name":"Foo:example","kind":"function","path":"app/src/main/Example.kt","line":1}]}"""
    val markerArgs = """{"query":"Foo"}"""
    val attemptPartial =
      "\n[tool:lsp.symbols $markerArgs]" +
        "\n[tool-result:lsp.symbols:${resultJson.length}:$resultJson]"
    store.appendEvent(
      TranscriptEvent(
        sessionId = sid, runId = "L-crash", kind = "assistant", text = attemptPartial, createdAt = 2L,
        isPartial = true, isFinal = false, parentRunId = "L-crash", attemptIndex = 0,
      ),
    )
    // Pre-condition control: the family dangles exactly once for backfill.
    assertEquals(listOf("L-crash"), findDanglingRunIds(rows(store, sid)))
    val sizeBefore = rows(store, sid).size

    // When: reopen (same provider origin) with the loop attached.
    val opened = factory(store, backend, provider).open(endpoint(), sid)
    try {
      val after = rows(store, sid)
      // Then: EXACTLY ONE INTERRUPTED backfill closes the dangling family…
      assertEquals(sizeBefore + 1, after.size)
      val marks = after.filter { it.kind == "system" && it.text.endsWith("interrupted") }
      assertEquals(listOf(interruptedMarkerText("L-crash")), marks.map { it.text })
      assertTrue(findDanglingRunIds(after).isEmpty())
      // …nothing re-executes and the crash rides no provider request.
      assertEquals(0, backend.invocations.get())
      assertEquals(0, provider.requests.get())
    } finally {
      opened.session.close()
    }

    // Idempotent: the second open stamps zero new rows.
    val second = factory(store, backend, provider).open(endpoint(), sid)
    try {
      assertEquals(sizeBefore + 1, rows(store, sid).size)
      assertTrue(findDanglingRunIds(rows(store, sid)).isEmpty())
      assertEquals(0, backend.invocations.get())
    } finally {
      second.session.close()
    }
  }
}
