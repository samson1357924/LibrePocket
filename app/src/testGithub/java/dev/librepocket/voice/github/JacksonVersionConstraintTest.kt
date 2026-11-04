package dev.librepocket.voice.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #132 防退化測試：
 * 保證 github flavor 的 Jackson 依賴被 BOM 約束在修復版本 (>= 2.18.12)，
 * 防止未來升級依賴或誤改 build.gradle.kts 導致回歸漏洞版本。
 *
 * 背景與安全修補門檻：
 * Azure Speech SDK 1.52.0 預設透過 azure-core 傳遞引入 Jackson 2.18.7。
 * Issue #132 追蹤之 16 筆 advisories 並非皆於 2.18.8 修復（例如 GHSA-q4xh-88c3-wmh7 需 2.18.10），
 * 且後續公布之 Critical 漏洞 GHSA-m8xf-2cr7-77ph (CVE-2026-110879，影響 jackson-databind <= 2.18.11)
 * 需至 2.18.12 才首次修補；因此不得以 2.18.8 當通用安全下限，必須沿 2.18 LTS 線升至 2.18.12。
 *
 * 此測試驗證：
 * 1. jackson-core、jackson-databind 與 jackson-datatype-jsr310 在執行期解析的套件版本皆符合安全約束 (>= 2.18.12 且非 snapshot)。
 * 2. jackson-annotations (JsonProperty) 存在於類別路徑並可正常載入且非空。
 */
class JacksonVersionConstraintTest {

    @Test
    fun jacksonAnnotations_isPresentAndLoadable() {
        val annotationClass = Class.forName("com.fasterxml.jackson.annotation.JsonProperty")
        assertNotNull("jackson-annotations (JsonProperty) should be loadable on github classpath", annotationClass)
    }

    @Test
    fun jacksonCoreVersion_isConstrainedToFixedRelease() {
        val version = com.fasterxml.jackson.core.json.PackageVersion.VERSION
        assertAtLeastVersion2_18_12("Jackson core", version)
    }

    @Test
    fun jacksonDatabindVersion_isConstrainedToFixedRelease() {
        val version = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION
        assertAtLeastVersion2_18_12("Jackson databind", version)
    }

    @Test
    fun jacksonJsr310Version_isConstrainedToFixedRelease() {
        val version = com.fasterxml.jackson.datatype.jsr310.PackageVersion.VERSION
        assertAtLeastVersion2_18_12("Jackson jsr310", version)
    }

    private fun assertAtLeastVersion2_18_12(moduleName: String, version: com.fasterxml.jackson.core.Version) {
        assertEquals("$moduleName major version should be 2", 2, version.majorVersion)
        val meetsVersionFloor = (version.minorVersion == 18 && version.patchLevel >= 12) || version.minorVersion > 18
        assertTrue(
            "$moduleName version (${version.majorVersion}.${version.minorVersion}.${version.patchLevel}) must be >= 2.18.12 (Issue #132 & CVE-2026-110879)",
            meetsVersionFloor,
        )
        assertEquals("Must not be snapshot version", false, version.isSnapshot)
    }
}
