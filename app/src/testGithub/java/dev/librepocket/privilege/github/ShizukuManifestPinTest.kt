package dev.librepocket.privilege.github

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        const val SHIZUKU_AUTHORITY = "${'$'}{applicationId}.shizuku"
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
            "github variant overlay (${file.path}) must not use tools:node='remove' on ShizukuProvider",
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
            assertEquals(
                "github variant overlay (${file.path}) must keep android:name=$SHIZUKU_PROVIDER_CLASS",
                SHIZUKU_PROVIDER_CLASS,
                provider.getAttributeNS(ANDROID_NS, "name"),
            )
        }
        if (provider.hasAttributeNS(ANDROID_NS, "authorities")) {
            assertEquals(
                "github variant overlay (${file.path}) must keep android:authorities=$SHIZUKU_AUTHORITY",
                SHIZUKU_AUTHORITY,
                provider.getAttributeNS(ANDROID_NS, "authorities"),
            )
        }
        if (provider.hasAttributeNS(ANDROID_NS, "enabled")) {
            assertEquals(
                "github variant overlay (${file.path}) must keep android:enabled=true",
                "true",
                provider.getAttributeNS(ANDROID_NS, "enabled"),
            )
        }
        if (provider.hasAttributeNS(ANDROID_NS, "exported")) {
            assertEquals(
                "github variant overlay (${file.path}) must keep android:exported=true",
                "true",
                provider.getAttributeNS(ANDROID_NS, "exported"),
            )
        }
        if (provider.hasAttributeNS(ANDROID_NS, "multiprocess")) {
            assertEquals(
                "github variant overlay (${file.path}) must keep android:multiprocess=false",
                "false",
                provider.getAttributeNS(ANDROID_NS, "multiprocess"),
            )
        }
        if (provider.hasAttributeNS(ANDROID_NS, "permission")) {
            assertEquals(
                "github variant overlay (${file.path}) must keep android:permission=$SHIZUKU_PERMISSION",
                SHIZUKU_PERMISSION,
                provider.getAttributeNS(ANDROID_NS, "permission"),
            )
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
        // NOTE: the tree currently carries NO github*/ variant-overlay
        // directory, so this loop runs zero times and its assertions never
        // execute. The fixture-driven pin (githubVariantOverlayWeakeningIsRejectedByFixture)
        // is the test that actually exercises the overlay contract.
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

    @Test fun githubVariantOverlayWeakeningIsRejectedByFixture() {
        // Fixture-driven pin of the variant-overlay contract: the real
        // github*/ tree currently carries no variant overlay directory, so
        // githubVariantOverlaysIfPresentDoNotWeakenShizukuProvider loops
        // zero times and its assertions never execute. This test drives the
        // SAME checkPath with synthetic overlays written to a temp directory:
        // every weakened shape must raise AssertionError, the compliant
        // overlay must pass.
        fun overlay(providerBody: String): String =
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <application>
                    $providerBody
                </application>
            </manifest>
            """.trimIndent()

        val compliant = overlay(
            """<provider
                android:name="rikka.shizuku.ShizukuProvider"
                android:authorities="${'$'}{applicationId}.shizuku"
                android:enabled="true"
                android:exported="true"
                android:multiprocess="false"
                android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
            """.trimIndent(),
        )
        fun weakened(messagePart: String, content: String) = messagePart to content

        // Each weakened case pins BOTH the exception type and the assertion
        // message: the message must name the breakpoint the overlay tripped
        // (exported/permission/tools:node/tools:remove/grantUriPermissions/
        // duplicate), otherwise a gutted helper could stay green on an
        // unrelated AssertionError.
        val weakened = mapOf(
            "exported=false" to weakened(
                "exported",
                overlay(
                    """<provider
                        android:name="rikka.shizuku.ShizukuProvider"
                        android:authorities="${'$'}{applicationId}.shizuku"
                        android:exported="false"
                        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
                    """.trimIndent(),
                ),
            ),
            "permission weakened" to weakened(
                "permission",
                overlay(
                    """<provider
                        android:name="rikka.shizuku.ShizukuProvider"
                        android:authorities="${'$'}{applicationId}.shizuku"
                        android:exported="true"
                        android:permission="android.permission.INTERNET" />
                    """.trimIndent(),
                ),
            ),
            "tools:node=remove" to weakened(
                "node",
                overlay(
                    """<provider
                        android:name="rikka.shizuku.ShizukuProvider"
                        tools:node="remove" />
                    """.trimIndent(),
                ),
            ),
            "tools:remove=exported" to weakened(
                "remove",
                overlay(
                    """<provider
                        android:name="rikka.shizuku.ShizukuProvider"
                        tools:remove="exported" />
                    """.trimIndent(),
                ),
            ),
            "grantUriPermissions=true" to weakened(
                "grantUriPermissions",
                overlay(
                    """<provider
                        android:name="rikka.shizuku.ShizukuProvider"
                        android:authorities="${'$'}{applicationId}.shizuku"
                        android:exported="true"
                        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
                        android:grantUriPermissions="true" />
                    """.trimIndent(),
                ),
            ),
            "duplicate provider" to weakened(
                "duplicate",
                overlay(
                    """<provider
                        android:name="rikka.shizuku.ShizukuProvider"
                        android:authorities="${'$'}{applicationId}.shizuku" />
                        <provider
                        android:name="rikka.shizuku.ShizukuProvider"
                        android:authorities="${'$'}{applicationId}.shizuku" />
                    """.trimIndent(),
                ),
            ),
        )
        val tmp = Files.createTempDirectory("github-variant-overlay").toFile()
        try {
            val manifest = File(tmp, "AndroidManifest.xml")
            for ((label, fixture) in weakened) {
                val (messagePart, content) = fixture
                manifest.writeText(content)
                val thrown = assertThrows(AssertionError::class.java) {
                    assertGithubOverlayDoesNotWeakenShizuku(manifest)
                }
                assertTrue(
                    "weakened variant overlay ($label) must fail on its own " +
                        "breakpoint assertion, got message: ${thrown.message}",
                    thrown.message?.contains(messagePart) == true,
                )
            }
            // tools:node="replace", provider level: the replace branch runs
            // assertFullShizukuContract on the redeclared provider, so a
            // weakened redeclaration throws and names the weakened attr.
            val providerReplaceWeakened = overlay(
                """<provider
                    android:name="rikka.shizuku.ShizukuProvider"
                    android:authorities="${'$'}{applicationId}.shizuku"
                    android:enabled="true"
                    android:exported="false"
                    android:multiprocess="false"
                    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
                    tools:node="replace" />
                """.trimIndent(),
            )
            val providerReplaceCompliant = overlay(
                """<provider
                    android:name="rikka.shizuku.ShizukuProvider"
                    android:authorities="${'$'}{applicationId}.shizuku"
                    android:enabled="true"
                    android:exported="true"
                    android:multiprocess="false"
                    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"
                    tools:node="replace" />
                """.trimIndent(),
            )
            // tools:node="replace", application level: the helper's <application>
            // branch demands exactly one redeclared ShizukuProvider (the
            // replacedProviders count check); zero-or-more-than-one trips that
            // assertion, and a single WEAKENED redeclaration passes the count
            // check but trips the provider-level attribute checks afterwards.
            fun applicationReplace(providerBodies: List<String>): String =
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools">
                    <application tools:node="replace">
                        ${providerBodies.joinToString("\n            ")}
                    </application>
                </manifest>
                """.trimIndent()
            val shizukuProvider = """<provider
                    android:name="rikka.shizuku.ShizukuProvider"
                    android:authorities="${'$'}{applicationId}.shizuku"
                    android:enabled="true"
                    android:exported="true"
                    android:multiprocess="false"
                    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />"""
            val weakenedShizukuProvider = """<provider
                    android:name="rikka.shizuku.ShizukuProvider"
                    android:authorities="${'$'}{applicationId}.shizuku"
                    android:enabled="true"
                    android:exported="false"
                    android:multiprocess="false"
                    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />"""
            val applicationReplaceCases = mapOf(
                "application replace with two redeclared ShizukuProviders (replacedProviders count)" to Pair(
                    "redeclare exactly one",
                    applicationReplace(listOf(shizukuProvider, shizukuProvider)),
                ),
                "application replace with a weakened redeclared ShizukuProvider" to Pair(
                    "exported",
                    applicationReplace(listOf(weakenedShizukuProvider)),
                ),
            )
            for ((label, fixture) in applicationReplaceCases) {
                val (messagePart, content) = fixture
                manifest.writeText(content)
                val thrown = assertThrows(AssertionError::class.java) {
                    assertGithubOverlayDoesNotWeakenShizuku(manifest)
                }
                assertTrue(
                    "weakened variant overlay ($label) must fail on its own " +
                        "breakpoint assertion, got message: ${thrown.message}",
                    thrown.message?.contains(messagePart) == true,
                )
            }
            manifest.writeText(compliant)
            assertGithubOverlayDoesNotWeakenShizuku(manifest)
            manifest.writeText(providerReplaceCompliant)
            assertGithubOverlayDoesNotWeakenShizuku(manifest)
            manifest.writeText(applicationReplace(listOf(shizukuProvider)))
            assertGithubOverlayDoesNotWeakenShizuku(manifest)
            manifest.writeText(providerReplaceWeakened)
            val providerReplaceThrown = assertThrows(AssertionError::class.java) {
                assertGithubOverlayDoesNotWeakenShizuku(manifest)
            }
            assertTrue(
                "provider-level tools:node='replace' with a weakened redeclaration " +
                    "must fail on its own breakpoint assertion, got message: ${providerReplaceThrown.message}",
                providerReplaceThrown.message?.contains("exported") == true,
            )
        } finally {
            tmp.deleteRecursively()
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
