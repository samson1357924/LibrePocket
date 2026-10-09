package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeListProvider(
    var models: List<String> = listOf("m1", "m2"),
    var failure: Throwable? = null,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    var calls = 0
    var lastRef: String? = null

    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()

    override suspend fun listModels(): List<String> {
        calls++
        failure?.let { throw it }
        return models
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupConnectionTest {

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
        fake: FakeListProvider = FakeListProvider(),
    ): Pair<SetupViewModel, EndpointStore> {
        val dir = Files.createTempDirectory("setup-conn-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "setup.preferences_pb") },
            ),
        )
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = InMemoryPolicyStore(),
            buildProvider = { _, _ -> fake },
        )
        return vm to store
    }

    private fun awaitIdle(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.testing) {
            if (System.currentTimeMillis() > end) error("timed out waiting for test")
            Thread.sleep(10)
        }
    }

    @Test
    fun successReportsModelCountAndPersistsNothing() {
        val (vm, store) = newVm()
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals(2, vm.form.value.testModels)
        assertNull(vm.form.value.errorCode)
        runBlocking { assertEquals(false, store.hasMetadata()) }
    }

    @Test
    fun unauthorizedMapsToKeyError() {
        val fake = FakeListProvider(failure = ProviderFailure(false, "HTTP 401 invalid_api_key"))
        val (vm, _) = newVm(fake)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("TEST_UNAUTHORIZED", vm.form.value.errorCode)
        assertNull(vm.form.value.testModels)
    }

    @Test
    fun retryableMapsToServerBusy() {
        val fake = FakeListProvider(failure = ProviderFailure(true, "HTTP 503"))
        val (vm, _) = newVm(fake)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("TEST_RETRYABLE", vm.form.value.errorCode)
    }

    @Test
    fun fatalUnknownMapsToFailed() {
        val fake = FakeListProvider(failure = ProviderFailure(false, "weird"))
        val (vm, _) = newVm(fake)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("TEST_FAILED", vm.form.value.errorCode)
    }

    @Test
    fun tooLargeMapsToTooLarge() {
        val fake = FakeListProvider(
            failure = ProviderFailure(
                false,
                "response truncated",
                code = ProviderFailureCode.TOO_LARGE,
            ),
        )
        val (vm, _) = newVm(fake)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("TEST_TOO_LARGE", vm.form.value.errorCode)
        assertNull(vm.form.value.testModels)
    }

    @Test
    fun tooLargeMessageWithoutCodeStillFails() {
        val fake = FakeListProvider(
            failure = ProviderFailure(false, "TOO_LARGE provider model-list response"),
        )
        val (vm, _) = newVm(fake)
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("TEST_FAILED", vm.form.value.errorCode)
    }

    @Test
    fun validationShortCircuitsWithoutProviderCall() {
        val fake = FakeListProvider()
        val (vm, _) = newVm(fake)
        vm.onApiKeyChange("short")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
    }

    @Test
    fun testResultClearsOnInputChange() {
        val (vm, _) = newVm()
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals(2, vm.form.value.testModels)
        vm.onModelChange("other-model")
        assertNull(vm.form.value.testModels)
    }

    @Test
    fun keyProviderContract() {
        // Documents the KeyProvider bridge used by testConnection/save paths.
        var captured: CharArray? = null
        val keys = KeyProvider { ref ->
            assertEquals("provider_key/preset:openai", ref)
            captured = "sk-test-key-123".toCharArray()
            captured!!
        }
        runBlocking {
            val got = keys.keyFor("provider_key/preset:openai")
            assertEquals("sk-test-key-123", String(got!!))
            got.fill('\u0000')
            assertTrue(captured!!.all { it == '\u0000' })
        }
    }
}
