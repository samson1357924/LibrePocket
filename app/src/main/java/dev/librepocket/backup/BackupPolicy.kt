package dev.librepocket.backup

/**
 * D07 backup policy (P7, BACKLOG D07).
 *
 * - Local backup bundles NEVER include API keys by default
 *   ([DEFAULT_INCLUDE_KEYS] = false). Opting in requires an explicit
 *   `includeKeys = true` at the call site; the default path drops key
 *   material before serialization, so a backup file restored on another
 *   device simply has no keys — the user re-enters them.
 * - System Auto Backup / device transfer excludes the same files via
 *   `res/xml/backup_rules.xml` (legacy) + `res/xml/data_extraction_rules.xml`
 *   (API 31+), wired in the manifest.
 */
object BackupPolicy {

    /** Backup format version written into every bundle. */
    const val BUNDLE_VERSION = 1

    /** Keys are excluded unless the caller explicitly opts in. */
    const val DEFAULT_INCLUDE_KEYS = false

    /**
     * Key-file inventory excluded from system backup (mirrors the two
     * `res/xml` rule files; see EncryptedPrefsVault.PREFS_FILE).
     *
     * NOTE: Android backup `path` is an exact file-or-directory match (no
     * globs/prefixes), so keysets are isolated in their own directory and WAL
     * sidecars are listed explicitly. Keyset files MUST be created under
     * `files/librepocket_keysets/` for the exclusion to apply.
     */
    val KEY_FILES_EXCLUDED: List<String> = listOf(
        "sharedpref/librepocket_keys.xml",
        "database/librepocket_vault",
        "database/librepocket_vault-wal",
        "database/librepocket_vault-shm",
        "file/librepocket_keysets",
    )

    /** Closed export mode: transcripts export redacted unless double-confirmed. */
    enum class ExportMode {
        REDACTED,
        PLAINTEXT,
    }
}
