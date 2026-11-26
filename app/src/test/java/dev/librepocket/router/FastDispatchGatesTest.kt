package dev.librepocket.router

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #178 Stage 1–2 gates on the WIRED fast-lane construction. TEST BOUNDARY:
 * there is still NO production caller and no `executionReady` flip — these
 * tests exercise `FastDispatcher.dispatchFresh` directly with a fake backend,
 * a real [InMemoryPolicyStore] (or a deliberate fake store), a real
 * [FastRouter] decision and a recording audit sink. They are NOT device E2E.
 */
class FastDispatchGatesTest {

    private val lspOn = ProjectionContext(userSwitches = mapOf("lsp" to true))
    private val identity = "test-session-identity"

    private class CountingBackend : LspTools.Backend {
        val invocations = AtomicInteger(0)
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            return listOf(LspTools.Symbol("$query:symbol", "function", "src/Main.kt", 1))
        }
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    private class RecordingAudit : FastToolAuditSink {
        val records = mutableListOf<FastToolAuditRecord>()
        var attempts = AtomicInteger(0)
        override suspend fun record(record: FastToolAuditRecord): Boolean {
            attempts.incrementAndGet()
            records.add(record)
            return true
        }
    }

    /** Fail-injection seam: first [allowCalls] evaluations delegate, then DENY. */
    private class RevokingPolicy(
        private val delegate: PolicyStore,
        private val allowCalls: Int,
    ) : PolicyStore by delegate {
        private val calls = AtomicInteger()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
            return if (calls.incrementAndGet() <= allowCalls) {
                delegate.evaluateFresh(action, resource)
            } else {
                PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
            }
        }
    }

    private class ThrowingPolicy(private val delegate: PolicyStore) : PolicyStore by delegate {
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
            throw IllegalStateException("policy backend boom (must never ride the outcome)")
    }

    /** Counts fresh evaluations without changing verdicts. */
    private class CountingPolicy(private val delegate: PolicyStore) : PolicyStore by delegate {
        val freshCalls = AtomicInteger(0)
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
            freshCalls.incrementAndGet()
            return delegate.evaluateFresh(action, resource)
        }
    }

    private suspend fun allowPolicy(): InMemoryPolicyStore = InMemoryPolicyStore(emptyList()).also {
        it.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 10))
    }

    private fun executors(backend: CountingBackend) =
        mapOf(LspTools.SYMBOLS_NAME to LspSymbolsExecutor(backend))

    private fun dispatcher(
        backend: CountingBackend,
        ctxSupplier: () -> ProjectionContext = { lspOn },
        policy: PolicyStore,
        audit: FastToolAuditSink = RecordingAudit(),
    ): FastDispatcher = FastDispatcher(
        executors = executors(backend),
        ctxSupplier = ctxSupplier,
        policy = policy,
        auditSink = audit,
    )

    private fun fastDecision() = FastRouter().decide("查 Foo 符號", lspOn, "s1", 1)

    private fun skipped(out: FastDispatchOutcome): FastDispatchOutcome.Skipped {
        val s = out as? FastDispatchOutcome.Skipped
        assertNotNull("expected typed Skipped, got $out", s)
        return s!!
    }

    @Test
    fun freshCtxAndPolicyAllow_executesOnceWritesExactlyOneAudit() = runBlocking {
        val backend = CountingBackend()
        val ctxCalls = AtomicInteger()
        val audit = RecordingAudit()
        val policy = CountingPolicy(allowPolicy())
        val reverifiedCtx = lspOn.copy()
        val delegate = LspSymbolsExecutor(backend)
        var executorCtx: ProjectionContext? = null
        val recordingExecutor = object : FastToolExecutor by delegate {
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
                executorCtx = ctx
                return delegate.execute(call, ctx)
            }
        }
        val d = FastDispatcher(
            executors = mapOf(LspTools.SYMBOLS_NAME to recordingExecutor),
            ctxSupplier = { if (ctxCalls.incrementAndGet() == 1) lspOn else reverifiedCtx },
            policy = policy,
            auditSink = audit,
        )

        val decision = fastDecision()
        val snap = d.confirm(decision, identity)
        assertNotNull("ALLOW policy must mint a confirmation snapshot", snap)
        val out = d.dispatchFresh(decision, snap!!, identity)

        assertTrue("fresh ALLOW must execute (got $out)", out is FastDispatchOutcome.Executed)
        assertSame("executor must use the reverified projection context", reverifiedCtx, executorCtx)
        assertEquals(1, backend.invocations.get())
        // fresh ctx at dispatch + fresh ctx at post-confirm reverify (>=2);
        // the decision's own snapshot was built separately by FastRouter.
        assertTrue("ctxSupplier must be re-read per dispatch: ${ctxCalls.get()}", ctxCalls.get() >= 2)
        assertTrue(
            "tool.exec policy must be freshly evaluated per dispatch: ${policy.freshCalls.get()}",
            policy.freshCalls.get() >= 2,
        )
        assertEquals(1, audit.records.size)
        val record = audit.records.single()
        assertEquals("ALLOW", record.decision)
        assertEquals("lsp.symbols", record.tool)
        assertEquals(decision.header, record.header)
        assertEquals(snap.paramsHash, record.paramsHash)
        assertTrue(record.redactedArguments.length <= 500)
    }

    @Test
    fun paramsHashIsDeterministicAndBindsSnapshotOperation() = runBlocking {
        val backend = CountingBackend()
        val d = dispatcher(backend, policy = allowPolicy())
        val decision = fastDecision()
        val s1 = d.confirm(decision, identity)!!
        val s2 = d.confirm(decision, identity)!!
        assertEquals(s1.paramsHash, s2.paramsHash)
        assertEquals(64, s1.paramsHash.length)
        assertTrue("hex must be Locale-independent lowercase", s1.paramsHash.all { it in '0'..'9' || it in 'a'..'f' })

        assertNotEquals(s1.paramsHash, d.confirm(decision, "$identity-2")!!.paramsHash)
        assertNotEquals(
            s1.paramsHash,
            d.confirm(decision.copy(header = decision.header.copy(turnIndex = 2)), identity)!!.paramsHash,
        )
        assertNotEquals(
            s1.paramsHash,
            d.confirm(
                decision.copy(
                    toolCall = decision.toolCall!!.copy(argumentsJson = "{\"query\":\"Other\"}"),
                ),
                identity,
            )!!.paramsHash,
        )

        // Tampered hash must not ride into the executor: binding is enforced
        // by verifySnapshot, not merely stored in the snapshot.
        val audit = RecordingAudit()
        val d2 = dispatcher(backend, policy = allowPolicy(), audit = audit)
        val tampered = s1.copy(paramsHash = "0".repeat(64))
        assertEquals(
            "CONFIRMATION_INVALID",
            skipped(d2.dispatchFresh(decision, tampered, identity)).denyCode,
        )
        assertEquals(0, backend.invocations.get())
    }

    @Test
    fun askNeverDefaultsToAllow_andPolicyDenyIsTypedSkipWithZeroBackend() = runBlocking {
        // Default ruleset: tool.exec matches only `*:*` ASK — confirm must refuse.
        val askBackend = CountingBackend()
        val askPolicy = InMemoryPolicyStore()
        val askAudit = RecordingAudit()
        val askDispatcher = dispatcher(askBackend, policy = askPolicy, audit = askAudit)
        assertNull("ASK must not mint a snapshot", askDispatcher.confirm(fastDecision(), identity))
        val askOut = askDispatcher.decideAndDispatchFresh("查 Foo 符號", identity, "s1", 1)
        assertEquals("CONFIRMATION_INVALID", skipped(askOut.outcome).denyCode)
        assertEquals(0, askBackend.invocations.get())
        // 拒絕也必須留痕（issue #178 item 4）：ASK 不寫 audit 是回歸。
        assertEquals(1, askAudit.attempts.get())
        assertEquals("DENY:POLICY", askAudit.records.single().decision)

        // DENY after a valid confirmation: typed skip, zero backend, one audit.
        val backend = CountingBackend()
        val policy = allowPolicy()
        val audit = RecordingAudit()
        val d = dispatcher(backend, policy = policy, audit = audit)
        val snap = d.confirm(fastDecision(), identity)!!
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.DENY, 10))
        val out = d.dispatchFresh(fastDecision(), snap, identity)
        assertEquals("POLICY_DENIED", skipped(out).denyCode)
        assertFalse(skipped(out).attempted)
        assertEquals(0, backend.invocations.get())
        assertEquals(1, audit.records.size)
        assertEquals("DENY:POLICY", audit.records.single().decision)
    }

    @Test
    fun policyEvaluationFailure_isFailClosedTypedSkip() = runBlocking {
        val backend = CountingBackend()
        val audit = RecordingAudit()
        val d = dispatcher(
            backend,
            policy = ThrowingPolicy(allowPolicy()),
            audit = audit,
        )
        val out = d.dispatchFresh(
            fastDecision(),
            FastConfirmationSnapshot("lsp.symbols", identity, "{}", "hash", 0L, "s1", 1),
            identity,
        )
        assertEquals("POLICY_DENIED", skipped(out).denyCode)
        assertEquals(0, backend.invocations.get())
        assertEquals(1, audit.records.size)
    }

    @Test
    fun twoTurnSwitchPermissionAndPolicyFlips_neverReuseAnOldVerdict() = runBlocking {
        val backend = CountingBackend()
        var switchOn = true
        val audit = RecordingAudit()
        val policy = allowPolicy()
        val d = dispatcher(backend, ctxSupplier = {
            ProjectionContext(userSwitches = mapOf("lsp" to switchOn))
        }, policy = policy, audit = audit)

        // Turn 1: allow + switch on ⇒ snapshot bound to the exact ruleset.
        val snap1 = d.confirm(fastDecision(), identity)!!
        assertTrue(d.dispatchFresh(fastDecision(), snap1, identity) is FastDispatchOutcome.Executed)
        assertEquals(1, backend.invocations.get())

        // Turn 2: flip policy to a DIFFERENT ALLOW ruleset (still ALLOW, new
        // revision). The old verdict/snapshot must not ride the new ruleset.
        policy.removeRule("tool.exec:lsp.symbols")
        policy.setRule(PolicyRule("tool.exec:*", Verdict.ALLOW, 10))
        val stale = d.dispatchFresh(fastDecision(), snap1, identity)
        assertEquals("CONFIRMATION_INVALID", skipped(stale).denyCode)
        assertEquals(1, backend.invocations.get())

        // Fresh snapshot in the new ruleset executes; that snapshot is reusable
        // while policy stays put but NOT after a switch/permission flip.
        val snap2 = d.confirm(fastDecision(), identity)!!
        assertTrue(d.dispatchFresh(fastDecision(), snap2, identity) is FastDispatchOutcome.Executed)
        assertEquals(2, backend.invocations.get())
        switchOn = false
        // Switch/permission flip ⇒ fresh projection is non-NATIVE ⇒ typed skip.
        assertEquals(
            dev.librepocket.tool.DenyReason.USER_DISABLED,
            skipped(d.dispatchFresh(fastDecision(), snap2, identity)).reasonCode,
        )
        assertEquals(2, backend.invocations.get())
        switchOn = true
        assertTrue(d.dispatchFresh(fastDecision(), snap2, identity) is FastDispatchOutcome.Executed)
        assertEquals(3, backend.invocations.get())
    }

    @Test
    fun revokeRacingBetweenConfirmAndDispatch_skipsWithZeroBackend() = runBlocking {
        val backend = CountingBackend()
        val audit = RecordingAudit()
        val d = dispatcher(
            backend,
            policy = RevokingPolicy(allowPolicy(), allowCalls = 1),
            audit = audit,
        )
        val snap = d.confirm(fastDecision(), identity)!! // consumes the single ALLOW
        val out = d.dispatchFresh(fastDecision(), snap, identity)
        assertEquals("POLICY_DENIED", skipped(out).denyCode)
        assertEquals(0, backend.invocations.get())
        assertEquals("DENY:POLICY", audit.records.single().decision)
    }

    @Test
    fun cancellationBeforeDispatch_propagatesAndNeverEntersExecutor() = runBlocking {
        val backend = CountingBackend()
        val audit = RecordingAudit()
        val d = dispatcher(backend, policy = allowPolicy(), audit = audit)
        val snap = d.confirm(fastDecision(), identity)!!
        val cancelled = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withContext(cancelled) { d.dispatchFresh(fastDecision(), snap, identity) }
            }
        }
        assertEquals(0, backend.invocations.get())
        assertEquals(0, audit.attempts.get())
    }

    @Test
    fun auditFailureAndAuditThrow_areTypedSkipsWithZeroExecution() = runBlocking {
        val backend = CountingBackend()
        // FastToolAuditSink.Boolean = 寫入是否成功（true=success），不是「是否故障」。
        var succeed = true
        val sink = object : FastToolAuditSink {
            override suspend fun record(record: FastToolAuditRecord): Boolean = succeed
        }
        val d = dispatcher(backend, policy = allowPolicy(), audit = sink)
        val snap = d.confirm(fastDecision(), identity)!!
        succeed = false // 寫入失敗 ⇒ AUDIT_ERROR typed skip、零執行
        assertEquals("AUDIT_ERROR", skipped(d.dispatchFresh(fastDecision(), snap, identity)).denyCode)
        assertEquals(0, backend.invocations.get())

        val throwingBackend = CountingBackend()
        val throwing = object : FastToolAuditSink {
            override suspend fun record(record: FastToolAuditRecord): Boolean =
                throw IllegalStateException("room boom")
        }
        val d2 = dispatcher(throwingBackend, policy = allowPolicy(), audit = throwing)
        val snap2 = d2.confirm(fastDecision(), identity)!!
        assertEquals("AUDIT_ERROR", skipped(d2.dispatchFresh(fastDecision(), snap2, identity)).denyCode)
        assertEquals(0, throwingBackend.invocations.get())
    }

    @Test
    fun auditCancellation_propagatesWithZeroExecution() = runBlocking {
        val backend = CountingBackend()
        val sink = object : FastToolAuditSink {
            override suspend fun record(record: FastToolAuditRecord): Boolean =
                throw CancellationException("audit cancelled")
        }
        val d = dispatcher(backend, policy = allowPolicy(), audit = sink)
        val snap = d.confirm(fastDecision(), identity)!!
        assertThrows(CancellationException::class.java) {
            runBlocking { d.dispatchFresh(fastDecision(), snap, identity) }
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test
    fun auditArgumentsAreRedactedBeforeStorage() = runBlocking {
        val backend = CountingBackend()
        val audit = RecordingAudit()
        val d = dispatcher(backend, policy = allowPolicy(), audit = audit)
        // Known card pattern from RedactorTest — must be redacted in audit storage.
        val secret = "4539 1488 0343 6467"
        val decision = FastRouter().decide("查 Foo 符號", lspOn, "s1", 1).copy(
            toolCall = ToolCall("lsp.symbols", "{\"query\":\"card $secret\"}", "call-1"),
        )
        val snap = d.confirm(decision, identity)!!
        d.dispatchFresh(decision, snap, identity)
        val json = audit.records.single().toAuditJson()
        assertFalse("raw secret must never reach audit storage: $json", json.contains(secret))
        assertTrue(json.contains("tool.exec"))
        assertEquals(1, backend.invocations.get())
    }

    @Test
    fun roomAuditSinkUsesExistingTranscriptToolKind_andFailsClosedWithoutSession() = runBlocking {
        // No new DAO/entity/table/migration: the sink appends through the
        // existing SessionStore transcript path with the existing `tool` kind.
        val store = FakeSessionStore()
        val sid = store.createSession("audit", "model")
        val sink = FastToolRoomAuditSink(store)
        val header = fastDecision().header.copy(sessionId = sid)
        val record = FastToolAuditRecord(
            header = header,
            tool = "lsp.symbols",
            decision = "ALLOW",
            modelVisibleTools = header.visibleTools,
            projectedTools = header.projectedTools,
            redactedArguments = "{\"query\":\"foo\"}",
            paramsHash = "hash",
            policyRevision = 7L,
            sessionId = sid,
            turnIndex = 1,
        )
        assertTrue(sink.record(record))
        assertEquals(1, store.events.size)
        assertEquals("tool", store.events.single().kind)
        assertTrue(store.events.single().text.contains("tool.exec"))
        assertFalse("session-less audit must fail closed", sink.record(record.copy(sessionId = null)))
        assertEquals(1, store.events.size)
    }

    @Test
    fun wiredConstructionRequiresAllThreeParts_failFast() = runBlocking {
        val policy = allowPolicy()
        val supplier: () -> ProjectionContext = { lspOn }
        val audit = RecordingAudit()
        val halfWired = listOf(
            { FastDispatcher(ctxSupplier = supplier) },
            { FastDispatcher(policy = policy) },
            { FastDispatcher(auditSink = audit) },
            { FastDispatcher(ctxSupplier = supplier, policy = policy) },
            { FastDispatcher(ctxSupplier = supplier, auditSink = audit) },
            { FastDispatcher(policy = policy, auditSink = audit) },
        )
        halfWired.forEach { build ->
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
        // All three present is the only legal wired construction.
        assertNotNull(FastDispatcher(ctxSupplier = supplier, policy = policy, auditSink = audit))
    }
}
