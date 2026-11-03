package dev.librepocket.voice.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #132 防退化測試：
 * 保證 github flavor 的 Jackson 依賴被 BOM 約束在修復版本 (>= 2.18.12)，
 * 防止未來升級依賴或誤改 build.gradle.kts 導致回歸漏洞版本。
 *
 * Azure Speech SDK 1.52.0 預設透過 azure-core 傳遞引入 jackson 2.18.7，
 * 存在 16 個已被 Jackson 2.18.8+ 修復的安全諮詢（Advisories）。
 * 此處限定在 2.18.x 安定支線以維持 Azure Speech SDK 1.52.0 二進位相容性。
 * 此測試驗證 jackson-core、jackson-databind 與 jackson-datatype-jsr310 在執行期解析的套件版本皆符合安全約束。
 */
class JacksonVersionConstraintTest {

    @Test
    fun jacksonCoreVersion_isConstrainedToFixedRelease() {
        val version = com.fasterxml.jackson.core.json.PackageVersion.VERSION
        assertEquals("Jackson core major version should be 2", 2, version.majorVersion)
        assertEquals("Jackson core minor version should be 18", 18, version.minorVersion)
        assertTrue(
            "Jackson core patch level (${version.patchLevel}) must be >= 12 (Issue #132)",
            version.patchLevel >= 12,
        )
        assertEquals("Must not be snapshot version", false, version.isSnapshot)
    }

    @Test
    fun jacksonDatabindVersion_isConstrainedToFixedRelease() {
        val version = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION
        assertEquals("Jackson databind major version should be 2", 2, version.majorVersion)
        assertEquals("Jackson databind minor version should be 18", 18, version.minorVersion)
        assertTrue(
            "Jackson databind patch level (${version.patchLevel}) must be >= 12 (Issue #132)",
            version.patchLevel >= 12,
        )
        assertEquals("Must not be snapshot version", false, version.isSnapshot)
    }

    @Test
    fun jacksonJsr310Version_isConstrainedToFixedRelease() {
        val version = com.fasterxml.jackson.datatype.jsr310.PackageVersion.VERSION
        assertEquals("Jackson jsr310 major version should be 2", 2, version.majorVersion)
        assertEquals("Jackson jsr310 minor version should be 18", 18, version.minorVersion)
        assertTrue(
            "Jackson jsr310 patch level (${version.patchLevel}) must be >= 12 (Issue #132)",
            version.patchLevel >= 12,
        )
        assertEquals("Must not be snapshot version", false, version.isSnapshot)
    }
}
