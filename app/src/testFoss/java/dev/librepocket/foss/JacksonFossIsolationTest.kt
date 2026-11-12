package dev.librepocket.foss

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarFile

/**
 * 驗證 FOSS 風味類別路徑（classpath）中完全隔離 Jackson 依賴。
 *
 * Jackson 僅隨 Azure Speech SDK 引入於 github flavor，
 * FOSS 版本必須維持零 Jackson 依賴以確保開源純淨度與最小攻擊面。
 */
class JacksonFossIsolationTest {

    @Test
    fun jacksonObjectMapper_notPresentOnFossClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper")
        }
    }

    @Test
    fun jacksonCore_notPresentOnFossClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.core.JsonFactory")
        }
    }

    @Test
    fun jacksonJsonProperty_notPresentOnFossClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.fasterxml.jackson.annotation.JsonProperty")
        }
    }

    @Test
    fun noJacksonClassesOrResources_presentOnFossClasspath() {
        val scan = scanJacksonClasspathEntries()
        assertTrue(
            "No classpath roots discovered for FOSS Jackson isolation scan: ${scan.roots.sorted()}",
            scan.roots.isNotEmpty(),
        )
        assertTrue(
            "Unreadable classpath entries during FOSS Jackson isolation scan: ${scan.unreadable.joinToString()}",
            scan.unreadable.isEmpty(),
        )
        assertTrue(
            "FOSS classpath must not contain com.fasterxml.jackson classes/resources, but found: ${scan.hits.joinToString()}",
            scan.hits.isEmpty(),
        )
    }

    private data class JacksonScanResult(
        val roots: Set<String>,
        val hits: List<String>,
        val unreadable: List<String>,
    )

    private fun scanJacksonClasspathEntries(): JacksonScanResult {
        val jacksonPathPrefix = "com/fasterxml/jackson/"
        val hits = linkedSetOf<String>()
        val roots = linkedSetOf<File>()
        val unreadable = mutableListOf<String>()
        collectClasspathRoots(roots)

        roots.forEach { entry ->
            try {
                when {
                    !entry.exists() -> {
                        unreadable += entry.path + " (missing classpath root)"
                    }
                    entry.isDirectory -> {
                        val root = File(entry, jacksonPathPrefix)
                        if (root.exists()) {
                            root.walkTopDown()
                                .filter { it.isFile && it.path.replace(File.separatorChar, '/').contains(jacksonPathPrefix) }
                                .take(10)
                                .forEach { file ->
                                    val rel = entry.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                                    hits += entry.path + "!" + rel
                                }
                        }
                    }
                    entry.isFile && entry.name.endsWith(".jar", ignoreCase = true) -> {
                        JarFile(entry).use { jar ->
                            jar.entries().asSequence()
                                .filter { !it.isDirectory && it.name.startsWith(jacksonPathPrefix) }
                                .take(10)
                                .forEach { jarEntry -> hits += entry.path + "!" + jarEntry.name }
                        }
                    }
                    else -> {
                        unreadable += entry.path + " (unsupported classpath root type)"
                    }
                }
            } catch (e: Exception) {
                val errorType = e.javaClass.simpleName
                val errorMessage = e.message ?: "no message"
                unreadable += entry.path + " (" + errorType + ": " + errorMessage + ")"
            }
        }
        return JacksonScanResult(roots = roots.map { it.path }.toSet(), hits = hits.toList(), unreadable = unreadable)
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
}
