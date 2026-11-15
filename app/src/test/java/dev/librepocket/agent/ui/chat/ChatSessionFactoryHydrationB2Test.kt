package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.chat.HistoryWindowCap
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.ToolCall
import dev.librepocket.provider.validateToolPairing
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class B2TailFakeProvider(
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

/**
 * B2 (#16) hydration-tail unification red pins (must FAIL on pre-B2 code where
 * openImpl travels MaxMessages(2000), must PASS after B2 unifies to DEFAULT).
 *
 * - [open_resumeUsesDefaultWindow]: 300 tiny rows (< 2000 DB tail, > 200 DEFAULT)
 *   must window to DEFAULT (199 request rows after the user-boundary snap),
 *   not ship 301.
 * - [createAndOpenShareDefaultWindow]: create with 250-row initialHistory and
 *   open with 250 DB rows must agree (both DEFAULT).
 *
 * (Red pins kept green through the #16 turn-boundary fix: the DEFAULT *window
 * constant* is unchanged (200), but the naive cut it produces is re-aligned to
 * the next user row, so the shipped request carries 199 rows here.)
 *
 * B2 red-run evidence lives in the PR description; this file pins post-B2
 * semantics only.
 */
class ChatSessionFactoryHydrationB2Test {
  private fun sampleEndpoint() = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = "gpt-4o-mini",
    apiKeyRef = "provider_key/preset:openai",
  )

  private fun factoryWith(
    transcripts: FakeSessionStore,
    provider: B2TailFakeProvider,
  ) = ChatSessionFactory(
    policy = allowKeyReadPolicy(),
    vaultSource = VaultSource {
      val vault = EncryptedPrefsVault(InMemoryPrefs())
      vault.putKey("preset:openai", "test-key-12345678".toCharArray())
      vault
    },
    buildProvider = { _, _ -> provider },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = transcripts
    },
  )

  private fun event(
    sid: String,
    runId: String,
    kind: String,
    text: String,
  ) = TranscriptEvent(
    sessionId = sid,
    runId = runId,
    kind = kind,
    text = text,
    createdAt = 1L,
  )

  @Test fun open_resumeUsesDefaultWindow() = runBlocking {
    val transcripts = FakeSessionStore()
    val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
    // 150 turns = 300 tiny rows (~3-5 chars each, char leg never binds).
    for (i in 0 until 150) {
      transcripts.appendEvent(event(sid, "r$i", "user", "u$i"))
      transcripts.appendEvent(event(sid, "r$i", "assistant", "a$i"))
    }
    val provider = B2TailFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "fresh"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
    try {
      // DB tail fits (300 < 2000): no DB-tail drop.
      assertEquals(0, opened.historyDroppedCount)
      opened.session.send("q-new")
      val req = provider.seenRequests.single()
      // B2: DEFAULT count leg (200) windows the 300-row prefix + live.
      // Prefix windows 300 -> 200, combined 200 + live = 201 -> naive cut
      // drops the leading user row u50; the user-boundary snap re-aligns the
      // naive cut onto the next user row (dropping the assistant a50 behind
      // it) -> 199 rows.
      assertEquals(
        "resume must travel DEFAULT (200, i.e. 199 request rows after the user-boundary snap), not 2000-tail (300+1=301), got ${req.messages.size}",
        199,
        req.messages.size,
      )
      // Re-anchored post-B2 content pin: kept prefix is rows 100..299 (u50..a149);
      // combined cut (naive 1) drops the leading user row u50; the
      // user-boundary snap then re-aligns over the leading assistant row a50
      // onto the next user row (u51) -> first shipped row is "u51".
      assertEquals("u51", req.messages.first().text)
      assertTrue(req.messages.last().text.startsWith("q-new"))
    } finally {
      opened.session.close()
    }
  }

  @Test fun createAndOpenShareDefaultWindow() = runBlocking {
    // Create path: 250-row fork-carried prefix via initialHistory.
    val createProvider = B2TailFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "fresh"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val createStore = FakeSessionStore()
    val createFactory = factoryWith(createStore, createProvider)
    val prefix = (0 until 250).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "m$i")
    }
    val created = createFactory.create(sampleEndpoint(), "t", prefix)
    try {
      created.session.send("q-new")
      val createReq = createProvider.seenRequests.single()
      val createSize = createReq.messages.size

      // Open path: same 250 rows via DB.
      val transcripts = FakeSessionStore()
      val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
      for (i in 0 until 125) {
        transcripts.appendEvent(event(sid, "r$i", "user", "m${2 * i}"))
        transcripts.appendEvent(event(sid, "r$i", "assistant", "m${2 * i + 1}"))
      }
      val openProvider = B2TailFakeProvider {
        flow {
          emit(StreamEvent.TextDelta(0, 0, "fresh"))
          emit(StreamEvent.Done("stop"))
        }
      }
      val opened = factoryWith(transcripts, openProvider).open(sampleEndpoint(), sid)
      try {
        opened.session.send("q-new")
        val openReq = openProvider.seenRequests.single()
        assertEquals(
          "create and resume must share one window (DEFAULT), got create=$createSize open=${openReq.messages.size}",
          createSize,
          openReq.messages.size,
        )
        // Create/open both: combined 201 -> cut 1 (m50, user) is dropped by the
        // count leg; the retained head m51 (assistant) is dropped by the
        // user-boundary snap -> first = m52.
        assertEquals(199, openReq.messages.size)
        // Re-anchored post-B2 content pins: rows m52..m249 + live ship first
        // (m52 is the first row after the combined-window count-leg cut and
        // the user-boundary snap), on BOTH paths.
        assertEquals("m52", createReq.messages.first().text)
        assertEquals("m52", openReq.messages.first().text)
        assertEquals(HistoryWindowCap.DEFAULT_MAX_MESSAGES, 200)
      } finally {
        opened.session.close()
      }
    } finally {
      created.session.close()
    }
  }

  /** Fixed-width fixture row: `width` chars exactly, prefix `m<i>:`. */
  private fun fixedWidthRow(i: Int, width: Int): String =
    ("m$i:" + "x".repeat(width)).take(width)

  @Test fun open_underCountOverCharBudget_windowsByCharLeg() = runBlocking {
    val transcripts = FakeSessionStore()
    val sid = transcripts.createSession("char leg", "preset:openai@https://api.openai.com/gpt-4o-mini")
    // 150 rows x 500 chars = 75_000 chars > 60k (char leg binds), 150 rows < 200
    // (count leg does not), 150 < 2000 (no DB-tail omission).
    for (i in 0 until 150) {
      val kind = if (i % 2 == 0) "user" else "assistant"
      transcripts.appendEvent(event(sid, "r$i", kind, fixedWidthRow(i, 500)))
    }
    val provider = B2TailFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "fresh"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
    try {
      // DB tail fits (150 < 2000): no DB-tail drop.
      assertEquals(0, opened.historyDroppedCount)
      opened.session.send("q-new")
      val req = provider.seenRequests.single()
      // Arithmetic (DEFAULT = Bounded(200, 60_000)):
      // construction: charWindowStart keeps the newest 120 full rows
      //   (120 x 500 = 60_000; a 121st row would reach 60_500 > 60_000, and the
      //   kept>0 rule never drops the NEWEST row) -> rows 30..149 survive;
      //   row 30 is even (user), so the user-boundary snap is a no-op.
      // buildRequest: combined = rows 30..149 + live("q-new", 5) = 121 rows;
      //   char window from the newest side: q-new (chars=5, kept=1), then rows
      //   149..32 (118 rows -> chars=59_005, kept=119); row 31 fits
      //   (59_505 <= 60_000, kept=120); row 30 would reach 60_005 > 60_000
      //   -> naive cut = 1 (drop row 30). user-boundary snap re-aligns over the
      //   leading assistant row 31 onto row 32 (user) -> base = rows 32..149
      //   + q-new = 119 rows.
      assertEquals(
        "char leg must window 75k chars to the 60k budget, got ${req.messages.size}",
        119,
        req.messages.size,
      )
      assertTrue(req.messages.first().text.startsWith("m32:"))
      assertTrue(req.messages.first().role == "user")
      assertTrue(req.messages.last().text.startsWith("q-new"))
      // Window bound on the carried prefix (all rows except the live one).
      assertTrue(req.messages.dropLast(1).sumOf { it.text.length } <= 60_000)
      // Total is prefix + live with the ephemeral time block suffixed to the
      // live row only (block <= ~200 chars; 1_000 slack).
      val totalChars = req.messages.sumOf { it.text.length }
      assertTrue("total chars must stay within the window + time block, was $totalChars", totalChars <= 60_000 + 1_000)
      // Keeping enough prefix to fit 60k leaves the 100k send-time cap untouched.
      assertEquals(0, opened.session.requestTrimmedCount.value)
      // contextOmission = 150 loaded + 1 live - 119 shipped = 32
      // (30 at the construction window + 2 at the combined window's naive cut
      // and user-boundary snap).
      assertEquals(32, opened.session.contextOmission.value)
    } finally {
      opened.session.close()
    }
  }

  @Test fun open_bothLegsAtSameCut_windowsToCharAndCountCap() = runBlocking {
    val transcripts = FakeSessionStore()
    val sid = transcripts.createSession("both legs", "preset:openai@https://api.openai.com/gpt-4o-mini")
    // 201 rows x 300 chars = 60_300 > 60k (char leg binds), 201 > 200 (count leg
    // binds at the same cut: count leg keeps rows 1..200), 201 < 2000 (no DB-tail drop).
    for (i in 0 until 201) {
      val kind = if (i % 2 == 0) "user" else "assistant"
      transcripts.appendEvent(event(sid, "r$i", kind, fixedWidthRow(i, 300)))
    }
    val provider = B2TailFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "fresh"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
    try {
      assertEquals(0, opened.historyDroppedCount)
      opened.session.send("q-new")
      val req = provider.seenRequests.single()
      // Arithmetic (DEFAULT = Bounded(200, 60_000)):
      // construction: count leg (201 -> keep rows 1..200, cut=1) and char leg
      //   (newest 200 rows sum exactly 60_000; not > 60_000, cut=1) AGREE at
      //   cut=1. User-boundary snap re-aligns over the leading assistant row 1
      //   onto row 2 (user) -> kept rows 2..200 (199 rows).
      // buildRequest: combined = rows 2..200 + live("q-new", 5) = 200 rows;
      //   count leg (200 -> keep all) and char leg (199 x 300 + 5 = 59_705,
      //   all fit) both cut 0 -> base = all 200 rows.
      // Construction-time user snap is not directly observable from this fixture
      // (the combined dual window masks it); the capped prefix keeps 199 rows —
      // this test's discriminating power is in the combined window.
      assertEquals(
        "both legs bind at the same cut at load; the combined window then keeps all 200 rows, got ${req.messages.size}",
        200,
        req.messages.size,
      )
      assertTrue(req.messages.first().text.startsWith("m2:"))
      assertTrue(req.messages.first().role == "user")
      assertTrue(req.messages.last().text.startsWith("q-new"))
      assertTrue(req.messages.dropLast(1).sumOf { it.text.length } <= 60_000)
      val totalChars = req.messages.sumOf { it.text.length }
      assertTrue("total chars must stay within the window + time block, was $totalChars", totalChars <= 60_000 + 1_000)
      assertEquals(0, opened.session.requestTrimmedCount.value)
      // contextOmission = 201 loaded + 1 live - 200 shipped = 2
      // (1 construction cut + 1 user-boundary snap; the combined window drops 0).
      assertEquals(2, opened.session.contextOmission.value)
    } finally {
      opened.session.close()
    }
  }

  @Test fun createAndOpenShareCharLegWindow() = runBlocking {
    // Create path: 250-row fork-carried prefix via initialHistory (char leg binds).
    val createProvider = B2TailFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "fresh"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val createStore = FakeSessionStore()
    val createFactory = factoryWith(createStore, createProvider)
    val prefix = (0 until 250).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = fixedWidthRow(i, 500))
    }
    val created = createFactory.create(sampleEndpoint(), "t", prefix)
    try {
      created.session.send("q-new")
      val createReq = createProvider.seenRequests.single()
      // Open path: same 250 rows via DB (250 x 500 = 125_000 chars).
      val transcripts = FakeSessionStore()
      val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
      for (i in 0 until 125) {
        transcripts.appendEvent(event(sid, "r$i", "user", fixedWidthRow(2 * i, 500)))
        transcripts.appendEvent(event(sid, "r$i", "assistant", fixedWidthRow(2 * i + 1, 500)))
      }
      val openProvider = B2TailFakeProvider {
        flow {
          emit(StreamEvent.TextDelta(0, 0, "fresh"))
          emit(StreamEvent.Done("stop"))
        }
      }
      val opened = factoryWith(transcripts, openProvider).open(sampleEndpoint(), sid)
      try {
        // DB tail fits (250 < 2000): no DB-tail drop.
        assertEquals(0, opened.historyDroppedCount)
        opened.session.send("q-new")
        val openReq = openProvider.seenRequests.single()
        // Arithmetic (same on both paths):
        // construction: count leg (250 -> cut 50) and char leg (newest 120 full
        //   rows = 60_000; a 121st would exceed -> charStart = 250 - 120 = 130)
        //   -> naive cut = max(50, 130) = 130; row 130 is even (user), so the
        //   user-boundary snap is a no-op -> capped rows 130..249 (120 rows).
        // buildRequest: combined = rows 130..249 + live("q-new", 5) = 121 rows;
        //   char window from the newest side: q-new (chars=5), then rows
        //   249..132 (118 rows -> chars=59_005, kept=119); row 131 fits
        //   (59_505 <= 60_000, kept=120); row 130 would reach 60_005 > 60_000
        //   -> naive cut = 1. user-boundary snap re-aligns over the leading
        //   assistant row 131 onto row 132 (user) -> base = rows 132..249
        //   + q-new = 119 rows.
        assertEquals(119, createReq.messages.size)
        // contextOmission = 250 initial + 1 live - 119 shipped = 132 on the
        // create path (130 construction window + 2 combined naive cut and snap).
        assertEquals(132, created.session.contextOmission.value)
        assertEquals(
          "create and resume must share one char leg window, got create=${createReq.messages.size} open=${openReq.messages.size}",
          createReq.messages.size,
          openReq.messages.size,
        )
        assertEquals("m132:", createReq.messages.first().text.take(5))
        assertEquals("m132:", openReq.messages.first().text.take(5))
        assertTrue(createReq.messages.last().text.startsWith("q-new"))
        assertTrue(openReq.messages.last().text.startsWith("q-new"))
        assertTrue(createReq.messages.sumOf { it.text.length } <= 60_000 + 1_000)
        assertTrue(openReq.messages.sumOf { it.text.length } <= 60_000 + 1_000)
        // contextOmission = 250 loaded + 1 live - 119 shipped = 132 on the
        // open path (same construction + combined accounting as create).
        assertEquals(132, opened.session.contextOmission.value)
      } finally {
        opened.session.close()
      }
    } finally {
      created.session.close()
    }
  }

  @Test fun create_giantLiveRow_survivesCharLeg() = runBlocking {
    // A single row > 60_000 chars reaches the request whole (never split):
    // the kept>0 safeguard always retains the NEWEST (live) row even over the
    // char budget. (A >60k row can never arrive via factory.open: the DB read
    // path truncates every hydrated row to MODEL_HISTORY_MAX_CHARS_PER_MESSAGE;
    // and an over-budget OLD row is evicted oldest-first by design — the
    // newest-row-survives shape is pinned in
    // TurnControllerDefaultCapTest.charLegGiantNewestSingleRowSurvives, the
    // old-row-evicted shape in TurnControllerDefaultCapTest.charLegGiantOldRowEvictedBeforeNewestSmall.)
    val provider = B2TailFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "fresh"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val store = FakeSessionStore()
    val factory = factoryWith(store, provider)
    val created = factory.create(sampleEndpoint(), "giant", emptyList())
    try {
      created.session.send("g".repeat(61_000))
      val req = provider.seenRequests.single()
      assertEquals(1, req.messages.size)
      // The 61k-char row ships whole (never truncated, never split); the
      // ephemeral time block is suffixed to its tail.
      assertTrue(req.messages.single().text.startsWith("g".repeat(61_000)))
    } finally {
      created.session.close()
    }
  }

  @Test fun create_straddlingToolBatchSurvivesWindowSnap() = runBlocking {
    // T3: a fully-formed tool-call batch straddling the DEFAULT window's naive
    // cut (via the char leg) must be dropped whole — never shipped half. The
    // factory -> controller chain must open on a user row and carry no orphan
    // tool / dangling toolCalls rows.
    // DB hydration cannot carry structured tool batches (loadModelHistory only
    // projects user/assistant text), so the tool-batch fixture travels the
    // create() + initialHistory path (fork-carried prefix).
    //
    // Fixture: 3 pad users + 84 (user, assistant-call, tool) triples = 255 rows,
    // each exactly 700 chars (char leg binds: newest 85 rows = 59_500 chars,
    // 86 rows = 60_200 > 60_000 -> charStart = 170). Row 170 is the tool row of
    // triple 55 (rows 168 user / 169 assistant call / 170 tool result), so the
    // naive cut lands INSIDE the pair; the pair snap must push it forward to the
    // end of the batch (171 = next user row).
    fun padded700(text: String): String = (text + "x".repeat(700)).take(700)
    val prefix = ArrayList<ChatMessage>(255)
    for (i in 0 until 3) prefix += ChatMessage(role = "user", text = padded700("pre-$i"))
    for (k in 0 until 84) {
      prefix += ChatMessage(role = "user", text = padded700("u$k"))
      prefix += ChatMessage(
        role = "assistant",
        text = padded700("call$k"),
        toolCalls = listOf(ToolCall(id = "c$k", name = "lsp.symbols", argumentsJson = """{"query":"q$k"}""")),
      )
      prefix += ChatMessage(role = "tool", text = padded700("r$k"), toolCallId = "c$k")
    }
    assertEquals(255, prefix.size)
    val provider = B2TailFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "fresh"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val store = FakeSessionStore()
    val factory = factoryWith(store, provider)
    val created = factory.create(sampleEndpoint(), "tool-batch", prefix)
    try {
      created.session.send("q-new")
      val req = provider.seenRequests.single()
      // Arithmetic: construction char window keeps the newest 85 rows
      // (rows 170..254, 85 x 700 = 59_500); naive cut = 170 lands ON the tool
      // row of the triple (168..170); the pair snap pushes the cut to 171,
      // dropping the whole straddling batch. Row 171 = "u56" (user), so the
      // user-boundary snap keeps it. capped history = rows 171..254 = 84 rows;
      // combined with the live row (85 rows) fits both legs -> 85 ship.
      assertEquals(
        "the straddling batch must drop whole; window then keeps rows 171..254 + live, got ${req.messages.size}",
        85,
        req.messages.size,
      )
      assertEquals("user", req.messages.first().role)
      assertTrue(
        "first row must be the user row right after the snapped batch, got \"${req.messages.first().text.take(8)}\"",
        req.messages.first().text.startsWith("u56"),
      )
      // No HALF pair: only the straddling batch (rows 168..170) is absent;
      // complete triples 56..83 survive whole (legal pairs — the point of
      // pair atomicity). So the pin is: triple 55 is gone entirely, and the
      // shipped shape passes the pairing gate.
      assertTrue(req.messages.none { it.text.startsWith("r55") || it.text.startsWith("call55") || it.text.startsWith("u55") })
      assertTrue(req.messages.last().text.startsWith("q-new"))
      // Complete triples 56..83 survive whole: 28 calls + 28 results.
      assertEquals(28, req.messages.count { it.toolCalls.isNotEmpty() })
      assertEquals(28, req.messages.count { it.role == "tool" })
      // Pairing gate equivalence: the shipped shape passes the same validator the
      // provider applies downstream — tool positions resolve to their call ids
      // (56..83), everything else null; any orphan/dangling batch throws.
      val pairing = validateToolPairing(req.messages, allowChatBackfill = true)
      assertEquals(
        "every retained tool row must resolve to its own call id",
        (56..83).map { "c$it" },
        pairing.filterNotNull(),
      )
      // 255 (initial) + 1 (live) - 85 (shipped) = 171 rows omitted across the
      // construction window (pair-snapped) + combined legs; the DB-tail leg is 0
      // on the create path (nothing to load) and the send-time trim is 0.
      assertEquals(171, created.session.contextOmission.value)
      assertEquals(0, created.session.requestTrimmedCount.value)
    } finally {
      created.session.close()
    }
  }
}
