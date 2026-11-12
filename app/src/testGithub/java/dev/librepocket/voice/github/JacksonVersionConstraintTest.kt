package dev.librepocket.voice.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile

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
 * 3. classpath 上所有可解析的 Jackson PackageVersion 皆符合相同安全下限，避免其他傳遞模組漏檢。
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

    @Test
    fun allJacksonPackageVersions_onClasspathMeetSecurityFloor() {
        val scan = scanJacksonPackageVersionClasses()
        assertTrue(
            "No classpath roots discovered for Jackson PackageVersion scan: ${scan.diagnostics()}",
            scan.roots.isNotEmpty(),
        )
        assertTrue(
            "Unreadable classpath entries during Jackson PackageVersion scan: ${scan.unreadable.joinToString()}",
            scan.unreadable.isEmpty(),
        )
        assertTrue(
            "Duplicate Jackson PackageVersion classes found on github classpath: ${scan.duplicateSummary()}",
            scan.duplicates.isEmpty(),
        )
        assertTrue(
            "Expected Jackson PackageVersion classes on github classpath, found none (roots=${scan.roots.size})",
            scan.locationsByClass.isNotEmpty(),
        )

        scan.locationsByClass.keys.sorted().forEach { className ->
            val clazz = Class.forName(className, false, Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader())
            val field = clazz.getField("VERSION")
            assertTrue("$className.VERSION must be static", Modifier.isStatic(field.modifiers))
            val value = field.get(null)
            assertTrue(
                "$className.VERSION must be com.fasterxml.jackson.core.Version but was ${value?.javaClass?.name}",
                value is com.fasterxml.jackson.core.Version,
            )
            assertAtLeastVersion2_18_12(className, value as com.fasterxml.jackson.core.Version)
        }
    }

    private data class PackageVersionScanResult(
        val roots: Set<String>,
        val locationsByClass: Map<String, Set<String>>,
        val unreadable: List<String>,
    ) {
        val duplicates: Map<String, Set<String>>
            get() = locationsByClass.filterValues { it.size > 1 }

        fun duplicateSummary(): String = duplicates.entries
            .sortedBy { it.key }
            .joinToString("; ") { (cls, locs) -> "$cls => ${locs.sorted().joinToString()}" }

        fun diagnostics(): String = "roots=${roots.sorted()}"
    }

    private fun scanJacksonPackageVersionClasses(): PackageVersionScanResult {
        val jacksonPathPrefix = "com/fasterxml/jackson/"
        val packageVersionSuffix = "/PackageVersion.class"
        val roots = linkedSetOf<File>()
        val unreadable = mutableListOf<String>()
        val locationsByClass = linkedMapOf<String, MutableSet<String>>()

        collectClasspathRoots(roots)

        roots.forEach { entry ->
            try {
                when {
                    entry.isDirectory -> {
                        val root = File(entry, jacksonPathPrefix)
                        if (root.exists()) {
                            root.walkTopDown()
                                .filter { it.isFile && it.path.replace(File.separatorChar, '/').endsWith(packageVersionSuffix) }
                                .forEach { file ->
                                    val rel = entry.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                                    val className = rel.removeSuffix(".class").replace('/', '.')
                                    locationsByClass.getOrPut(className) { linkedSetOf() } += "${entry.path}!$rel"
                                }
                        }
                    }
                    entry.isFile && entry.name.endsWith(".jar", ignoreCase = true) -> {
                        JarFile(entry).use { jar ->
                            jar.entries().asSequence()
                                .filter { !it.isDirectory && it.name.startsWith(jacksonPathPrefix) && it.name.endsWith("PackageVersion.class") }
                                .forEach { jarEntry ->
                                    val className = jarEntry.name.removeSuffix(".class").replace('/', '.')
                                    locationsByClass.getOrPut(className) { linkedSetOf() } += "${entry.path}!${jarEntry.name}"
                                }
                        }
                    }
                }
            } catch (e: Exception) {
                unreadable += "${entry.path} (${e.javaClass.simpleName}: ${e.message})"
            }
        }

        return PackageVersionScanResult(
            roots = roots.map { it.path }.toSet(),
            locationsByClass = locationsByClass.mapValues { it.value.toSet() },
            unreadable = unreadable,
        )
    }

    private fun collectClasspathRoots(roots: MutableSet<File>) {
        val classPath = System.getProperty("java.class.path").orEmpty()
        if (classPath.isNotBlank()) {
            classPath.split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .forEach { roots += File(it) }
        }
        listOf(Thread.currentThread().contextClassLoader, ClassLoader.getSystemClassLoader())
            .forEach { loader -> collectUrlRoots(loader, roots) }
    }

    private fun collectUrlRoots(loader: ClassLoader?, roots: MutableSet<File>) {
        var current = loader
        while (current != null) {
            if (current is java.net.URLClassLoader) {
                current.urLs.forEach { url ->
                    try {
                        if (url.protocol == "file") {
                            roots += File(url.toURI())
                        }
                    } catch (_: Exception) {
                        // Ignore non-file or malformed URLs for classpath scanning.
                    }
                }
            }
            current = current.parent
        }
    }

    private fun assertAtLeastVersion2_18_12(moduleName: String, version: com.fasterxml.jackson.core.Version) {
        assertEquals("$moduleName must not be snapshot version", false, version.isSnapshot)
        val major = version.majorVersion
        val minor = version.minorVersion
        val patch = version.patchLevel
        val meetsFloor = major > 2 || (major == 2 && (minor > 18 || (minor == 18 && patch >= 12)))
        assertTrue(
            "$moduleName version ($major.$minor.$patch) must be >= 2.18.12 (Issue #132 & CVE-2026-110879). " +
                "If intentionally upgrading beyond Jackson 2.x, update this security floor and verification metadata consistently.",
            meetsFloor,
        )
    }
}
