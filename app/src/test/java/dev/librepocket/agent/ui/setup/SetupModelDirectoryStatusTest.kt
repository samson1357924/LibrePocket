package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.models.ModelsDevSnapshot
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class PausingSetupFormFlow(
    private val delegate: MutableStateFlow<SetupUiState>,
    private val refreshThread: AtomicReference<Thread?>,
    private val pauseNextRead: AtomicBoolean,
    private val readCaptured: CountDownLatch,
    private val releaseRead: CountDownLatch,
    private val refreshWriteCompleted: CountDownLatch,
) : MutableStateFlow<SetupUiState> by delegate {
    override var value: SetupUiState
        get() {
            val capturedValue = delegate.value
            if (Thread.currentThread() === refreshThread.get() && pauseNextRead.compareAndSet(true, false)) {
                readCaptured.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS)) { "timed out waiting to release model-refresh read" }
            }
            return capturedValue
        }
        set(value) {
            delegate.value = value
            signalRefreshWriteCompleted()
        }

    override fun compareAndSet(expect: SetupUiState, update: SetupUiState): Boolean {
        val changed = delegate.compareAndSet(expect, update)
        if (changed) signalRefreshWriteCompleted()
        return changed
    }

    private fun signalRefreshWriteCompleted() {
        if (Thread.currentThread() === refreshThread.get() && readCaptured.count == 0L) {
            refreshWriteCompleted.countDown()
        }
    }
}

private class PausingSetupFormCompareAndSetFlow(
    private val delegate: MutableStateFlow<SetupUiState>,
    private val firstRefreshThread: AtomicReference<Thread?>,
    private val secondPublicationThread: AtomicReference<Thread?>,
    private val pauseNextCas: AtomicBoolean,
    private val casPaused: CountDownLatch,
    private val releaseCas: CountDownLatch,
    private val firstPublicationSettled: CountDownLatch,
    private val secondPublicationSettled: CountDownLatch,
) : MutableStateFlow<SetupUiState> by delegate {
    override fun compareAndSet(expect: SetupUiState, update: SetupUiState): Boolean {
        val currentThread = Thread.currentThread()
        val isFirstRefresh = currentThread === firstRefreshThread.get()
        if (isFirstRefresh && pauseNextCas.compareAndSet(true, false)) {
            casPaused.countDown()
            check(releaseCas.await(8, TimeUnit.SECONDS)) { "timed out waiting to release old refresh CAS" }
        }

        val changed = delegate.compareAndSet(expect, update)
        if (changed) {
            if (isFirstRefresh && casPaused.count == 0L) firstPublicationSettled.countDown()
            if (currentThread === secondPublicationThread.get()) secondPublicationSettled.countDown()
        }
        return changed
    }
}

private class DirectoryStatusProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> = listOf("live-model")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupModelDirectoryStatusTest {
    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val viewModelStores = ArrayList<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModelStores.forEach { it.clear() }
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun remoteStatusPreservesLiveOptionsAndFreeTextModel(): Unit {
        runBlocking {
            val vm = newViewModel { DIRECTORY_OPENAI }
            vm.onApiKeyChange("synthetic-test-key")
            vm.onModelChange("user/free-text-model")

            vm.refreshModels()
            awaitModelsIdle(vm)

            assertEquals(ModelDirectoryStatus.Remote, vm.form.value.modelDirectoryStatus)
            assertEquals("user/free-text-model", vm.form.value.model)
            assertEquals("gpt-4o-mini", vm.form.value.modelOptions.first())
            assertTrue(vm.form.value.modelOptions.contains("live-model"))
            assertTrue(vm.form.value.modelOptions.contains("directory-model"))
        }
    }

    @Test
    fun bundledStatusNamesDirectoryFallbackReasonAndKeepsLiveOptions(): Unit {
        runBlocking {
            val vm = newViewModel {
                throw ModelsDevSnapshot.FetchException(ModelsDevSnapshot.SnapshotFallbackReason.TIMEOUT)
            }
            vm.onApiKeyChange("synthetic-test-key")
            vm.onModelChange("user/free-text-model")

            vm.refreshModels()
            awaitModelsIdle(vm)

            assertEquals(
                ModelDirectoryStatus.Bundled(ModelsDevSnapshot.SnapshotFallbackReason.TIMEOUT),
                vm.form.value.modelDirectoryStatus,
            )
            assertEquals("user/free-text-model", vm.form.value.model)
            assertEquals("gpt-4o-mini", vm.form.value.modelOptions.first())
            assertTrue(vm.form.value.modelOptions.contains("live-model"))
        }
    }

    @Test
    fun obsoleteRefreshCannotOverwriteNewPresetOrDirectoryStatus(): Unit {
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val firstRelease = CompletableDeferred<Unit>()
            val firstFinished = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val secondBody = CompletableDeferred<String>()
            val fetchCount = AtomicInteger()
            val vm = newViewModel { _ ->
                when (fetchCount.incrementAndGet()) {
                    1 -> {
                        firstStarted.complete(Unit)
                        try {
                            firstRelease.await()
                            throw ModelsDevSnapshot.FetchException(
                                ModelsDevSnapshot.SnapshotFallbackReason.NETWORK_ERROR,
                            )
                        } finally {
                            firstFinished.complete(Unit)
                        }
                    }
                    2 -> {
                        secondStarted.complete(Unit)
                        secondBody.await()
                    }
                    else -> error("unexpected extra directory fetch")
                }
            }

            vm.onApiKeyChange("synthetic-openai-key")
            vm.onModelChange("user/old-free-text")
            vm.refreshModels()
            withTimeout(3_000) { firstStarted.await() }

            vm.selectPreset(dev.librepocket.preset.ProviderCatalog.ANTHROPIC_ID)
            vm.onApiKeyChange("synthetic-anthropic-key")
            vm.onModelChange("user/new-free-text")
            vm.refreshModels()
            withTimeout(3_000) { secondStarted.await() }
            secondBody.complete(DIRECTORY_ANTHROPIC)
            awaitModelsIdle(vm)

            assertEquals("user/new-free-text", vm.form.value.model)
            assertEquals(ModelDirectoryStatus.Remote, vm.form.value.modelDirectoryStatus)
            assertTrue(vm.form.value.modelOptions.contains("anthropic-model"))
            assertFalse(vm.form.value.modelOptions.contains("directory-model"))

            firstRelease.complete(Unit)
            withTimeout(3_000) { firstFinished.await() }
            delay(100)

            assertEquals("user/new-free-text", vm.form.value.model)
            assertEquals(ModelDirectoryStatus.Remote, vm.form.value.modelDirectoryStatus)
            assertTrue(vm.form.value.modelOptions.contains("anthropic-model"))
            assertFalse(vm.form.value.modelOptions.contains("directory-model"))
        }
    }

    @Test
    fun completedRefreshCannotOverwriteFormEditedAfterGenerationCheck(): Unit {
        runBlocking {
            val refreshThread = AtomicReference<Thread?>()
            val pauseNextRead = AtomicBoolean(false)
            val readCaptured = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val refreshWriteCompleted = CountDownLatch(1)
            val vm = newViewModel {
                refreshThread.set(Thread.currentThread())
                pauseNextRead.set(true)
                DIRECTORY_OPENAI
            }
            installPausingFormRead(
                vm = vm,
                refreshThread = refreshThread,
                pauseNextRead = pauseNextRead,
                readCaptured = readCaptured,
                releaseRead = releaseRead,
                refreshWriteCompleted = refreshWriteCompleted,
            )

            vm.onApiKeyChange("synthetic-openai-key")
            vm.onModelChange("user/old-free-text")
            vm.refreshModels()

            var readWasPaused = false
            try {
                readWasPaused = readCaptured.await(5, TimeUnit.SECONDS)
                assertTrue("refresh should pause after its generation check", readWasPaused)

                vm.selectPreset(dev.librepocket.preset.ProviderCatalog.ANTHROPIC_ID)
                vm.onApiKeyChange("synthetic-anthropic-key")
                vm.onModelChange("user/new-free-text")
            } finally {
                releaseRead.countDown()
                val refreshSettled = refreshWriteCompleted.await(5, TimeUnit.SECONDS)
                if (readWasPaused) {
                    assertTrue("paused refresh should finish after release", refreshSettled)
                }
            }

            val state = vm.form.value
            assertEquals(dev.librepocket.preset.ProviderCatalog.ANTHROPIC_ID, state.presetId)
            assertEquals("synthetic-anthropic-key", state.apiKey)
            assertEquals("user/new-free-text", state.model)
            assertTrue(state.modelOptions.isEmpty())
            assertFalse(state.modelsLoading)
            assertEquals(ModelDirectoryStatus.NotLoaded, state.modelDirectoryStatus)
        }
    }

    @Test
    fun refreshCasCannotCommitAcrossEqualFormStateAfterGenerationChanges(): Unit {
        runBlocking {
            val firstRefreshThread = AtomicReference<Thread?>()
            val secondPublicationThread = AtomicReference<Thread?>()
            val pauseNextCas = AtomicBoolean(false)
            val casPaused = CountDownLatch(1)
            val releaseCas = CountDownLatch(1)
            val firstPublicationSettled = CountDownLatch(1)
            val secondPublicationSettled = CountDownLatch(1)
            val secondFetchStarted = CountDownLatch(1)
            val secondBody = CompletableDeferred<String>()
            val fetchCount = AtomicInteger()
            val vm = newViewModel {
                when (fetchCount.incrementAndGet()) {
                    1 -> {
                        firstRefreshThread.set(Thread.currentThread())
                        pauseNextCas.set(true)
                        DIRECTORY_OPENAI
                    }
                    2 -> {
                        secondFetchStarted.countDown()
                        val body = secondBody.await()
                        secondPublicationThread.set(Thread.currentThread())
                        body
                    }
                    else -> error("unexpected extra directory fetch")
                }
            }
            installPausingFormCas(
                vm = vm,
                firstRefreshThread = firstRefreshThread,
                secondPublicationThread = secondPublicationThread,
                pauseNextCas = pauseNextCas,
                casPaused = casPaused,
                releaseCas = releaseCas,
                firstPublicationSettled = firstPublicationSettled,
                secondPublicationSettled = secondPublicationSettled,
            )

            val originalApiKey = "synthetic-openai-key"
            val originalModel = "user/old-free-text"
            vm.onApiKeyChange(originalApiKey)
            vm.onModelChange(originalModel)
            vm.refreshModels()

            var secondRefreshRequested = false
            try {
                assertTrue("old model refresh should pause immediately before CAS", casPaused.await(5, TimeUnit.SECONDS))

                vm.selectPreset(dev.librepocket.preset.ProviderCatalog.ANTHROPIC_ID)
                vm.onApiKeyChange("synthetic-anthropic-key")
                vm.onModelChange("user/intermediate-free-text")
                vm.selectPreset(dev.librepocket.preset.ProviderCatalog.OPENAI_ID)
                vm.onApiKeyChange(originalApiKey)
                vm.onModelChange(originalModel)

                secondRefreshRequested = true
                vm.refreshModels()
                assertTrue("second refresh should be held inside its fetcher", secondFetchStarted.await(5, TimeUnit.SECONDS))

                assertRefreshStillLoading(vm, originalApiKey, originalModel)
                releaseCas.countDown()
                assertTrue("old refresh CAS/update loop should settle", firstPublicationSettled.await(5, TimeUnit.SECONDS))
                assertRefreshStillLoading(vm, originalApiKey, originalModel)
            } finally {
                releaseCas.countDown()
                secondBody.complete(DIRECTORY_OPENAI)
                firstPublicationSettled.await(5, TimeUnit.SECONDS)
                if (secondRefreshRequested) secondPublicationSettled.await(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun assertRefreshStillLoading(vm: SetupViewModel, apiKey: String, model: String) {
        val state = vm.form.value
        assertEquals(dev.librepocket.preset.ProviderCatalog.OPENAI_ID, state.presetId)
        assertEquals(apiKey, state.apiKey)
        assertEquals(model, state.model)
        assertTrue(state.modelOptions.isEmpty())
        assertTrue(state.modelsLoading)
        assertEquals(ModelDirectoryStatus.NotLoaded, state.modelDirectoryStatus)
    }

    @Suppress("UNCHECKED_CAST")
    private fun installPausingFormCas(
        vm: SetupViewModel,
        firstRefreshThread: AtomicReference<Thread?>,
        secondPublicationThread: AtomicReference<Thread?>,
        pauseNextCas: AtomicBoolean,
        casPaused: CountDownLatch,
        releaseCas: CountDownLatch,
        firstPublicationSettled: CountDownLatch,
        secondPublicationSettled: CountDownLatch,
    ) {
        val field = SetupViewModel::class.java.getDeclaredField("_form")
        field.isAccessible = true
        val original = field.get(vm) as MutableStateFlow<SetupUiState>
        field.set(
            vm,
            PausingSetupFormCompareAndSetFlow(
                delegate = original,
                firstRefreshThread = firstRefreshThread,
                secondPublicationThread = secondPublicationThread,
                pauseNextCas = pauseNextCas,
                casPaused = casPaused,
                releaseCas = releaseCas,
                firstPublicationSettled = firstPublicationSettled,
                secondPublicationSettled = secondPublicationSettled,
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun installPausingFormRead(
        vm: SetupViewModel,
        refreshThread: AtomicReference<Thread?>,
        pauseNextRead: AtomicBoolean,
        readCaptured: CountDownLatch,
        releaseRead: CountDownLatch,
        refreshWriteCompleted: CountDownLatch,
    ) {
        val field = SetupViewModel::class.java.getDeclaredField("_form")
        field.isAccessible = true
        val original = field.get(vm) as MutableStateFlow<SetupUiState>
        field.set(
            vm,
            PausingSetupFormFlow(
                delegate = original,
                refreshThread = refreshThread,
                pauseNextRead = pauseNextRead,
                readCaptured = readCaptured,
                releaseRead = releaseRead,
                refreshWriteCompleted = refreshWriteCompleted,
            ),
        )
    }

    private fun newViewModel(fetchDirectory: suspend (String) -> String): SetupViewModel {
        val dir = Files.createTempDirectory("setup-directory-status").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "directory-status.preferences_pb") },
            ),
        )
        val viewModel = SetupViewModel(
            store = store,
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            policy = InMemoryPolicyStore(),
            buildProvider = { _, _ -> DirectoryStatusProvider() },
            fetchDirectory = fetchDirectory,
        )
        ViewModelStore().also {
            it.put("setup", viewModel)
            viewModelStores.add(it)
        }
        return viewModel
    }

    private suspend fun awaitModelsIdle(vm: SetupViewModel) {
        withTimeout(8_000) {
            while (vm.form.value.modelsLoading) delay(10)
        }
    }

    private companion object {
        const val DIRECTORY_OPENAI = """{"openai":{"models":{"directory-model":{}}}}"""
        const val DIRECTORY_ANTHROPIC = """{"anthropic":{"models":{"anthropic-model":{}}}}"""
    }
}
