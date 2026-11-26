package dev.librepocket.agent.assistant

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * #137 C1/C2 的 source-set manifest 契約（純 JVM；不跑 Gradle、不碰 merged artifact）。
 *
 * 既有 Robolectric 測試已從 merged manifest 驗證三服務解析、`:voice` process、
 * RECORD_AUDIO 缺席與 VoiceHandoffProvider lockdown；本檔補其未覆蓋的 source-set/
 * flavor 面：三 flavor 共用 main manifest 的三服務＋ voice_interaction.xml 雙綁定、
 * flavor overlay 不得移除／覆寫它們、RECORD_AUDIO node="remove" 仍為唯一來源、
 * play 六項 policy remove 與零疊加 service/permission。
 *
 * VoiceHandoffProvider 的 identity/TTL 邊界已由 VoiceHandoffContractTest/
 * VoiceHandoffProviderTest 覆蓋，此處只 pin「flavor 不可覆寫其宣告」。
 */
class AssistantManifestContractTest {

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NS = "http://schemas.android.com/tools"
        val FLAVORS = listOf("play", "foss", "github")

        /** main manifest 內的相對 service 名 → 系統 binding intent action。 */
        val ASSISTANT_SERVICES = mapOf(
            ".assistant.LibreVoiceInteractionService" to
                "android.service.voice.VoiceInteractionService",
            ".assistant.LibreVoiceInteractionSessionService" to
                "android.service.voice.VoiceInteractionSessionService",
            ".assistant.LibreRecognitionService" to "android.speech.RecognitionService",
        )

        /** flavor overlay 唯一允許的 component 集合（精確比對，排除助理三服務／handoff provider）。 */
        val ALLOWED_OVERLAY_SERVICES = mapOf(
            "play" to emptySet<String>(),
            "foss" to setOf(".foss.FossAccessibilityService"),
            "github" to setOf(".github.GithubAccessibilityService"),
        )
        val ALLOWED_OVERLAY_PROVIDERS = mapOf(
            "play" to emptySet<String>(),
            "foss" to emptySet<String>(),
            "github" to setOf("rikka.shizuku.ShizukuProvider"),
        )

        /** 這些 tools:node 值（小寫比對）會改寫／移除 merged component，flavor overlay 不得使用。 */
        val DESTRUCTIVE_NODE_ACTIONS = setOf("remove", "removeall", "replace", "replaceall")

        val SAFE_BASE_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "com.android.alarm.permission.SET_ALARM",
        )

        /** play overlay 只允許這些 policy 禁止項的 remove 錨點，不得新增任何權限。 */
        val PLAY_POLICY_REMOVALS = setOf(
            "android.permission.SEND_SMS",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_VPN_SERVICE",
        )
    }

    /** 回溯找 app/src（Gradle 單測 cwd=app/ 或 repo root 皆可）；與既有 manifest pin 同策略。 */
    private fun appSrcDir(): File {
        System.getProperty("test.src.dir")?.let { return File(it) }
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return File(dir, "src")
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return File(dir, "app/src")
            dir = dir.parentFile ?: break
        }
        error("cannot locate app/src from ${System.getProperty("user.dir")}")
    }

    private fun manifest(sourceSet: String): File =
        File(appSrcDir(), "$sourceSet/AndroidManifest.xml").also {
            assertTrue("missing ${sourceSet} manifest: ${it.path}", it.isFile)
        }

    private fun parseAny(file: File): Element {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return dbf.newDocumentBuilder().parse(file).documentElement
    }

    private fun parse(file: File): Element = parseAny(file).also {
        assertEquals("manifest", it.localName ?: it.tagName)
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val list = parent.getElementsByTagNameNS("*", tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.android(name: String): String? =
        if (hasAttributeNS(ANDROID_NS, name)) getAttributeNS(ANDROID_NS, name) else null

    private fun Element.tools(name: String): String? =
        if (hasAttributeNS(TOOLS_NS, name)) getAttributeNS(TOOLS_NS, name) else null

    /**
     * voice_interaction.xml 以全限定名綁定 service，main manifest 則用相對名
     * （`.assistant.X`）；DOM 回原始值，故比對前統一收斂為相對名。
     */
    private fun assistantComponent(raw: String?): String? {
        val value = raw?.trim() ?: return null
        return if (value.startsWith("dev.librepocket.agent.")) {
            "." + value.removePrefix("dev.librepocket.agent.")
        } else {
            value
        }
    }

    /** flavor overlay 的 component 宣告不得用 tools:node 移除／替換 merged 結果。 */
    private fun assertNoDestructiveNode(overlay: Element, tag: String, flavor: String) {
        for (element in elements(overlay, tag)) {
            val node = element.tools("node")?.trim().orEmpty()
            assertTrue(
                "$flavor overlay <$tag tools:node=\"$node\"> 不得改寫／移除 merged component",
                node.lowercase() !in DESTRUCTIVE_NODE_ACTIONS,
            )
        }
    }

    /** 三服務宣告＋voice_interaction.xml 雙綁定；main manifest 為三 flavor 唯一來源。 */
    @Test
    fun mainManifest_declaresAssistantServiceTriadAndVoiceInteractionBinding() {
        val main = parse(manifest("main"))
        val assistantServices = elements(main, "service")
            .mapNotNull { service ->
                assistantComponent(service.android("name"))?.let { it to service }
            }
            .filter { (name, _) -> name.startsWith(".assistant.") }
        val declared = assistantServices.map { (name, _) -> name }
        assertEquals(
            "main manifest 須恰宣告三個助理服務，實際=$declared",
            ASSISTANT_SERVICES.keys.toSet(),
            declared.toSet(),
        )
        assertEquals("服務不可重複宣告", ASSISTANT_SERVICES.size, declared.size)

        for ((name, action) in ASSISTANT_SERVICES) {
            val service = assistantServices.single { (component, _) -> component == name }.second
            assertEquals("$name must stay exported for system binding", "true", service.android("exported"))
            assertEquals("$name must run in :voice", ":voice", service.android("process"))
            val expectedPermission = if (name == ".assistant.LibreRecognitionService") {
                "android.permission.BIND_RECOGNITION_SERVICE"
            } else {
                "android.permission.BIND_VOICE_INTERACTION"
            }
            assertEquals("$name binding permission", expectedPermission, service.android("permission"))
            val actions = elements(service, "intent-filter").flatMap { filter ->
                elements(filter, "action").mapNotNull { it.android("name") }
            }
            assertEquals("$name intent-filter action", listOf(action), actions)
        }

        val voiceService = assistantServices
            .single { (component, _) -> component == ".assistant.LibreVoiceInteractionService" }.second
        val meta = elements(voiceService, "meta-data").single()
        assertEquals("android.voice_interaction", meta.android("name"))
        assertEquals("@xml/voice_interaction", meta.android("resource"))

        val bindingFile = File(appSrcDir(), "main/res/xml/voice_interaction.xml").also {
            assertTrue("missing voice_interaction.xml: ${it.path}", it.isFile)
        }
        val binding = parseAny(bindingFile)
        assertEquals("voice-interaction-service", binding.localName ?: binding.tagName)
        assertEquals(
            ".assistant.LibreVoiceInteractionSessionService",
            assistantComponent(binding.android("sessionService")),
        )
        assertEquals(
            ".assistant.LibreRecognitionService",
            assistantComponent(binding.android("recognitionService")),
        )
        assertEquals("true", binding.android("supportsAssist"))
    }

    /** 任一 flavor overlay 只可宣告白名單 component，不得移除／覆寫共用三服務或 handoff provider。 */
    @Test
    fun flavorOverlays_doNotOverrideAssistantDeclarations() {
        for (flavor in FLAVORS) {
            val overlay = parse(manifest(flavor))
            for (tag in listOf("application", "service", "provider")) {
                assertNoDestructiveNode(overlay, tag, flavor)
            }

            // 精確集合比對：白名單不含助理三服務、VoiceHandoffProvider 或其他意外 component。
            assertEquals(
                "$flavor overlay service 白名單",
                ALLOWED_OVERLAY_SERVICES.getValue(flavor),
                elements(overlay, "service").mapNotNull { it.android("name") }.toSet(),
            )
            assertEquals(
                "$flavor overlay provider 白名單",
                ALLOWED_OVERLAY_PROVIDERS.getValue(flavor),
                elements(overlay, "provider").mapNotNull { it.android("name") }.toSet(),
            )

            // overlay 的 uses-permission 只能是 play 的六項 policy remove 錨點；
            // RECORD_AUDIO 的 remove 錨點只在 main（另見 recordAudioRemove 測）。
            for (permission in elements(overlay, "uses-permission")) {
                val name = permission.android("name")
                assertTrue(
                    "$flavor overlay 不得新增權限：$name",
                    name != null && name in PLAY_POLICY_REMOVALS,
                )
                assertEquals(
                    "$flavor overlay 的 uses-permission 只能是 remove 錨點：$name",
                    "remove",
                    permission.tools("node")?.trim(),
                )
            }
        }
    }

    /** 這些來源集的 permission 白名單不接受出現在條件式宣告中的額外項目。 */
    @Test
    fun noSdkConditionalPermissionDeclarationsInReviewedSourceSets() {
        for (sourceSet in listOf("main") + FLAVORS) {
            val manifest = parse(manifest(sourceSet))
            for (tag in listOf("uses-permission-sdk-23", "uses-permission-sdk-m")) {
                assertTrue("$sourceSet must not declare <$tag>", elements(manifest, tag).isEmpty())
            }
        }
    }

    /** 零新增 RECORD_AUDIO：remove 錨點只在 main，flavor 不得重加（github AAR 為 load-bearing）。 */
    @Test
    fun recordAudioRemove_isSoleDeclarationAcrossFlavors() {
        val recordAudio = "android.permission.RECORD_AUDIO"
        val mainPermissions = elements(parse(manifest("main")), "uses-permission")
            .filter { it.android("name") == recordAudio }
        assertEquals("main manifest 須有且僅有 RECORD_AUDIO remove 錨點", 1, mainPermissions.size)
        assertEquals("remove", mainPermissions.single().tools("node"))

        for (flavor in FLAVORS) {
            val flavorPermissions = elements(parse(manifest(flavor)), "uses-permission")
                .filter { it.android("name") == recordAudio }
            assertTrue(
                "$flavor overlay 不得重新宣告 RECORD_AUDIO（即使 remove 也不重複）",
                flavorPermissions.isEmpty(),
            )
        }
    }

    /** play 不得引入 policy 禁止的權限／service；foss/github 亦不得疊加任何 uses-permission。 */
    @Test
    fun playOverlay_keepsOnlyPolicyRemovalsAndNoServices() {
        val mainAdditive = elements(parse(manifest("main")), "uses-permission")
            .filter { !it.tools("node").equals("remove", ignoreCase = true) }
            .mapNotNull { it.android("name") }
            .toSet()
        assertEquals("main manifest 的疊加權限集合", SAFE_BASE_PERMISSIONS, mainAdditive)

        val play = parse(manifest("play"))
        val removals = elements(play, "uses-permission")
        assertEquals("play overlay 應恰有六項 policy remove 錨點", 6, removals.size)
        assertEquals(
            PLAY_POLICY_REMOVALS,
            removals.mapNotNull { it.android("name") }.toSet(),
        )
        assertTrue(
            "play overlay 每項皆須 tools:node=\"remove\"",
            removals.all { it.tools("node").equals("remove", ignoreCase = true) },
        )
        assertTrue("play 不得宣告任何 service", elements(play, "service").isEmpty())

        for (flavor in listOf("foss", "github")) {
            assertTrue(
                "$flavor overlay 不得疊加 uses-permission",
                elements(parse(manifest(flavor)), "uses-permission").isEmpty(),
            )
        }
    }
}
