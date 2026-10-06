package dev.librepocket.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D07 `AuditExportTest` (ROADMAP P7 §验收.1 / BACKLOG D07 + B6 脫敏匯出)。
 *
 * - 轉錄匯出預設為脫敏版：給定含電話/郵件/金鑰/bearer 的語料，輸出不含原文；
 * - 明文匯出未經二次確認一律拒絕（缺首肯 / 缺次肯 / 空 token）；
 * - 雙確認通過才放行明文，且全程寫審計事件；
 * - 審計匯出本身不含明文敏感（再脫敏一遍）。
 *
 * Pure JVM (no Android / no Robolectric).
 */
class AuditExportTest {

    private val phone = "0912-345-678"
    private val email = "user@example.com"
    private val apiKey = "sk-abcDEF1234567890"
    private val bearer = "abcdef123456"

    private fun sensitiveEvents() = listOf(
        BackupEvent(1, "run-1", "user", "call me $phone or $email", 0, 1L),
        BackupEvent(2, "run-1", "assistant", "key is $apiKey", 0, 2L),
        BackupEvent(3, "run-1", "tool", "Authorization: Bearer $bearer", 0, 3L),
    )

    private fun assertNoSecrets(out: String) {
        for (secret in listOf(phone, email, apiKey, bearer)) {
            assertFalse("secret leaked: $secret\nin: $out", secret in out)
        }
    }

    @Test fun redactedExport_containsNoSecrets() {
        val audit = AuditLog()
        val out = TranscriptExport.exportRedacted("sess-1", sensitiveEvents(), audit)

        assertNoSecrets(out)
        assertTrue("redaction markers expected", "⟦REDACTED" in out)
        // Still valid JSONL: 3 lines, each parseable.
        assertEquals(3, out.trim().lines().size)
        // One audit row for the export itself.
        assertEquals(1, audit.countByType()[AuditType.REDACTED_EXPORT])
    }

    @Test fun plaintext_withoutFirstConfirm_refusedAndAudited() {
        val audit = AuditLog()
        try {
            TranscriptExport.requestPlaintext("sess-1", false, audit)
            fail("missing first confirm must throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(1, audit.countByType()[AuditType.PLAINTEXT_DENIED])
        assertEquals(null, audit.countByType()[AuditType.PLAINTEXT_REQUESTED])
    }

    @Test fun plaintext_withoutSecondConfirm_refusedAndAudited() {
        val audit = AuditLog()
        val token = TranscriptExport.requestPlaintext("sess-1", true, audit)
        try {
            TranscriptExport.confirmPlaintext("sess-1", sensitiveEvents(), token, false, audit)
            fail("missing second confirm must throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        val counts = audit.countByType()
        assertEquals(1, counts[AuditType.PLAINTEXT_REQUESTED])
        assertEquals(1, counts[AuditType.PLAINTEXT_DENIED])
    }

    @Test fun plaintext_withBlankToken_refused() {
        val audit = AuditLog()
        try {
            TranscriptExport.confirmPlaintext("sess-1", sensitiveEvents(), "", true, audit)
            fail("blank token must throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(1, audit.countByType()[AuditType.PLAINTEXT_DENIED])
    }

    @Test fun plaintext_withForgedToken_refused() {
        val audit = AuditLog()
        try {
            TranscriptExport.confirmPlaintext("sess-1", sensitiveEvents(), "forged-token", true, audit)
            fail("forged token must throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(1, audit.countByType()[AuditType.PLAINTEXT_DENIED])
    }

    @Test fun plaintext_tokenSingleUse_replayRefused() {
        val audit = AuditLog()
        val token = TranscriptExport.requestPlaintext("sess-1", true, audit)
        TranscriptExport.confirmPlaintext("sess-1", sensitiveEvents(), token, true, audit)
        try {
            TranscriptExport.confirmPlaintext("sess-1", sensitiveEvents(), token, true, audit)
            fail("replayed token must throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun plaintext_tokenBoundToSession_crossSessionRefused() {
        val audit = AuditLog()
        val token = TranscriptExport.requestPlaintext("sess-1", true, audit)
        try {
            TranscriptExport.confirmPlaintext("sess-2", sensitiveEvents(), token, true, audit)
            fail("cross-session token must throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun plaintext_expiredToken_refused() {
        val audit = AuditLog()
        val issuedAt = 1_700_000_000_000L
        val token = TranscriptExport.requestPlaintext("sess-1", true, audit, atMs = issuedAt, nowMs = issuedAt)
        try {
            TranscriptExport.confirmPlaintext(
                "sess-1",
                sensitiveEvents(),
                token,
                true,
                audit,
                atMs = issuedAt + TranscriptExport.TOKEN_TTL_MS + 1,
                nowMs = issuedAt + TranscriptExport.TOKEN_TTL_MS + 1,
            )
            fail("expired token must throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(1, audit.countByType()[AuditType.PLAINTEXT_DENIED])
    }

    @Test fun plaintext_doubleConfirmed_emitsPlaintextAndAudits() {
        val audit = AuditLog()
        val token = TranscriptExport.requestPlaintext("sess-1", true, audit)
        val out = TranscriptExport.confirmPlaintext("sess-1", sensitiveEvents(), token, true, audit)

        // Double-confirmed plaintext intentionally carries the original text.
        assertTrue(phone in out)
        val counts = audit.countByType()
        assertEquals(1, counts[AuditType.PLAINTEXT_REQUESTED])
        assertEquals(1, counts[AuditType.PLAINTEXT_CONFIRMED])
        assertEquals(null, counts[AuditType.PLAINTEXT_DENIED])
    }

    @Test fun auditExport_containsNoSecrets() {
        val audit = AuditLog()
        // Even if a future caller files an odd detail string, export re-redacts.
        audit.record(AuditType.REDACTED_EXPORT, "sess-1", "events=3", 1L)
        audit.record(AuditType.PLAINTEXT_REQUESTED, "sess-1", "first-confirm=yes", 2L)
        audit.record(AuditType.BACKUP_CREATED, "sess-2", "note $email $phone", 3L)

        val out = audit.exportJson()
        assertNoSecrets(out)
        assertEquals(3, out.trim().lines().size)
    }
}
