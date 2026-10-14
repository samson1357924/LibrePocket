package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class SpyProbeProvider(
    var models: List<String> = listOf("m1", "m2"),
    var failure: Throwable? = null,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    // @Volatile + single IO-writer: only listModels increments; tests only read.
    @Volatile var calls = 0

    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()

    override suspend fun listModels(): List<String> {
        calls++
        failure?.let { throw it }
        return models
    }
}

/**
 * Recording fail-closed stub: captures the action/resource of every fresh
 * evaluation, then throws (mirrors [ThrowingPolicy] but records for the
 * provider.call action/resource assertions — [ThrowingPolicy] itself records
 * no action/resource).
 */
private class RecordingThrowingPolicy(
    private val error: Exception = IOException("POLICY_UNAVAILABLE"),
) : PolicyStore {
    var freshCalls = 0
    var lastAction: String? = null
        private set
    var lastResource: String? = null
        private set

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        lastAction = action
        lastResource = resource
        throw error
    }
}

/**
 * Stage S2: probe provider.call fresh gate (testConnection + refreshModels).
 *
 * Only ALLOW proceeds to provider construction/listModels/directory fetch;
 * ASK/DENY/read-failure fail closed with PROBE_POLICY_DENIED and zero I/O.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupProbePolicyTest {

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

    private fun newVm(
        policy: PolicyStore,
        fake: SpyProbeProvider,
        buildCalls: AtomicInteger = AtomicInteger(0),
        dirCalls: AtomicInteger = AtomicInteger(0),
        directoryBody: String = """{"openai":{"models":{"snap-a":{"reasoning":false,"tool_call":true}}}}""",
    ): SetupViewModel {
        val dir = Files.createTempDirectory("setup-probe-policy-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "probe-policy.preferences_pb") },
            ),
        )
        return SetupViewModel(
            store = store,
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            policy = policy,
            buildProvider = { _, _ ->
                buildCalls.incrementAndGet()
                fake
            },
            fetchDirectory = { _: String ->
                dirCalls.incrementAndGet()
                directoryBody
            },
        )
    }

    private fun awaitTestIdle(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.testing) {
            if (System.currentTimeMillis() > end) error("timed out waiting for test")
            Thread.sleep(10)
        }
    }

    private fun awaitModelsIdle(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.modelsLoading) {
            if (System.currentTimeMillis() > end) error("timed out waiting for models")
            Thread.sleep(10)
        }
    }

    @Test
    fun testConnectionDenyZeroRequests() {
        val policy = MutablePolicy(Verdict.DENY)
        val fake = SpyProbeProvider()
        val buildCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitTestIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.testing)
        assertNull(vm.form.value.testModels)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(1, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun testConnectionAskZeroRequests() {
        val policy = MutablePolicy(Verdict.ASK)
        val fake = SpyProbeProvider()
        val buildCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitTestIdle(vm)
        // ASK shares the fail-closed code (no probe dialog in S2).
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.testing)
        assertNull(vm.form.value.testModels)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(1, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun testConnectionPolicyReadFailureZeroRequests() {
        val policy = RecordingThrowingPolicy()
        val fake = SpyProbeProvider()
        val buildCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitTestIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.testing)
        assertNull(vm.form.value.testModels)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(1, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun refreshModelsDenyZeroRequests() {
        val policy = MutablePolicy(Verdict.DENY)
        val fake = SpyProbeProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls, dirCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(1, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun refreshModelsAskZeroRequests() {
        val policy = MutablePolicy(Verdict.ASK)
        val fake = SpyProbeProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls, dirCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(1, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun refreshModelsPolicyReadFailureZeroRequests() {
        val policy = RecordingThrowingPolicy()
        val fake = SpyProbeProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls, dirCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(1, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun testConnectionStaleDiscardOnEditDuringGate() {
        val policy = GatedPolicy(Verdict.ALLOW).apply { gateNext = true }
        val fake = SpyProbeProvider()
        val buildCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        assertTrue(policy.entered.await(10, TimeUnit.SECONDS))
        // Identity edit bumps revision while the gate is suspended.
        vm.onApiKeyChange("sk-edited-key-456")
        policy.release.countDown()
        // Bug-manifest window (no awaitTestIdle here): the edit above already
        // reset testing=false synchronously, so awaiting testing would return
        // immediately without waiting for the gated IO coroutine. A missing
        // fence would construct the provider within ~ms of release; read
        // buildCalls twice 200ms apart and require both 0.
        Thread.sleep(200)
        assertEquals(0, buildCalls.get())
        Thread.sleep(200)
        assertEquals(0, buildCalls.get())
        assertEquals(0, fake.calls)
        assertFalse(vm.form.value.testing)
        assertNull(vm.form.value.errorCode)
        assertEquals(1, policy.freshCalls)
    }

    @Test
    fun refreshModelsStaleDiscardOnEditDuringGate() {
        val policy = GatedPolicy(Verdict.ALLOW).apply { gateNext = true }
        val fake = SpyProbeProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls, dirCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        assertTrue(policy.entered.await(10, TimeUnit.SECONDS))
        vm.onApiKeyChange("sk-edited-key-456")
        policy.release.countDown()
        // Bug-manifest window (no awaitModelsIdle here): the edit above
        // already reset modelsLoading=false synchronously, so awaiting the
        // flag would return immediately without waiting for the gated IO
        // coroutine. A missing fence would construct the provider within ~ms
        // of release; read buildCalls twice 200ms apart and require both 0.
        Thread.sleep(200)
        assertEquals(0, buildCalls.get())
        Thread.sleep(200)
        assertEquals(0, buildCalls.get())
        assertEquals(0, fake.calls)
        assertEquals(0, dirCalls.get())
        assertFalse(vm.form.value.modelsLoading)
        assertNull(vm.form.value.errorCode)
        assertEquals(1, policy.freshCalls)
    }

    @Test
    fun validationFailureSkipsPolicyAndProvider() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val fake = SpyProbeProvider()
        val buildCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls)
        vm.onApiKeyChange("short")
        vm.testConnection()
        awaitTestIdle(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, policy.freshCalls)

        val policy2 = MutablePolicy(Verdict.ALLOW)
        val fake2 = SpyProbeProvider()
        val buildCalls2 = AtomicInteger(0)
        val dirCalls2 = AtomicInteger(0)
        val vm2 = newVm(policy2, fake2, buildCalls2, dirCalls2)
        vm2.onApiKeyChange("short")
        vm2.refreshModels()
        awaitModelsIdle(vm2)
        assertEquals("SETUP_KEY_TOO_SHORT", vm2.form.value.errorCode)
        assertEquals(0, fake2.calls)
        assertEquals(0, buildCalls2.get())
        assertEquals(0, dirCalls2.get())
        assertEquals(0, policy2.freshCalls)
    }

    @Test
    fun preIoRegateFlipBlocksWithZeroRequests() {
        // First gate ALLOWs, pre-IO re-gate DENYs: no provider I/O.
        val policy = FlipAfterPolicy(allowCalls = 1)
        val fake = SpyProbeProvider()
        val buildCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitTestIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.testing)
        assertNull(vm.form.value.testModels)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(2, policy.freshCalls)
        assertEquals(listOf("provider.call", "provider.call"), policy.actions)
        assertEquals(listOf("preset:openai", "preset:openai"), policy.resources)
    }

    @Test
    fun refreshModelsPreIoRegateFlipBlocksWithZeroRequests() {
        // First gate ALLOWs, pre-IO re-gate DENYs: no provider I/O, no directory fetch.
        val policy = FlipAfterPolicy(allowCalls = 1)
        val fake = SpyProbeProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls, dirCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(2, policy.freshCalls)
        assertEquals(listOf("provider.call", "provider.call"), policy.actions)
        assertEquals(listOf("preset:openai", "preset:openai"), policy.resources)
    }

    @Test
    fun refreshModelsPostLiveFlipBlocksDirectory() {
        // First two gates ALLOW so live listModels runs, post-live re-gate
        // DENYs: directory fetch blocked with zero directory I/O.
        val policy = FlipAfterPolicy(allowCalls = 2)
        val fake = SpyProbeProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls, dirCalls)
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertEquals("PROBE_POLICY_DENIED", vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(1, fake.calls)
        assertEquals(1, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(3, policy.freshCalls)
        assertEquals(listOf("provider.call", "provider.call", "provider.call"), policy.actions)
        assertEquals(listOf("preset:openai", "preset:openai", "preset:openai"), policy.resources)
    }

    @Test
    fun probeDeniedHasCopy() {
        assertEquals(
            "政策未允許連線探測（provider.call 非 ALLOW），未發出任何請求，請檢查權限設定後重試。",
            setupErrorText("PROBE_POLICY_DENIED"),
        )
    }
}
