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
 *   must window to DEFAULT (200), not ship 301.
 * - [createAndOpenShareDefaultWindow]: create with 250-row initialHistory and
 *   open with 250 DB rows must agree (both DEFAULT).
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
      // Prefix windows 300 -> 200, combined 200 + live = 201 -> 200.
      assertEquals(
        "resume must travel DEFAULT (200), not 2000-tail (300+1=301), got ${req.messages.size}",
        200,
        req.messages.size,
      )
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
        assertEquals(200, openReq.messages.size)
        assertEquals(HistoryWindowCap.DEFAULT_MAX_MESSAGES, 200)
      } finally {
        opened.session.close()
      }
    } finally {
      created.session.close()
    }
  }
}
