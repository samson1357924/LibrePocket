package dev.librepocket.web

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/**
 * D3 WebSwitchStore 真相源測試（純 JVM，暫存目錄隔離）。
 *
 * - 新戶預設：master 關、ddgs 關、consent false、URL 空；
 * - 寫入持久化（同檔新實例可見）；
 * - 讀取拋 IOException → loadFailed 全關 fail-closed；其餘例外照拋。
 */
class WebSwitchStoreTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(dir: java.io.File = Files.createTempDirectory("web-test").toFile().also {
        tmpDirs.add(it)
    }): WebSwitchStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "web.preferences_pb") },
        )
        return WebSwitchStore(dataStore)
    }

    @Test fun freshDefaultsAreAllClosed() = runBlocking {
        val s = newStore().observeState().first { it.loaded }
        assertFalse(s.loadFailed)
        assertFalse(s.masterEnabled)
        assertFalse(s.ddgsOptIn)
        assertFalse(s.consentAck)
        assertEquals("", s.searxngBaseUrl)
    }

    @Test fun writesPersistAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("web-test").toFile().also { tmpDirs.add(it) }
        val first = newStore(dir)
        first.setMasterEnabled(true)
        first.setDdgsOptIn(true)
        first.setSearxngBaseUrl("https://search.example")
        first.acknowledgeConsent()
        scopes.forEach { it.cancel() }
        scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]?.join() }

        val second = newStore(dir).observeState().first { it.loaded }
        assertFalse(second.loadFailed)
        assertTrue(second.masterEnabled)
        assertTrue(second.ddgsOptIn)
        assertTrue(second.consentAck)
        assertEquals("https://search.example", second.searxngBaseUrl)
    }

    @Test fun readFailureIsLoadFailedFailClosed() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<Preferences> =
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

    @Test fun nonIoExceptionPropagates() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<Preferences> =
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
}
