package dev.librepocket.session

import androidx.room.withTransaction
import dev.librepocket.redact.Redactor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room-backed [SessionStore] (M4, spec §8).
 *
 * Write-path guarantees (spec §8.4):
 * - every event text passes [Redactor.redact] before insert;
 * - image bytes never reach the DB (only [TranscriptEvent.imagesOmitted]);
 * - `seq` is assigned as (max+1) inside the same transaction as the insert,
 *   with a [Mutex] as the second line of defence for same-session concurrency.
 *
 * The DAO is blocking Java; all DB access is confined to [Dispatchers.IO].
 * Prune auto-runs every [AUTO_PRUNE_EVERY] appends with the default policy;
 * callers needing other policies invoke [prune] explicitly.
 *
 * Notes:
 * - seq gaps are legal (prune deletes old rows; import preserves file seqs);
 *   only strictly-increasing order + uniqueness are required.
 * - session titles are redacted on every current write path (create/import),
 *   and re-redacted on every list/get read, so rows written before write-time
 *   redaction existed are still safe to display.
 */
class RoomSessionStore(
    private val db: LibrePocketDb,
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionStore {

    private val dao: SessionDao get() = db.sessionDao()
    private val appendMutex = Mutex()
    private var appendsSincePrune = 0

    override suspend fun createSession(title: String, model: String): String =
        withContext(Dispatchers.IO) {
            require(model.isNotBlank()) { "model must not be blank" }
            val now = clock()
            val id = UUID.randomUUID().toString()
            dao.insertSession(
                SessionEntity(
                    id,
                    Redactor.redact(title).text,
                    model,
                    now,
                    now,
                    false,
                ),
            )
            id
        }

    override suspend fun appendEvent(event: TranscriptEvent): Long {
        require(event.kind in VALID_KINDS) { "unknown event kind: ${event.kind}" }
        val redacted = Redactor.redact(event.text).text
        val (text, truncated) = if (redacted.length > MAX_TEXT_CHARS) {
            redacted.take(MAX_TEXT_CHARS) to true
        } else {
            redacted to false
        }
        val rowId = appendMutex.withLock {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    require(dao.sessionById(event.sessionId) != null) {
                        "unknown session"
                    }
                    val seq = dao.nextSeq(event.sessionId)
                    val row = dao.insertEvent(
                        TranscriptEventEntity(
                            0,
                            event.sessionId,
                            seq,
                            event.runId,
                            event.kind,
                            text,
                            truncated,
                            event.imagesOmitted,
                            event.createdAt,
                        ),
                    )
                    dao.touchSession(event.sessionId, clock())
                    row
                }
            }
        }
        appendsSincePrune++
        if (appendsSincePrune >= AUTO_PRUNE_EVERY) {
            appendsSincePrune = 0
            prune(PrunePolicy())
        }
        return rowId
    }

    override suspend fun loadEvents(
        sessionId: String,
        afterSeq: Long,
        limit: Int,
    ): List<TranscriptEvent> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit must be positive" }
        dao.eventsAfter(sessionId, afterSeq, limit).map { it.toEvent() }
    }

    override suspend fun listSessions(): List<SessionMeta> = withContext(Dispatchers.IO) {
        dao.allSessions().map { it.toMeta() }
    }

    override suspend fun getSession(sessionId: String): SessionMeta? =
        withContext(Dispatchers.IO) {
            dao.sessionById(sessionId)?.toMeta()
        }

    override suspend fun exportJsonl(sessionId: String, destFile: File) {
        // Paged streaming export: memory stays O(page), not O(session).
        // Writes to a tmp sibling first; destFile appears atomically only on
        // success, so a failure or oversize abort never leaves a half file.
        // The byte budget is enforced DURING the write (not after
        // materializing everything) to avoid OOM on huge sessions.
        val parent = destFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            error("export failed: cannot create directory ${parent.path}")
        }
        val tmp = File(parent, destFile.name + ".tmp")
        var bytes = 0L
        var afterSeq = 0L
        withContext(Dispatchers.IO) {
            try {
                tmp.bufferedWriter(Charsets.UTF_8).use { out ->
                    while (true) {
                        val page = dao.eventsAfter(sessionId, afterSeq, EXPORT_PAGE_SIZE)
                        if (page.isEmpty()) break
                        for (row in page) {
                            val event = row.toEvent()
                            // B1 defense-in-depth: legacy rows may predate write-time
                            // redaction, so re-redact at export time before touching disk.
                            // Mirrors TranscriptExport.exportRedacted semantics.
                            val redacted = Redactor.redact(event.text).text
                            val line = JsonlCodec.encode(event.copy(text = redacted))
                            bytes += line.toByteArray(Charsets.UTF_8).size + 1
                            check(bytes <= EXPORT_MAX_BYTES) { "export too large" }
                            out.write(line)
                            out.newLine()
                            afterSeq = row.seq
                        }
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            if (!tmp.renameTo(destFile)) {
                tmp.delete()
                error("export failed: cannot move tmp file into place")
            }
        }
    }

    override suspend fun importJsonl(srcFile: File): String {
        // Refuse oversized files BEFORE reading (export caps at the same bound).
        // A missing file has length 0 and falls through to the reader, which
        // throws FileNotFoundException as before.
        require(srcFile.length() <= IMPORT_MAX_BYTES) { "import too large" }
        // Stream line-by-line: memory stays O(file) via the size gate above,
        // never 2x (readLines + parsed list). Parsing completes before any
        // write, so a bad line still rolls back the whole batch (transaction).
        val parsed = ArrayList<JsonlLine>()
        var lastSeq = 0L
        var lineNumber = 0
        withContext(Dispatchers.IO) {
            srcFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    lineNumber++
                    if (raw.isBlank()) continue
                    val line = JsonlCodec.decode(lineNumber, raw)
                    require(line.seq > lastSeq) { "import failed at line $lineNumber" }
                    lastSeq = line.seq
                    parsed.add(line)
                }
            }
        }
        val now = clock()
        val title = parsed.firstOrNull { it.kind == "user" }?.text?.let {
            Redactor.redact(it).text.take(TITLE_CHARS)
        } ?: IMPORT_FALLBACK_TITLE
        return withContext(Dispatchers.IO) {
            db.withTransaction {
                val sessionId = UUID.randomUUID().toString()
                dao.insertSession(
                    SessionEntity(
                        sessionId,
                        title,
                        IMPORT_MODEL,
                        now,
                        now,
                        false,
                    ),
                )
                for (line in parsed) {
                    // Same truncate parity as appendEvent: over-long texts are cut
                    // and flagged instead of bypassing the MAX_TEXT_CHARS bound.
                    val redactedText = Redactor.redact(line.text).text
                    val (text, truncated) = if (redactedText.length > MAX_TEXT_CHARS) {
                        redactedText.take(MAX_TEXT_CHARS) to true
                    } else {
                        redactedText to false
                    }
                    dao.insertEvent(
                        TranscriptEventEntity(
                            0,
                            sessionId,
                            line.seq,
                            line.runId,
                            line.kind,
                            text,
                            truncated,
                            line.imagesOmitted,
                            line.createdAt,
                        ),
                    )
                }
                sessionId
            }
        }
    }

    override suspend fun prune(policy: PrunePolicy): PruneResult =
        withContext(Dispatchers.IO) {
            var deletedSessions = 0
            var deletedEvents = 0
            val cutoff = clock() - policy.maxAgeDays * MILLIS_PER_DAY
            val stale = dao.staleSessionIds(cutoff).toSet()
            val survivors = ArrayList<String>()
            for (session in dao.allSessions()) {
                if (session.sessionId in stale) {
                    if (policy.keepPinnedSessions && session.isPinned) {
                        survivors.add(session.sessionId)
                    } else {
                        dao.deleteSession(session.sessionId) // CASCADE clears events
                        deletedSessions++
                    }
                } else {
                    survivors.add(session.sessionId)
                }
            }
            for (sid in survivors) {
                val count = dao.eventCount(sid)
                if (count > policy.maxEventsPerSession) {
                    val through = dao.maxSeq(sid) - policy.maxEventsPerSession
                    deletedEvents += dao.deleteEventsThrough(sid, through)
                }
            }
            PruneResult(deletedEvents = deletedEvents, deletedSessions = deletedSessions)
        }

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteSession(sessionId)
        }
    }

    private fun TranscriptEventEntity.toEvent(): TranscriptEvent = TranscriptEvent(
        seq = seq,
        sessionId = sessionId,
        runId = runId,
        kind = kind,
        text = text,
        imagesOmitted = imagesOmitted,
        createdAt = createdAt,
    )

    private fun SessionEntity.toMeta(): SessionMeta = SessionMeta(
        sessionId = sessionId,
        // Defense in depth: rows predating write-time redaction are cleaned here.
        title = Redactor.redact(title).text,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        const val MAX_TEXT_CHARS = 100_000
        const val EXPORT_MAX_BYTES = 20 * 1024 * 1024
        const val IMPORT_MAX_BYTES = EXPORT_MAX_BYTES
        const val EXPORT_PAGE_SIZE = 500
        const val TITLE_CHARS = 30
        const val AUTO_PRUNE_EVERY = 100
        const val MILLIS_PER_DAY = 86_400_000L
        const val IMPORT_MODEL = "imported/unknown"
        const val IMPORT_FALLBACK_TITLE = "imported"
    }
}
