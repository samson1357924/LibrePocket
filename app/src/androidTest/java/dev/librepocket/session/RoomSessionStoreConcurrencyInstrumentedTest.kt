package dev.librepocket.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RoomSessionStore] on-device concurrency test (SPEC §10.3 [I]).
 *
 * In-memory Room on a real device: 10 coroutines × 50 appends must keep
 * `seq` dense (1..500) with no gaps or duplicates. The JVM/Robolectric twin
 * (`SessionStoreTest.append_concurrentKeepsSeqDense`) covers the same
 * invariant off-device.
 *
 * Requires a device or emulator; see
 * `docs/specs/P1_ANDROIDTEST_RUNBOOK.md`. Never touches the network.
 */
@RunWith(AndroidJUnit4::class)
class RoomSessionStoreConcurrencyInstrumentedTest {

    private lateinit var db: LibrePocketDb

    @After
    fun tearDown() {
        if (this::db.isInitialized) {
            db.close()
        }
    }

    @Test
    fun concurrentAppendsKeepSeqDense() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = LibrePocketDb.openInMemory(context)
        val store = RoomSessionStore(db)
        val sid = store.createSession("t", "p/m")

        List(10) { w ->
            launch(Dispatchers.IO) {
                repeat(50) { i ->
                    store.appendEvent(
                        TranscriptEvent(
                            sessionId = sid,
                            runId = "run-$w",
                            kind = "user",
                            text = "w$w-$i",
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }.joinAll()

        val seqs = store.loadEvents(sid, limit = 1000).map { it.seq }.sorted()
        assertEquals((1L..500L).toList(), seqs)
    }
}
