package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage-3 tests P2-9: crafted success-shape import → export → reimport is
 * field-equivalent over runId/kind/text/isPartial/isFinal/parentRunId/
 * attemptIndex/failureReason: `isFinal=false` intermediate partials,
 * payload-free `reasoning` markers, and full family linkage
 * (`parentRunId`/`attemptIndex`/`failureReason`) all survive both legs.
 * Deliberately NOT compared: `createdAt` wall-clock, `seq`/`sessionId`
 * reassignment, and raw-line bytes (hence "field-", never "byte-").
 * Rejections of illegal shapes stay pinned in [S2ImportFailClosedTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Stage3ImportRoundTripTest {

    private lateinit var db: LibrePocketDb

    @After fun tearDown() {
        if (this::db.isInitialized) db.close()
    }

    private fun openStore(): RoomSessionStore {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        var now = 1_700_000_000_000L
        return RoomSessionStore(db) { now++ }
    }

    @Test fun craftedSuccessShape_importExportReimport_fieldEquivalent() = runBlocking {
        val store = openStore()
        val tmpDir = Files.createTempDirectory("s3-roundtrip").toFile()
        try {
            // Crafted success ledger: clean pair + retry family with linkage
            // + intermediate partial (isFinal=false, the only legal non-final
            // shape) + reasoning marker + terminal + audit rows.
            val rows = listOf(
                TranscriptEvent(seq = 1, sessionId = "x", runId = "r1", kind = "user", text = "q1", createdAt = 1L),
                TranscriptEvent(seq = 2, sessionId = "x", runId = "r1", kind = "assistant", text = "a1", createdAt = 2L),
                TranscriptEvent(seq = 3, sessionId = "x", runId = "r1", kind = "reasoning", text = "", createdAt = 3L),
                TranscriptEvent(seq = 4, sessionId = "x", runId = "r2", kind = "user", text = "q2", createdAt = 4L),
                TranscriptEvent(
                    seq = 5, sessionId = "x", runId = "a2", kind = "assistant", text = "half",
                    createdAt = 5L, isPartial = true, isFinal = false,
                    parentRunId = "r2", attemptIndex = 1, failureReason = null,
                ),
                TranscriptEvent(
                    seq = 6, sessionId = "x", runId = "a2", kind = "assistant", text = "a2-done",
                    createdAt = 6L, parentRunId = "r2", attemptIndex = 1,
                ),
                TranscriptEvent(seq = 7, sessionId = "x", runId = "r2", kind = "retry", text = "retry 1", createdAt = 7L),
                TranscriptEvent(seq = 8, sessionId = "x", runId = "u3", kind = "system", text = "usage input=1 output=2", createdAt = 8L),
            )
            val src = File(tmpDir, "crafted.jsonl")
            src.writeText(rows.joinToString("\n") { JsonlCodec.encode(it) } + "\n", Charsets.UTF_8)
            val sid1 = store.importJsonl(src)
            val exp1 = File(tmpDir, "exp1.jsonl")
            store.exportJsonl(sid1, exp1)
            val sid2 = store.importJsonl(exp1)
            val exp2 = File(tmpDir, "exp2.jsonl")
            store.exportJsonl(sid2, exp2)
            val lines1 = exp1.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            val lines2 = exp2.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertEquals("reimport must preserve every row, got ${lines1.size} vs ${lines2.size}", lines1.size, lines2.size)
            assertEquals(8, lines1.size)
            for (i in lines1.indices) {
                val a = JsonlCodec.decode(i + 1, lines1[i])
                val b = JsonlCodec.decode(i + 1, lines2[i])
                assertEquals("line ${i + 1} runId", a.runId, b.runId)
                assertEquals("line ${i + 1} kind", a.kind, b.kind)
                assertEquals("line ${i + 1} text", a.text, b.text)
                assertEquals("line ${i + 1} isPartial", a.isPartial, b.isPartial)
                assertEquals("line ${i + 1} isFinal", a.isFinal, b.isFinal)
                assertEquals("line ${i + 1} parentRunId", a.parentRunId, b.parentRunId)
                assertEquals("line ${i + 1} attemptIndex", a.attemptIndex, b.attemptIndex)
                assertEquals("line ${i + 1} failureReason", a.failureReason, b.failureReason)
            }
            // Spot pins: the fragile shapes survived, not just row counts.
            val partial = lines1.map { JsonlCodec.decode(1, it) }.single { it.isFinal == false }
            assertEquals(true, partial.isPartial)
            assertEquals("r2", partial.parentRunId)
            assertEquals(1, partial.attemptIndex)
            assertTrue(lines1.map { JsonlCodec.decode(1, it) }.any { it.kind == "reasoning" })
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
