package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.nio.file.Files
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Records every fresh evaluation, then allows (resource-scope assertions). */
private class RecordingAllowPolicy : PolicyStore {
    var freshCalls = 0
    val actions = ArrayList<String>()
    val resources = ArrayList<String>()

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        actions.add(action)
        resources.add(resource)
        return PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    }
}

private class CapturingProbeProvider(
    var models: List<String> = listOf("m1", "m2"),
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    @Volatile var calls = 0

    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()

    override suspend fun listModels(): List<String> {
        calls++
        return models
    }
}

/**
 * Stage1 entity/policy split pins for the probe paths (M1/M2): `provider.call`
 * is evaluated against the preset-shared scope (`preset:custom`) for custom
 * URLs, while the transported [ProviderConfig] carries the per-URL entity id.
 *
 * Plus one documentation pin: a single `preset:custom` ALLOW still authorizes
 * every custom URL (Stage2 #13 owns per-endpoint scoping; see
 * [SetupValidation.policyResourceForPreset]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupPolicyResourceScopeTest {

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
        fake: CapturingProbeProvider,
        buildCalls: AtomicInteger = AtomicInteger(0),
        dirCalls: AtomicInteger = AtomicInteger(0),
        directoryBody: String = """{"openai":{"models":{"snap-a":{"reasoning":false,"tool_call":true}}}}""",
        capturedConfig: Array<ProviderConfig?> = arrayOfNulls(1),
    ): SetupViewModel {
        val dir = Files.createTempDirectory("setup-resource-scope-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "resource-scope.preferences_pb") },
            ),
        )
        return SetupViewModel(
            store = store,
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            policy = policy,
            buildProvider = { config: ProviderConfig, _: KeyProvider ->
                buildCalls.incrementAndGet()
                capturedConfig[0] = config
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

    private fun customForm(vm: SetupViewModel, url: String) {
        vm.selectPreset("custom")
        vm.onBaseUrlChange(url)
        vm.onApiKeyChange("unit-test-key-12345678")
    }

    @Test fun testConnectionCustomUrlUsesPresetResource() {
        val url = "http://127.0.0.1:11434/v1"
        val policy = RecordingAllowPolicy()
        val fake = CapturingProbeProvider()
        val buildCalls = AtomicInteger(0)
        val captured: Array<ProviderConfig?> = arrayOfNulls(1)
        val vm = newVm(policy, fake, buildCalls, capturedConfig = captured)
        customForm(vm, url)
        vm.testConnection()
        awaitTestIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertEquals(1, buildCalls.get())
        assertTrue(policy.resources.isNotEmpty())
        assertTrue(policy.resources.all { it == "preset:custom" })
        assertTrue(policy.actions.all { it == "provider.call" })
    }

    @Test fun refreshModelsCustomUrlUsesPresetResource() {
        val url = "http://127.0.0.1:11434/v1"
        val policy = RecordingAllowPolicy()
        val fake = CapturingProbeProvider()
        val buildCalls = AtomicInteger(0)
        val vm = newVm(policy, fake, buildCalls)
        customForm(vm, url)
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertEquals(listOf("m1", "m2"), vm.form.value.modelOptions)
        assertEquals(1, buildCalls.get())
        assertTrue(policy.resources.isNotEmpty())
        assertTrue(policy.resources.all { it == "preset:custom" })
        assertTrue(policy.actions.all { it == "provider.call" })
    }

    @Test fun probeTransportIdentityIsPerUrlWhileResourceStaysPreset() {
        val urlA = "http://127.0.0.1:11434/v1"
        val urlB = "http://127.0.0.1:11435/v1"
        val capturedA: Array<ProviderConfig?> = arrayOfNulls(1)
        val capturedB: Array<ProviderConfig?> = arrayOfNulls(1)

        val policyA = RecordingAllowPolicy()
        val policyB = RecordingAllowPolicy()
        val vmA = newVm(policyA, CapturingProbeProvider(), capturedConfig = capturedA)
        customForm(vmA, urlA)
        vmA.testConnection()
        awaitTestIdle(vmA)

        val vmB = newVm(policyB, CapturingProbeProvider(), capturedConfig = capturedB)
        customForm(vmB, urlB)
        vmB.testConnection()
        awaitTestIdle(vmB)

        assertTrue(policyA.resources.isNotEmpty())
        assertTrue(policyA.resources.all { it == "preset:custom" })
        assertTrue(policyB.resources.isNotEmpty())
        assertTrue(policyB.resources.all { it == "preset:custom" })

        assertNotNull(capturedA[0])
        assertNotNull(capturedB[0])
        val configA = capturedA[0]!!
        val configB = capturedB[0]!!
        assertEquals(SetupValidation.deriveEndpointId("custom", urlA), configA.id)
        assertEquals(urlA, configA.baseUrl)
        assertEquals(SetupValidation.deriveEndpointId("custom", urlB), configB.id)
        assertEquals(urlB, configB.baseUrl)
        assertNotEquals(configA.id, configB.id)
    }

    @Test fun singlePresetAllowAuthorizesEveryCustomUrl_documented() {
        // Documents the Stage2-known residual: authorization is preset-shared,
        // so one `preset:custom` ALLOW covers distinct custom URLs. If Stage2
        // scopes authorization per endpoint, this test must change with it.
        val urlA = "http://127.0.0.1:11434/v1"
        val urlB = "http://127.0.0.1:11435/v1"
        val policy = InMemoryPolicyStore(
            initialRules = listOf(PolicyRule("provider.call:preset:custom", Verdict.ALLOW, 10)),
        )
        val buildCallsA = AtomicInteger(0)
        val vmA = newVm(policy, CapturingProbeProvider(), buildCallsA)
        customForm(vmA, urlA)
        vmA.testConnection()
        awaitTestIdle(vmA)
        assertNull(vmA.form.value.errorCode)
        assertEquals(1, buildCallsA.get())

        val buildCallsB = AtomicInteger(0)
        val vmB = newVm(policy, CapturingProbeProvider(), buildCallsB)
        customForm(vmB, urlB)
        vmB.testConnection()
        awaitTestIdle(vmB)
        assertNull(vmB.form.value.errorCode)
        assertEquals(1, buildCallsB.get())
    }
}
