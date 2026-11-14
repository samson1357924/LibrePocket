package dev.librepocket.agent.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import dev.librepocket.web.MAX_WEB_BASE_URL_LENGTH
import dev.librepocket.web.WEB_CONSENT_ACK_KEY
import dev.librepocket.web.WEB_DDGS_OPTIN_KEY
import dev.librepocket.web.WEB_MASTER_ENABLED_KEY
import dev.librepocket.web.WEB_SEARXNG_URL_KEY
import dev.librepocket.web.WebProbe
import dev.librepocket.web.WebSearchSettings
import dev.librepocket.web.WebSwitchStore
import dev.librepocket.web.WebUnavailableReason
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebSearchViewModelTest {

    private val managedStores = mutableListOf<ViewModelStore>()
    private val managedSchedulers = mutableListOf<TestCoroutineScheduler>()

    @After
    fun tearDown() {
        // Deterministic cleanup: clear all tracked VMs/scopes, drain tracked test schedulers,
        // then reset Main to avoid coroutine/poller leaks polluting subsequent tests.
        managedStores.forEach { it.clear() }
        managedSchedulers.forEach { runCatching { it.advanceUntilIdle() } }
        DispatchersResetGuard.reset()
        managedStores.clear()
        managedSchedulers.clear()
    }

    private fun trackStore(store: ViewModelStore): ViewModelStore = store.also { managedStores += it }

    private fun TestScope.trackScheduler(): TestCoroutineScheduler =
        testScheduler.also { if (it !in managedSchedulers) managedSchedulers += it }

    private fun trackScheduler(scheduler: TestCoroutineScheduler): TestCoroutineScheduler =
        scheduler.also { if (it !in managedSchedulers) managedSchedulers += it }

    private object DispatchersResetGuard {
        fun reset() {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    private class FakePreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val writeMutex = Mutex()

        private val _readCount = AtomicInteger(0)
        private val _writeCount = AtomicInteger(0)
        val readCount: Int
            get() = _readCount.get()
        val writeCount: Int
            get() = _writeCount.get()
        val writtenUrls = mutableListOf<String>()

        var failReadsRemaining = 0
        var failWritesRemaining = 0
        var midStreamFailure: CompletableDeferred<IOException>? = null
        var onSubscribe: (suspend (subscriptionIndex: Int) -> Unit)? = null
        var beforeWrite: (suspend (writeIndex: Int) -> Unit)? = null
        var beforeEmit: (suspend (subscriptionIndex: Int, prefs: Preferences) -> Unit)? = null
        var afterRead: ((subscriptionIndex: Int) -> Unit)? = null

        fun setOutOfBand(prefs: Preferences) {
            state.value = prefs
        }

        fun currentPrefs(): Preferences = state.value
        fun currentUrl(): String = state.value[WEB_SEARXNG_URL_KEY] ?: ""
        fun currentMaster(): Boolean = state.value[WEB_MASTER_ENABLED_KEY] ?: false
        fun currentDdgs(): Boolean = state.value[WEB_DDGS_OPTIN_KEY] ?: false
        fun currentConsent(): Boolean = state.value[WEB_CONSENT_ACK_KEY] ?: false

        override val data: Flow<Preferences> = flow {
            val sub = _readCount.incrementAndGet()
            onSubscribe?.invoke(sub)
            if (failReadsRemaining > 0) {
                failReadsRemaining--
                throw IOException("injected read failure #$sub")
            }
            val failure = midStreamFailure
            if (failure != null) {
                midStreamFailure = null
                emitAll(
                    merge(
                        state.map { Result.success(it) },
                        flow { emit(Result.failure<Preferences>(failure.await())) },
                    ).map { it.getOrThrow() },
                )
            } else {
                var emitted = false
                try {
                    val emitHook = beforeEmit
                    if (emitHook != null) {
                        emitAll(
                            state.onEach {
                                emitHook(sub, it)
                                emitted = true
                            },
                        )
                    } else if (afterRead != null) {
                        emitAll(state.onEach { emitted = true })
                    } else {
                        emitAll(state)
                    }
                } finally {
                    if (emitted) {
                        afterRead?.invoke(sub)
                    }
                }
            }
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return writeMutex.withLock {
                val idx = _writeCount.incrementAndGet()
                beforeWrite?.invoke(idx)
                if (failWritesRemaining > 0) {
                    failWritesRemaining--
                    throw IOException("injected write failure #$idx")
                }
                val prev = state.value
                val next = transform(prev)
                state.value = next
                val prevUrl = prev[WEB_SEARXNG_URL_KEY] ?: ""
                val nextUrl = next[WEB_SEARXNG_URL_KEY] ?: ""
                if (nextUrl != prevUrl || next.asMap().containsKey(WEB_SEARXNG_URL_KEY) && !prev.asMap().containsKey(WEB_SEARXNG_URL_KEY)) {
                    writtenUrls.add(nextUrl)
                }
                next
            }
        }
    }

    private fun prefs(
        master: Boolean = false,
        ddgs: Boolean = false,
        consent: Boolean = false,
        url: String = "",
    ): Preferences = emptyPreferences().toMutablePreferences().apply {
        this[WEB_MASTER_ENABLED_KEY] = master
        this[WEB_DDGS_OPTIN_KEY] = ddgs
        this[WEB_CONSENT_ACK_KEY] = consent
        this[WEB_SEARXNG_URL_KEY] = url
    }.toPreferences()

    private fun TestScope.installStandardVm(
        fakeDs: FakePreferencesDataStore = FakePreferencesDataStore(),
        probeProvider: suspend (String) -> Pair<WebProbe, WebProbe> = { url ->
            WebSearchSettings.probeSearxngUrl(url) to WebProbe.Ready
        },
    ): WebSearchViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val ioDispatcher = dispatcher
        val store = WebSwitchStore(fakeDs, ioDispatcher)
        val vmStore = trackStore(ViewModelStore())
        trackScheduler()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WebSearchViewModel(store, ioDispatcher, probeProvider) as T
            }
        }
        return ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
    }

    private fun TestScope.collectUi(vm: WebSearchViewModel, seen: MutableList<WebSearchSettingsState>? = null) =
        backgroundScope.launch {
            vm.uiState.collect { state -> seen?.add(state) }
        }

    @Test
    fun fastCharByCharTyping_withSlowFakeDataStore_updatesUiImmediatelyAndPersistsExactFinalUrl() = runTest {
        val fakeDs = FakePreferencesDataStore().apply {
            beforeWrite = { delay(30) }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        val full = "https://search.example"
        for (i in 1..full.length) {
            val prefix = full.substring(0, i)
            vm.onSearxngUrlChange(prefix)
            runCurrent()
            assertEquals("dropped/lagged char at step $i", prefix, vm.uiState.value.searxngUrl)
        }
        advanceUntilIdle()
        assertEquals(full, fakeDs.currentUrl())
        assertEquals(full, vm.uiState.value.searxngUrl)
        assertEquals(WebProbe.Ready, vm.uiState.value.searxngProbe)
        assertNull(vm.uiState.value.writeError)
        collector.cancel()
    }

    @Test
    fun synchronousMainDispatch_typingUpdatesUiImmediatelyWithoutDrop() = runTest {
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(unconfined)
        trackScheduler()
        val fakeDs = FakePreferencesDataStore().apply {
            beforeWrite = { delay(50) }
        }
        val vmStore = trackStore(ViewModelStore())
        val vm = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
        })[WebSearchViewModel::class.java]
        val collector = backgroundScope.launch(unconfined) { vm.uiState.collect {} }
        vm.uiState.first { !it.isLoading }

        val full = "https://search.example"
        for (i in 1..full.length) {
            val prefix = full.substring(0, i)
            vm.onSearxngUrlChange(prefix)
            assertEquals(prefix, vm.uiState.value.searxngUrl)
        }
        advanceUntilIdle()
        assertEquals(full, fakeDs.currentUrl())
        assertEquals(full, vm.uiState.value.searxngUrl)
        collector.cancel()
    }

    @Test
    fun outOfOrderSlowWritesImmunity_coalescesQueuedUrlWritesAndNoUiRegression() = runTest {
        val firstWriteEntered = CompletableDeferred<Unit>()
        val firstWriteGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore().apply {
            beforeWrite = { idx ->
                if (idx == 1) {
                    firstWriteEntered.complete(Unit)
                    firstWriteGate.await()
                }
            }
        }
        val vm = installStandardVm(fakeDs)
        val seen = mutableListOf<WebSearchSettingsState>()
        val collector = collectUi(vm, seen)
        vm.uiState.first { !it.isLoading }

        vm.onSearxngUrlChange("h")
        runCurrent()
        firstWriteEntered.await()

        vm.onSearxngUrlChange("ht")
        vm.onSearxngUrlChange("htt")
        vm.onSearxngUrlChange("https://search.example")
        runCurrent()
        assertEquals("https://search.example", vm.uiState.value.searxngUrl)

        firstWriteGate.complete(Unit)
        advanceUntilIdle()

        // First write was already in-flight; queued intermediate URL writes are coalesced to latest.
        assertEquals(listOf("h", "https://search.example"), fakeDs.writtenUrls)
        assertEquals("https://search.example", fakeDs.currentUrl())
        assertEquals("https://search.example", vm.uiState.value.searxngUrl)
        val afterFinalTyped = seen.dropWhile { it.searxngUrl != "https://search.example" }
        assertTrue(afterFinalTyped.isNotEmpty())
        assertTrue(afterFinalTyped.all { it.searxngUrl == "https://search.example" })
        collector.cancel()
    }

    @Test
    fun highFrequencyUrlEditsWhileWriteInFlight_persistOnlyInFlightAndLatestDraft() = runTest {
        val firstWriteEntered = CompletableDeferred<Unit>()
        val firstWriteGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore().apply {
            beforeWrite = { idx ->
                if (idx == 1) {
                    firstWriteEntered.complete(Unit)
                    firstWriteGate.await()
                }
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onSearxngUrlChange("h")
        runCurrent()
        firstWriteEntered.await()

        var latest = "h"
        repeat(500) { i ->
            latest = "https://search.example/$i"
            vm.onSearxngUrlChange(latest)
        }
        runCurrent()
        assertEquals(latest, vm.uiState.value.searxngUrl)

        firstWriteGate.complete(Unit)
        advanceUntilIdle()

        // Backlog must remain bounded to latest draft while first write is in-flight.
        assertEquals(listOf("h", latest), fakeDs.writtenUrls)
        assertEquals(latest, fakeDs.currentUrl())
        assertEquals(latest, vm.uiState.value.searxngUrl)
        assertNull(vm.uiState.value.writeError)
        collector.cancel()
    }

    @Test
    fun onSearxngUrlChange_overMaxLength_notEnqueuedAndShowsWriteError() = runTest {
        val fakeDs = FakePreferencesDataStore(prefs(url = "https://good.example"))
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        val overMax = "https://example.com/" + "a".repeat(MAX_WEB_BASE_URL_LENGTH + 1 - "https://example.com/".length)
        vm.onSearxngUrlChange(overMax)
        runCurrent()
        assertEquals(overMax, vm.uiState.value.searxngUrl)
        assertEquals("SearXNG 網址過長，未儲存至本機設定。", vm.uiState.value.writeError)
        advanceUntilIdle()
        assertEquals(0, fakeDs.writeCount)
        assertEquals("https://good.example", fakeDs.currentUrl())
        collector.cancel()
    }

    @Test
    fun resetVsQueuedUrlWriteRace_resetWinsWithoutResurrectingStaleDraft() = runTest {
        val firstWriteEntered = CompletableDeferred<Unit>()
        val firstWriteGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://old.example"),
        ).apply {
            beforeWrite = { idx ->
                if (idx == 1) {
                    firstWriteEntered.complete(Unit)
                    firstWriteGate.await()
                }
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onSearxngUrlChange("https://first.example")
        runCurrent()
        firstWriteEntered.await()

        // Queue a newer draft behind the blocked write, then immediately reset.
        vm.onSearxngUrlChange("https://stale.example")
        vm.onResetSettings()
        runCurrent()
        assertEquals("", vm.uiState.value.searxngUrl)

        firstWriteGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(fakeDs.writtenUrls.contains("https://stale.example"))
        assertEquals("", fakeDs.currentUrl())
        assertFalse(fakeDs.currentMaster())
        assertFalse(fakeDs.currentDdgs())
        assertFalse(fakeDs.currentConsent())
        assertEquals("", vm.uiState.value.searxngUrl)
        assertFalse(vm.uiState.value.canReset)
        collector.cancel()
    }

    @Test
    fun invalidNonBlankUrls_probeAgreementAndUserinfoBlockedFromPersistenceWithWriteError() = runTest {
        val fakeDs = FakePreferencesDataStore(prefs(url = "https://good.example"))
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        for (bad in listOf("ftp://example.com", "not-a-url")) {
            vm.onSearxngUrlChange(bad)
            runCurrent()
            assertEquals(WebProbe.NotReady(WebUnavailableReason.INVALID_URL), vm.uiState.value.searxngProbe)
            advanceUntilIdle()
            assertNull("harmless invalid draft $bad should not emit storage writeError", vm.uiState.value.writeError)
            assertEquals(bad, fakeDs.currentUrl())

            vm.onProbe()
            advanceUntilIdle()
            val probeMsg = vm.uiState.value.probeMessage
            assertNotNull(probeMsg)
            assertTrue(probeMsg!!.contains("SearXNG：URL 格式無效"))
            assertTrue(probeMsg.contains("DDGS：設定格式有效（未連網）"))
        }

        // Restore valid persisted baseline, then test userinfo URLs blocked from disk persistence.
        vm.onSearxngUrlChange("https://good.example")
        advanceUntilIdle()
        assertEquals("https://good.example", fakeDs.currentUrl())
        val writesBefore = fakeDs.writeCount

        for (userinfoUrl in listOf("https://user:pass@host", "http://@host")) {
            vm.onSearxngUrlChange(userinfoUrl)
            runCurrent()
            assertEquals(WebProbe.NotReady(WebUnavailableReason.INVALID_URL), vm.uiState.value.searxngProbe)
            advanceUntilIdle()
            assertEquals(writesBefore, fakeDs.writeCount)
            assertEquals("https://good.example", fakeDs.currentUrl())
            assertNotNull(vm.uiState.value.writeError)
            assertEquals(userinfoUrl, vm.uiState.value.searxngUrl)

            vm.onProbe()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.probeMessage!!.contains("SearXNG：URL 格式無效"))
            assertNotNull(vm.uiState.value.writeError)
        }

        // Fixing URL clears writeError and persists new valid URL.
        vm.onSearxngUrlChange("https://recovered.example")
        advanceUntilIdle()
        assertNull(vm.uiState.value.writeError)
        assertEquals("https://recovered.example", fakeDs.currentUrl())
        collector.cancel()
    }

    @Test
    fun staleProbeMessage_clearedOnUrlEditMasterToggleDdgsToggleAndReset() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
        )
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onProbe()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.probeMessage)

        vm.onSearxngUrlChange("https://search2.example")
        runCurrent()
        assertNull(vm.uiState.value.probeMessage)
        advanceUntilIdle()

        vm.onProbe()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.probeMessage)
        vm.onMasterSwitch(true)
        runCurrent()
        assertNull(vm.uiState.value.probeMessage)
        advanceUntilIdle()

        vm.onProbe()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.probeMessage)
        vm.onMasterSwitch(false)
        runCurrent()
        assertNull(vm.uiState.value.probeMessage)
        advanceUntilIdle()

        vm.onProbe()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.probeMessage)
        vm.onDdgsSwitch(true)
        runCurrent()
        assertNull(vm.uiState.value.probeMessage)
        advanceUntilIdle()

        vm.onProbe()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.probeMessage)
        vm.onResetSettings()
        runCurrent()
        assertNull(vm.uiState.value.probeMessage)
        advanceUntilIdle()
        collector.cancel()
    }

    @Test
    fun consentFlow_openDismissAtomicConfirmDoubleTapBlockDismissAndFailureRetry() = runTest {
        val writeEntered = CompletableDeferred<Unit>()
        val writeGate = CompletableDeferred<Unit>()
        var gateWrites = false
        val fakeDs = FakePreferencesDataStore().apply {
            beforeWrite = {
                if (gateWrites) {
                    writeEntered.complete(Unit)
                    writeGate.await()
                }
            }
        }
        val vm = installStandardVm(fakeDs)
        val seen = mutableListOf<WebSearchSettingsState>()
        val collector = collectUi(vm, seen)
        vm.uiState.first { !it.isLoading }

        // !consentAck + onMasterSwitch(true) -> dialog opens with 0 store writes.
        vm.onMasterSwitch(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showConsent)
        assertEquals(0, fakeDs.writeCount)
        assertFalse(vm.uiState.value.masterEnabled)

        // Dismiss -> leaves masterEnabled=false, consentAck=false, 0 store writes.
        vm.onConsentDismiss()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)
        assertEquals(0, fakeDs.writeCount)
        assertFalse(fakeDs.currentMaster())
        assertFalse(fakeDs.currentConsent())

        // Open again, gate write and inject IO failure on confirm:
        // double-tap ignored, dismiss blocked while submitting, failure keeps dialog open + surfaces writeError.
        vm.onMasterSwitch(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showConsent)

        gateWrites = true
        fakeDs.failWritesRemaining = 1
        vm.onConsentConfirm()
        runCurrent()
        assertTrue(vm.uiState.value.consentSubmitting)
        // Double-tap while submitting ignored.
        vm.onConsentConfirm()
        // Dismiss while submitting blocked.
        vm.onConsentDismiss()
        runCurrent()
        assertTrue(vm.uiState.value.showConsent)

        writeEntered.await()
        writeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, fakeDs.writeCount)
        assertFalse(vm.uiState.value.consentSubmitting)
        assertTrue(vm.uiState.value.showConsent)
        assertNotNull(vm.uiState.value.writeError)
        assertFalse(vm.uiState.value.masterEnabled)
        assertFalse(fakeDs.currentConsent())

        // Retry confirm cleanly -> atomic consentAck + masterEnabled write.
        gateWrites = false
        vm.onConsentConfirm()
        advanceUntilIdle()
        assertEquals(2, fakeDs.writeCount)
        assertFalse(vm.uiState.value.showConsent)
        assertFalse(vm.uiState.value.consentSubmitting)
        assertNull(vm.uiState.value.writeError)
        assertTrue(vm.uiState.value.masterEnabled)
        assertTrue(fakeDs.currentConsent())
        assertTrue(fakeDs.currentMaster())

        // Toggle off, then second onMasterSwitch(true) skips dialog.
        vm.onMasterSwitch(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.masterEnabled)
        vm.onMasterSwitch(true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)
        assertTrue(vm.uiState.value.masterEnabled)
        assertTrue(seen.none { it.writeError != null && it.consentSubmitting })
        collector.cancel()
    }

    @Test
    fun whileSubscribed5000_backgroundOver5sAndResubscribe_zeroLoadingFlicker() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = false, consent = true, url = "https://search.example"),
        ).apply {
            onSubscribe = { sub ->
                if (sub >= 2) {
                    delay(10_000)
                }
            }
        }
        val vm = installStandardVm(fakeDs)

        val firstJob = launch { vm.uiState.collect {} }
        val loaded = vm.uiState.first { !it.isLoading }
        assertTrue(loaded.masterEnabled)
        assertTrue(loaded.switchesEnabled)
        assertEquals("https://search.example", loaded.searxngUrl)
        firstJob.cancel()
        firstJob.join()

        // Under 5000ms: WhileSubscribed(5000) keeps upstream subscription alive (readCount stays 1).
        advanceTimeBy(4_999)
        runCurrent()
        val warmSubJob = launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals(1, fakeDs.readCount)
        warmSubJob.cancel()
        warmSubJob.join()

        // Exceed WhileSubscribed(5000) so upstream actually cancels.
        advanceTimeBy(5_001)
        runCurrent()

        val seen = mutableListOf<WebSearchSettingsState>()
        val secondJob = launch { vm.uiState.collect { seen.add(it) } }
        runCurrent()
        assertEquals(2, fakeDs.readCount)

        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(seen.isNotEmpty())
        assertTrue("expected zero LOADING flicker on resubscribe, saw $seen", seen.none { it.isLoading })
        assertTrue(seen.all { it.switchesEnabled && it.masterEnabled && it.searxngUrl == "https://search.example" })
        secondJob.cancel()
        secondJob.join()
    }

    @Test
    fun readFailure_ioExceptionFailsClosedAndRetryLoadRecovers() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://search.example"),
        ).apply {
            failReadsRemaining = 1
            onSubscribe = { sub ->
                if (sub == 2) delay(100)
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)

        val failed = vm.uiState.first { !it.isLoading }
        assertTrue(failed.loadFailed)
        assertFalse(failed.switchesEnabled)
        assertFalse(failed.masterEnabled)
        assertFalse(failed.ddgsOptIn)
        assertFalse(failed.canReset)

        // All mutations fail-closed with 0 DataStore writes.
        vm.onMasterSwitch(true)
        vm.onDdgsSwitch(true)
        vm.onSearxngUrlChange("https://blocked.example")
        vm.onResetSettings()
        vm.onProbe()
        vm.onConsentConfirm()
        advanceUntilIdle()
        assertEquals(0, fakeDs.writeCount)
        assertFalse(vm.uiState.value.showConsent)
        assertNull(vm.uiState.value.probeMessage)
        assertEquals("", vm.uiState.value.searxngUrl)
        assertTrue(vm.uiState.value.loadFailed)

        // retryLoad() enters loading without loadFailed, then recovers when read succeeds.
        vm.retryLoad()
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.switchesEnabled)

        advanceTimeBy(100)
        advanceUntilIdle()
        val recovered = vm.uiState.value
        assertFalse(recovered.isLoading)
        assertFalse(recovered.loadFailed)
        assertTrue(recovered.switchesEnabled)
        assertTrue(recovered.masterEnabled)
        assertTrue(recovered.ddgsOptIn)
        assertEquals("https://search.example", recovered.searxngUrl)
        collector.cancel()
    }

    @Test
    fun writeFailures_surfaceWriteErrorOnMasterDdgsResetAndUrl() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = false, consent = true, url = "https://search.example"),
        )
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        fakeDs.failWritesRemaining = 1
        vm.onMasterSwitch(false)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.writeError)

        fakeDs.failWritesRemaining = 1
        vm.onDdgsSwitch(true)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.writeError)

        fakeDs.failWritesRemaining = 1
        vm.onResetSettings()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.writeError)

        fakeDs.failWritesRemaining = 1
        vm.onSearxngUrlChange("https://updated.example")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.writeError)
        assertEquals("https://updated.example", vm.uiState.value.searxngUrl)
        collector.cancel()
    }

    @Test
    fun canReset_reflectsNonDefaultStateAndResetsCleanly() = runTest {
        val fakeDs = FakePreferencesDataStore()
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        assertFalse(vm.uiState.value.canReset)

        vm.onSearxngUrlChange("draft")
        runCurrent()
        assertTrue(vm.uiState.value.canReset)
        vm.onSearxngUrlChange("")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canReset)

        vm.onDdgsSwitch(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canReset)
        vm.onResetSettings()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canReset)

        vm.onMasterSwitch(true)
        advanceUntilIdle()
        vm.onConsentConfirm()
        advanceUntilIdle()
        vm.onMasterSwitch(false)
        advanceUntilIdle()
        // consentAck == true still allows reset even when masterEnabled == false.
        assertTrue(vm.uiState.value.canReset)
        vm.onResetSettings()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canReset)
        assertFalse(fakeDs.currentConsent())
        collector.cancel()
    }

    @Test
    fun onProbe_staleAsyncProbeDiscardedWhenUrlEditedMidFlight() = runTest {
        val probeEntered = CompletableDeferred<Unit>()
        val probeGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore(prefs(url = "https://a.example"))
        val vm = installStandardVm(
            fakeDs = fakeDs,
            probeProvider = { url ->
                if (url == "https://a.example") {
                    probeEntered.complete(Unit)
                    probeGate.await()
                }
                WebSearchSettings.probeSearxngUrl(url) to WebProbe.Ready
            },
        )
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onProbe()
        runCurrent()
        probeEntered.await()

        // Edit URL while probe for a.example is in flight -> cancels/supersedes stale probe.
        vm.onSearxngUrlChange("https://b.example")
        probeGate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.probeMessage)
        collector.cancel()
    }

    @Test
    fun onProbe_exceptionMapsToNotReady_cancellationPropagates_andDoubleTapSupersedesFirst() = runTest {
        var mode = "throw_unexpected"
        val firstProbeEntered = CompletableDeferred<Unit>()
        val firstProbeGate = CompletableDeferred<Unit>()
        val firstProbeCancelled = CompletableDeferred<Boolean>()
        val fakeDs = FakePreferencesDataStore(prefs(url = "https://search.example"))
        val vm = installStandardVm(
            fakeDs = fakeDs,
            probeProvider = { url ->
                when (mode) {
                    "throw_unexpected" -> throw IllegalStateException("unexpected probe failure")
                    "throw_cancel" -> throw CancellationException("probe cancelled directly")
                    "double_tap_first" -> {
                        firstProbeEntered.complete(Unit)
                        try {
                            firstProbeGate.await()
                            WebProbe.NotReady(WebUnavailableReason.MISSING_URL) to
                                WebProbe.NotReady(WebUnavailableReason.MISSING_URL)
                        } catch (e: CancellationException) {
                            firstProbeCancelled.complete(true)
                            throw e
                        }
                    }
                    else -> WebSearchSettings.probeSearxngUrl(url) to WebProbe.Ready
                }
            },
        )
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        // Unexpected Exception -> mapped to NO_PROVIDER ("未就緒").
        vm.onProbe()
        advanceUntilIdle()
        assertEquals("設定檢查（不連線）— SearXNG：未就緒；DDGS：未就緒", vm.uiState.value.probeMessage)

        // Reset probeMessage via URL edit, then verify CancellationException propagates (not mapped to 未就緒).
        vm.onSearxngUrlChange("https://search2.example")
        advanceUntilIdle()
        assertNull(vm.uiState.value.probeMessage)
        mode = "throw_cancel"
        vm.onProbe()
        advanceUntilIdle()
        assertNull(vm.uiState.value.probeMessage)

        // Rapid double-tap: second onProbe() cancels and supersedes slow first probe.
        mode = "double_tap_first"
        vm.onProbe()
        runCurrent()
        firstProbeEntered.await()

        mode = "ready"
        vm.onProbe()
        firstProbeGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(firstProbeCancelled.await())
        assertEquals(
            "設定檢查（不連線）— SearXNG：設定格式有效（未連網）；DDGS：設定格式有效（未連網）",
            vm.uiState.value.probeMessage,
        )
        collector.cancel()
    }

    @Test
    fun retryLoad_doubleIoFailureReentrancyAndClearsStaleProbeWriteErrorAndDirtyInvalidUrl() = runTest {
        val midFailure = CompletableDeferred<IOException>()
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://search.example"),
        ).apply {
            failReadsRemaining = 2
            midStreamFailure = midFailure
            onSubscribe = { sub ->
                if (sub >= 2) delay(50)
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)

        // First read fails -> retryLoad() -> second read ALSO fails -> loadFailed again.
        vm.uiState.first { !it.isLoading }
        assertTrue(vm.uiState.value.loadFailed)

        vm.retryLoad()
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.loadFailed)
        advanceTimeBy(50)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.loadFailed)

        // Re-entrant rapid retryLoad() -> third read succeeds.
        vm.retryLoad()
        vm.retryLoad()
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.loadFailed)
        advanceTimeBy(50)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loadFailed)
        assertEquals("https://search.example", vm.uiState.value.searxngUrl)

        // Build stale dirty unsafe URL + writeError + probeMessage before mid-stream loadFailed.
        vm.onSearxngUrlChange("https://user:pass@bad.example")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.writeError)
        vm.onProbe()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.probeMessage)

        midFailure.complete(IOException("mid-stream boom"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loadFailed)
        assertEquals("", vm.uiState.value.searxngUrl)
        assertEquals(WebProbe.NotReady(WebUnavailableReason.MISSING_URL), vm.uiState.value.searxngProbe)
        assertNull(vm.uiState.value.probeMessage)

        // retryLoad() clears stale probeMessage, writeError, and dirty invalid URL.
        vm.retryLoad()
        advanceTimeBy(50)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loadFailed)
        assertEquals("https://search.example", vm.uiState.value.searxngUrl)
        assertNull(vm.uiState.value.probeMessage)
        assertNull(vm.uiState.value.writeError)
        assertEquals(WebProbe.Ready, vm.uiState.value.searxngProbe)
        collector.cancel()
    }

    @Test
    fun onCleared_viaViewModelStore_cancelsProbeClosesChannelAndLetsNonCancellableWriteComplete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        trackScheduler()
        val probeEntered = CompletableDeferred<Unit>()
        val probeGate = CompletableDeferred<Unit>()
        val probeCancelled = CompletableDeferred<Boolean>()
        val probeCalls = AtomicInteger(0)
        val writeEntered = CompletableDeferred<Unit>()
        val writeGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore().apply {
            beforeWrite = { idx ->
                if (idx == 1) {
                    writeEntered.complete(Unit)
                    writeGate.await()
                }
            }
        }
        val vmStore = trackStore(ViewModelStore())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WebSearchViewModel(
                    store = WebSwitchStore(fakeDs, dispatcher),
                    ioDispatcher = dispatcher,
                    probeProvider = {
                        probeCalls.incrementAndGet()
                        probeEntered.complete(Unit)
                        try {
                            probeGate.await()
                            WebProbe.Ready to WebProbe.Ready
                        } catch (e: CancellationException) {
                            probeCancelled.complete(true)
                            throw e
                        }
                    },
                ) as T
            }
        }
        val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onProbe()
        runCurrent()
        probeEntered.await()

        vm.onSearxngUrlChange("https://persisted-during-clear.example")
        runCurrent()
        writeEntered.await()

        // Clear ViewModelStore while probe and NonCancellable write are in flight.
        vmStore.clear()
        runCurrent()
        assertTrue(probeCancelled.await())

        writeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("https://persisted-during-clear.example", fakeDs.currentUrl())

        // Subsequent calls after onCleared() must not throw, write, probe, or re-read.
        val writesAfterClear = fakeDs.writeCount
        val readsAfterClear = fakeDs.readCount
        val probesAfterClear = probeCalls.get()
        vm.onSearxngUrlChange("https://after-clear.example")
        vm.onMasterSwitch(true)
        vm.onDdgsSwitch(true)
        vm.onProbe()
        vm.onResetSettings()
        vm.retryLoad()
        vm.onConsentConfirm()
        vm.onConsentDismiss()
        advanceUntilIdle()
        assertEquals(writesAfterClear, fakeDs.writeCount)
        assertEquals(readsAfterClear, fakeDs.readCount)
        assertEquals(probesAfterClear, probeCalls.get())
        assertEquals("https://persisted-during-clear.example", fakeDs.currentUrl())
        collector.cancel()
    }

    @Test
    fun isSafeUrlForStorage_rejectsLongControlFormatSurrogateQueryFragmentAndSchemelessUserinfo() = runTest {
        val fakeDs = FakePreferencesDataStore(prefs(url = "https://good.example"))
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        val overMax = "https://example.com/" + "a".repeat(MAX_WEB_BASE_URL_LENGTH + 1 - "https://example.com/".length)
        val rejectedUrls = listOf(
            overMax,
            "https://search.example/\u0000",
            "https://search\u200B.example",
            "https://search.example/\uD800",
            "https://search.example/\u2028",
            "https://search.example/?token=secret",
            "https://search.example/#frag",
            "https://search.example\\path",
            "search.example\\path",
            "search.example?q=1",
            "search.example#frag",
            "user:pass@host",
            "https:/user:pass@host",
            "//user:pass@host",
            "\\",
            "?",
            "#",
            "@",
            "\u0085",
            "\u2029",
        )
        for (bad in rejectedUrls) {
            vm.onSearxngUrlChange(bad)
            advanceUntilIdle()
            assertEquals("unexpected DataStore write for $bad", 0, fakeDs.writeCount)
            assertEquals("https://good.example", fakeDs.currentUrl())
            assertNotNull("expected writeError for $bad", vm.uiState.value.writeError)
        }
        collector.cancel()
    }

    @Test
    fun loadingAndRetrying_failClosedRejectAllMutationsWithZeroWrites() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://saved.example"),
        ).apply {
            failReadsRemaining = 1
            onSubscribe = { delay(100) }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        runCurrent()

        // LOADING state before initial read emits.
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.switchesEnabled)
        assertFalse(vm.uiState.value.urlEditable)
        assertFalse(vm.uiState.value.canReset)
        assertEquals("", vm.uiState.value.searxngUrl)
        assertEquals(WebProbe.NotReady(WebUnavailableReason.MISSING_URL), vm.uiState.value.searxngProbe)

        vm.onMasterSwitch(true)
        vm.onDdgsSwitch(false)
        vm.onSearxngUrlChange("https://blocked-loading.example")
        vm.onProbe()
        vm.onResetSettings()
        vm.onConsentConfirm()
        vm.onConsentDismiss()
        advanceTimeBy(100)
        advanceUntilIdle()

        // First read failed -> 0 writes occurred during LOADING.
        assertTrue(vm.uiState.value.loadFailed)
        assertEquals(0, fakeDs.writeCount)

        // retrying state after retryLoad().
        vm.retryLoad()
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.switchesEnabled)
        assertFalse(vm.uiState.value.urlEditable)
        assertFalse(vm.uiState.value.canReset)
        assertEquals("", vm.uiState.value.searxngUrl)
        assertEquals(WebProbe.NotReady(WebUnavailableReason.MISSING_URL), vm.uiState.value.searxngProbe)

        vm.onMasterSwitch(false)
        vm.onDdgsSwitch(false)
        vm.onSearxngUrlChange("https://blocked-retrying.example")
        vm.onProbe()
        vm.onResetSettings()
        vm.onConsentConfirm()
        advanceTimeBy(100)
        advanceUntilIdle()

        assertEquals(0, fakeDs.writeCount)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.loadFailed)
        assertEquals("https://saved.example", vm.uiState.value.searxngUrl)
        assertNull(vm.uiState.value.probeMessage)
        collector.cancel()
    }

    @Test
    fun onConsentConfirm_whenShowConsentFalse_isNoOpWithZeroWrites() = runTest {
        val fakeDs = FakePreferencesDataStore(prefs(consent = false, master = false))
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        assertFalse(vm.uiState.value.showConsent)
        vm.onConsentConfirm()
        advanceUntilIdle()

        assertEquals(0, fakeDs.writeCount)
        assertFalse(vm.uiState.value.showConsent)
        assertFalse(vm.uiState.value.consentSubmitting)
        assertFalse(vm.uiState.value.masterEnabled)
        assertFalse(fakeDs.currentConsent())
        assertFalse(fakeDs.currentMaster())
        collector.cancel()
    }

    @Test
    fun consentSubmitting_disablesControlsAndBlocksAllMutationsAndRetryLoad() = runTest {
        val writeEntered = CompletableDeferred<Unit>()
        val writeGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore(prefs(url = "https://search.example")).apply {
            beforeWrite = {
                writeEntered.complete(Unit)
                writeGate.await()
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onMasterSwitch(true)
        runCurrent()
        assertTrue(vm.uiState.value.showConsent)

        vm.onConsentConfirm()
        runCurrent()
        writeEntered.await()

        val submittingState = vm.uiState.value
        assertTrue(submittingState.consentSubmitting)
        assertTrue(submittingState.showConsent)
        assertFalse(submittingState.switchesEnabled)
        assertFalse(submittingState.urlEditable)
        assertFalse(submittingState.canReset)

        // All mutations and retryLoad() must be blocked while consentSubmitting == true.
        vm.onSearxngUrlChange("https://blocked.example")
        vm.onMasterSwitch(false)
        vm.onDdgsSwitch(true)
        vm.onProbe()
        vm.onResetSettings()
        vm.retryLoad()
        vm.onConsentDismiss()
        vm.onConsentConfirm()
        runCurrent()

        writeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fakeDs.writeCount)
        assertEquals(1, fakeDs.readCount)
        assertEquals("https://search.example", fakeDs.currentUrl())
        assertFalse(fakeDs.currentDdgs())
        assertTrue(fakeDs.currentMaster())
        assertTrue(fakeDs.currentConsent())
        assertFalse(vm.uiState.value.consentSubmitting)
        assertFalse(vm.uiState.value.showConsent)
        assertNull(vm.uiState.value.probeMessage)
        assertTrue(vm.uiState.value.switchesEnabled)
        assertTrue(vm.uiState.value.urlEditable)
        assertTrue(vm.uiState.value.canReset)
        collector.cancel()
    }

    @Test
    fun loadFailed_afterUrlDirtyAndShowConsent_failClosesUrlProbeAndClearsShowConsent() = runTest {
        val midFailure = CompletableDeferred<IOException>()
        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, consent = false, url = "https://initial.example"),
        ).apply {
            midStreamFailure = midFailure
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onMasterSwitch(true)
        vm.onSearxngUrlChange("https://dirty.example")
        runCurrent()
        assertTrue(vm.uiState.value.showConsent)
        assertEquals("https://dirty.example", vm.uiState.value.searxngUrl)

        midFailure.complete(IOException("mid-stream failure"))
        advanceUntilIdle()

        val failed = vm.uiState.value
        assertTrue(failed.loadFailed)
        assertEquals("", failed.searxngUrl)
        assertEquals(WebProbe.NotReady(WebUnavailableReason.MISSING_URL), failed.searxngProbe)
        assertFalse(failed.showConsent)
        assertFalse(failed.switchesEnabled)
        assertFalse(failed.urlEditable)
        assertFalse(failed.canReset)
        collector.cancel()
    }

    @Test
    fun abortedUrlWriteAndResetWhenLoadFailedBeforeMutex_clearsUrlDirtyCleanly() = runTest {
        // Subcase 1: queued processUrlWrite sees loadFailed before acquiring storeWriteMutex.
        run {
            val midFailure = CompletableDeferred<IOException>()
            val writeEntered = CompletableDeferred<Unit>()
            val writeGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(
                prefs(master = false, consent = true, url = "https://initial.example"),
            ).apply {
                midStreamFailure = midFailure
                beforeWrite = { idx ->
                    if (idx == 1) {
                        writeEntered.complete(Unit)
                        writeGate.await()
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            vm.onDdgsSwitch(true)
            runCurrent()
            writeEntered.await()

            vm.onSearxngUrlChange("https://aborted-draft.example")
            runCurrent()

            midFailure.complete(IOException("load failed before url mutex"))
            runCurrent()
            writeGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fakeDs.writeCount)
            assertEquals("https://initial.example", fakeDs.currentUrl())
            assertTrue(vm.uiState.value.loadFailed)
            assertEquals("", vm.uiState.value.searxngUrl)

            vm.retryLoad()
            advanceUntilIdle()
            assertEquals("https://initial.example", vm.uiState.value.searxngUrl)
            collector.cancel()
        }

        // Subcase 2: queued onResetSettings() sees loadFailed before acquiring storeWriteMutex.
        run {
            val midFailure = CompletableDeferred<IOException>()
            val writeEntered = CompletableDeferred<Unit>()
            val writeGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(
                prefs(master = true, ddgs = false, consent = true, url = "https://initial.example"),
            ).apply {
                midStreamFailure = midFailure
                beforeWrite = { idx ->
                    if (idx == 1) {
                        writeEntered.complete(Unit)
                        writeGate.await()
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            vm.onDdgsSwitch(true)
            runCurrent()
            writeEntered.await()

            vm.onResetSettings()
            runCurrent()

            midFailure.complete(IOException("load failed before reset mutex"))
            runCurrent()
            writeGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fakeDs.writeCount)
            assertEquals("https://initial.example", fakeDs.currentUrl())
            assertTrue(vm.uiState.value.loadFailed)

            vm.retryLoad()
            advanceUntilIdle()
            assertEquals("https://initial.example", vm.uiState.value.searxngUrl)
            collector.cancel()
        }
    }

    @Test
    fun typingWhileResetAllSuspended_newUrlSurvivesOnDiskAndInUi() = runTest {
        val resetEntered = CompletableDeferred<Unit>()
        val resetGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://old.example"),
        ).apply {
            beforeWrite = { idx ->
                if (idx == 1) {
                    resetEntered.complete(Unit)
                    resetGate.await()
                }
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onResetSettings()
        runCurrent()
        resetEntered.await()

        vm.onSearxngUrlChange("https://new.example")
        runCurrent()
        assertEquals("https://new.example", vm.uiState.value.searxngUrl)

        resetGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("https://new.example", fakeDs.currentUrl())
        assertEquals("https://new.example", vm.uiState.value.searxngUrl)
        assertEquals(2, fakeDs.writeCount)
        assertFalse(fakeDs.currentMaster())
        assertFalse(fakeDs.currentDdgs())
        assertFalse(fakeDs.currentConsent())
        collector.cancel()
    }

    @Test
    fun resetQueuedNotYetDequeued_postResetDraftSurvives_withoutWorkerDispatch() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://old.example"),
        )
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        // All enqueued before worker dispatch: UrlFlush(A), ResetAll(gen1), pending=B(gen1,no marker).
        vm.onSearxngUrlChange("https://a.example")
        vm.onResetSettings()
        vm.onSearxngUrlChange("https://b.example")

        advanceUntilIdle()

        assertEquals("https://b.example", fakeDs.currentUrl())
        assertEquals("https://b.example", vm.uiState.value.searxngUrl)
        assertFalse(fakeDs.writtenUrls.contains("https://a.example"))
        assertFalse(fakeDs.currentMaster())
        assertFalse(fakeDs.currentDdgs())
        assertFalse(fakeDs.currentConsent())
        collector.cancel()
    }

    @Test
    fun resetQueuedNotYetDequeued_postResetDraftSurvives_withLaterConsentConfirm_withoutWorkerDispatch() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = true, consent = false, url = "https://old.example"),
        )
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        // A -> Reset -> B -> MasterOn(consent sheet) -> ConsentConfirm, all before worker dispatch.
        vm.onSearxngUrlChange("https://a.example")
        vm.onResetSettings()
        vm.onSearxngUrlChange("https://b.example")
        vm.onMasterSwitch(true)
        vm.onConsentConfirm()

        advanceUntilIdle()

        assertEquals("https://b.example", fakeDs.currentUrl())
        assertEquals("https://b.example", vm.uiState.value.searxngUrl)
        assertFalse(fakeDs.writtenUrls.contains("https://a.example"))
        assertTrue(fakeDs.currentMaster())
        assertTrue(fakeDs.currentConsent())
        // ResetAll must have executed (clearing stale ddgs) despite later queued consent submission.
        assertFalse(fakeDs.currentDdgs())
        assertFalse(vm.uiState.value.consentSubmitting)
        assertFalse(vm.uiState.value.showConsent)
        collector.cancel()

        val vmStore2 = trackStore(ViewModelStore())
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val ioDispatcher = dispatcher
        val store = WebSwitchStore(fakeDs, ioDispatcher)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WebSearchViewModel(store, ioDispatcher) as T
        }
        val vm2 = ViewModelProvider(vmStore2, factory)[WebSearchViewModel::class.java]
        val collector2 = collectUi(vm2)
        vm2.uiState.first { !it.isLoading }
        assertEquals("https://b.example", vm2.uiState.value.searxngUrl)
        assertTrue(vm2.uiState.value.masterEnabled)
        assertFalse(vm2.uiState.value.ddgsOptIn)
        collector2.cancel()
    }

    @Test
    fun inFlightUrlWrite_resetQueued_postResetDraftWins_andPersistsAcrossRecreation() = runTest {
        val firstWriteEntered = CompletableDeferred<Unit>()
        val firstWriteGate = CompletableDeferred<Unit>()
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://old.example"),
        ).apply {
            beforeWrite = { idx ->
                if (idx == 1) {
                    firstWriteEntered.complete(Unit)
                    firstWriteGate.await()
                }
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        trackScheduler()
        val ioDispatcher = dispatcher
        val store = WebSwitchStore(fakeDs, ioDispatcher)
        val vmStore = trackStore(ViewModelStore())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WebSearchViewModel(store, ioDispatcher) as T
            }
        }
        val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onSearxngUrlChange("https://a.example")
        runCurrent()
        firstWriteEntered.await()

        // Reset queued while A is in-flight, then post-reset draft B arrives.
        vm.onResetSettings()
        vm.onSearxngUrlChange("https://b.example")
        runCurrent()
        assertEquals("https://b.example", vm.uiState.value.searxngUrl)

        firstWriteGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("https://b.example", fakeDs.currentUrl())
        assertEquals("https://b.example", vm.uiState.value.searxngUrl)
        assertFalse(fakeDs.currentMaster())
        assertFalse(fakeDs.currentDdgs())
        assertFalse(fakeDs.currentConsent())
        collector.cancel()

        // Recreate VM over same persisted store state: post-reset draft must survive.
        val vmStore2 = trackStore(ViewModelStore())
        val vm2 = ViewModelProvider(vmStore2, factory)[WebSearchViewModel::class.java]
        val collector2 = collectUi(vm2)
        vm2.uiState.first { !it.isLoading }
        assertEquals("https://b.example", vm2.uiState.value.searxngUrl)
        assertEquals("https://b.example", fakeDs.currentUrl())
        collector2.cancel()
    }

    @Test
    fun onResetSettings_failureRestoresPersistedSearxngUrl() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://search.example"),
        ).apply {
            failWritesRemaining = 1
            beforeWrite = { delay(30) }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onResetSettings()
        runCurrent()
        assertEquals("", vm.uiState.value.searxngUrl)

        advanceUntilIdle()
        assertEquals("https://search.example", vm.uiState.value.searxngUrl)
        assertEquals("https://search.example", fakeDs.currentUrl())
        assertNotNull(vm.uiState.value.writeError)
        assertTrue(vm.uiState.value.canReset)
        collector.cancel()
    }

    @Test
    fun onMasterSwitchTrue_vsResetAllLag_reopensConsentInsteadOfWriteError() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
        )
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        // Enqueue resetAll() then onMasterSwitch(true) in the same frame before snapshot updates.
        vm.onResetSettings()
        vm.onMasterSwitch(true)
        advanceUntilIdle()

        // setMasterEnabled(true) ran after resetAll() cleared consentAck -> threw WEB_CONSENT_REQUIRED.
        assertEquals(2, fakeDs.writeCount)
        assertFalse(fakeDs.currentConsent())
        assertFalse(fakeDs.currentMaster())
        assertTrue(vm.uiState.value.showConsent)
        assertNull(vm.uiState.value.writeError)
        collector.cancel()
    }

    @Test
    fun retryLoad_acrossWhileSubscribedOver5sWhileRetrySuspended_keepsLoadingWithoutLoadFailedFlash() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = false, consent = true, url = "https://search.example"),
        ).apply {
            failReadsRemaining = 1
            onSubscribe = { sub ->
                if (sub >= 2) {
                    delay(10_000)
                }
            }
        }
        val vm = installStandardVm(fakeDs)

        val firstJob = launch { vm.uiState.collect {} }
        val failed = vm.uiState.first { it.loadFailed }
        assertTrue(failed.loadFailed)

        vm.retryLoad()
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.loadFailed)
        assertFalse(vm.uiState.value.switchesEnabled)

        firstJob.cancel()
        firstJob.join()

        // Exceed WhileSubscribed(5_000) while retry read is still suspended.
        advanceTimeBy(5_001)
        runCurrent()

        val seen = mutableListOf<WebSearchSettingsState>()
        val secondJob = launch { vm.uiState.collect { seen.add(it) } }
        runCurrent()
        assertEquals(3, fakeDs.readCount)
        assertTrue(seen.isNotEmpty())
        assertTrue("expected isLoading=true and no loadFailed flash, saw $seen", seen.all { it.isLoading && !it.loadFailed && !it.switchesEnabled })

        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertTrue("expected zero loadFailed flashes across resubscribe, saw $seen", seen.none { it.loadFailed })
        val recovered = vm.uiState.value
        assertFalse(recovered.isLoading)
        assertFalse(recovered.loadFailed)
        assertTrue(recovered.switchesEnabled)
        assertTrue(recovered.masterEnabled)
        assertEquals("https://search.example", recovered.searxngUrl)
        secondJob.cancel()
        secondJob.join()
    }

    @Test
    fun retryLoad_midFlightRunCurrentOverlap_cancelsStalledReadAndRecovers() = runTest {
        val sub1Entered = CompletableDeferred<Unit>()
        val sub1Gate = CompletableDeferred<Unit>()
        val sub1Cancelled = CompletableDeferred<Boolean>()
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = true, consent = true, url = "https://recovered.example"),
        ).apply {
            onSubscribe = { sub ->
                if (sub == 1) {
                    sub1Entered.complete(Unit)
                    try {
                        sub1Gate.await()
                    } catch (e: CancellationException) {
                        sub1Cancelled.complete(true)
                        throw e
                    }
                }
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        runCurrent()
        sub1Entered.await()
        assertTrue(vm.uiState.value.isLoading)
        assertEquals(1, fakeDs.readCount)

        vm.retryLoad()
        runCurrent()
        assertTrue(sub1Cancelled.await())
        assertEquals(2, fakeDs.readCount)

        sub1Gate.complete(Unit)
        advanceUntilIdle()
        val recovered = vm.uiState.value
        assertFalse(recovered.isLoading)
        assertFalse(recovered.loadFailed)
        assertTrue(recovered.switchesEnabled)
        assertTrue(recovered.masterEnabled)
        assertTrue(recovered.ddgsOptIn)
        assertEquals("https://recovered.example", recovered.searxngUrl)
        collector.cancel()
    }

    @Test
    fun consentAttemptId_finallyIsolation_multiAttemptResetAndClearedNonCancellable() = runTest {
        // Part 1: attempt #1 suspended -> fails -> finally clears #1's consentSubmitting ->
        // reset + reopen consent -> attempt #2 suspended in flight -> completes cleanly.
        run {
            var gateConsentWrites = true
            val attempt1Entered = CompletableDeferred<Unit>()
            val attempt1Gate = CompletableDeferred<Unit>()
            val attempt2Entered = CompletableDeferred<Unit>()
            val attempt2Gate = CompletableDeferred<Unit>()
            var consentIdx = 0
            val fakeDs = FakePreferencesDataStore(prefs(url = "https://search.example")).apply {
                beforeWrite = {
                    if (gateConsentWrites) {
                        when (++consentIdx) {
                            1 -> {
                                attempt1Entered.complete(Unit)
                                attempt1Gate.await()
                            }
                            2 -> {
                                attempt2Entered.complete(Unit)
                                attempt2Gate.await()
                            }
                        }
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            // Attempt #1 suspended and fails.
            vm.onMasterSwitch(true)
            runCurrent()
            fakeDs.failWritesRemaining = 1
            vm.onConsentConfirm()
            runCurrent()
            attempt1Entered.await()
            assertTrue(vm.uiState.value.consentSubmitting)
            attempt1Gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.consentSubmitting)
            assertTrue(vm.uiState.value.showConsent)
            assertNotNull(vm.uiState.value.writeError)

            // Dismiss + reset + reopen consent + Attempt #2 suspended in flight.
            vm.onConsentDismiss()
            gateConsentWrites = false
            vm.onDdgsSwitch(true)
            vm.onResetSettings()
            advanceUntilIdle()
            gateConsentWrites = true

            vm.onMasterSwitch(true)
            runCurrent()
            assertTrue(vm.uiState.value.showConsent)
            vm.onConsentConfirm()
            runCurrent()
            attempt2Entered.await()
            assertTrue(vm.uiState.value.consentSubmitting)
            assertFalse(vm.uiState.value.switchesEnabled)

            attempt2Gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.consentSubmitting)
            assertFalse(vm.uiState.value.showConsent)
            assertNull(vm.uiState.value.writeError)
            assertTrue(vm.uiState.value.masterEnabled)
            assertTrue(fakeDs.currentConsent())
            assertTrue(fakeDs.currentMaster())
            collector.cancel()
        }

        // Part 2: Queued ConsentConfirm aborted when midStreamFailure arrives ahead of it,
        // plus ViewModelStore.clear() during NonCancellable ConsentConfirm lets persist finish without post-clear state leak.
        run {
            val midFailure = CompletableDeferred<IOException>()
            val urlEntered = CompletableDeferred<Unit>()
            val urlGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(prefs(url = "https://init.example")).apply {
                midStreamFailure = midFailure
                beforeWrite = { idx ->
                    if (idx == 1) {
                        urlEntered.complete(Unit)
                        urlGate.await()
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            vm.onSearxngUrlChange("https://queued.example")
            runCurrent()
            urlEntered.await()

            vm.onMasterSwitch(true)
            runCurrent()
            vm.onConsentConfirm()
            runCurrent()
            assertTrue(vm.uiState.value.consentSubmitting)

            midFailure.complete(IOException("loadFailed before queued ConsentConfirm"))
            runCurrent()
            urlGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, fakeDs.writeCount)
            assertFalse(fakeDs.currentConsent())
            assertFalse(fakeDs.currentMaster())
            assertFalse(vm.uiState.value.consentSubmitting)
            assertFalse(vm.uiState.value.showConsent)
            assertTrue(vm.uiState.value.loadFailed)
            collector.cancel()
        }

        run {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val writeEntered = CompletableDeferred<Unit>()
            val writeGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore().apply {
                beforeWrite = {
                    writeEntered.complete(Unit)
                    writeGate.await()
                }
            }
            val vmStore = trackStore(ViewModelStore())
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WebSearchViewModel(WebSwitchStore(fakeDs, dispatcher), dispatcher) as T
                }
            }
            val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            vm.onMasterSwitch(true)
            runCurrent()
            vm.onConsentConfirm()
            runCurrent()
            writeEntered.await()
            assertTrue(vm.uiState.value.consentSubmitting)

            vmStore.clear()
            runCurrent()
            writeGate.complete(Unit)
            advanceUntilIdle()

            assertTrue(fakeDs.currentConsent())
            assertTrue(fakeDs.currentMaster())
            collector.cancel()
        }
    }

    @Test
    fun dispatchersIo_rapidOrderingAndResetVsDdgs_isDeterministicWithoutPostResetResurrection() = runBlocking {
        val mainExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "web-vm-test-main")
        }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        val vmStore = trackStore(ViewModelStore())
        var vm: WebSearchViewModel? = null
        var collector: Job? = null
        try {
            val fakeDs = FakePreferencesDataStore(
                prefs(master = false, ddgs = false, consent = true, url = "https://init.example"),
            ).apply {
                beforeWrite = { idx ->
                    if (idx % 2 == 1) delay(2)
                }
            }
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WebSearchViewModel(
                        store = WebSwitchStore(fakeDs, Dispatchers.IO),
                        ioDispatcher = Dispatchers.IO,
                    ) as T
                }
            }
            val createdVm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
            vm = createdVm
            val createdCollector = launch(mainDispatcher) { createdVm.uiState.collect {} }
            collector = createdCollector
            withTimeout(5_000) {
                createdVm.uiState.first { !it.isLoading && it.switchesEnabled }
            }

            for (i in 0 until 10) {
                val sentinel = "https://sentinel-master-$i.example"
                val expectedMaster = i % 2 == 1
                if (expectedMaster) {
                    createdVm.onMasterSwitch(false)
                    createdVm.onMasterSwitch(true)
                } else {
                    createdVm.onMasterSwitch(true)
                    createdVm.onMasterSwitch(false)
                }
                createdVm.onSearxngUrlChange(sentinel)
                withTimeout(5_000) {
                    fakeDs.data.first { (it[WEB_SEARXNG_URL_KEY] ?: "") == sentinel }
                    createdVm.uiState.first { it.masterEnabled == expectedMaster && it.searxngUrl == sentinel }
                }
                assertEquals("iteration $i master mismatch", expectedMaster, fakeDs.currentMaster())
            }

            for (i in 0 until 10) {
                val preUrl = "https://pre-reset-$i.example"
                createdVm.onSearxngUrlChange(preUrl)
                withTimeout(5_000) {
                    fakeDs.data.first { (it[WEB_SEARXNG_URL_KEY] ?: "") == preUrl }
                    createdVm.uiState.first { it.searxngUrl == preUrl && it.canReset }
                }
                val postSentinel = "https://post-reset-$i.example"
                createdVm.onDdgsSwitch(true)
                createdVm.onResetSettings()
                createdVm.onSearxngUrlChange(postSentinel)
                withTimeout(5_000) {
                    fakeDs.data.first { (it[WEB_SEARXNG_URL_KEY] ?: "") == postSentinel }
                    createdVm.uiState.first { !it.ddgsOptIn && !it.masterEnabled && it.searxngUrl == postSentinel }
                }
                assertFalse("iteration $i resurrected ddgsOptIn", fakeDs.currentDdgs())
                assertFalse("iteration $i resurrected masterEnabled", fakeDs.currentMaster())
                assertFalse("iteration $i resurrected consentAck", fakeDs.currentConsent())
            }

            createdVm.onDdgsSwitch(true)
            createdVm.onResetSettings()
            withTimeout(5_000) {
                fakeDs.data.first {
                    (it[WEB_SEARXNG_URL_KEY] ?: "").isEmpty() &&
                        it[WEB_DDGS_OPTIN_KEY] == false &&
                        it[WEB_MASTER_ENABLED_KEY] == false &&
                        it[WEB_CONSENT_ACK_KEY] == false
                }
                createdVm.uiState.first {
                    !it.ddgsOptIn && !it.masterEnabled && it.searxngUrl.isEmpty() && !it.canReset
                }
            }
            assertFalse(fakeDs.currentDdgs())
            assertFalse(fakeDs.currentMaster())
            assertFalse(fakeDs.currentConsent())
            assertEquals("", fakeDs.currentUrl())
            createdCollector.cancel()
            createdCollector.join()
        } finally {
            try {
                collector?.cancel()
                collector?.let { withTimeout(5_000) { it.join() } }
            } finally {
                try {
                    vmStore.clear()
                    vm?.viewModelScope?.coroutineContext?.get(Job)?.let { job ->
                        withTimeout(5_000) { job.join() }
                    }
                } finally {
                    Dispatchers.resetMain()
                    mainExecutor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun combine_urlDirtyClearedOnSnapshotArrival_neverFlashesOldUrl() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        trackScheduler()
        val fakeDs = FakePreferencesDataStore(
            prefs(master = true, ddgs = false, consent = true, url = "https://old.example"),
        ).apply {
            beforeWrite = { delay(20) }
        }
        val vmStore = trackStore(ViewModelStore())
        val vm = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
        })[WebSearchViewModel::class.java]
        val seen = mutableListOf<WebSearchSettingsState>()
        val collector = backgroundScope.launch(mainDispatcher) {
            vm.uiState.collect { seen.add(it) }
        }
        vm.uiState.first { !it.isLoading }
        assertEquals("https://old.example", vm.uiState.value.searxngUrl)

        val beforeType = seen.size
        vm.onSearxngUrlChange("https://new.example")
        advanceUntilIdle()

        val afterType = seen.drop(beforeType)
        assertTrue(afterType.isNotEmpty())
        assertEquals("https://new.example", afterType.first().searxngUrl)
        assertTrue(
            "expected no flash back to old URL while clearing urlDirty, saw ${afterType.map { it.searxngUrl }}",
            afterType.all { it.searxngUrl == "https://new.example" },
        )
        assertEquals("https://new.example", fakeDs.currentUrl())
        assertEquals("https://new.example", vm.uiState.value.searxngUrl)
        collector.cancel()
    }

    @Test
    fun combine_midStreamLoadFailed_neverEmitsIntermediateEnabledFrameAndClearsWriteError() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        trackScheduler()

        for (initialUrl in listOf("", "https://old.example")) {
            val midFailure = CompletableDeferred<IOException>()
            val fakeDs = FakePreferencesDataStore(
                prefs(master = true, ddgs = true, consent = true, url = initialUrl),
            ).apply {
                midStreamFailure = midFailure
            }
            val vmStore = trackStore(ViewModelStore())
            val vm = ViewModelProvider(vmStore, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
            })[WebSearchViewModel::class.java]
            val seen = mutableListOf<WebSearchSettingsState>()
            val collector = backgroundScope.launch(mainDispatcher) {
                vm.uiState.collect { seen.add(it) }
            }
            vm.uiState.first { !it.isLoading }

            vm.onSearxngUrlChange("https://user:pass@bad.example")
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.writeError)
            assertEquals("https://user:pass@bad.example", vm.uiState.value.searxngUrl)

            val beforeFailure = seen.size
            midFailure.complete(IOException("mid-stream boom"))
            advanceUntilIdle()

            val afterFailure = seen.drop(beforeFailure)
            assertTrue(afterFailure.isNotEmpty())
            assertTrue(
                "expected no intermediate switchesEnabled && !loadFailed frame, saw $afterFailure",
                afterFailure.none { it.switchesEnabled && !it.loadFailed },
            )
            assertTrue(vm.uiState.value.loadFailed)
            assertFalse(vm.uiState.value.switchesEnabled)
            assertEquals("", vm.uiState.value.searxngUrl)
            assertNull(vm.uiState.value.writeError)
            collector.cancel()
        }
    }

    @Test
    fun defaultProbeProvider_zeroNetworkAcrossBlankInvalidAndValid_andDirectRecoveryAfterProbeException() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        trackScheduler()
        val fakeDs = FakePreferencesDataStore(prefs(url = ""))
        val defaultStore = trackStore(ViewModelStore())
        val defaultVm = ViewModelProvider(defaultStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WebSearchViewModel(WebSwitchStore(fakeDs, dispatcher), dispatcher) as T
        })[WebSearchViewModel::class.java]
        val collector = collectUi(defaultVm)
        defaultVm.uiState.first { !it.isLoading }

        defaultVm.onProbe()
        advanceUntilIdle()
        assertEquals(
            "設定檢查（不連線）— SearXNG：缺 URL；DDGS：設定格式有效（未連網）",
            defaultVm.uiState.value.probeMessage,
        )

        defaultVm.onSearxngUrlChange("ftp://bad")
        advanceUntilIdle()
        defaultVm.onProbe()
        advanceUntilIdle()
        assertEquals(
            "設定檢查（不連線）— SearXNG：URL 格式無效；DDGS：設定格式有效（未連網）",
            defaultVm.uiState.value.probeMessage,
        )

        defaultVm.onSearxngUrlChange("https://search.example")
        advanceUntilIdle()
        defaultVm.onProbe()
        advanceUntilIdle()
        assertEquals(
            "設定檢查（不連線）— SearXNG：設定格式有效（未連網）；DDGS：設定格式有效（未連網）",
            defaultVm.uiState.value.probeMessage,
        )
        collector.cancel()

        // Direct onProbe() recovery from exception without editing URL first.
        var shouldThrow = true
        val recoverDs = FakePreferencesDataStore(prefs(url = "https://search.example"))
        val recoverStore = trackStore(ViewModelStore())
        val recoverVm = ViewModelProvider(recoverStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WebSearchViewModel(
                    store = WebSwitchStore(recoverDs, dispatcher),
                    ioDispatcher = dispatcher,
                    probeProvider = { url ->
                        if (shouldThrow) {
                            throw IllegalStateException("transient probe failure")
                        }
                        WebSearchSettings.probeSearxngUrl(url) to WebProbe.Ready
                    },
                ) as T
        })[WebSearchViewModel::class.java]
        val recoverCollector = collectUi(recoverVm)
        recoverVm.uiState.first { !it.isLoading }

        recoverVm.onProbe()
        advanceUntilIdle()
        assertEquals(
            "設定檢查（不連線）— SearXNG：未就緒；DDGS：未就緒",
            recoverVm.uiState.value.probeMessage,
        )

        shouldThrow = false
        recoverVm.onProbe()
        advanceUntilIdle()
        assertEquals(
            "設定檢查（不連線）— SearXNG：設定格式有效（未連網）；DDGS：設定格式有效（未連網）",
            recoverVm.uiState.value.probeMessage,
        )
        recoverCollector.cancel()
    }

    @Test
    fun ddgsSwitchVsResetAll_deterministicDeferredRaces() = runTest {
        // Case 1: DdgsSwitch(true) suspended mid-store.setDdgsOptIn(true) when onResetSettings() is called.
        run {
            val ddgsEntered = CompletableDeferred<Unit>()
            val ddgsGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(
                prefs(master = true, ddgs = false, consent = true, url = "https://old.example"),
            ).apply {
                beforeWrite = { idx ->
                    if (idx == 1) {
                        ddgsEntered.complete(Unit)
                        ddgsGate.await()
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            vm.onDdgsSwitch(true)
            runCurrent()
            ddgsEntered.await()

            vm.onResetSettings()
            runCurrent()
            ddgsGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, fakeDs.writeCount)
            assertFalse(fakeDs.currentDdgs())
            assertFalse(fakeDs.currentConsent())
            assertFalse(fakeDs.currentMaster())
            assertEquals("", fakeDs.currentUrl())
            assertFalse(vm.uiState.value.ddgsOptIn)
            assertFalse(vm.uiState.value.masterEnabled)
            assertEquals("", vm.uiState.value.searxngUrl)
            assertFalse(vm.uiState.value.canReset)
            collector.cancel()
        }

        // Case 2: DdgsSwitch(true) queued behind another suspended write when onResetSettings() bumps resetGeneration.
        run {
            val firstWriteEntered = CompletableDeferred<Unit>()
            val firstWriteGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(
                prefs(master = true, ddgs = false, consent = true, url = "https://old.example"),
            ).apply {
                beforeWrite = { idx ->
                    if (idx == 1) {
                        firstWriteEntered.complete(Unit)
                        firstWriteGate.await()
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            vm.onSearxngUrlChange("https://first.example")
            runCurrent()
            firstWriteEntered.await()

            vm.onDdgsSwitch(true)
            vm.onResetSettings()
            runCurrent()
            firstWriteGate.complete(Unit)
            advanceUntilIdle()

            // Only first UrlWrite + ResetAll executed; stale DdgsSwitch was skipped.
            assertEquals(2, fakeDs.writeCount)
            assertFalse(fakeDs.currentDdgs())
            assertFalse(fakeDs.currentConsent())
            assertFalse(fakeDs.currentMaster())
            assertEquals("", fakeDs.currentUrl())
            assertFalse(vm.uiState.value.ddgsOptIn)
            assertFalse(vm.uiState.value.canReset)
            collector.cancel()
        }

        // Case 3: onDdgsSwitch(true) issued while ResetAll is suspended -> post-reset ddgsOptIn survives.
        run {
            val resetEntered = CompletableDeferred<Unit>()
            val resetGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(
                prefs(master = true, ddgs = false, consent = true, url = "https://old.example"),
            ).apply {
                beforeWrite = { idx ->
                    if (idx == 1) {
                        resetEntered.complete(Unit)
                        resetGate.await()
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val collector = collectUi(vm)
            vm.uiState.first { !it.isLoading }

            vm.onResetSettings()
            runCurrent()
            resetEntered.await()

            vm.onDdgsSwitch(true)
            runCurrent()
            resetGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, fakeDs.writeCount)
            assertTrue(fakeDs.currentDdgs())
            assertFalse(fakeDs.currentMaster())
            assertFalse(fakeDs.currentConsent())
            assertEquals("", fakeDs.currentUrl())
            assertTrue(vm.uiState.value.ddgsOptIn)
            assertFalse(vm.uiState.value.masterEnabled)
            assertEquals("", vm.uiState.value.searxngUrl)
            assertTrue(vm.uiState.value.canReset)
            collector.cancel()
        }
    }

    @Test
    fun inFlightWrites_midStreamLoadFailedBeforeWriteException_stayFailClosedAndRecoverCleanly() = runTest {
        suspend fun TestScope.verifyCase(
            label: String,
            initial: Preferences,
            prepare: suspend TestScope.(WebSearchViewModel) -> Unit = {},
            trigger: (WebSearchViewModel) -> Unit,
            assertRecovered: (WebSearchViewModel, FakePreferencesDataStore, List<WebSearchSettingsState>) -> Unit,
        ) {
            val midFailure = CompletableDeferred<IOException>()
            val writeEntered = CompletableDeferred<Unit>()
            val writeGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(initial).apply {
                midStreamFailure = midFailure
                beforeWrite = { idx ->
                    if (idx == 1) {
                        writeEntered.complete(Unit)
                        writeGate.await()
                    }
                }
            }
            val vm = installStandardVm(fakeDs)
            val seen = mutableListOf<WebSearchSettingsState>()
            val collector = collectUi(vm, seen)
            vm.uiState.first { !it.isLoading }

            this.prepare(vm)
            trigger(vm)
            runCurrent()
            writeEntered.await()

            val beforeMidFailureIdx = seen.size
            midFailure.complete(IOException("mid-stream boom ($label)"))
            runCurrent()
            assertTrue("$label: expected loadFailed", vm.uiState.value.loadFailed)

            fakeDs.failWritesRemaining = 1
            writeGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("$label: expected 1 failed write attempt", 1, fakeDs.writeCount)
            assertTrue("$label: loadFailed must stay true", vm.uiState.value.loadFailed)
            assertNull("$label: writeError must be null during loadFailed", vm.uiState.value.writeError)
            assertFalse("$label: switchesEnabled must be false", vm.uiState.value.switchesEnabled)
            assertEquals("$label: searxngUrl must be empty", "", vm.uiState.value.searxngUrl)
            assertFalse("$label: showConsent must be false", vm.uiState.value.showConsent)
            assertFalse("$label: consentSubmitting must be false", vm.uiState.value.consentSubmitting)
            val duringLoadFailed = seen.drop(beforeMidFailureIdx)
            assertTrue("$label: expected loadFailed frames", duringLoadFailed.isNotEmpty())
            assertTrue(
                "$label: expected fail-closed frames with null writeError, saw $duringLoadFailed",
                duringLoadFailed.all {
                    it.loadFailed &&
                        it.writeError == null &&
                        !it.switchesEnabled &&
                        it.searxngUrl.isEmpty() &&
                        !it.showConsent
                },
            )

            val beforeRetryIdx = seen.size
            vm.retryLoad()
            advanceUntilIdle()

            val afterRetry = seen.drop(beforeRetryIdx)
            assertTrue("$label: expected retry frames", afterRetry.isNotEmpty())
            assertTrue(
                "$label: expected no writeError after retryLoad, saw $afterRetry",
                afterRetry.none { it.writeError != null },
            )
            assertFalse("$label: should finish loading", vm.uiState.value.isLoading)
            assertFalse("$label: loadFailed should clear", vm.uiState.value.loadFailed)
            assertNull("$label: writeError must be null after recovery", vm.uiState.value.writeError)
            assertTrue("$label: switchesEnabled should recover", vm.uiState.value.switchesEnabled)
            assertRecovered(vm, fakeDs, afterRetry)
            collector.cancel()
        }

        verifyCase(
            label = "UrlWrite",
            initial = prefs(master = true, ddgs = false, consent = true, url = "https://initial.example"),
            trigger = { it.onSearxngUrlChange("https://draft-failed.example") },
            assertRecovered = { vm, ds, afterRetry ->
                assertEquals("https://initial.example", vm.uiState.value.searxngUrl)
                assertEquals("https://initial.example", ds.currentUrl())
                assertTrue(afterRetry.none { it.searxngUrl == "https://draft-failed.example" })
            },
        )

        verifyCase(
            label = "MasterSwitch",
            initial = prefs(master = true, ddgs = false, consent = true, url = "https://initial.example"),
            trigger = { it.onMasterSwitch(false) },
            assertRecovered = { vm, ds, _ ->
                assertEquals("https://initial.example", vm.uiState.value.searxngUrl)
                assertTrue(vm.uiState.value.masterEnabled)
                assertTrue(ds.currentMaster())
            },
        )

        verifyCase(
            label = "DdgsSwitch",
            initial = prefs(master = true, ddgs = false, consent = true, url = "https://initial.example"),
            trigger = { it.onDdgsSwitch(true) },
            assertRecovered = { vm, ds, _ ->
                assertEquals("https://initial.example", vm.uiState.value.searxngUrl)
                assertFalse(vm.uiState.value.ddgsOptIn)
                assertFalse(ds.currentDdgs())
            },
        )

        verifyCase(
            label = "ConsentConfirm",
            initial = prefs(master = false, ddgs = false, consent = false, url = "https://initial.example"),
            prepare = { vm ->
                vm.onMasterSwitch(true)
                runCurrent()
                assertTrue(vm.uiState.value.showConsent)
            },
            trigger = { it.onConsentConfirm() },
            assertRecovered = { vm, ds, _ ->
                assertEquals("https://initial.example", vm.uiState.value.searxngUrl)
                assertFalse(vm.uiState.value.showConsent)
                assertFalse(vm.uiState.value.consentSubmitting)
                assertFalse(vm.uiState.value.masterEnabled)
                assertFalse(ds.currentConsent())
            },
        )

        verifyCase(
            label = "ResetAll",
            initial = prefs(master = true, ddgs = true, consent = true, url = "https://initial.example"),
            trigger = { it.onResetSettings() },
            assertRecovered = { vm, ds, _ ->
                assertEquals("https://initial.example", vm.uiState.value.searxngUrl)
                assertEquals("https://initial.example", ds.currentUrl())
                assertTrue(vm.uiState.value.masterEnabled)
                assertTrue(vm.uiState.value.ddgsOptIn)
                assertTrue(vm.uiState.value.canReset)
            },
        )
    }

    @Test
    fun outOfBandConsentAck_clearsInternalShowConsentWithoutResurrection() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = false, consent = false, url = "https://search.example"),
        )
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        vm.onMasterSwitch(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showConsent)
        assertEquals(0, fakeDs.writeCount)

        // Out-of-band DataStore update sets consent = true -> clears InternalState.showConsent.
        fakeDs.updateData { cur ->
            cur.toMutablePreferences().apply {
                this[WEB_CONSENT_ACK_KEY] = true
            }.toPreferences()
        }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)

        // Resetting DataStore out-of-band back to consent = false, master = false must not resurrect showConsent.
        fakeDs.updateData { cur ->
            cur.toMutablePreferences().apply {
                this[WEB_CONSENT_ACK_KEY] = false
                this[WEB_MASTER_ENABLED_KEY] = false
            }.toPreferences()
        }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)
        assertFalse(vm.uiState.value.masterEnabled)

        // Also verify out-of-band consent = true, master = true clears InternalState.showConsent without resurrection.
        vm.onMasterSwitch(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showConsent)

        fakeDs.updateData { cur ->
            cur.toMutablePreferences().apply {
                this[WEB_CONSENT_ACK_KEY] = true
                this[WEB_MASTER_ENABLED_KEY] = true
            }.toPreferences()
        }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)
        assertTrue(vm.uiState.value.masterEnabled)

        fakeDs.updateData { cur ->
            cur.toMutablePreferences().apply {
                this[WEB_CONSENT_ACK_KEY] = false
                this[WEB_MASTER_ENABLED_KEY] = false
            }.toPreferences()
        }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)
        assertFalse(vm.uiState.value.masterEnabled)
        collector.cancel()
    }

    @Test
    fun masterSwitch_consentRequiredCatchAfterOutOfBandConsentAck_doesNotResurrectShowConsent() = runTest {
        val writeEntered = CompletableDeferred<Unit>()
        val writeGate = CompletableDeferred<Unit>()
        var rejectConsentRequired = false
        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = false, consent = false, url = "https://search.example"),
        ).apply {
            beforeWrite = {
                if (rejectConsentRequired) {
                    rejectConsentRequired = false
                    writeEntered.complete(Unit)
                    writeGate.await()
                    throw IllegalStateException("WEB_CONSENT_REQUIRED")
                }
            }
        }
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }

        // Open consent from default (consent = false, master = false), then acknowledge out-of-band.
        vm.onMasterSwitch(true)
        runCurrent()
        assertTrue(vm.uiState.value.showConsent)

        fakeDs.updateData { cur ->
            cur.toMutablePreferences().apply {
                this[WEB_CONSENT_ACK_KEY] = true
            }.toPreferences()
        }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)

        // Dispatch MasterSwitch(true) while snapshot says consentAck = true; store write pauses and rejects
        // with WEB_CONSENT_REQUIRED after an out-of-band false -> true transition has updated snapshotDriver.
        rejectConsentRequired = true
        vm.onMasterSwitch(true)
        runCurrent()
        writeEntered.await()

        fakeDs.setOutOfBand(prefs(master = false, ddgs = false, consent = false, url = "https://search.example"))
        runCurrent()
        fakeDs.setOutOfBand(prefs(master = false, ddgs = false, consent = true, url = "https://search.example"))
        runCurrent()
        assertFalse(vm.uiState.value.showConsent)

        writeGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)
        assertNull(vm.uiState.value.writeError)

        // Later out-of-band reset to consent = false, master = false must not resurrect showConsent.
        fakeDs.updateData { cur ->
            cur.toMutablePreferences().apply {
                this[WEB_CONSENT_ACK_KEY] = false
                this[WEB_MASTER_ENABLED_KEY] = false
            }.toPreferences()
        }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConsent)
        assertFalse(vm.uiState.value.masterEnabled)
        assertFalse(fakeDs.currentConsent())
        assertFalse(fakeDs.currentMaster())
        collector.cancel()
    }

    @Test
    fun preResetWriteEmissionOverlappingResetAll_beforeOnMasterSwitchTrue_reopensConsent() = runTest {
        // (a) onDdgsSwitch(true) completes write, then onResetSettings() starts store.resetAll() (paused in beforeWrite),
        // while paused Main processes Snap_PreReset (ddgs = true, consent = true), then store.resetAll() completes
        // and onMasterSwitch(true) catches WEB_CONSENT_REQUIRED before Snap_Reset reaches snapshotDriver ->
        // when Snap_Reset arrives, showConsent == true.
        run {
            val ioScheduler = trackScheduler(TestCoroutineScheduler())
            val ioDispatcher = StandardTestDispatcher(ioScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)

            val resetEntered = CompletableDeferred<Unit>()
            val resetGate = CompletableDeferred<Unit>()
            val fakeDs = FakePreferencesDataStore(
                prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
            ).apply {
                beforeWrite = { idx ->
                    if (idx == 2) {
                        resetEntered.complete(Unit)
                        resetGate.await()
                    }
                }
            }
            val vmStore = trackStore(ViewModelStore())
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
                }
            }
            val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
            val collector = collectUi(vm)
            try {
                vm.uiState.first { !it.isLoading }
                ioScheduler.runCurrent()

                vm.onDdgsSwitch(true)
                ioScheduler.runCurrent()
                assertEquals(1, fakeDs.writeCount)
                assertTrue(fakeDs.currentDdgs())

                vm.onResetSettings()
                ioScheduler.runCurrent()
                resetEntered.await()

                // While resetAll() is paused, Main processes Snap_PreReset (ddgs = true, consent = true).
                runCurrent()
                assertTrue(vm.uiState.value.ddgsOptIn)
                assertFalse(vm.uiState.value.masterEnabled)

                // Complete store.resetAll(), then onMasterSwitch(true) catches WEB_CONSENT_REQUIRED
                // before Snap_Reset reaches snapshotDriver.
                resetGate.complete(Unit)
                ioScheduler.runCurrent()
                assertEquals(2, fakeDs.writeCount)
                assertFalse(fakeDs.currentConsent())

                vm.onMasterSwitch(true)
                ioScheduler.runCurrent()
                assertEquals(3, fakeDs.writeCount)
                assertFalse(vm.uiState.value.showConsent)

                ioScheduler.advanceUntilIdle()
                advanceUntilIdle()

                assertTrue(vm.uiState.value.showConsent)
                assertNull(vm.uiState.value.writeError)
                assertFalse(vm.uiState.value.masterEnabled)
                assertFalse(vm.uiState.value.ddgsOptIn)
                assertEquals("", vm.uiState.value.searxngUrl)
                assertFalse(fakeDs.currentConsent())
                assertFalse(fakeDs.currentMaster())
            } finally {
                resetGate.complete(Unit)
                collector.cancel()
                vmStore.clear()
                ioScheduler.advanceUntilIdle()
            }
        }

        // (b) Snap_PreReset (consent = true) reaches snapshotDriver AFTER handleMasterSwitch catches WEB_CONSENT_REQUIRED,
        // then Snap_Reset (consent = false) arrives -> showConsent == true.
        run {
            val ioScheduler = trackScheduler(TestCoroutineScheduler())
            val ioDispatcher = StandardTestDispatcher(ioScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)

            val preResetCaptured = CompletableDeferred<Unit>()
            val preResetDeliverGate = CompletableDeferred<Unit>()
            val resetCaptured = CompletableDeferred<Unit>()
            val resetDeliverGate = CompletableDeferred<Unit>()
            var interceptPreReset = false
            val fakeDs = FakePreferencesDataStore(
                prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
            ).apply {
                beforeEmit = { sub, emitted ->
                    if (sub == 1 && interceptPreReset) {
                        if (emitted[WEB_DDGS_OPTIN_KEY] == true) {
                            preResetCaptured.complete(Unit)
                            preResetDeliverGate.await()
                        } else if (emitted[WEB_CONSENT_ACK_KEY] != true) {
                            resetCaptured.complete(Unit)
                            resetDeliverGate.await()
                        }
                    }
                }
            }
            val vmStore = trackStore(ViewModelStore())
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
                }
            }
            val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
            val collector = collectUi(vm)
            try {
                vm.uiState.first { !it.isLoading }
                ioScheduler.runCurrent()

                interceptPreReset = true
                vm.onDdgsSwitch(true)
                ioScheduler.runCurrent()
                assertEquals(1, fakeDs.writeCount)
                assertTrue(fakeDs.currentDdgs())

                // Main pulls Snap_PreReset (ddgs = true, consent = true) and suspends before snapshotDriver processes it.
                runCurrent()
                preResetCaptured.await()
                assertFalse(vm.uiState.value.ddgsOptIn)

                vm.onResetSettings()
                vm.onMasterSwitch(true)
                ioScheduler.runCurrent()
                assertEquals(3, fakeDs.writeCount)
                assertFalse(fakeDs.currentConsent())
                assertFalse(vm.uiState.value.showConsent)

                // Now Snap_PreReset reaches snapshotDriver AFTER handleMasterSwitch caught WEB_CONSENT_REQUIRED.
                preResetDeliverGate.complete(Unit)
                runCurrent()
                ioScheduler.runCurrent()
                runCurrent()
                resetCaptured.await()
                assertTrue(vm.uiState.value.ddgsOptIn)
                assertFalse(vm.uiState.value.showConsent)

                // Then Snap_Reset (consent = false) arrives -> promotes pendingConsentReopen to showConsent == true.
                resetDeliverGate.complete(Unit)
                ioScheduler.advanceUntilIdle()
                advanceUntilIdle()

                assertTrue(vm.uiState.value.showConsent)
                assertNull(vm.uiState.value.writeError)
                assertFalse(vm.uiState.value.masterEnabled)
                assertFalse(vm.uiState.value.ddgsOptIn)
                assertEquals("", vm.uiState.value.searxngUrl)
                assertFalse(fakeDs.currentConsent())
                assertFalse(fakeDs.currentMaster())
            } finally {
                preResetDeliverGate.complete(Unit)
                resetDeliverGate.complete(Unit)
                collector.cancel()
                vmStore.clear()
                ioScheduler.advanceUntilIdle()
            }
        }
    }

    @Test
    fun outOfBandConsentRevocationLag_onMasterSwitchTrue_reopensConsentWithoutWriteError() = runTest {
        val ioScheduler = trackScheduler(TestCoroutineScheduler())
        val ioDispatcher = StandardTestDispatcher(ioScheduler)
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)

        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
        )
        val vmStore = trackStore(ViewModelStore())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
            }
        }
        val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
        val collector = collectUi(vm)
        try {
            vm.uiState.first { !it.isLoading }
            ioScheduler.runCurrent()

            // Revoke consent out-of-band WITHOUT draining snapshotDriver on Main first.
            fakeDs.setOutOfBand(prefs(master = false, ddgs = false, consent = false, url = "https://search.example"))
            vm.onMasterSwitch(true)
            ioScheduler.runCurrent()
            assertEquals(1, fakeDs.writeCount)

            ioScheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.showConsent)
            assertNull(vm.uiState.value.writeError)
            assertFalse(vm.uiState.value.masterEnabled)
            assertFalse(fakeDs.currentConsent())
            assertFalse(fakeDs.currentMaster())
        } finally {
            collector.cancel()
            vmStore.clear()
            ioScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun pendingConsentReopen_conflatedFalseToTrueAndWhileSubscribedGap_clearsPendingAndNeverResurrects() = runTest {
        // (a) Armed pendingConsentReopen* + out-of-band false -> true conflation (snapshotDriver sees true -> true)
        // clears pendingConsentReopen* via live currentConsentAndMaster() check and never resurrects on later false.
        run {
            val ioScheduler = trackScheduler(TestCoroutineScheduler())
            val ioDispatcher = StandardTestDispatcher(ioScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)

            val fakeDs = FakePreferencesDataStore(
                prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
            )
            val vmStore = trackStore(ViewModelStore())
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
                }
            }
            val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
            val collector = collectUi(vm)
            try {
                vm.uiState.first { !it.isLoading }
                ioScheduler.runCurrent()

                // Arm pendingConsentReopen* before snapshotDriver consumes Snap_Reset.
                vm.onResetSettings()
                vm.onMasterSwitch(true)
                ioScheduler.runCurrent()
                assertEquals(2, fakeDs.readCount)
                assertFalse(vm.uiState.value.showConsent)

                // Out-of-band false -> true without intermediate runCurrent() conflates to true on snapshotDriver.
                fakeDs.setOutOfBand(prefs(master = false, ddgs = false, consent = false, url = ""))
                fakeDs.setOutOfBand(prefs(master = false, ddgs = false, consent = true, url = ""))

                runCurrent()
                ioScheduler.runCurrent()
                runCurrent()
                ioScheduler.advanceUntilIdle()
                advanceUntilIdle()

                assertFalse(vm.uiState.value.showConsent)
                assertNull(vm.uiState.value.writeError)
                assertEquals(3, fakeDs.readCount)

                // Subsequent out-of-band revocation must NOT resurrect showConsent.
                fakeDs.setOutOfBand(prefs(master = false, ddgs = false, consent = false, url = ""))
                ioScheduler.advanceUntilIdle()
                advanceUntilIdle()

                assertFalse(vm.uiState.value.showConsent)
                assertNull(vm.uiState.value.writeError)
            } finally {
                collector.cancel()
                vmStore.clear()
                ioScheduler.advanceUntilIdle()
            }
        }

        // (b) Armed pendingConsentReopen* + unsubscribe >5s + out-of-band consent=true while unsubscribed +
        // resubscribe clears pendingConsentReopen* and never resurrects on later consent=false.
        run {
            val ioScheduler = trackScheduler(TestCoroutineScheduler())
            val ioDispatcher = StandardTestDispatcher(ioScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)

            val resetEmitCaptured = CompletableDeferred<Unit>()
            val resetEmitGate = CompletableDeferred<Unit>()
            var blockResetEmit = false
            val fakeDs = FakePreferencesDataStore(
                prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
            ).apply {
                beforeEmit = { sub, emitted ->
                    if (sub == 1 && blockResetEmit && emitted[WEB_CONSENT_ACK_KEY] != true) {
                        resetEmitCaptured.complete(Unit)
                        resetEmitGate.await()
                    }
                }
            }
            val vmStore = trackStore(ViewModelStore())
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
                }
            }
            val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
            val collector = collectUi(vm)
            var collector2: Job? = null
            try {
                vm.uiState.first { !it.isLoading }
                ioScheduler.runCurrent()

                blockResetEmit = true
                vm.onResetSettings()
                vm.onMasterSwitch(true)
                ioScheduler.runCurrent()
                assertEquals(2, fakeDs.readCount)

                // Park sub == 1 before Snap_Reset reaches snapshotDriver while pendingConsentReopen* is armed.
                runCurrent()
                resetEmitCaptured.await()
                assertFalse(vm.uiState.value.showConsent)

                // Unsubscribe collector and exceed WhileSubscribed(5_000) so upstream cancels before Snap_Reset.
                collector.cancel()
                collector.join()
                advanceTimeBy(5_001)
                runCurrent()

                // Out-of-band consent=true while unsubscribed.
                fakeDs.setOutOfBand(
                    prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
                )

                // Resubscribe -> snapshotDriver checks live store, sees consent=true, clears pendingConsentReopen*.
                collector2 = collectUi(vm)
                runCurrent()
                ioScheduler.runCurrent()
                runCurrent()
                ioScheduler.advanceUntilIdle()
                advanceUntilIdle()

                assertFalse(vm.uiState.value.showConsent)
                assertNull(vm.uiState.value.writeError)
                assertEquals(4, fakeDs.readCount)

                // Later out-of-band consent=false must NOT resurrect showConsent.
                fakeDs.setOutOfBand(
                    prefs(master = false, ddgs = false, consent = false, url = "https://search.example"),
                )
                ioScheduler.advanceUntilIdle()
                advanceUntilIdle()

                assertFalse(vm.uiState.value.showConsent)
                assertNull(vm.uiState.value.writeError)
            } finally {
                resetEmitGate.complete(Unit)
                collector.cancel()
                collector2?.cancel()
                vmStore.clear()
                ioScheduler.advanceUntilIdle()
            }
        }
    }

    @Test
    fun masterSwitch_consentRequiredCatchToctouAckBeforeUpdateState_rechecksAndDoesNotArm() = runTest {
        val ioScheduler = trackScheduler(TestCoroutineScheduler())
        val ioDispatcher = StandardTestDispatcher(ioScheduler)
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)

        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
        )
        fakeDs.afterRead = { sub ->
            if (sub == 2) {
                // Force snapshotDriver to process consent=false -> true (bumping snapshotEpoch)
                // right after currentConsentAndMaster() read (false, false) and before updateState runs.
                testScheduler.runCurrent()
                fakeDs.setOutOfBand(
                    prefs(master = false, ddgs = false, consent = true, url = "https://search.example"),
                )
                testScheduler.runCurrent()
            }
        }
        val vmStore = trackStore(ViewModelStore())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WebSearchViewModel(WebSwitchStore(fakeDs, ioDispatcher), ioDispatcher) as T
            }
        }
        val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
        val collector = collectUi(vm)
        try {
            vm.uiState.first { !it.isLoading }
            ioScheduler.runCurrent()
            assertEquals(1, fakeDs.readCount)

            // Revoke consent out-of-band without draining Main so onMasterSwitch(true) sends MasterSwitch(true)
            // and hits WEB_CONSENT_REQUIRED.
            fakeDs.setOutOfBand(
                prefs(master = false, ddgs = false, consent = false, url = "https://search.example"),
            )
            vm.onMasterSwitch(true)
            ioScheduler.runCurrent()
            ioScheduler.advanceUntilIdle()
            advanceUntilIdle()

            // Attempt 1 read (false, false) on sub == 2, detected snapshotEpoch change, retried on sub == 3
            // reading (true, false), and exited without arming pendingConsentReopen*.
            assertEquals(3, fakeDs.readCount)
            assertFalse(vm.uiState.value.showConsent)
            assertNull(vm.uiState.value.writeError)

            // Subsequent out-of-band consent=false must NOT resurrect showConsent.
            fakeDs.setOutOfBand(
                prefs(master = false, ddgs = false, consent = false, url = "https://search.example"),
            )
            ioScheduler.advanceUntilIdle()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.showConsent)
            assertNull(vm.uiState.value.writeError)
        } finally {
            fakeDs.afterRead = null
            collector.cancel()
            vmStore.clear()
            ioScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun canReset_remainsTrueWhenClearingPersistedUrlFails() = runTest {
        val fakeDs = FakePreferencesDataStore(
            prefs(master = false, ddgs = false, consent = false, url = "https://search.example"),
        )
        val vm = installStandardVm(fakeDs)
        val collector = collectUi(vm)
        vm.uiState.first { !it.isLoading }
        assertTrue(vm.uiState.value.canReset)

        fakeDs.failWritesRemaining = 1
        vm.onSearxngUrlChange("")
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.writeError)
        assertEquals("", vm.uiState.value.searxngUrl)
        assertEquals("https://search.example", fakeDs.currentUrl())
        assertTrue(vm.uiState.value.canReset)

        // Reset still clears persisted URL and resets canReset to false.
        vm.onResetSettings()
        advanceUntilIdle()
        assertNull(vm.uiState.value.writeError)
        assertEquals("", fakeDs.currentUrl())
        assertFalse(vm.uiState.value.canReset)
        collector.cancel()
    }
}
