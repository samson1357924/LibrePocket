package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.nio.file.Files
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeModelProvider(
    var models: List<String> = emptyList(),
    var failure: Throwable? = null,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    var calls = 0

    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()

    override suspend fun listModels(): List<String> {
        calls++
        failure?.let { throw it }
        return models
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupModelsTest {

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
        fake: FakeModelProvider = FakeModelProvider(),
        directoryBody: String = """{"openai":{"models":{"snap-a":{"reasoning":false,"tool_call":true}}}}""",
    ): SetupViewModel {
        val dir = Files.createTempDirectory("setup-models-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "models.preferences_pb") },
            ),
        )
        return SetupViewModel(
            store = store,
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            policy = InMemoryPolicyStore(),
            buildProvider = { _, _ -> fake },
            fetchDirectory = { directoryBody },
        )
    }

    private fun awaitModelsIdle(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.modelsLoading) {
            if (System.currentTimeMillis() > end) error("timed out waiting for models")
            Thread.sleep(10)
        }
    }

    @Test
    fun livePlusSnapshotMergesDefaultFirst() {
        val vm = newVm(FakeModelProvider(models = listOf("live-x")))
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        awaitModelsIdle(vm)
        val options = vm.form.value.modelOptions
        assertEquals("gpt-4o-mini", options.first())
        assertTrue(options.contains("live-x"))
        assertTrue(options.contains("snap-a"))
    }

    @Test
    fun providerFailureFallsBackToBundled() {
        val dir = Files.createTempDirectory("setup-models-fb-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "fb.preferences_pb") },
            ),
        )
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            policy = InMemoryPolicyStore(),
            buildProvider = { _, _ -> FakeModelProvider(failure = java.io.IOException("offline")) },
            fetchDirectory = { throw java.io.IOException("offline") },
        )
        vm.onApiKeyChange("sk-test-key-123")
        vm.refreshModels()
        awaitModelsIdle(vm)
        val options = vm.form.value.modelOptions
        assertEquals("gpt-4o-mini", options.first())
        assertEquals(listOf("gpt-4o-mini"), options)
    }

    @Test
    fun validationFailureSkipsProvider() {
        val fake = FakeModelProvider(models = listOf("live-x"))
        val vm = newVm(fake)
        vm.onApiKeyChange("short")
        vm.refreshModels()
        awaitModelsIdle(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
        assertTrue(vm.form.value.modelOptions.isEmpty())
    }

    @Test
    fun freeTextModelPreserved() {
        val vm = newVm(FakeModelProvider(models = listOf("live-x")))
        vm.onApiKeyChange("sk-test-key-123")
        vm.onModelChange("my-custom-model")
        vm.refreshModels()
        awaitModelsIdle(vm)
        // User text untouched; options offered separately.
        assertEquals("my-custom-model", vm.form.value.model)
        assertTrue(vm.form.value.modelOptions.isNotEmpty())
    }
}
