package dev.librepocket.privilege.github

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Shizuku manifest 面釘死測試（僅 github 風味宣告 ShizukuProvider）：
 * - github overlay 必須在 `<manifest>/<application>` 下宣告唯一
 *   `rikka.shizuku.ShizukuProvider`，且
 *   `authorities="${applicationId}.shizuku"`（後綴 `.shizuku`，自尋 binder 合約）＋
 *   `enabled=true` ＋
 *   `exported=true`（Manager 回傳 binder 必需，Shizuku 13.x 官方合約）＋
 *   `multiprocess=false` ＋
 *   `permission="android.permission.INTERACT_ACROSS_USERS_FULL"`（防止第三方 App
 *   存取 sendBinder/getBinder，同時允許 shizuku_server 與本 App UID 存取），
 *   且無 `<path-permission>` / `<grant-uri-permission>` 子節點與
 *   `grantUriPermissions="true"` / `readPermission` / `writePermission` 覆寫；
 * - 若存在 `githubDebug` / `githubRelease` 等 `github*` 變體 overlay，不得移除
 *   `<application>` 或弱化／覆寫／重複 `ShizukuProvider`；
 * - 所有非 `github*` / `test*` / `androidTest*` 的 `app/src/<sourceSet>/AndroidManifest.xml`
 *   （`main`、`play`、`foss`、`debug`、`release`、`playDebug`、`playRelease`、
 *   `fossDebug`、`fossRelease` 等）零匹配 `ShizukuProvider` / `rikka.shizuku` /
 *   `moe.shizuku`（文本級 fail-closed：含註解亦紅，刻意過近似，見 docs_claim_check
 *   MCP 面同政策）。
 *
 * 全靜態（只讀源集 manifest XML，namespace-aware 解析），零真機、零 binder、零 su。
 * 路徑解析：Gradle 單測工作目錄為模組目錄（`app/`）時走 `src/<sourceSet>/…`；
 * IDE/倉庫根工作目錄時走 `app/src/<sourceSet>/…`；向上回溯找首個命中。
 */
class ShizukuManifestPinTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
        const val SHIZUKU_PROVIDER_CLASS = "rikka.shizuku.ShizukuProvider"
        const val SHIZUKU_AUTHORITY = "\${applicationId}.shizuku"
        const val SHIZUKU_PERMISSION = "android.permission.INTERACT_ACROSS_USERS_FULL"
    }

    private fun appSrcDir(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        var dir = File(cwd)
        while (true) {
            val direct = File(dir, "src/main/AndroidManifest.xml")
            if (direct.isFile) return File(dir, "src")
            val fromRoot = File(dir, "app/src/main/AndroidManifest.xml")
            if (fromRoot.isFile) return File(dir, "app/src")
            dir = dir.parentFile ?: break
        }
        throw AssertionError(
            "cannot locate app/src from $cwd",
        )
    }

    private fun flavorManifest(flavor: String): File {
        val file = File(appSrcDir(), "$flavor/AndroidManifest.xml")
        if (!file.isFile) {
            throw AssertionError("missing required manifest: ${file.path}")
        }
        return file
    }

    private fun parseManifest(file: File): Document {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = dbf.newDocumentBuilder().parse(file)
        val root = doc.documentElement
        assertEquals(
            "manifest root must be <manifest> in ${file.path}",
            "manifest",
            root.localName ?: root.tagName,
        )
        assertNull(
            "<manifest> must not carry an XML namespace URI in ${file.path}",
            root.namespaceURI,
        )
        return doc
    }

    private fun providerElements(doc: Document): List<Element> {
        val providers = doc.getElementsByTagNameNS("*", "provider")
        return (0 until providers.length).map { providers.item(it) as Element }
    }

    private fun assertProviderParentIsApplication(file: File, provider: Element) {
        assertEquals(
            "<provider> tag name must be provider in ${file.path}",
            "provider",
            provider.localName ?: provider.tagName,
        )
        assertNull(
            "<provider> must not carry an XML namespace URI in ${file.path}",
            provider.namespaceURI,
        )
        val parent = provider.parentNode as? Element
            ?: throw AssertionError("<provider> in ${file.path} must have an Element parent")
        assertEquals(
            "ShizukuProvider parent must be <application> in ${file.path}",
            "application",
            parent.localName ?: parent.tagName,
        )
        assertNull(
            "<application> must not carry an XML namespace URI in ${file.path}",
            parent.namespaceURI,
        )
        val grandparent = parent.parentNode as? Element
        assertEquals(
            "<application> parent must be <manifest> in ${file.path}",
            "manifest",
            grandparent?.localName ?: grandparent?.tagName,
        )
    }

    private fun isShizukuProviderElement(provider: Element): Boolean {
        val name = provider.getAttributeNS(ANDROID_NS, "name").trim()
        val authorities = provider.getAttributeNS(ANDROID_NS, "authorities")
            .split(';')
            .map { it.trim() }
        return name.contains("ShizukuProvider", ignoreCase = true) ||
            name.contains("rikka.shizuku", ignoreCase = true) ||
            name.contains("moe.shizuku", ignoreCase = true) ||
            authorities.any { it.endsWith(".shizuku", ignoreCase = true) }
    }

    private fun assertFullShizukuContract(file: File, provider: Element) {
        assertProviderParentIsApplication(file, provider)
        assertEquals(
            "provider name must be $SHIZUKU_PROVIDER_CLASS in ${file.path}",
            SHIZUKU_PROVIDER_CLASS,
            provider.getAttributeNS(ANDROID_NS, "name"),
        )
        assertEquals(
            "authorities must be applicationId-suffixed .shizuku in ${file.path}",
            SHIZUKU_AUTHORITY,
            provider.getAttributeNS(ANDROID_NS, "authorities"),
        )
        assertEquals(
            "enabled must be true in ${file.path}",
            "true",
            provider.getAttributeNS(ANDROID_NS, "enabled"),
        )
        assertEquals(
            "exported must be true (Shizuku 13.x contract) in ${file.path}",
            "true",
            provider.getAttributeNS(ANDROID_NS, "exported"),
        )
        assertEquals(
            "multiprocess must be false in ${file.path}",
            "false",
            provider.getAttributeNS(ANDROID_NS, "multiprocess"),
        )
        assertEquals(
            "permission must be $SHIZUKU_PERMISSION in ${file.path}",
            SHIZUKU_PERMISSION,
            provider.getAttributeNS(ANDROID_NS, "permission"),
        )
        assertEquals(
            "ShizukuProvider must not declare <path-permission> children in ${file.path}",
            0,
            provider.getElementsByTagNameNS("*", "path-permission").length,
        )
        assertEquals(
            "ShizukuProvider must not declare <grant-uri-permission> children in ${file.path}",
            0,
            provider.getElementsByTagNameNS("*", "grant-uri-permission").length,
        )
        assertTrue(
            "ShizukuProvider must not enable android:grantUriPermissions in ${file.path}",
            provider.getAttributeNS(ANDROID_NS, "grantUriPermissions").let { it.isEmpty() || it == "false" },
        )
        assertFalse(
            "ShizukuProvider must not override android:readPermission in ${file.path}",
            provider.hasAttributeNS(ANDROID_NS, "readPermission"),
        )
        assertFalse(
            "ShizukuProvider must not override android:writePermission in ${file.path}",
            provider.hasAttributeNS(ANDROID_NS, "writePermission"),
        )
        assertFalse(
            "ShizukuProvider must not use tools:node='remove' in ${file.path}",
            provider.getAttributeNS(TOOLS_NS, "node").equals("remove", ignoreCase = true),
        )
        assertFalse(
            "ShizukuProvider must not strip attributes with tools:remove in ${file.path}",
            provider.hasAttributeNS(TOOLS_NS, "remove"),
        )
    }

    private fun assertGithubOverlayDoesNotWeakenShizuku(file: File) {
        val doc = parseManifest(file)
        val apps = doc.getElementsByTagNameNS("*", "application")
        for (i in 0 until apps.length) {
            val app = apps.item(i) as Element
            val nodeAction = app.getAttributeNS(TOOLS_NS, "node").trim()
            assertFalse(
                "github variant overlay (${file.path}) must not use tools:node='remove' on <application>",
                nodeAction.equals("remove", ignoreCase = true),
            )
            if (nodeAction.equals("replace", ignoreCase = true)) {
                val replacedProviders = providerElements(doc).filter(::isShizukuProviderElement)
                assertEquals(
                    "github variant overlay (${file.path}) replacing <application> must redeclare exactly one compliant ShizukuProvider",
                    1,
                    replacedProviders.size,
                )
            }
        }
        val shizukuProviders = providerElements(doc).filter(::isShizukuProviderElement)
        if (shizukuProviders.isEmpty()) return
        assertEquals(
            "github variant overlay (${file.path}) must not duplicate ShizukuProvider",
            1,
            shizukuProviders.size,
        )
        val provider = shizukuProviders.single()
        assertProviderParentIsApplication(file, provider)
        val nodeAction = provider.getAttributeNS(TOOLS_NS, "node").trim()
        assertFalse(
            "github variant overlay (${file.path}) must not remove ShizukuProvider",
            nodeAction.equals("remove", ignoreCase = true),
        )
        if (nodeAction.equals("replace", ignoreCase = true)) {
            assertFullShizukuContract(file, provider)
            return
        }
        val toolsRemove = provider.getAttributeNS(TOOLS_NS, "remove")
        for (protectedAttr in listOf(
            "permission",
            "readPermission",
            "writePermission",
            "authorities",
            "enabled",
            "exported",
            "multiprocess",
        )) {
            assertFalse(
                "github variant overlay (${file.path}) must not strip $protectedAttr via tools:remove",
                toolsRemove.contains(protectedAttr),
            )
        }
        if (provider.hasAttributeNS(ANDROID_NS, "name")) {
            assertEquals(SHIZUKU_PROVIDER_CLASS, provider.getAttributeNS(ANDROID_NS, "name"))
        }
        if (provider.hasAttributeNS(ANDROID_NS, "authorities")) {
            assertEquals(SHIZUKU_AUTHORITY, provider.getAttributeNS(ANDROID_NS, "authorities"))
        }
        if (provider.hasAttributeNS(ANDROID_NS, "enabled")) {
            assertEquals("true", provider.getAttributeNS(ANDROID_NS, "enabled"))
        }
        if (provider.hasAttributeNS(ANDROID_NS, "exported")) {
            assertEquals("true", provider.getAttributeNS(ANDROID_NS, "exported"))
        }
        if (provider.hasAttributeNS(ANDROID_NS, "multiprocess")) {
            assertEquals("false", provider.getAttributeNS(ANDROID_NS, "multiprocess"))
        }
        if (provider.hasAttributeNS(ANDROID_NS, "permission")) {
            assertEquals(SHIZUKU_PERMISSION, provider.getAttributeNS(ANDROID_NS, "permission"))
        }
        assertFalse(
            "github variant overlay (${file.path}) must not override android:readPermission",
            provider.hasAttributeNS(ANDROID_NS, "readPermission"),
        )
        assertFalse(
            "github variant overlay (${file.path}) must not override android:writePermission",
            provider.hasAttributeNS(ANDROID_NS, "writePermission"),
        )
        assertTrue(
            "github variant overlay (${file.path}) must not enable android:grantUriPermissions",
            provider.getAttributeNS(ANDROID_NS, "grantUriPermissions").let { it.isEmpty() || it == "false" },
        )
        assertEquals(
            "github variant overlay (${file.path}) must not declare <path-permission>",
            0,
            provider.getElementsByTagNameNS("*", "path-permission").length,
        )
        assertEquals(
            "github variant overlay (${file.path}) must not declare <grant-uri-permission>",
            0,
            provider.getElementsByTagNameNS("*", "grant-uri-permission").length,
        )
    }

    private fun assertNoShizukuReference(label: String, file: File) {
        val text = file.readText()
        assertTrue(
            "$label (${file.path}) must not reference ShizukuProvider, rikka.shizuku, or moe.shizuku",
            !text.contains("ShizukuProvider") &&
                !text.contains("rikka.shizuku") &&
                !text.contains("moe.shizuku"),
        )
    }

    @Test fun githubDeclaresShizukuProviderWithContractAttrs() {
        val file = flavorManifest("github")
        val doc = parseManifest(file)
        val shizuku = providerElements(doc).filter(::isShizukuProviderElement)
        assertEquals("github must declare exactly one ShizukuProvider in ${file.path}", 1, shizuku.size)
        assertFullShizukuContract(file, shizuku.single())
    }

    @Test fun githubVariantOverlaysIfPresentDoNotWeakenShizukuProvider() {
        val srcDir = appSrcDir()
        val dirs = srcDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("github") && it.name != "github" }
            ?.sortedBy { it.name }
            ?: throw AssertionError("cannot list source sets under ${srcDir.path}")
        for (dir in dirs) {
            val manifest = File(dir, "AndroidManifest.xml")
            if (!manifest.isFile) continue
            assertGithubOverlayDoesNotWeakenShizuku(manifest)
        }
    }

    @Test fun mainHasNoShizukuReference() {
        assertNoShizukuReference("main manifest", flavorManifest("main"))
    }

    @Test fun playHasNoShizukuReference() {
        assertNoShizukuReference("play overlay", flavorManifest("play"))
    }

    @Test fun fossHasNoShizukuReference() {
        assertNoShizukuReference("foss overlay", flavorManifest("foss"))
    }

    @Test fun buildTypeOverlaysIfPresentHaveNoShizukuReference() {
        val srcDir = appSrcDir()
        val dirs = srcDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: throw AssertionError("cannot list source sets under ${srcDir.path}")

        val scanned = mutableSetOf<String>()
        for (dir in dirs) {
            val name = dir.name
            if (name.startsWith("github") || name.startsWith("test") || name.startsWith("androidTest")) {
                continue
            }
            val manifest = File(dir, "AndroidManifest.xml")
            if (!manifest.isFile) continue
            assertNoShizukuReference("$name manifest", manifest)
            scanned += name
        }
        assertTrue(
            "expected main, play, foss manifests in scan, got $scanned",
            scanned.containsAll(listOf("main", "play", "foss")),
        )
    }
}
