package dev.librepocket.web

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.WebSearchLocal
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D3 WebSwitchStore 真相源測試（純 JVM，暫存目錄隔離）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebSwitchStoreTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        closeActiveStores()
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(
        dir: java.io.File = Files.createTempDirectory("web-test").toFile().also {
            tmpDirs.add(it)
        },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): WebSwitchStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "web.preferences_pb") },
        )
        return WebSwitchStore(dataStore, ioDispatcher)
    }

    private fun closeActiveStores() {
        runBlocking {
            scopes.forEach { it.cancel() }
            scopes.forEach { it.coroutineContext[Job]?.join() }
            scopes.clear()
        }
    }

    private class SpyPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        val editCount = AtomicInteger(0)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            editCount.incrementAndGet()
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Test fun freshDefaultsAreAllClosed() = runBlocking {
        val s = newStore().observeState().first { it.loaded }
        assertFalse(s.loadFailed)
        assertFalse(s.masterEnabled)
        assertFalse(s.ddgsOptIn)
        assertFalse(s.consentAck)
        assertEquals("", s.searxngBaseUrl)
    }

    @Test fun coldStart_persistedOnStaysClosedOnFirstFrame() = runBlocking {
        val dir = Files.createTempDirectory("web-test").toFile().also { tmpDirs.add(it) }
        val writer = newStore(dir)
        writer.confirmConsentAndEnableMaster()
        writer.setDdgsOptIn(true)
        writer.setSearxngBaseUrl("https://search.example")
        closeActiveStores()

        val reader = newStore(dir)
        val firstFrame = reader.observeState().first()
        assertEquals(WebSearchSnapshot.LOADING, firstFrame)
        assertFalse(firstFrame.loaded)
        assertFalse(firstFrame.loadFailed)
        assertFalse(firstFrame.masterEnabled)
        assertFalse(firstFrame.ddgsOptIn)
        assertFalse(firstFrame.consentAck)
        assertEquals("", firstFrame.searxngBaseUrl)

        val loaded = reader.observeState().first { it.loaded }
        assertFalse(loaded.loadFailed)
        assertTrue(loaded.masterEnabled)
        assertTrue(loaded.ddgsOptIn)
        assertTrue(loaded.consentAck)
        assertEquals("https://search.example", loaded.searxngBaseUrl)
    }

    @Test fun writesPersistAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("web-test").toFile().also { tmpDirs.add(it) }
        val first = newStore(dir)
        first.confirmConsentAndEnableMaster()
        first.setDdgsOptIn(true)
        first.setSearxngBaseUrl("https://search.example")
        closeActiveStores()

        val second = newStore(dir).observeState().first { it.loaded }
        assertFalse(second.loadFailed)
        assertTrue(second.masterEnabled)
        assertTrue(second.ddgsOptIn)
        assertTrue(second.consentAck)
        assertEquals("https://search.example", second.searxngBaseUrl)
    }

    @Test fun confirmConsentAndEnableMaster_isSingleAtomicEditAndPersistsAcrossInstances() = runBlocking {
        val spyDs = SpyPreferencesDataStore()
        val spyStore = WebSwitchStore(spyDs)
        spyStore.confirmConsentAndEnableMaster()
        assertEquals(1, spyDs.editCount.get())
        val snap = spyStore.observeState().first { it.loaded }
        assertTrue(snap.consentAck)
        assertTrue(snap.masterEnabled)

        val dir = Files.createTempDirectory("web-test").toFile().also { tmpDirs.add(it) }
        newStore(dir).confirmConsentAndEnableMaster()
        closeActiveStores()
        val persisted = newStore(dir).observeState().first { it.loaded }
        assertTrue(persisted.consentAck)
        assertTrue(persisted.masterEnabled)
    }

    @Test fun setMasterEnabled_requiresConsentWhenEnabling_allowsDisablingWithoutConsent() = runBlocking {
        val store = newStore()
        try {
            store.setMasterEnabled(true)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("WEB_CONSENT_REQUIRED", e.message)
        }
        val afterDenied = store.observeState().first { it.loaded }
        assertFalse(afterDenied.masterEnabled)
        assertFalse(afterDenied.consentAck)

        store.dataStore.edit { prefs -> prefs[WEB_CONSENT_ACK_KEY] = false }
        try {
            store.setMasterEnabled(true)
            fail("expected IllegalStateException with explicit consentAck=false")
        } catch (e: IllegalStateException) {
            assertEquals("WEB_CONSENT_REQUIRED", e.message)
        }
        val afterExplicitFalse = store.observeState().first { it.loaded }
        assertFalse(afterExplicitFalse.masterEnabled)
        assertFalse(afterExplicitFalse.consentAck)

        store.setMasterEnabled(false)
        val afterDisable = store.observeState().first { it.loaded }
        assertFalse(afterDisable.masterEnabled)
        assertFalse(afterDisable.consentAck)
    }

    @Suppress("DEPRECATION")
    @Test fun acknowledgeConsent_setsConsentWithMasterOffClearsTamperedMasterAndUnblocksSetMasterEnabled() = runBlocking {
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[WEB_MASTER_ENABLED_KEY] = true
            prefs[WEB_CONSENT_ACK_KEY] = false
        }
        store.acknowledgeConsent()

        val raw = store.dataStore.data.first()
        assertEquals(true, raw[WEB_CONSENT_ACK_KEY])
        assertEquals(false, raw[WEB_MASTER_ENABLED_KEY])

        val afterAck = store.observeState().first { it.loaded }
        assertTrue(afterAck.consentAck)
        assertFalse(afterAck.masterEnabled)

        store.setMasterEnabled(true)
        val afterEnable = store.observeState().first { it.loaded }
        assertTrue(afterEnable.consentAck)
        assertTrue(afterEnable.masterEnabled)

        // Calling acknowledgeConsent again once already consented keeps masterEnabled=true.
        store.acknowledgeConsent()
        val afterReAck = store.observeState().first { it.loaded }
        assertTrue(afterReAck.consentAck)
        assertTrue(afterReAck.masterEnabled)
    }

    @Test fun confirmConsentAndEnableMaster_failureLeavesConsentAndMasterFalse() = runBlocking {
        val state = MutableStateFlow(emptyPreferences())
        val failingDs = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                transform(state.value)
                throw IOException("simulated disk write failure")
            }
        }
        val store = WebSwitchStore(failingDs)
        try {
            store.confirmConsentAndEnableMaster()
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("simulated disk write failure", e.message)
        }
        val snap = store.observeState().first { it.loaded }
        assertFalse(snap.consentAck)
        assertFalse(snap.masterEnabled)
    }

    @Test fun observeState_enforcesConsentInvariantOnTamperedPrefs() = runBlocking {
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[WEB_MASTER_ENABLED_KEY] = true
            prefs[WEB_CONSENT_ACK_KEY] = false
        }
        val snap = store.observeState().first { it.loaded }
        assertFalse(snap.consentAck)
        assertFalse(snap.masterEnabled)

        val absentConsentStore = newStore()
        absentConsentStore.dataStore.edit { prefs ->
            prefs[WEB_MASTER_ENABLED_KEY] = true
            prefs.remove(WEB_CONSENT_ACK_KEY)
        }
        val absentConsentSnap = absentConsentStore.observeState().first { it.loaded }
        assertFalse(absentConsentSnap.consentAck)
        assertFalse(absentConsentSnap.masterEnabled)
    }

    @Test fun switchFlipsAndUrlClear_roundTripWithoutClobberingSiblings() = runBlocking {
        val store = newStore()
        store.confirmConsentAndEnableMaster()
        store.setDdgsOptIn(true)
        store.setSearxngBaseUrl("https://search.example")

        store.setMasterEnabled(false)
        var s = store.observeState().first { it.loaded }
        assertFalse(s.masterEnabled)
        assertTrue(s.ddgsOptIn)
        assertTrue(s.consentAck)
        assertEquals("https://search.example", s.searxngBaseUrl)

        store.setMasterEnabled(true)
        s = store.observeState().first { it.loaded }
        assertTrue(s.masterEnabled)
        assertTrue(s.ddgsOptIn)
        assertTrue(s.consentAck)
        assertEquals("https://search.example", s.searxngBaseUrl)

        store.setDdgsOptIn(false)
        s = store.observeState().first { it.loaded }
        assertTrue(s.masterEnabled)
        assertFalse(s.ddgsOptIn)
        assertTrue(s.consentAck)
        assertEquals("https://search.example", s.searxngBaseUrl)

        store.setSearxngBaseUrl("")
        s = store.observeState().first { it.loaded }
        assertTrue(s.masterEnabled)
        assertFalse(s.ddgsOptIn)
        assertTrue(s.consentAck)
        assertEquals("", s.searxngBaseUrl)
    }

    @Test fun resetAll_resetsAllFourKeysAtomicallyAndPersistsAcrossInstances() = runBlocking {
        val spyDs = SpyPreferencesDataStore()
        val spyStore = WebSwitchStore(spyDs)
        spyStore.confirmConsentAndEnableMaster()
        spyStore.setDdgsOptIn(true)
        spyStore.setSearxngBaseUrl("https://search.example")
        val editsBefore = spyDs.editCount.get()
        spyStore.resetAll()
        assertEquals(editsBefore + 1, spyDs.editCount.get())
        val rawAfter = spyDs.data.first()
        assertEquals(false, rawAfter[WEB_MASTER_ENABLED_KEY])
        assertEquals(false, rawAfter[WEB_DDGS_OPTIN_KEY])
        assertEquals(false, rawAfter[WEB_CONSENT_ACK_KEY])
        assertEquals("", rawAfter[WEB_SEARXNG_URL_KEY])
        val spyAfter = spyStore.observeState().first { it.loaded }
        assertFalse(spyAfter.masterEnabled)
        assertFalse(spyAfter.ddgsOptIn)
        assertFalse(spyAfter.consentAck)
        assertEquals("", spyAfter.searxngBaseUrl)

        val dir = Files.createTempDirectory("web-test").toFile().also { tmpDirs.add(it) }
        val first = newStore(dir)
        first.confirmConsentAndEnableMaster()
        first.setDdgsOptIn(true)
        first.setSearxngBaseUrl("https://search.example")
        first.resetAll()
        closeActiveStores()

        val second = newStore(dir).observeState().first { it.loaded }
        assertFalse(second.loadFailed)
        assertFalse(second.masterEnabled)
        assertFalse(second.ddgsOptIn)
        assertFalse(second.consentAck)
        assertEquals("", second.searxngBaseUrl)
    }

    @Test fun setSearxngBaseUrl_rejectsUnsafeInputsAndAllowsSafeDrafts() = runBlocking {
        val store = newStore()
        store.setSearxngBaseUrl("https://valid.example")

        val tooLong = "https://example.com/" + "a".repeat(MAX_WEB_BASE_URL_LENGTH)
        val exactMaxPlusOne = "https://a.co/" + "a".repeat(MAX_WEB_BASE_URL_LENGTH + 1 - "https://a.co/".length)
        assertEquals(MAX_WEB_BASE_URL_LENGTH + 1, exactMaxPlusOne.length)
        val unsafeInputs = listOf(
            tooLong,
            exactMaxPlusOne,
            "https://search.example/\u0000",
            "https://search.example/\n",
            "https://search.example/\t",
            "https://search.example/\u007F",
            "https://search\u200B.example",
            "https://search.example/\uD800",
            "https://search.example/\uDC00",
            "https://search.example/\uDC00\uD800",
            "https://user:pass@host",
            "http://@host",
            "https://evil.com\\@host",
            "user:pass@host",
            "https:/user:pass@host",
            "//user:pass@host",
            "https://search.example/?token=secret",
            "https://search.example/#secret",
            "\u2028",
            "\u2029",
            "https://search.example/\u2028",
            "https://search.example/\u2029",
            "https://search.example\\path",
            "search.example\\path",
            "search.example?q=1",
            "search.example#frag",
            "https://search.example/\u0085",
            "\\",
            "?",
            "#",
            "@",
        )
        for (bad in unsafeInputs) {
            assertFalse("expected isSafeUrlForStorage=false for $bad", isSafeUrlForStorage(bad))
            try {
                store.setSearxngBaseUrl(bad)
                fail("expected IllegalArgumentException for $bad")
            } catch (e: IllegalArgumentException) {
                assertEquals("UNSAFE_WEB_BASE_URL", e.message)
            }
        }
        // Rejected writes must not clobber existing persisted URL.
        assertEquals("https://valid.example", store.observeState().first { it.loaded }.searxngBaseUrl)

        val exactMaxUrl = "https://a.co/" + "a".repeat(MAX_WEB_BASE_URL_LENGTH - "https://a.co/".length)
        assertEquals(MAX_WEB_BASE_URL_LENGTH, exactMaxUrl.length)
        assertTrue(isSafeUrlForStorage(exactMaxUrl))
        store.setSearxngBaseUrl(exactMaxUrl)
        assertEquals(exactMaxUrl, store.observeState().first { it.loaded }.searxngBaseUrl)

        store.setSearxngBaseUrl("searxng.local")
        assertEquals("searxng.local", store.observeState().first { it.loaded }.searxngBaseUrl)

        val validSurrogate = "https://search.example/\uD83D\uDE00"
        store.setSearxngBaseUrl(validSurrogate)
        assertEquals(validSurrogate, store.observeState().first { it.loaded }.searxngBaseUrl)

        store.setSearxngBaseUrl("")
        assertEquals("", store.observeState().first { it.loaded }.searxngBaseUrl)
    }

    @Test fun decideEffective_truthTable() {
        val loaded = WebSearchSnapshot(
            masterEnabled = true,
            ddgsOptIn = false,
            consentAck = true,
            searxngBaseUrl = "https://search.example",
            loaded = true,
            loadFailed = false,
        )
        val failed = WebSearchSnapshot(
            masterEnabled = false,
            ddgsOptIn = false,
            consentAck = false,
            searxngBaseUrl = "",
            loaded = true,
            loadFailed = true,
        )
        val loadedChanged = loaded.copy(masterEnabled = false, ddgsOptIn = true)

        assertEquals(loaded, WebSearchSnapshot.decideEffective(loaded, WebSearchSnapshot.LOADING))
        assertEquals(
            WebSearchSnapshot.LOADING,
            WebSearchSnapshot.decideEffective(WebSearchSnapshot.LOADING, WebSearchSnapshot.LOADING),
        )
        assertEquals(loaded, WebSearchSnapshot.decideEffective(WebSearchSnapshot.LOADING, loaded))
        assertEquals(failed, WebSearchSnapshot.decideEffective(loaded, failed))
        assertEquals(failed, WebSearchSnapshot.decideEffective(WebSearchSnapshot.LOADING, failed))
        assertEquals(failed, WebSearchSnapshot.decideEffective(failed, failed))
        assertEquals(failed, WebSearchSnapshot.decideEffective(failed, WebSearchSnapshot.LOADING))
        assertEquals(loadedChanged, WebSearchSnapshot.decideEffective(loaded, loadedChanged))
        assertEquals(loaded, WebSearchSnapshot.decideEffective(failed, loaded))
    }

    @Test fun decideEffective_foldedOverGapSequenceNeverFlashes() {
        val loaded = WebSearchSnapshot(
            masterEnabled = true,
            ddgsOptIn = true,
            consentAck = true,
            searxngBaseUrl = "https://search.example",
            loaded = true,
            loadFailed = false,
        )
        val failed = WebSearchSnapshot(
            masterEnabled = false,
            ddgsOptIn = false,
            consentAck = false,
            searxngBaseUrl = "",
            loaded = true,
            loadFailed = true,
        )
        val mid = listOf(loaded, WebSearchSnapshot.LOADING)
            .fold(WebSearchSnapshot.LOADING, WebSearchSnapshot::decideEffective)
        assertEquals(loaded, mid)
        val final = listOf(failed).fold(mid, WebSearchSnapshot::decideEffective)
        assertEquals(failed, final)
    }

    @Test fun resubscribeAfterTimeout_decideEffectiveKeepsLoadedThroughLoadingGap() = runTest {
        val loaded = WebSearchSnapshot(
            masterEnabled = true,
            ddgsOptIn = false,
            consentAck = true,
            searxngBaseUrl = "https://search.example",
            loaded = true,
            loadFailed = false,
        )
        var subscriptions = 0
        val upstream = flow {
            val n = ++subscriptions
            emit(WebSearchSnapshot.LOADING)
            if (n == 1) {
                emit(loaded)
            } else {
                delay(10_000)
                emit(loaded)
            }
        }
        val stateScope = CoroutineScope(
            coroutineContext[kotlin.coroutines.ContinuationInterceptor]
                as CoroutineDispatcher + SupervisorJob(),
        )
        val shared = upstream.stateIn(
            stateScope,
            SharingStarted.WhileSubscribed(5000),
            WebSearchSnapshot.LOADING,
        )
        val warmed = ArrayList<WebSearchSnapshot>()
        val firstJob = launch { shared.collect { warmed.add(it) } }
        advanceUntilIdle()
        assertTrue(warmed.last().loaded)
        firstJob.cancel()
        firstJob.join()

        advanceTimeBy(5001)
        runCurrent()

        val gapSeen = ArrayList<WebSearchSnapshot>()
        val gapJob = launch { shared.collect { gapSeen.add(it) } }
        runCurrent()
        assertTrue("expected LOADING gap after resubscribe, saw $gapSeen", gapSeen.contains(WebSearchSnapshot.LOADING))
        var effective = warmed.last()
        for (snapshot in gapSeen) {
            effective = WebSearchSnapshot.decideEffective(effective, snapshot)
            assertTrue("effective flashed to $effective on $snapshot", effective.loaded)
            assertFalse(effective.loadFailed)
        }
        assertEquals(loaded, effective)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(loaded, gapSeen.last())
        assertEquals(loaded, WebSearchSnapshot.decideEffective(effective, gapSeen.last()))
        gapJob.cancel()
        gapJob.join()
        stateScope.cancel()
        advanceUntilIdle()
    }

    @Test fun readFailureIsLoadFailedFailClosed() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> =
                flow { throw IOException("injected web read failure") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IOException("injected web read failure")
            }
        }
        val s = WebSwitchStore(broken).observeState().first { it.loaded }
        assertTrue(s.loadFailed)
        assertFalse(s.masterEnabled)
        assertFalse(s.ddgsOptIn)
        assertFalse(s.consentAck)
        assertEquals("", s.searxngBaseUrl)
    }

    @Test fun observeState_midStreamIoExceptionTransitionsToFailClosedLoadFailed() = runBlocking {
        val loadedPrefs = emptyPreferences().toMutablePreferences().apply {
            this[WEB_MASTER_ENABLED_KEY] = true
            this[WEB_DDGS_OPTIN_KEY] = true
            this[WEB_CONSENT_ACK_KEY] = true
            this[WEB_SEARXNG_URL_KEY] = "https://search.example"
        }.toPreferences()
        val midFailDs = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                emit(loadedPrefs)
                throw IOException("injected mid-stream web read failure")
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IOException("injected write failure")
            }
        }
        val emissions = WebSwitchStore(midFailDs).observeState().toList()
        assertEquals(3, emissions.size)
        assertEquals(WebSearchSnapshot.LOADING, emissions[0])

        val loaded = emissions[1]
        assertTrue(loaded.loaded)
        assertFalse(loaded.loadFailed)
        assertTrue(loaded.masterEnabled)
        assertTrue(loaded.ddgsOptIn)
        assertTrue(loaded.consentAck)
        assertEquals("https://search.example", loaded.searxngBaseUrl)

        val failed = emissions[2]
        assertTrue(failed.loaded)
        assertTrue(failed.loadFailed)
        assertFalse(failed.masterEnabled)
        assertFalse(failed.ddgsOptIn)
        assertFalse(failed.consentAck)
        assertEquals("", failed.searxngBaseUrl)
    }

    @Test fun nonIoExceptionPropagates() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> =
                flow { throw IllegalStateException("injected non-io") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IllegalStateException("injected non-io")
            }
        }
        try {
            WebSwitchStore(broken).observeState().first { it.loaded }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("injected non-io"))
        }
    }

    @Test fun observeState_cancellationPropagates() = runBlocking {
        val cancelling = object : DataStore<Preferences> {
            override val data: Flow<Preferences> =
                flow { throw CancellationException("injected observe cancel") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw CancellationException("injected observe cancel")
            }
        }
        try {
            WebSwitchStore(cancelling).observeState().first { it.loaded }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("injected observe cancel", e.message)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun writeFailures_ioAndCancellationPropagateOnAllSetters() = runBlocking {
        fun failingWriteStore(failureFactory: () -> Throwable): WebSwitchStore {
            val ds = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow { emit(emptyPreferences()) }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    // Run transform on consented prefs so setMasterEnabled(true) exercises transport failure.
                    val consented = emptyPreferences().toMutablePreferences().apply {
                        this[WEB_CONSENT_ACK_KEY] = true
                    }.toPreferences()
                    transform(consented)
                    throw failureFactory()
                }
            }
            return WebSwitchStore(ds)
        }

        val ioStore = failingWriteStore { IOException("injected write IO") }
        val setters: List<Pair<String, suspend (WebSwitchStore) -> Unit>> = listOf(
            "setMasterEnabled(true)" to { s -> s.setMasterEnabled(true) },
            "setMasterEnabled(false)" to { s -> s.setMasterEnabled(false) },
            "setDdgsOptIn(true)" to { s -> s.setDdgsOptIn(true) },
            "setSearxngBaseUrl" to { s -> s.setSearxngBaseUrl("https://search.example") },
            "acknowledgeConsent" to { s -> s.acknowledgeConsent() },
            "confirmConsentAndEnableMaster" to { s -> s.confirmConsentAndEnableMaster() },
            "resetAll" to { s -> s.resetAll() },
        )
        for ((name, call) in setters) {
            try {
                call(ioStore)
                fail("expected IOException for $name")
            } catch (e: IOException) {
                assertEquals("injected write IO", e.message)
            }
        }

        val cancelStore = failingWriteStore { CancellationException("injected write cancel") }
        for ((name, call) in setters) {
            try {
                call(cancelStore)
                fail("expected CancellationException for $name")
            } catch (e: CancellationException) {
                assertEquals("injected write cancel", e.message)
            }
        }
    }

    @Test fun customIoDispatcher_runsDataStoreEditsOnInjectedDispatcher() = runBlocking {
        val threadName = AtomicReference<String>()
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "web-custom-io-thread")
        }
        val customDispatcher = executor.asCoroutineDispatcher()
        try {
            val ds = object : DataStore<Preferences> {
                private val state = MutableStateFlow(emptyPreferences())
                override val data: Flow<Preferences> = state
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    threadName.set(Thread.currentThread().name)
                    val next = transform(state.value)
                    state.value = next
                    return next
                }
            }
            val store = WebSwitchStore(ds, customDispatcher)
            store.confirmConsentAndEnableMaster()
            assertTrue(
                "expected custom io thread, got ${threadName.get()}",
                threadName.get()?.startsWith("web-custom-io-thread") == true,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun snapshotDrivesSearchSuspend_failClosedUntilLoadedAndConsented() = runBlocking {
        val httpCalls = AtomicInteger(0)
        val fakeHttp = WebHttpGet { _, _ ->
            httpCalls.incrementAndGet()
            WebHttpResponse(
                200,
                """{"results":[{"title":"Kotlin","url":"https://kotlinlang.org/","content":"Docs"}]}""",
            )
        }
        fun registryFor(snap: WebSearchSnapshot): WebSearchRegistry =
            WebSearchRegistry(listOf(SearxngSearchProvider(snap.searxngBaseUrl, fakeHttp)))

        suspend fun runWithSnapshot(snap: WebSearchSnapshot): WebSearchLocal.SearchOutcome =
            WebSearchLocal.searchSuspend(
                query = "kotlin",
                count = 5,
                registry = registryFor(snap),
                masterEnabled = snap.masterEnabled,
                ddgsOptIn = snap.ddgsOptIn,
                consentConfirmed = snap.consentAck,
            )

        // LOADING -> PROVIDER_DISABLED, 0 HTTP calls
        val loadingOut = runWithSnapshot(WebSearchSnapshot.LOADING) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.USER_DISABLED, loadingOut.reason)
        assertEquals("PROVIDER_DISABLED", loadingOut.detail)
        assertEquals(0, httpCalls.get())

        // loadFailed -> PROVIDER_DISABLED, 0 HTTP calls
        val brokenDs = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("read err") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IOException("write err")
            }
        }
        val failedSnap = WebSwitchStore(brokenDs).observeState().first { it.loaded }
        val failedOut = runWithSnapshot(failedSnap) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.USER_DISABLED, failedOut.reason)
        assertEquals("PROVIDER_DISABLED", failedOut.detail)
        assertEquals(0, httpCalls.get())

        // Tampered raw prefs (master=true without consentAck) -> observed snapshot fails closed
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[WEB_MASTER_ENABLED_KEY] = true
            prefs[WEB_CONSENT_ACK_KEY] = false
            prefs[WEB_SEARXNG_URL_KEY] = "https://search.example"
        }
        val tamperedSnap = store.observeState().first { it.loaded }
        val tamperedOut = runWithSnapshot(tamperedSnap) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.USER_DISABLED, tamperedOut.reason)
        assertEquals("PROVIDER_DISABLED", tamperedOut.detail)
        assertEquals(0, httpCalls.get())

        // Loaded + consented + valid URL -> Ok
        store.confirmConsentAndEnableMaster()
        val readySnap = store.observeState().first { it.loaded && it.masterEnabled }
        val okOut = runWithSnapshot(readySnap) as WebSearchLocal.SearchOutcome.Ok
        assertEquals(1, okOut.results.size)
        assertEquals(1, httpCalls.get())
    }

    @Test fun currentConsentAndMaster_defaultsTamperedAndConsentedStates() = runBlocking {
        // Fresh defaults -> (false, false)
        val freshStore = newStore()
        assertEquals(false to false, freshStore.currentConsentAndMaster())

        // Tampered master=true with consent=false -> (false, false)
        freshStore.dataStore.edit { prefs ->
            prefs[WEB_MASTER_ENABLED_KEY] = true
            prefs[WEB_CONSENT_ACK_KEY] = false
        }
        assertEquals(false to false, freshStore.currentConsentAndMaster())

        // Tampered master=true with consent absent -> (false, false)
        freshStore.dataStore.edit { prefs ->
            prefs[WEB_MASTER_ENABLED_KEY] = true
            prefs.remove(WEB_CONSENT_ACK_KEY)
        }
        assertEquals(false to false, freshStore.currentConsentAndMaster())

        // Consented with master=false -> (true, false)
        freshStore.dataStore.edit { prefs ->
            prefs[WEB_CONSENT_ACK_KEY] = true
            prefs[WEB_MASTER_ENABLED_KEY] = false
        }
        assertEquals(true to false, freshStore.currentConsentAndMaster())

        // Consented with master=true -> (true, true), and toggling master off returns (true, false)
        freshStore.confirmConsentAndEnableMaster()
        assertEquals(true to true, freshStore.currentConsentAndMaster())
        freshStore.setMasterEnabled(false)
        assertEquals(true to false, freshStore.currentConsentAndMaster())
    }

    @Test fun currentConsentAndMaster_ioAndCancellationExceptionsPropagate() = runBlocking {
        val ioBrokenDs = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("injected consent read IO") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IOException("unused")
            }
        }
        try {
            WebSwitchStore(ioBrokenDs).currentConsentAndMaster()
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("injected consent read IO", e.message)
        }

        val cancellingDs = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw CancellationException("injected consent read cancel") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw CancellationException("unused")
            }
        }
        try {
            WebSwitchStore(cancellingDs).currentConsentAndMaster()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("injected consent read cancel", e.message)
        }
    }
}
