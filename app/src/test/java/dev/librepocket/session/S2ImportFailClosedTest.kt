package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.cancelledMarkerText
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.interruptedMarkerText
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2 P0 import fail-closed regressions (production write semantics unchanged).
 *
 * Current (S2 implemented): `JsonlCodec.decode` enforces the F2 shape gate,
 * F3 finality rule, F7 field caps, and F11 strict typing, and
 * `RoomSessionStore.importJsonl` parses every line before writing so any bad
 * line aborts with zero sessions created. This file pins that Current
 * behavior; Target keeps the fail-closed posture — gate changes must update
 * the codec, the store import path, and these tests together. Production
 * write semantics (`SessionTranscriptSink`) are unchanged and
 * `isSwitchSnapshotTerminalMark` stays text-only by design (see
 * `findDanglingRunIds`).
 *
 * - F2 shape gate: `parentRunId`/`attemptIndex` only on `assistant`/`reasoning`,
 *   `failureReason` only on `assistant` (production never writes a system
 *   `failureReason` column — verified in `SessionTranscriptSink`: every system
 *   row passes `failureReason=null`, legacy failures are `system` text rows),
 *   `isFinal=false` only on `assistant`/`reasoning`.
 * - F3: system terminal requires `isFinal=1` (pre-F missing key defaults true).
 * - F7: `runId`/`parentRunId` ≤ 256, `failureReason` ≤ 4096.
 * - F11: every field read gated; `null`/object/array/wrong-primitive surface
 *   as line-only IAE; explicit-`null` optionals read back as documented defaults.
 *
 * Runs under Robolectric (in-memory Room) for import paths; `findDangling` is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class S2ImportFailClosedTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private lateinit var store: RoomSessionStore
    private lateinit var tmpDir: File

    private fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        store = RoomSessionStore(db) { now }
        tmpDir = Files.createTempDirectory("s2-import").toFile()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (this::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    private fun importFailsWithLine(file: File, line: Int): IllegalArgumentException {
        val before = db.sessionDao().allSessions().size
        try {
            runBlocking { store.importJsonl(file) }
            fail("import must throw for ${file.name}")
        } catch (e: IllegalArgumentException) {
            assertTrue("expected line $line, was: ${e.message}", e.message!!.contains("line $line"))
            assertEquals("zero session on failed import", before, db.sessionDao().allSessions().size)
            return e
        }
        throw AssertionError("unreachable")
    }

    @Test fun import_rejectsIllegalShapeMatrix() {
        setUp()
        // kind × illegal-field matrix: every cell must fail with zero session.
        val illegalKinds = listOf("user", "retry", "system", "tool", "steer")
        var idx = 0
        for (kind in illegalKinds) {
            // parentRunId on non-attempt kind.
            run {
                val f = File(tmpDir, "m-parent-$idx.jsonl")
                f.writeText(
                    "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"$kind\",\"text\":\"t\"," +
                        "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"L\"}\n",
                    Charsets.UTF_8,
                )
                importFailsWithLine(f, 1)
                idx++
            }
            // attemptIndex on non-attempt kind.
            run {
                val f = File(tmpDir, "m-attempt-$idx.jsonl")
                f.writeText(
                    "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"$kind\",\"text\":\"t\"," +
                        "\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":0}\n",
                    Charsets.UTF_8,
                )
                importFailsWithLine(f, 1)
                idx++
            }
            // failureReason on non-assistant kind (system included: production
            // system rows never carry the column).
            run {
                val f = File(tmpDir, "m-reason-$idx.jsonl")
                f.writeText(
                    "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"$kind\",\"text\":\"t\"," +
                        "\"imagesOmitted\":0,\"createdAt\":1,\"failureReason\":\"boom\"}\n",
                    Charsets.UTF_8,
                )
                importFailsWithLine(f, 1)
                idx++
            }
            // isFinal=false on non-attempt kind (pre-F missing key still defaults true).
            run {
                val f = File(tmpDir, "m-final-$idx.jsonl")
                f.writeText(
                    "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"$kind\",\"text\":\"t\"," +
                        "\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":false}\n",
                    Charsets.UTF_8,
                )
                importFailsWithLine(f, 1)
                idx++
            }
        }
        // reasoning + failureReason is illegal (production markers never carry it).
        run {
            val f = File(tmpDir, "m-reasoning-reason.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"reasoning\",\"text\":\"\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"failureReason\":\"boom\"}\n",
                Charsets.UTF_8,
            )
            importFailsWithLine(f, 1)
        }
        // reasoning empty-text non-final stays legal (production non-final markers).
        run {
            val f = File(tmpDir, "ok-reasoning-nonfinal.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"reasoning\",\"text\":\"\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":false}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            assertEquals(1, runBlocking { store.loadEvents(sid) }.size)
            runBlocking { store.deleteSession(sid) }
        }
        // Positive controls: production shapes stay accepted.
        run {
            val f = File(tmpDir, "ok-assistant-link.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"ok\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"L\",\"attemptIndex\":1}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            runBlocking { store.deleteSession(sid) }
        }
        run {
            val f = File(tmpDir, "ok-reasoning-link.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"reasoning\",\"text\":\"\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"L\",\"attemptIndex\":1,\"isFinal\":false}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            runBlocking { store.deleteSession(sid) }
        }
        run {
            val f = File(tmpDir, "ok-assistant-reason.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"assistant\",\"text\":\"half\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":true,\"failureReason\":\"boom\"}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            runBlocking { store.deleteSession(sid) }
        }
    }

    @Test fun danglingRule_systemNonFinalDoesNotClose() {
        // All three production system-mark shapes: non-final still dangles,
        // final closes. Pre-F default (missing key → true) still closes.
        val marks = listOf(
            cancelledMarkerText("L"),
            interruptedMarkerText("L"),
            "turn L failed: boom",
        )
        for (mark in marks) {
            val user = TranscriptEvent(
                sessionId = "s", seq = 1, runId = "L", kind = "user", text = "q", createdAt = now,
            )
            val nonFinalMark = TranscriptEvent(
                sessionId = "s", seq = 2, runId = "L", kind = "system",
                text = mark, createdAt = now, isFinal = false,
            )
            assertEquals(
                "non-final system mark must still dangle: $mark",
                listOf("L"),
                findDanglingRunIds(listOf(user, nonFinalMark)),
            )
            val finalMark = nonFinalMark.copy(seq = 2, isFinal = true)
            assertTrue(
                "final system mark closes: $mark",
                findDanglingRunIds(listOf(user, finalMark)).isEmpty(),
            )
        }
        // Pre-F semantics: default isFinal=true still closes (no explicit flag).
        val user = TranscriptEvent(
            sessionId = "s", seq = 1, runId = "L", kind = "user", text = "q", createdAt = now,
        )
        val defaultFinal = TranscriptEvent(
            sessionId = "s", seq = 2, runId = "L", kind = "system",
            text = cancelledMarkerText("L"), createdAt = now,
        )
        assertTrue(defaultFinal.isFinal)
        assertTrue(findDanglingRunIds(listOf(user, defaultFinal)).isEmpty())
    }

    @Test fun import_rejectsHugeRunIdParentFailureReason() {
        setUp()
        // Over-limit single columns fail with line number and zero session,
        // without echoing the offending input fragment.
        val hugeRun = "r".repeat(JsonlCodec.MAX_RUN_ID_CHARS + 1)
        run {
            val f = File(tmpDir, "huge-run.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"$hugeRun\",\"kind\":\"user\",\"text\":\"t\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1}\n",
                Charsets.UTF_8,
            )
            val e = importFailsWithLine(f, 1)
            assertTrue(!e.message!!.contains(hugeRun.take(32)))
        }
        val hugeParent = "p".repeat(JsonlCodec.MAX_RUN_ID_CHARS + 1)
        run {
            val f = File(tmpDir, "huge-parent.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"$hugeParent\",\"attemptIndex\":1}\n",
                Charsets.UTF_8,
            )
            val e = importFailsWithLine(f, 1)
            assertTrue(!e.message!!.contains(hugeParent.take(32)))
        }
        val hugeReason = "e".repeat(JsonlCodec.MAX_FAILURE_REASON_CHARS + 1)
        run {
            val f = File(tmpDir, "huge-reason.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"assistant\",\"text\":\"half\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":true,\"failureReason\":\"$hugeReason\"}\n",
                Charsets.UTF_8,
            )
            val e = importFailsWithLine(f, 1)
            assertTrue(!e.message!!.contains(hugeReason.take(32)))
        }
        // At-limit boundaries stay accepted (no over-clip of legit rows).
        run {
            val okRun = "r".repeat(JsonlCodec.MAX_RUN_ID_CHARS)
            val f = File(tmpDir, "ok-run-cap.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"$okRun\",\"kind\":\"user\",\"text\":\"t\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            runBlocking { store.deleteSession(sid) }
        }
        run {
            val okParent = "p".repeat(JsonlCodec.MAX_RUN_ID_CHARS)
            val f = File(tmpDir, "ok-parent-cap.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"$okParent\",\"attemptIndex\":1}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            runBlocking { store.deleteSession(sid) }
        }
        run {
            val okReason = "e".repeat(JsonlCodec.MAX_FAILURE_REASON_CHARS)
            val f = File(tmpDir, "ok-reason-cap.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"assistant\",\"text\":\"half\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":true,\"failureReason\":\"$okReason\"}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            runBlocking { store.deleteSession(sid) }
        }
    }

    @Test fun import_nullAndStructuralValuesFailWithLineNumber() {
        setUp()
        // Required-field null/object/array all fail with line number and zero session.
        val requiredCases = listOf(
            "{\"seq\":null,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":{},\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":[],\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":null,\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":{},\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":[],\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":null,\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":{},\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":[],\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":null,\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":{},\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":[],\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":null,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":{},\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":[],\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":null}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":{}}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":[]}",
        )
        for ((i, line) in requiredCases.withIndex()) {
            val f = File(tmpDir, "null-req-$i.jsonl")
            f.writeText(line + "\n", Charsets.UTF_8)
            importFailsWithLine(f, 1)
        }
        // Required-field wrong primitives fail the same way (line-only, zero session).
        val requiredWrongPrimitive = listOf(
            "{\"seq\":\"1\",\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":true,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1.5,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":123,\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":true,\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":123,\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":123,\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":true,\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":\"x\",\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":true,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":1.5,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":\"x\"}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":true}",
        )
        for ((i, line) in requiredWrongPrimitive.withIndex()) {
            val f = File(tmpDir, "wrong-req-$i.jsonl")
            f.writeText(line + "\n", Charsets.UTF_8)
            importFailsWithLine(f, 1)
        }
        // Optional-field object/array fail (JsonNull stays the documented default).
        val optionalStructural = listOf(
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":{}}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":[]}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":{}}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":[]}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"failureReason\":{}}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"failureReason\":[]}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":{}}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":[]}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":{}}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":[]}",
        )
        for ((i, line) in optionalStructural.withIndex()) {
            val f = File(tmpDir, "null-opt-$i.jsonl")
            f.writeText(line + "\n", Charsets.UTF_8)
            importFailsWithLine(f, 1)
        }
        // Optional-field wrong primitives fail the same way.
        val optionalWrongPrimitive = listOf(
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":\"x\"}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":2}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":\"x\"}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":2}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"failureReason\":123}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"failureReason\":true}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":123}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":true}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":\"x\"}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":1.5}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":true}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":\"1\"}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":\"1\"}",
            "{\"seq\":1,\"runId\":\"A2\",\"kind\":\"assistant\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":\"1\"}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":\"1\",\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":\"1\"}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1.5}",
        )
        for ((i, line) in optionalWrongPrimitive.withIndex()) {
            val f = File(tmpDir, "wrong-opt-$i.jsonl")
            f.writeText(line + "\n", Charsets.UTF_8)
            importFailsWithLine(f, 1)
        }
        // Explicit-null optionals stay accepted and read back as documented defaults.
        run {
            val f = File(tmpDir, "ok-opt-null.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":null,\"isFinal\":null," +
                    "\"failureReason\":null,\"parentRunId\":null,\"attemptIndex\":null}\n",
                Charsets.UTF_8,
            )
            val sid = runBlocking { store.importJsonl(f) }
            val ev = runBlocking { store.loadEvents(sid) }.single()
            assertEquals(false, ev.isPartial)
            assertEquals(true, ev.isFinal)
            assertEquals(null, ev.failureReason)
            assertEquals(null, ev.parentRunId)
            assertEquals(null, ev.attemptIndex)
            runBlocking { store.deleteSession(sid) }
        }
        // Top-level structural lines fail with line number as well.
        for ((i, line) in listOf("null", "[]", "{}").withIndex()) {
            val f = File(tmpDir, "null-top-$i.jsonl")
            f.writeText(line + "\n", Charsets.UTF_8)
            importFailsWithLine(f, 1)
        }
        // Two-line files: a valid first line plus a bad second line fails at
        // line 2 with zero sessions (one per gate family: F2 shape, F7 cap, F11 type).
        run {
            val f = File(tmpDir, "two-f2.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"user\",\"text\":\"hi\",\"imagesOmitted\":0,\"createdAt\":1}\n" +
                    "{\"seq\":2,\"runId\":\"r2\",\"kind\":\"user\",\"text\":\"hi2\",\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"L\"}\n",
                Charsets.UTF_8,
            )
            importFailsWithLine(f, 2)
        }
        run {
            val hugeSecond = "q".repeat(JsonlCodec.MAX_RUN_ID_CHARS + 1)
            val f = File(tmpDir, "two-f7.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"user\",\"text\":\"hi\",\"imagesOmitted\":0,\"createdAt\":1}\n" +
                    "{\"seq\":2,\"runId\":\"$hugeSecond\",\"kind\":\"user\",\"text\":\"hi2\",\"imagesOmitted\":0,\"createdAt\":1}\n",
                Charsets.UTF_8,
            )
            val e = importFailsWithLine(f, 2)
            assertTrue(!e.message!!.contains(hugeSecond.take(32)))
        }
        run {
            val f = File(tmpDir, "two-f11.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"user\",\"text\":\"hi\",\"imagesOmitted\":0,\"createdAt\":1}\n" +
                    "{\"seq\":2,\"runId\":\"r2\",\"kind\":\"user\",\"text\":\"hi2\",\"imagesOmitted\":0,\"createdAt\":null}\n",
                Charsets.UTF_8,
            )
            importFailsWithLine(f, 2)
        }
        // No-echo: distinctive fragments actually present in the file must never
        // surface in the line-number-only error (F11 null + F2 shape + F7 cap legs).
        val smuggled = "smuggled-noecho-9d4f2a71c0"
        run {
            val f = File(tmpDir, "noecho-null.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"$smuggled\"," +
                    "\"imagesOmitted\":0,\"createdAt\":null}\n",
                Charsets.UTF_8,
            )
            val e = importFailsWithLine(f, 1)
            assertTrue(!e.message!!.contains(smuggled))
        }
        run {
            val f = File(tmpDir, "noecho-shape.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"$smuggled\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"${smuggled}-parent\"}\n",
                Charsets.UTF_8,
            )
            val e = importFailsWithLine(f, 1)
            assertTrue(!e.message!!.contains(smuggled))
        }
        run {
            val hugeFrag = "z".repeat(JsonlCodec.MAX_RUN_ID_CHARS + 1)
            val f = File(tmpDir, "noecho-huge.jsonl")
            f.writeText(
                "{\"seq\":1,\"runId\":\"$hugeFrag\",\"kind\":\"user\",\"text\":\"$smuggled\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1}\n",
                Charsets.UTF_8,
            )
            val e = importFailsWithLine(f, 1)
            assertTrue(!e.message!!.contains(hugeFrag.take(32)))
            assertTrue(!e.message!!.contains(smuggled))
        }
        // Direct decode parity: null/object/array never leak content into the message.
        try {
            JsonlCodec.decode(9, "{\"seq\":1,\"runId\":null,\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}")
            fail("null runId must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 9"))
            assertTrue(!e.message!!.contains("null"))
        }
    }
}
