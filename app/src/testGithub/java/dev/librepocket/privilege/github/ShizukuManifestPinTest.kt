package dev.librepocket.privilege.github

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Shizuku manifest 面釘死測試（僅 github 風味宣告 ShizukuProvider）：
 * - github overlay 必須宣告 `rikka.shizuku.ShizukuProvider`，且
 *   `authorities="${applicationId}.shizuku"`（後綴 `.shizuku`，自尋 binder 合約）＋
 *   `exported=true`（Manager 回傳 binder 必需，Shizuku 13.x 官方合約）＋
 *   `multiprocess=false`；
 * - play/foss overlay 零匹配 `ShizukuProvider`（文本級 fail-closed：含註解亦紅，
 *   刻意過近似，見 docs_claim_check MCP 面同政策）。
 *
 * 全靜態（只讀三 overlay XML），零真機、零 binder、零 su。
 * 路徑解析：Gradle 單測工作目錄為模組目錄（`app/`）時走 `src/<flavor>/…`；
 * IDE/倉庫根工作目錄時走 `app/src/<flavor>/…`；向上回溯找首個命中。
 */
class ShizukuManifestPinTest {

    private fun flavorManifest(flavor: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        var dir = File(cwd)
        while (true) {
            val direct = File(dir, "src/$flavor/AndroidManifest.xml")
            if (direct.isFile) return direct
            val fromRoot = File(dir, "app/src/$flavor/AndroidManifest.xml")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile ?: break
        }
        throw AssertionError(
            "cannot locate src/$flavor/AndroidManifest.xml from $cwd",
        )
    }

    private fun providerElements(file: File): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val providers = doc.getElementsByTagName("provider")
        return (0 until providers.length).map { providers.item(it) as Element }
    }

    @Test fun githubDeclaresShizukuProviderWithContractAttrs() {
        val file = flavorManifest("github")
        val shizuku = providerElements(file).filter {
            it.getAttribute("android:name") == "rikka.shizuku.ShizukuProvider"
        }
        assertEquals("github must declare exactly one ShizukuProvider in ${file.path}", 1, shizuku.size)
        val provider = shizuku.single()
        assertEquals(
            "authorities must be applicationId-suffixed .shizuku",
            "\${applicationId}.shizuku",
            provider.getAttribute("android:authorities"),
        )
        assertEquals("exported must be true (Shizuku 13.x contract)", "true", provider.getAttribute("android:exported"))
        assertEquals("multiprocess must be false", "false", provider.getAttribute("android:multiprocess"))
    }

    @Test fun playHasNoShizukuReference() {
        val text = flavorManifest("play").readText()
        assertTrue(
            "play overlay must not reference ShizukuProvider",
            !text.contains("ShizukuProvider") && !text.contains("rikka.shizuku"),
        )
    }

    @Test fun fossHasNoShizukuReference() {
        val text = flavorManifest("foss").readText()
        assertTrue(
            "foss overlay must not reference ShizukuProvider",
            !text.contains("ShizukuProvider") && !text.contains("rikka.shizuku"),
        )
    }
}
