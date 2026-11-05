package dev.librepocket.backup

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the system-backup exclusion contract (mirrors res/xml/backup_rules.xml
 * + data_extraction_rules.xml).
 *
 * Android backup `path` is exact-match (no globs): filename prefixes do NOT
 * exclude anything, so keysets must live in their own directory and SQLite
 * WAL sidecars must be listed explicitly.
 */
class BackupPolicyTest {

    @Test fun keyExclusions_coverVaultSidecars() {
        assertTrue(BackupPolicy.KEY_FILES_EXCLUDED.contains("database/librepocket_vault"))
        assertTrue(BackupPolicy.KEY_FILES_EXCLUDED.contains("database/librepocket_vault-wal"))
        assertTrue(BackupPolicy.KEY_FILES_EXCLUDED.contains("database/librepocket_vault-shm"))
    }

    @Test fun keyExclusions_useKeysetDirectoryNotPrefix() {
        assertTrue(BackupPolicy.KEY_FILES_EXCLUDED.contains("file/librepocket_keysets"))
        assertTrue(
            "prefix-style entries never match (exact-match semantics)",
            BackupPolicy.KEY_FILES_EXCLUDED.none { it.endsWith("_") },
        )
    }

    @Test fun keys_excludedByDefault() {
        assertTrue(!BackupPolicy.DEFAULT_INCLUDE_KEYS)
    }

    /**
     * Finding D [P3]: device-bound assistant state must not migrate.
     *
     * `assistant_role.xml` holds `everHeld` for the system ROLE_ASSISTANT
     * grant — a per-device system state. Restoring it onto an unauthorized
     * device would misreport REVOKED, so both rule files must exclude it.
     * It is deliberately NOT part of [BackupPolicy.KEY_FILES_EXCLUDED]
     * (key/vault-only).
     */
    @Test fun assistantRole_notInKeyFilesExcluded() {
        assertTrue(
            "assistant_role.xml is device-bound state, not key material",
            BackupPolicy.KEY_FILES_EXCLUDED.none { it.endsWith("assistant_role.xml") },
        )
    }

    @Test fun assistantRole_excludedFromBothRuleFiles() {
        val backup = ruleFileText("backup_rules.xml")
        val extraction = ruleFileText("data_extraction_rules.xml")
        val exclude = "<exclude domain=\"sharedpref\" path=\"assistant_role.xml\" />"
        assertTrue("backup_rules.xml must exclude $exclude", backup.contains(exclude))
        assertTrue("data_extraction_rules.xml must exclude $exclude (cloud + transfer)", extraction.contains(exclude))
        // Both extraction branches carry it (not just cloud-backup).
        // No split limit: 3+ occurrences must fail, not saturate at 2.
        val occurrences = extraction.split(exclude).size - 1
        assertTrue(
            "data_extraction_rules.xml must exclude assistant_role.xml in both cloud-backup and device-transfer, found $occurrences",
            occurrences == 2,
        )
    }

    /**
     * Unit-test working dir is the module dir; walk up so the test also
     * resolves when Gradle runs it from a different cwd (same shape as
     * VoiceTtsConfirmGenerationTest.findSource).
     */
    private fun ruleFileText(name: String): String {
        val bases = generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }.take(6).toList()
        val rels = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name")
        for (base in bases) {
            for (rel in rels) {
                val f = java.io.File(base, rel)
                if (f.isFile) return f.readText()
            }
        }
        error("not found: $name under ${System.getProperty("user.dir")}")
    }
}
