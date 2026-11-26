package dev.librepocket

import dev.librepocket.automation.A11yAction
import dev.librepocket.automation.AutomationCore
import dev.librepocket.jev.JevWire
import dev.librepocket.mcp.McpWire
import dev.librepocket.router.FastRouter
import dev.librepocket.skill.SkillHashes
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #175: hex/escape 輸出必須與 default locale 無關。
 *
 *  本檔案的**行為測試**只陳述輸出不變數：把 JVM 預設 locale 換成
 *  `fa`/`ar`/`tr`（非 ASCII 數字/字母大小寫語系）後，代表性 hash／wire
 *  escape／a11y JSON helper 的輸出仍全 ASCII，且與 `Locale.ROOT` 一致。
 *  行為測試**不宣稱**覆蓋全部 production callsite——16 個 hex/escape
 *  format callsite 是否綁 `Locale.ROOT` 由 [allProductionHexFormatCallsitesArePinnedToRootLocale]
 *  的 source-scan 結構性測試負責（JVM 的 `Formatter` 不會本地化 `%x`，
 *  移除 pin 在 JVM 行為測試上不會轉 RED）。
 *
 *  純 JVM（無 Android／Robolectric／網路）。
 */
class LocaleRootHexRegressionTest {

    companion object {
        // 用組字而非直接寫 "\\u0001"：避免 `\u` 在詞法階段被當成 Unicode escape 展開。
        private val U = '\\'.toString() + "u"

        /** 引號、反斜線、C0：四個 escaper 都該轉成 ASCII 轉義。 */
        private val C0_RAW = "\"" + "\\" + Char(1)

        /**
         * 孤立 high surrogate：只有 McpWire.esc / FastRouter.esc 會轉成
         * `\\uXXXX`；JevWire / provider 的 else 分支原样 append，
         * 故不把它放進四個 escaper 共用的輸入。
         */
        private val LONE_HIGH_SURROGATE = Char(0xD800)

        /**
         * production 的 16 個 hex/escape format callsite（相對 repo root）。
         * Pair 的第二個值是預期呼叫數，用來抓出新増／遺漏的 callsite。
         */
        private val HEX_FORMAT_FILES: List<Pair<String, Int>> = listOf(
            "app/src/main/java/dev/librepocket/automation/AutomationCore.kt" to 3,
            "app/src/main/java/dev/librepocket/backup/BackupBundle.kt" to 1,
            "app/src/main/java/dev/librepocket/files/ScopedFileStore.kt" to 1,
            "app/src/main/java/dev/librepocket/jev/JevCache.kt" to 1,
            "app/src/main/java/dev/librepocket/jev/JevClient.kt" to 1,
            "app/src/main/java/dev/librepocket/linux/LinuxEnv.kt" to 1,
            "app/src/main/java/dev/librepocket/shell/PrivilegeAuditLog.kt" to 1,
            "app/src/main/java/dev/librepocket/skill/SkillHashes.kt" to 1,
            "app/src/main/java/dev/librepocket/mcp/McpWire.kt" to 3,
            "app/src/main/java/dev/librepocket/provider/ProviderTransport.kt" to 1,
            "app/src/main/java/dev/librepocket/router/FastRouter.kt" to 2,
        )

        private val HEX_FORMAT_SPEC = Regex("""%0?\d*[xX]""")
    }

    private lateinit var savedLocale: Locale
    private lateinit var savedDisplayLocale: Locale
    private lateinit var savedFormatLocale: Locale

    @Before fun saveDefaultLocale() {
        savedLocale = Locale.getDefault()
        savedDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        savedFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
    }

    /** 每個測試後還原，避免 default locale 外洩成跨測試 flake。 */
    @After fun restoreDefaultLocale() {
        // Setting the general default also overwrites both category defaults.
        Locale.setDefault(savedLocale)
        Locale.setDefault(Locale.Category.DISPLAY, savedDisplayLocale)
        Locale.setDefault(Locale.Category.FORMAT, savedFormatLocale)
    }

    private fun assertAscii(label: String, value: String) {
        assertTrue(
            "[$label] hex/escape output must stay ASCII, got: $value",
            value.all { it.code < 0x80 },
        )
    }

    /**
     * [FastRouter.esc] 是 companion 的 private helper，且工具模板目前尚未
     * executionReady（decide() 在建立 ToolCall 前就 DENIED）。以 Java reflection
     * 覆蓋兩種可能的編譯落點（外層 class 的 private static 或 companion 的
     * private instance），不改動 production 可見性。
     */
    @Suppress("DEPRECATION")
    private fun fastRouterEsc(raw: String): String {
        val method = listOf(FastRouter::class.java, FastRouter.Companion::class.java)
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                m.name == "esc" && m.parameterTypes.contentEquals(arrayOf<Class<*>>(String::class.java))
            } ?: throw AssertionError("FastRouter esc helper not found")
        method.isAccessible = true
        val receiver = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else FastRouter.Companion
        @Suppress("UNCHECKED_CAST")
        return method.invoke(receiver, raw) as String
    }

    private fun samples(): List<Pair<String, () -> String>> = listOf(
        "SkillHashes.sha256Hex" to { SkillHashes.sha256Hex("abc") },
        "McpWire.esc" to { McpWire.esc(C0_RAW) },
        "JevWire.jsonEscape" to { JevWire.jsonEscape(C0_RAW) },
        "provider.jsonEscape" to { dev.librepocket.provider.jsonEscape(C0_RAW) },
        "AutomationCore.inputJson(quote)" to { AutomationCore.inputJson("n${Char(1)}", "x") },
        "AutomationCore.fingerprintFor(hex)" to {
            AutomationCore.fingerprintFor(A11yAction.Tap("n"), "goal")
        },
        "FastRouter.esc(args)" to { fastRouterEsc(C0_RAW) },
    )

    @Test fun hexAndEscapesStayAsciiAndRootIdenticalInNonAsciiLocales() {
        val cases = samples()
        Locale.setDefault(Locale.ROOT)
        val rootOutputs = cases.map { it.second() }
        rootOutputs.forEachIndexed { i, out -> assertAscii(cases[i].first, out) }

        for (tag in listOf("fa", "ar", "tr")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            cases.forEachIndexed { i, (label, run) ->
                val out = run()
                assertAscii("[$tag] $label", out)
                assertEquals("[$tag] $label must match Locale.ROOT output", rootOutputs[i], out)
            }
        }
    }

    @Test fun c0ControlAlwaysBecomesAsciiUnicodeEscapeUnderFa() {
        Locale.setDefault(Locale.forLanguageTag("fa"))
        val escapes = listOf(
            "McpWire.esc" to McpWire.esc(C0_RAW),
            "JevWire.jsonEscape" to JevWire.jsonEscape(C0_RAW),
            "provider.jsonEscape" to dev.librepocket.provider.jsonEscape(C0_RAW),
            "FastRouter.esc" to fastRouterEsc(C0_RAW),
        )
        escapes.forEach { (label, out) ->
            assertAscii("[$label]", out)
            assertTrue("[$label] C0 must be escaped as ASCII hex: $out", out.contains(U + "0001"))
        }
    }

    /** 只有支援孤立 surrogate 的兩個 escaper 會把它轉成 ASCII 轉義。 */
    @Test fun loneSurrogateEscapesOnlyInEscapersThatSupportIt() {
        Locale.setDefault(Locale.forLanguageTag("fa"))
        val raw = C0_RAW + LONE_HIGH_SURROGATE
        listOf("McpWire.esc" to McpWire.esc(raw), "FastRouter.esc" to fastRouterEsc(raw))
            .forEach { (label, out) ->
                assertAscii("[$label]", out)
                assertTrue("[$label] lone surrogate must be escaped: $out", out.contains(U + "d800"))
            }
    }

    /**
     * 唯一能在 JVM 上讓「移除 Locale.ROOT」變 RED 的結構性測試：直接用 regex
     * 定位 production 的 `%02x` / `\\u%04x` format callsite，要求每行都有
     * `Locale.ROOT`（含 `java.util.Locale.ROOT`）。行號可漂移；呼叫數漂移
     * （新增或遺漏 callsite）也會失敗。
     */
    @Test fun allProductionHexFormatCallsitesArePinnedToRootLocale() {
        val root = locateRepoRoot()
        var total = 0
        HEX_FORMAT_FILES.forEach { (relativePath, expectedCount) ->
            val source = File(root, relativePath)
            if (!source.isFile) throw AssertionError("Missing production source: ${source.path}")
            val callsites = source.readLines().mapIndexed { index, line -> "${relativePath}:${index + 1}" to line }
                .filter { (_, line) -> HEX_FORMAT_SPEC.containsMatchIn(line) && "format(" in line }
            assertEquals("$relativePath format callsite count", expectedCount, callsites.size)
            callsites.forEach { (where, line) ->
                assertTrue("$where must pin Locale.ROOT: $line", line.contains("Locale.ROOT"))
            }
            total += callsites.size
        }
        assertEquals("total production hex/escape format callsites", 16, total)
    }

    /**
     * 從 Gradle 的 test working dir（可能是 repo root 或 `:app` 模組目錄）
     * 向上找含 `app/build.gradle.kts` 的根；找不到就明確失敗，不靜默跳過。
     */
    private fun locateRepoRoot(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        var cursor: File? = start
        while (cursor != null) {
            if (File(cursor, "app/build.gradle.kts").isFile) return cursor
            cursor = cursor.parentFile
        }
        throw AssertionError(
            "Cannot locate repo root with app/build.gradle.kts from user.dir=${start.path}",
        )
    }

    /**
     * Stage 3 cleanup contract: deliberately leak the default locale inside a
     * test body, then invoke the @After hook by reflection and require it to
     * restore the baseline. If the hook is removed or stops restoring, this
     * fails instead of silently polluting later tests.
     */
    @Test fun afterHookRestoresDefaultLocale() {
        val hook = javaClass.declaredMethods.single { it.isAnnotationPresent(After::class.java) }
        val general = Locale.forLanguageTag("tr")
        val display = Locale.forLanguageTag("ar")
        val format = Locale.forLanguageTag("fa")
        Locale.setDefault(general)
        Locale.setDefault(Locale.Category.DISPLAY, display)
        Locale.setDefault(Locale.Category.FORMAT, format)
        // Keep this test's own @Before snapshot intact for the real teardown.
        val probe = LocaleRootHexRegressionTest()
        probe.saveDefaultLocale()
        Locale.setDefault(Locale.ROOT)
        hook.invoke(probe)
        assertEquals(general, Locale.getDefault())
        assertEquals(display, Locale.getDefault(Locale.Category.DISPLAY))
        assertEquals(format, Locale.getDefault(Locale.Category.FORMAT))
    }
}
