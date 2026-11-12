package dev.librepocket.backup

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

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
        assertExcludedOnceInBothRuleFiles(
            domain = "sharedpref",
            path = "assistant_role.xml",
        )
    }

    /**
     * #136-B1 grant lifecycle: privilege grant DataStores are device-bound
     * authorization state (NOT key/vault material, so deliberately NOT part of
     * [BackupPolicy.KEY_FILES_EXCLUDED]), and must be excluded from both
     * Auto Backup and API 31+ cloud-backup / device-transfer.
     */
    @Test fun privilegeGrants_notInKeyFilesExcluded() {
        assertTrue(
            "librepocket_privilege_bridge.preferences_pb is device-bound grant state, not key material",
            BackupPolicy.KEY_FILES_EXCLUDED.none { it.contains("librepocket_privilege_bridge.preferences_pb") },
        )
        assertTrue(
            "librepocket_shizuku_grant.preferences_pb is device-bound grant state, not key material",
            BackupPolicy.KEY_FILES_EXCLUDED.none { it.contains("librepocket_shizuku_grant.preferences_pb") },
        )
    }

    @Test fun privilegeGrants_excludedFromBothRuleFiles() {
        val privilegePaths = listOf(
            "datastore/librepocket_privilege_bridge.preferences_pb",
            "datastore/librepocket_shizuku_grant.preferences_pb",
        )
        for (path in privilegePaths) {
            assertExcludedOnceInBothRuleFiles(domain = "file", path = path)
        }
    }

    private fun assertExcludedOnceInBothRuleFiles(domain: String, path: String) {
        val factory = DocumentBuilderFactory.newInstance()
        val backupDoc = factory.newDocumentBuilder()
            .parse(InputSource(StringReader(ruleFileText("backup_rules.xml"))))
        val extractionDoc = factory.newDocumentBuilder()
            .parse(InputSource(StringReader(ruleFileText("data_extraction_rules.xml"))))

        assertEquals("full-backup-content", backupDoc.documentElement.tagName)
        assertEquals("data-extraction-rules", extractionDoc.documentElement.tagName)

        val fullBackupPaths = directExcludePaths(backupDoc.documentElement, domain = domain)
        val cloudBackupEl = directChildElement(extractionDoc.documentElement, "cloud-backup")
        val deviceTransferEl = directChildElement(extractionDoc.documentElement, "device-transfer")
        val cloudPaths = directExcludePaths(cloudBackupEl, domain = domain)
        val transferPaths = directExcludePaths(deviceTransferEl, domain = domain)

        assertEquals(
            "backup_rules.xml <full-backup-content> must exclude domain=$domain path=$path exactly once",
            1,
            fullBackupPaths.count { it == path },
        )
        assertEquals(
            "data_extraction_rules.xml <cloud-backup> must exclude domain=$domain path=$path exactly once",
            1,
            cloudPaths.count { it == path },
        )
        assertEquals(
            "data_extraction_rules.xml <device-transfer> must exclude domain=$domain path=$path exactly once",
            1,
            transferPaths.count { it == path },
        )
    }

    private fun directChildElement(parent: Element, tagName: String): Element {
        val children = parent.childNodes
        val matches = (0 until children.length)
            .map { children.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tagName }
        assertEquals("expected exactly one <$tagName>", 1, matches.size)
        return matches.single()
    }

    private fun directExcludePaths(container: Element, domain: String): List<String> {
        val children = container.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "exclude" && it.getAttribute("domain") == domain }
            .map { it.getAttribute("path") }
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
