package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class SlowThenFastListProvider(
    val entered: CountDownLatch = CountDownLatch(1),
    val release: CountDownLatch = CountDownLatch(1),
    val fastDone: CountDownLatch = CountDownLatch(1),
    val calls: AtomicInteger = AtomicInteger(0),
    /** A's ViewModel-coroutine Job, captured on the slow path inside listModels. */
    val aJob: CompletableDeferred<Job> = CompletableDeferred(),
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS

    /**
     * Deterministic slow/fast race: call order is fixed by the gate, not by
     * thread scheduling. Slow attempt A blocks in listModels until the test
     * releases it; fast attempt B returns immediately.
     *
     * Ordering is B-published-before-A-released: the test waits for B's
     * ViewModel-terminal value (testModels == 2 && !testing) BEFORE counting
     * down `release`, so A's stale writeback always lands last. Pre-guard
     * this surfaces as a stale 5-item write (RED); post-guard it is
     * discarded and the terminal stays 2 (GREEN). [calls] proves both ran.
     *
     * Prod structure (verified): `testConnection()` launches ONE
     * `viewModelScope.launch(Dispatchers.IO)` coroutine per attempt with no
     * child launch and no `withContext` Job-hop, so `listModels()` executes
     * INSIDE A's coroutine — `currentCoroutineContext().job` captured here
     * IS the same Job that performs the guard check plus the possible
     * `_form.update` writeback. Joining it therefore proves writeback
     * completion, which no provider-return latch can: [fastDone]/`slowDone`
     * fire at provider return (listModels finally), i.e. BEFORE the
     * ViewModel's `_form.update`. A >2s IO stall between provider return
     * and writeback would let a `slowDone.await` + cancel-collector +
     * fast-assert sequence pass BEFORE a regressed writeback lands
     * (derivable false-green). The test joins [aJob] instead.
     *
     * [fastDone] fires at provider return, i.e. BEFORE the ViewModel's
     * `_form.update` publishes B — it stays only as a deadlock guard for
     * A's gate, never as an ordering signal. The stale-never-written pin is
     * the live history collector plus the [aJob] join in the test.
     * Residual: the collector observes StateFlow emissions and can still
     * conflate under pathological same-instant writes (accepted).
     */
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()

    override suspend fun listModels(): List<String> {
        val n = calls.incrementAndGet()
        if (n == 1) {
            // Capture A's Job: complete() never suspends and returns false
            // (no-op) if already completed — safe at the head of the slow path.
            aJob.complete(currentCoroutineContext().job)
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("slow gate timeout")
            // Deadlock guard only (not an ordering signal): wait until B's
            // provider call has returned so A's slow return always lands
            // last. B-published-before-A-released is pinned by the test's
            // ViewModel-terminal wait, not by this latch.
            if (!fastDone.await(10, TimeUnit.SECONDS)) error("fast completion gate timeout")
            return listOf("slow-1", "slow-2", "slow-3", "slow-4", "slow-5")
        } else {
            try {
                return listOf("fast-1", "fast-2")
            } finally {
                fastDone.countDown()
            }
        }
    }
}

/**
 * Phase A2 (#17 P2): edit-KeepExisting intent + testConnection generation guard
 * + revision-rule uniformity.
 *
 * RED-first: [editBlankKeyReusesLiveRefOnModelOnlyChange] and
 * [slowTestConnectionDoesNotOverwriteNewer] fail on the pre-A2 code (blank key
 * unconditionally rejected; stale testConnection overwrites the newer form).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupEditKeepExistingTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("setup-keep-existing-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "keep-existing.preferences_pb") },
            ),
        )
    }

    private fun newVm(
        store: EndpointStore,
        vault: KeyVault,
        policy: PolicyStore,
        buildProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider,
    ): SetupViewModel = SetupViewModel(
        store = store,
        vaultSource = VaultSource { vault },
        policy = policy,
        buildProvider = buildProvider,
    )

    private fun awaitSaveSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    private fun awaitTestIdle(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.testing) {
            if (System.currentTimeMillis() > end) error("timed out waiting for test")
            Thread.sleep(10)
        }
    }

    private fun awaitLogoutSettled(vm: SetupViewModel, timeoutMs: Long = 10000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.logoutInFlight) {
            if (System.currentTimeMillis() > end) error("timed out waiting for logout")
            Thread.sleep(10)
        }
    }

    private fun openAiConfig(model: String = "gpt-4o-mini") = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    @Test
    fun editBlankKeyReusesLiveRefOnModelOnlyChange() {
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        val putsBefore = vault.putKeys
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW)) { _, _ ->
            error("no provider needed") as LlmProvider
        }
        // Edit entry: key stays blank (dots display); model-only change keeps
        // the same effectiveBaseUrl full-string equality (stricter than origin)
        // and providerId, so the blank key means "keep existing".
        vm.prefillForEdit(openAiConfig())
        vm.onModelChange("gpt-4o")
        vm.save()
        awaitSaveSettled(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        runBlocking {
            val live = store.snapshotStrict()
            // Live vault ref preserved (metadata-only CAS), model updated.
            assertEquals("provider_key/preset:openai", live?.apiKeyRef)
            assertEquals("gpt-4o", live?.model)
            assertTrue(vault.hasKey("preset:openai"))
        }
        // Zero key-material I/O (only existence-only hasKey): the stored key
        // bytes were never read or rewritten (no staged put, no retire, no getKey).
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
    }

    @Test
    fun editBlankKeyAfterOriginChangeRequiresRepaste() {
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val custom = EndpointConfig(
            providerId = "preset:custom",
            presetId = "custom",
            label = "自訂",
            baseUrl = "http://127.0.0.1:11434/v1",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "local",
            apiKeyRef = "provider_key/preset:custom",
        )
        runBlocking {
            vault.putKey("preset:custom", "test-key-123".toCharArray())
            store.save(custom)
        }
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW)) { _, _ ->
            error("no provider needed") as LlmProvider
        }
        vm.prefillForEdit(custom)
        // Endpoint identity changed (same preset, different effectiveBaseUrl
        // full-string, stricter than origin): the blank key no longer means
        // "keep existing" — fail closed, require re-paste.
        vm.onBaseUrlChange("http://127.0.0.1:11435/v1")
        vm.save()
        awaitSaveSettled(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertEquals(false, vm.form.value.saved)
    }

    @Test
    fun slowTestConnectionDoesNotOverwriteNewer() = runBlocking {
        // Guard shape under test: the post-listModels `_form.update` discards
        // unless entryRevision AND generation AND state testConnectionRevision
        // all still match. This is a 組合 barrier, not single-guard
        // independent: neutering EITHER dimension alone (entryRevision check
        // or generation checks) leaves this test GREEN — the other dimension
        // still discards A. Only neutering BOTH dimensions turns it RED with
        // a stale 5-item terminal (verified by temporary src/main neuters,
        // since reverted). Note testConnection's post-listModels path has no
        // suspension point, so job-cancel alone does NOT suppress A here
        // (unlike refreshModels, where ensureActive inside fetchSnapshotOutcome
        // is an additional independent suppression — see the symmetric test).
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val fake = SlowThenFastListProvider()
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW)) { _, _ -> fake }
        vm.onApiKeyChange("test-key-aaa-123")
        vm.testConnection()
        assertTrue(vm.form.value.testing)
        // Deterministic gate: wait until the slow attempt has ENTERED
        // listModels and blocked — this proves the race was exercised (no
        // thread-scheduling luck). Pre-A2 this never unblocks deterministically
        // into the fast value.
        assertTrue("slow attempt never entered", fake.entered.await(10, TimeUnit.SECONDS))
        // Identity/key edit while the slow attempt is blocked, then a fresh
        // attempt with the new key: the slow attempt must not overwrite it.
        vm.onApiKeyChange("test-key-bbb-123")
        vm.testConnection()
        // FIRST wait for B's ViewModel-terminal publish (provider return is
        // NOT enough: fastDone fires in listModels' finally, before the
        // ViewModel's _form.update). Releasing A only after B's terminal value
        // is published pins B-published-before-A-released, so a regressed
        // guard must surface as a stale slow write (RED) instead of an
        // A-then-B lucky overwrite (false green).
        withTimeout(10_000) { vm.form.first { it.testModels == 2 && !it.testing } }
        // History collector on an explicit scope started BEFORE releasing A:
        // StateFlow is conflated, so a post-hoc first{slow} subscribed after
        // idle would miss a transient slow overwritten by B; the live collector
        // observes every emission from B-terminal onward. The explicit histScope
        // (detached from the test's runBlocking scope) makes cancellation
        // explicit: even a handshake-timeout throw inside try still runs the
        // finally that cancels/joins the scope. (Residual: same-instant
        // conflated writes could still merge upstream — accepted.)
        val seen = Collections.synchronizedList(mutableListOf<Any?>())
        val firstSeen = CompletableDeferred<Unit>()
        val histScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        histScope.launch { vm.form.collect { seen.add(it.testModels); firstSeen.complete(Unit) } }
        // Release slow AFTER B published so slow always finishes last:
        // pre-guard overwrites to 5 (RED), post-guard discards to 2 (GREEN).
        // Completion proof is the [SlowThenFastListProvider.aJob] join below:
        // a provider-return latch (`slowDone`, now removed) fires BEFORE A's
        // `_form.update` writeback, so a >2s IO stall between provider return
        // and writeback would let latch-await + collector-cancel + fast-assert
        // pass before a regressed writeback lands (false-green). Joining A's
        // Job covers the guard check plus the possible writeback itself.
        // B-published-before-A-released makes a regressed stale write
        // terminal (nothing overwrites after A), so the post-join terminal
        // and collector asserts cannot miss it — the bounded 2s post-release
        // `first{slow}` watch is dropped as unneeded (it proved only absence
        // within a window, never writeback completion).
        try {
            // Handshake (first statement inside try so a timeout still hits the
            // finally below): wait until the collector has observed B-terminal
            // before releasing A; otherwise release + A's whole writeback could
            // complete before the collector's first emission (vacuous pin).
            // seen is a plain synchronized list (collector pushes); the firstSeen
            // gate fires on the collector's first emission, replacing the old
            // poll. Same anti-vacuous purpose; bounded 10s, first emission
            // immediate post-B-terminal in practice.
            withTimeout(10_000) { firstSeen.await() }
            fake.release.countDown()
            val aCoroutine = withTimeout(10_000) { fake.aJob.await() }
            // join() never throws, even on cancelled completion — the 15s
            // bound is failure-only (a stuck A fails instead of hanging).
            withTimeout(15_000) { aCoroutine.join() }
        } finally {
            histScope.cancel()
            withTimeoutOrNull(5_000) { histScope.coroutineContext.job.join() }
        }
        // The stale 5-item result must NEVER be written (not even transiently):
        // A has fully completed above, so the terminal and the full collector
        // history are conclusive.
        assertNull(vm.form.value.errorCode)
        assertEquals(2, vm.form.value.testModels)
        assertTrue("slow result must be discarded (collector saw stale): $seen", seen.none { it == 5 })
        // Both attempts ran; the slow 5-item result was discarded, not terminal.
        assertEquals(2, fake.calls.get())
    }

    @Test
    fun modelChangeDoesNotBumpRefreshRevision() {
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW)) { _, _ ->
            error("no provider needed") as LlmProvider
        }
        vm.onApiKeyChange("test-key-123")
        val before = vm.form.value.modelRefreshRevision
        val testBefore = vm.form.value.testConnectionRevision
        // Model edits are out of scope for the identity/key fence (#17): they
        // must not invalidate an in-flight refresh/test or a KeepExisting intent.
        vm.onModelChange("my-custom-model")
        assertEquals(before, vm.form.value.modelRefreshRevision)
        assertEquals(testBefore, vm.form.value.testConnectionRevision)
        assertEquals("my-custom-model", vm.form.value.model)
        vm.onApiKeyChange("test-key-456")
        assertTrue(vm.form.value.modelRefreshRevision != before)
        assertTrue(vm.form.value.testConnectionRevision != testBefore)
    }

    @Test
    fun editKeepExistingAskShowsDialogThenSavesOnConfirm() {
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ASK)) { _, _ ->
            error("no provider needed") as LlmProvider
        }
        vm.prefillForEdit(openAiConfig())
        vm.onModelChange("gpt-4o")
        vm.save()
        awaitSaveSettled(vm)
        // ASK minted the one-shot dialog; nothing persisted yet.
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(false, vm.form.value.saved)
        vm.confirmKeyWriteSave()
        awaitSaveSettled(vm)
        assertEquals(false, vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        runBlocking {
            val live = store.snapshotStrict()
            assertEquals("provider_key/preset:openai", live?.apiKeyRef)
            assertEquals("gpt-4o", live?.model)
        }
        // Zero key-material I/O beyond the seed put (only existence-only
        // hasKey): the stored key bytes were never rewritten, retired, or read
        // back.
        assertEquals(1, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
    }

    @Test
    fun editKeepExistingBlankKeySkipsLengthGate() {
        assertNull(
            SetupValidation.validate(
                "openai",
                "https://api.openai.com/v1",
                "",
                SetupValidation.KeyMode.EDIT_KEEP_EXISTING,
            ),
        )
        assertNull(
            SetupValidation.validate(
                "openai",
                "https://api.openai.com/v1",
                "   ",
                SetupValidation.KeyMode.EDIT_KEEP_EXISTING,
            ),
        )
    }

    @Test
    fun newModeBlankKeyStillRejected() {
        assertEquals(
            "SETUP_KEY_TOO_SHORT",
            SetupValidation.validate(
                "openai",
                "https://api.openai.com/v1",
                "",
                SetupValidation.KeyMode.NEW,
            ),
        )
        // Default (3-arg) overload stays NEW-strict for existing callers.
        assertEquals(
            "SETUP_KEY_TOO_SHORT",
            SetupValidation.validate("openai", "https://api.openai.com/v1", ""),
        )
    }

    @Test
    fun editModeShortNonBlankStillRejected() {
        assertEquals(
            "SETUP_KEY_TOO_SHORT",
            SetupValidation.validate(
                "openai",
                "https://api.openai.com/v1",
                "short",
                SetupValidation.KeyMode.EDIT_KEEP_EXISTING,
            ),
        )
    }

    @Test
    fun editModeUrlGateStillApplies() {
        // The URL gate precedes the key gate in both modes.
        assertEquals(
            "SETUP_BASE_URL_BLANK",
            SetupValidation.validate(
                "openai",
                "  ",
                "",
                SetupValidation.KeyMode.EDIT_KEEP_EXISTING,
            ),
        )
    }

    @Test
    fun editKeepExistingMissingKeyFailsClosed() {
        // F3: hasKey=false rejects reuse — metadata carries a well-formed ref
        // but the vault holds no key bytes. Blank-key save must fail closed
        // with POLICY_DENIED_KEY_WRITE (zero key-material writes).
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        runBlocking {
            // Store only; vault intentionally left keyless.
            store.save(openAiConfig())
        }
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW)) { _, _ ->
            error("no provider needed") as LlmProvider
        }
        vm.prefillForEdit(openAiConfig())
        vm.onModelChange("gpt-4o")
        vm.save()
        awaitSaveSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertEquals(false, vm.form.value.saved)
        // Only the existence-only hasKey precondition ran (false); no
        // key-material I/O: no staged put, no retire, no getKey.
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(1, vault.hasKeys)
    }

    @Test
    fun logoutDropsKeepExistingBaseline() {
        // F3: logout entry drops the edit baseline — a blank key after logout
        // must NOT reuse (KeepExisting invalid, re-paste required). After a
        // successful teardown the fresh form (blank key, same openai identity)
        // validates as NEW, so the failure is SETUP_KEY_TOO_SHORT, not the
        // KeepExisting POLICY_DENIED path (which would indicate the baseline
        // survived).
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW)) { _, _ ->
            error("no provider needed") as LlmProvider
        }
        vm.prefillForEdit(openAiConfig())
        // Sanity: without logout the same blank-key shape would reuse (covered
        // by editBlankKeyReusesLiveRefOnModelOnlyChange); logout must invalidate it.
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        awaitLogoutSettled(vm)
        val logoutOk = runBlocking { withTimeout(10_000) { done.await() } }
        assertTrue(logoutOk)
        assertNull(vm.form.value.errorCode)
        // Fresh form after teardown: blank key, openai identity.
        assertEquals("", vm.form.value.apiKey)
        vm.save()
        awaitSaveSettled(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertEquals(false, vm.form.value.saved)
        // F1: logout entry resets the probe fences — a failed/stale testing or
        // modelsLoading flag must not stick and block later entries.
        assertFalse(vm.form.value.testing)
        assertFalse(vm.form.value.modelsLoading)
    }
}
