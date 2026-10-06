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
}
