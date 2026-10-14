package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.JsonlCodec
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class FiveDimProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
  val seen = java.util.Collections.synchronizedList(mutableListOf<ChatRequest>())
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    seen.add(request)
    handler(request).collect { emit(it) }
  }
  override suspend fun listModels(): List<String> = emptyList()
}

/**
 * P1-5 Phase4: 五維斷言 (LateFailure五維模板 DB+JSONL+reopen+UI+flushDurable).
 *
 * 覆蓋: SwitchSnapshot / SnapshotAtomicity / ByteCap(除cleanFence) /
 * SameIdReopenFence / EarlyExit-supplement。每案 Given/When/Then、可注入gate、無固定sleep。
 * 實際覆蓋: SwitchSnapshot與SameIdReopenFence為全五維；ByteCap無UI (marker-only
 * ledger無回放消息故N/A)；Atomicity空ledger故無UI/flushDurable (N/A)；
 * EarlyExit僅2維supplement (seed + round-trip + loadEvents，本體由
 * ChatSameIdReopenEarlyExitTest.denyEarlyExit覆蓋)。
 *
 * 快照旗標閘: isFinal/isPartial/parentRunId/attemptIndex現會放行 (verbatim
 * copy) — 本檔以 assertSnapshotFlagsValid 在測試層擋下壞旗標 (正: 合法通過;
 * 反: 四種壞形各一例必須擋), 生產層驗證列為後續事項 (見檔尾註)。
 */
class SnapshotFiveDimPhase4Test {

  private val tmpDirs = ArrayList<File>()
  private val scopes = ArrayList<CoroutineScope>()
  private val chatViewModels = ChatTestViewModelStore()
  private val chatMain = ChatTestMainDispatcher()

  @Before fun setUp() = chatMain.install()

  @After fun tearDown() {
    try {
      chatMain.clearViewModels(chatViewModels)
    } finally {
      try {
        cancelAndJoinChatTestScopes(scopes)
      } finally {
        try {
          tmpDirs.forEach { it.deleteRecursively() }
        } finally {
          chatMain.resetAndClose()
        }
      }
    }
  }

  private fun onMain(block: () -> Unit) = runBlocking { chatMain.run(block) }

  private fun endpoint(model: String) = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = model,
    apiKeyRef = "provider_key/preset:openai",
  )

  private fun newStore(): EndpointStore {
    val dir = Files.createTempDirectory("p15-fivedim").toFile()
    tmpDirs.add(dir)
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    scopes.add(scope)
    return EndpointStore(
      PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "p15.preferences_pb") }),
    )
  }

  private fun factoryWith(transcripts: FakeSessionStore, fake: FiveDimProvider) = ChatSessionFactory(
    policy = allowKeyReadPolicy(),
    vaultSource = VaultSource {
      val v = EncryptedPrefsVault(InMemoryPrefs())
      v.putKey("preset:openai", "test-key-12345678".toCharArray())
      v
    },
    buildProvider = { _, _ -> fake },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = transcripts
    },
  )

  private fun newVm(store: EndpointStore, transcripts: FakeSessionStore, fake: FiveDimProvider): ChatViewModel {
    val vault = EncryptedPrefsVault(InMemoryPrefs())
    runBlocking { vault.putKey("preset:openai", "test-key-12345678".toCharArray()) }
    return chatViewModels.own(ChatViewModel(store, factoryWith(transcripts, fake), allowKeyReadPolicy()))
  }

  private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) error("timed out waiting for condition")
      Thread.sleep(10)
    }
  }

  private fun event(
    sid: String, runId: String, kind: String, text: String,
    isPartial: Boolean = false, isFinal: Boolean = true,
    parentRunId: String? = null, attemptIndex: Int? = null,
    failureReason: String? = null,
  ) = TranscriptEvent(
    sessionId = sid, runId = runId, kind = kind, text = text, createdAt = 1L,
    isPartial = isPartial, isFinal = isFinal, parentRunId = parentRunId,
    attemptIndex = attemptIndex, failureReason = failureReason,
  )

  private fun rows(store: FakeSessionStore, sid: String) =
    store.events.filter { it.sessionId == sid }.sortedBy { it.seq }

  /** 五維模板: DB已由各案自斷; 此補 JSONL+reopen+UI+flushDurable。 */
  private suspend fun assertJsonlReopenUiFlush(
    transcripts: FakeSessionStore, sid: String,
    vm: ChatViewModel?, expectUiTexts: List<String>?,
    expectFlushDurable: Boolean?,
  ) {
    // JSONL round-trip incl. linkage.
    for (e in rows(transcripts, sid)) {
      val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
      assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
      assertEquals("parentRunId must round-trip for ${e.kind}", e.parentRunId, back.parentRunId)
      assertEquals("attemptIndex must round-trip for ${e.kind}", e.attemptIndex, back.attemptIndex)
    }
    // Reopen reload equals (second open zero-new checked by callers).
    val reloaded = transcripts.loadEvents(sid, 0, 2000)
    assertEquals(
      rows(transcripts, sid).map { it.runId to it.text to it.kind },
      reloaded.map { it.runId to it.text to it.kind },
    )
    // UI messages.
    if (vm != null && expectUiTexts != null) {
      assertEquals(expectUiTexts, vm.messages.value.map { it.text })
    }
    // flushDurable via a live session reopen (durable proof, not fake true).
    if (expectFlushDurable != null) {
      val f = factoryWith(transcripts, FiveDimProvider { flow { emit(StreamEvent.TextDelta(0, 0, "x")); emit(StreamEvent.Done("stop")) } })
      val opened = f.open(endpoint("gpt-4o-mini"), sid)
      try {
        val durable = withTimeout(8_000) { opened.session.flushDurable() }
        assertEquals("flushDurable must be $expectFlushDurable", expectFlushDurable, durable)
      } finally {
        opened.session.close()
      }
    }
  }

  /**
   * 快照旗標閘 (測試層): 合法快照通過; 壞 isFinal/isPartial/parentRunId/
   * attemptIndex各一例必須擋 (fail with message)。生產 snapshotSwitchHistory
   * 現為verbatim copy (放行) — 本閘先在測試層擋下迴歸, 生產層 fail-closed
   * 驗證列為後續 (見檔尾註, 不在本PR改生產語義)。
   */
  private fun assertSnapshotFlagsValid(events: List<TranscriptEvent>) {
    for (e in events) {
      if (e.kind == "assistant") {
        // isFinal/isPartial一致性: non-final必須是partial (intermediate)。
        if (!e.isFinal) {
          check(e.isPartial) { "non-final assistant must be partial: $e" }
        }
        // parentRunId/attemptIndex一致性: 有parent必須有index; 首嘗parent null。
        if (e.parentRunId != null) {
          check(e.attemptIndex != null && e.attemptIndex!! > 0) {
            "parent-linked assistant must carry attemptIndex>0: $e"
          }
          check(e.runId != e.parentRunId) { "attempt runId must differ from parent: $e" }
        } else {
          check(e.attemptIndex == null || e.attemptIndex == 0) {
            "first-attempt assistant must have null/0 attemptIndex: $e"
          }
        }
      }
      if (e.kind == "reasoning") {
        check(e.text.isEmpty()) { "reasoning marker must have empty text: $e" }
      }
    }
  }

  // ---- SwitchSnapshot五維: cancel partial fork. ----

  @Test fun switchSnapshotCancelPartial_fiveDim() = runBlocking {
    // Given: 舊ledger (q0/a0 + q-cancel/half-cancel partial final)。
    val store = newStore()
    runBlocking { store.save(endpoint("gpt-4o-mini")) }
    val transcripts = FakeSessionStore()
    val fake = FiveDimProvider { flow { emit(StreamEvent.TextDelta(0, 0, "a-next")); emit(StreamEvent.Done("stop")) } }
    val factory = factoryWith(transcripts, fake)
    val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
    transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
    transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
    transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))
    transcripts.appendEvent(event(oldId, "L", "assistant", "half-cancelled", isPartial = true, attemptIndex = 0))
    // When: 同源fork。
    val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
    val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
    // Then DB: marker + 全prefix verbatim + 無dangling + 旗標閘通過。
    assertEquals(4, copied)
    val forked = rows(transcripts, newId)
    assertEquals(listOf("system", "user", "assistant", "user", "assistant"), forked.map { it.kind })
    assertSnapshotFlagsValid(forked.drop(1))
    assertTrue(findDanglingRunIds(forked).isEmpty())
    // UI: reopen回放半截 flagged。
    val vm = newVm(store, transcripts, fake)
    onMain { vm.openSession(newId) }
    awaitTrue { vm.currentSessionId.value == newId && vm.messages.value.size == 4 }
    assertEquals(listOf("q0", "a0", "q-cancel", "half-cancelled"), vm.messages.value.map { it.text })
    assertNull(vm.notice.value)
    // JSONL+reopen+flushDurable。
    assertJsonlReopenUiFlush(transcripts, newId, vm, listOf("q0", "a0", "q-cancel", "half-cancelled"), true)
    // Reopen backfill零新增。
    val before = forked.size
    val opened = factory.open(endpoint("gpt-4o-mini"), newId)
    try {
      assertEquals(before, rows(transcripts, newId).size)
    } finally {
      opened.session.close()
    }
    val opened2 = factory.open(endpoint("gpt-4o-mini"), newId)
    try {
      assertEquals(before, rows(transcripts, newId).size)
    } finally {
      opened2.session.close()
    }
  }

  // ---- SnapshotAtomicity五維: mid-copy失敗零列 + reopen乾淨. ----

  @Test fun snapshotAtomicityMidCopyFail_fiveDim() = runBlocking {
    // Given: 兩turn舊ledger (先不注入) + 快照時第2寫注入失敗。
    val transcripts = object : FakeSessionStore() {
      var calls = 0
      var failAt = -1
      override suspend fun appendEvent(e: TranscriptEvent): Long {
        calls++
        if (calls == failAt) throw java.io.IOException("injected snapshot fault")
        return super.appendEvent(e)
      }
    }
    val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
    transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
    transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
    transcripts.appendEvent(event(oldId, "L", "user", "q1"))
    transcripts.appendEvent(event(oldId, "L", "assistant", "a1"))
    val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
    transcripts.calls = 0
    transcripts.failAt = 2
    // When: snapshot中段失敗。
    try {
      snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
      fail("snapshot must throw on mid-copy failure")
    } catch (_: java.io.IOException) {
    }
    // Then DB: 零列 + 無dangling; JSONL空亦round-trip; reopen零INTR。
    assertTrue(rows(transcripts, newId).isEmpty())
    assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())
    // N/A UI/flushDurable: 空ledger無回放消息、無drain對象，故僅JSONL+reopen。
    assertJsonlReopenUiFlush(transcripts, newId, null, null, null)
    val factory = factoryWith(transcripts, FiveDimProvider { flow { emit(StreamEvent.TextDelta(0, 0, "a-next")); emit(StreamEvent.Done("stop")) } })
    val opened = factory.open(endpoint("gpt-4o-mini"), newId)
    try {
      assertTrue(rows(transcripts, newId).none { it.text.endsWith("interrupted") })
    } finally {
      opened.session.close()
    }
  }

  // ---- ByteCap五維 (非cleanFence): over-budget全棄 + marker. ----

  @Test fun byteCapOverBudget_fiveDim() = runBlocking {
    // Given: Issue #86 Phase B (PR slim): 11 turn x 100k (22列/2.2M字，列內、字超)。
    // 原 100 turn / 200列 / 20M 全量版移至 ChatSnapshotByteCapFullStressTest
    // (NIGHTLY_STRESS=1)。Fixture "x" 已是 redacted-identical，走預 redact 快道。
    val transcripts = FakeSessionStore()
    val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
    val huge = "x".repeat(100_000)
    repeat(11) { i ->
      transcripts.appendPreRedactedForBudgetTest(event(oldId, "r$i", "user", huge))
      transcripts.appendPreRedactedForBudgetTest(event(oldId, "r$i", "assistant", huge))
    }
    // When: fork。
    val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
    val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
    // Then DB: 零prefix + byte marker + 無dangling。
    assertEquals(0, copied)
    val forked = rows(transcripts, newId)
    assertEquals(2, forked.size)
    assertTrue(forked[0].text.startsWith("model switched "))
    assertTrue(isSnapshotIncompleteMarker(forked[1].text))
    assertTrue(forked[1].text.contains("byte budget exceeded"))
    assertTrue(findDanglingRunIds(forked).isEmpty())
    // N/A UI: marker-only ledger無回放消息，故僅JSONL+reopen+flushDurable。
    // JSONL+reopen+flushDurable (新ledger可drain)。
    assertJsonlReopenUiFlush(transcripts, newId, null, null, true)
    val factory = factoryWith(transcripts, FiveDimProvider { flow { emit(StreamEvent.TextDelta(0, 0, "a-next")); emit(StreamEvent.Done("stop")) } })
    val opened = factory.open(endpoint("gpt-4o"), newId)
    try {
      assertTrue(rows(transcripts, newId).none { it.text.endsWith("interrupted") })
    } finally {
      opened.session.close()
    }
  }

  // ---- SameIdReopenFence五維: settled double-reopen零新增. ----

  @Test fun sameIdSettledDoubleReopen_fiveDim() = runBlocking {
    // Given: settled ledger (user+assistant final)。
    val store = newStore()
    runBlocking { store.save(endpoint("gpt-4o-mini")) }
    val transcripts = FakeSessionStore()
    val fake = FiveDimProvider { flow { emit(StreamEvent.TextDelta(0, 0, "a-next")); emit(StreamEvent.Done("stop")) } }
    val factory = factoryWith(transcripts, fake)
    val sid = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
    transcripts.appendEvent(event(sid, "L", "user", "q0"))
    transcripts.appendEvent(event(sid, "L", "assistant", "a0"))
    assertSnapshotFlagsValid(rows(transcripts, sid))
    // When: double reopen。
    val o1 = factory.open(endpoint("gpt-4o-mini"), sid)
    try {
      assertTrue(rows(transcripts, sid).none { it.text.endsWith("interrupted") })
    } finally {
      o1.session.close()
    }
    val sizeAfterFirst = rows(transcripts, sid).size
    val o2 = factory.open(endpoint("gpt-4o-mini"), sid)
    try {
      // Then DB: 零新增 + 無dangling; UI: openSession回放。
      assertEquals(sizeAfterFirst, rows(transcripts, sid).size)
      assertTrue(findDanglingRunIds(rows(transcripts, sid)).isEmpty())
    } finally {
      o2.session.close()
    }
    val vm = newVm(store, transcripts, fake)
    onMain { vm.openSession(sid) }
    awaitTrue { vm.currentSessionId.value == sid && vm.messages.value.size == 2 }
    assertEquals(listOf("q0", "a0"), vm.messages.value.map { it.text })
    assertJsonlReopenUiFlush(transcripts, sid, vm, listOf("q0", "a0"), true)
  }

  // ---- EarlyExit 2維supplement: DENY early-exit + ALLOW attach (本體由denyEarlyExit覆蓋). ----

  @Test fun earlyExitDenyAllow_twoDimSupplement() {
    // Given: seeded user列 + counting session (flush成功)。
    val store = newStore()
    runBlocking { store.save(endpoint("gpt-4o-mini")) }
    val transcripts = FakeSessionStore()
    val sid = runBlocking { transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini") }
    runBlocking { transcripts.appendEvent(event(sid, "L", "user", "q-cancel")) }
    // When/Then: 由 ChatSameIdReopenEarlyExitTest.denyEarlyExit 覆蓋本體;
    // 此案僅補2維: JSONL round-trip + loadEvents等價 + 無dangling家族誤標。
    // N/A Factory.open/UI/flushDurable: 單user種子列無完整turn可hydrate/drain，本體測已 pin。
    runBlocking {
      for (e in rows(transcripts, sid)) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals(e.isFinal, back.isFinal)
        assertEquals(e.isPartial, back.isPartial)
      }
      val reloaded = transcripts.loadEvents(sid, 0, 100)
      assertEquals(
        rows(transcripts, sid).map { it.runId to it.text to it.kind },
        reloaded.map { it.runId to it.text to it.kind },
      )
      // DB: 單user列dangling為預期 (defer形, 非leak) — backfill閘由本體測。
      assertEquals(listOf("L"), findDanglingRunIds(rows(transcripts, sid)))
      assertTrue(rows(transcripts, sid).none { it.text.endsWith("interrupted") })
    }
  }

  // ---- 旗標閘正: 合法快照通過. ----

  @Test fun snapshotFlagsValid_acceptsLegal() {
    val good = listOf(
      event("s", "r0", "user", "q0"),
      event("s", "r0", "assistant", "a0", attemptIndex = 0),
      event("s", "L", "user", "q1"),
      event("s", "F1", "assistant", "half", isPartial = true, isFinal = false, parentRunId = "L", attemptIndex = 1),
      event("s", "F1", "reasoning", ""),
      event("s", "L", "system", "turn L cancelled"),
    )
    assertSnapshotFlagsValid(good)
  }

  // ---- 旗標閘反: 四種壞形必須擋. ----

  @Test fun snapshotFlagsValid_rejectsBadIsFinal() {
    // non-final卻非partial (intermediate必須partial)。
    val bad = listOf(event("s", "F1", "assistant", "half", isPartial = false, isFinal = false, parentRunId = "L", attemptIndex = 1))
    try {
      assertSnapshotFlagsValid(bad)
      fail("bad isFinal/isPartial must be rejected")
    } catch (_: IllegalStateException) {
    }
  }

  @Test fun snapshotFlagsValid_rejectsBadParent() {
    // 有parent卻無index。
    val bad = listOf(event("s", "F1", "assistant", "half", isPartial = true, isFinal = false, parentRunId = "L", attemptIndex = null))
    try {
      assertSnapshotFlagsValid(bad)
      fail("bad parentRunId/attemptIndex must be rejected")
    } catch (_: IllegalStateException) {
    }
  }

  @Test fun snapshotFlagsValid_rejectsSameIdParent() {
    // runId == parentRunId (fresh-id必須不同)。
    val bad = listOf(event("s", "L", "assistant", "half", isPartial = true, isFinal = false, parentRunId = "L", attemptIndex = 1))
    try {
      assertSnapshotFlagsValid(bad)
      fail("same-id parent must be rejected")
    } catch (_: IllegalStateException) {
    }
  }

  @Test fun snapshotFlagsValid_rejectsBadReasoningText() {
    val bad = listOf(event("s", "R1", "reasoning", "non-empty"))
    try {
      assertSnapshotFlagsValid(bad)
      fail("non-empty reasoning marker must be rejected")
    } catch (_: IllegalStateException) {
    }
  }
}

// 生產後續 (P1-5, 不在本PR改生產語義):
// snapshotSwitchHistory現為verbatim copy (放行壞旗標) — 本檔先以測試層閘
// assertSnapshotFlagsValid擋下迴歸 (壞快照無法通過五維案)。生產層 fail-closed
// 驗證 (copy前校验isFinal/isPartial/parentRunId/attemptIndex, 壞則全棄+
// incomplete marker) 列為後續Target, 需另PR + Room/假store對照 + 威脅模型更新。
