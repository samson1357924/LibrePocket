package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.JsonlCodec
import dev.librepocket.session.TranscriptEvent
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class WindowProvider : LlmProvider {
  override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): kotlinx.coroutines.flow.Flow<StreamEvent> = emptyFlow()
  override suspend fun listModels(): List<String> = emptyList()
}

/**
 * P1-2 Phase4: backfill scan-window (BACKFILL_SCAN_LIMIT = 2000) pins.
 *
 * Given: a 2001-row ledger (1000 complete turns + one dangling user at the
 *   head) vs a control ledger with the dangling inside the tail window.
 * When: Factory.open runs the bounded backfill (loadTailEvents 2000).
 * Then: the window-outside dangling is NOT marked (stays a known gap —
 *   never a silent false-close), while the window-inside control gains
 *   exactly one INTERRUPTED and the second open adds zero.
 *
 * Five dimensions per case: DB kinds + dangling, JSONL round-trip, reopen
 * equality, UI replay via Factory hydrate is out of scope here (no VM), and
 * flushDurable is N/A for synthetic seeds (documented, not asserted).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatBackfillWindowPhase4Test {

  private fun endpoint() = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = "gpt-4o-mini",
    apiKeyRef = "provider_key/preset:openai",
  )

  private fun factory(store: FakeSessionStore) = ChatSessionFactory(
    policy = InMemoryPolicyStore(),
    vaultSource = VaultSource {
      val v = EncryptedPrefsVault(InMemoryPrefs())
      v.putKey("preset:openai", "test-key-12345678".toCharArray())
      v
    },
    buildProvider = { _, _ -> WindowProvider() },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = store
    },
  )

  private fun event(sid: String, runId: String, kind: String, text: String) = TranscriptEvent(
    sessionId = sid, runId = runId, kind = kind, text = text, createdAt = 1L,
  )

  private fun rows(store: FakeSessionStore, sid: String) =
    store.events.filter { it.sessionId == sid }.sortedBy { it.seq }

  private fun assertJsonlRoundTrip(events: List<TranscriptEvent>) {
    for (e in events) {
      val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
      assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
      assertEquals("parentRunId must round-trip for ${e.kind}", e.parentRunId, back.parentRunId)
      assertEquals("attemptIndex must round-trip for ${e.kind}", e.attemptIndex, back.attemptIndex)
    }
  }

  @Test fun danglingOutsideWindow_notMarked_knownGap() = runBlocking {
    // Given: 1000 complete turns (2000 rows) + one dangling user at the head
    // (oldest, seq 1) → total 2001 rows; the head falls outside the 2000 tail.
    val store = FakeSessionStore()
    val sid = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
    store.appendEvent(event(sid, "L-old", "user", "q-stranded-head"))
    repeat(1000) { i ->
      store.appendEvent(event(sid, "r$i", "user", "q-$i"))
      store.appendEvent(event(sid, "r$i", "assistant", "a-$i"))
    }
    assertEquals(2001, rows(store, sid).size)
    // Sanity: full-scan dangling sees the head; tail-window scan does not.
    assertTrue(findDanglingRunIds(rows(store, sid)).contains("L-old"))
    assertTrue(findDanglingRunIds(store.loadTailEvents(sid, BACKFILL_SCAN_LIMIT)).none { it == "L-old" })

    // When: Factory.open backfills within the bounded window.
    val f = factory(store)
    val opened = f.open(endpoint(), sid)
    try {
      // Then: no INTERRUPTED for the out-of-window head (known gap, never a
      // silent false-close); the tail stays clean; JSONL round-trips.
      val after = rows(store, sid)
      assertTrue(
        "window-outside dangling must not gain a marker (known gap), was ${after.takeLast(3)}",
        after.none { it.kind == "system" && it.text.endsWith("interrupted") },
      )
      assertJsonlRoundTrip(after)
    } finally {
      opened.session.close()
    }
    // Second open: still zero new (idempotent, no window drift).
    val sizeAfterFirst = rows(store, sid).size
    val second = f.open(endpoint(), sid)
    try {
      assertEquals(sizeAfterFirst, rows(store, sid).size)
    } finally {
      second.session.close()
    }
  }

  @Test fun danglingInsideWindow_markedExactlyOnce_secondOpenZero() = runBlocking {
    // Given: 999 complete turns (1998 rows) + one dangling user at the tail
    // (newest) → total 1999 rows, fully inside the 2000 window (control).
    val store = FakeSessionStore()
    val sid = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
    repeat(999) { i ->
      store.appendEvent(event(sid, "r$i", "user", "q-$i"))
      store.appendEvent(event(sid, "r$i", "assistant", "a-$i"))
    }
    store.appendEvent(event(sid, "L-new", "user", "q-stranded-tail"))
    assertEquals(1999, rows(store, sid).size)
    assertEquals(listOf("L-new"), findDanglingRunIds(rows(store, sid)))

    // When: Factory.open backfills.
    val f = factory(store)
    val sizeBefore = rows(store, sid).size
    val first = f.open(endpoint(), sid)
    try {
      // Then: exactly one INTERRUPTED closes the family; JSONL round-trips.
      val after = rows(store, sid)
      assertEquals(sizeBefore + 1, after.size)
      val mark = after.last()
      assertEquals("system", mark.kind)
      assertEquals(interruptedMarkerText("L-new"), mark.text)
      assertTrue(findDanglingRunIds(after).isEmpty())
      assertJsonlRoundTrip(after)
    } finally {
      first.session.close()
    }
    // Second open: zero new.
    val sizeAfterFirst = rows(store, sid).size
    val second = f.open(endpoint(), sid)
    try {
      assertEquals(sizeAfterFirst, rows(store, sid).size)
      assertTrue(findDanglingRunIds(rows(store, sid)).isEmpty())
    } finally {
      second.session.close()
    }
  }
}
