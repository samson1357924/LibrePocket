package dev.librepocket.tool

import dev.librepocket.router.FastDispatchSurfaceArchitectureTest
import dev.librepocket.router.SafeToolSlice
import dev.librepocket.voice.VoiceTools
import dev.librepocket.web.WebExtract
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four-state contract for every built-in tool (issue #19 regression pin).
 *
 * This test does NOT wire anything: it generates the table from the actual
 * [ToolRegistry.ALL] data, pins that every row's declared/implemented/wired/
 * verified classification stays conservative, cross-checks it with the
 * registry readiness flag + the fast-lane architecture pin, and requires
 * docs/CAPABILITY_MATRIX.md's machine-readable table to agree row-for-row.
 *
 * State meanings (CAPABILITY_MATRIX §狀態詞彙):
 * - declared: a `ToolDef` exists with non-blank name/description/schema.
 * - implemented `SLICE`: schema + handlers exist in ToolRegistry/SafeToolSlice
 *   (test seam only, production composition never attaches the slice).
 *   `SCAFFOLD`: production code or a projection descriptor exists, but there
 *   is NO production dispatcher/confirmation/executor wiring — never wired.
 * - wired: false for ALL tools (production composition is records-only;
 *   `toolSlice = null`, no production caller of the internal fast lane).
 * - verified: false for ALL tools (no device E2E; JVM unit tests are not
 *   device-verified and do not promote this state).
 *
 * `executionReady` is a separate REGISTRY readiness flag, not a synonym for
 * implemented/wired/verified; only [SafeToolSlice.TOOL_NAME] is true.
 */
class BuiltinToolFourStateContractTest {

    private enum class Impl(val token: String) {
        SLICE("slice"),
        SCAFFOLD("scaffold"),
    }

    private data class Row(
        val name: String,
        val declared: Boolean,
        val impl: Impl,
        val wired: Boolean,
        val verified: Boolean,
        val executionReady: Boolean,
    )

    /** Generated from registry truth; a newly added tool defaults to the conservative scaffold state. */
    private val contract: List<Row> = ToolRegistry.ALL.map { tool ->
        Row(
            name = tool.name,
            declared = true,
            impl = if (tool.name == SafeToolSlice.TOOL_NAME) Impl.SLICE else Impl.SCAFFOLD,
            wired = false,
            verified = false,
            executionReady = tool.executionReady,
        )
    }

    /** All switches/permissions/foreground on, per flavor: projection ∩ executionReady. */
    private fun allOn(flavor: Flavor) = ProjectionContext(
        flavor = flavor,
        automationEnabled = true,
        grantedPermissions = setOf(
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_CONTACTS",
            "listener:notification",
        ),
        userSwitches = mapOf(
            "automation" to true,
            "shell" to true,
            "privilege_bridge" to true,
            "calendar" to true,
            "contacts_full" to true,
            "notification" to true,
            "screenshot" to true,
            "webfetch" to true,
            "websearch" to true,
            "webextract" to true,
            "database" to true,
            "lsp" to true,
            "clipboard" to true,
            "files" to true,
            "voice_input" to true,
            "voice_output" to true,
            "azure_tts" to true,
            "openai_tts" to true,
            "linux" to true,
            "compile" to true,
            "decompile" to true,
        ),
        isForeground = true,
    )

    @Test
    fun contractCoversEveryDeclaredToolExactlyOnce() {
        assertEquals(43, contract.size)
        assertEquals(ToolRegistry.ALL.map { it.name }, contract.map { it.name })
        assertEquals(contract.size, contract.map { it.name }.toSet().size)
        for (tool in ToolRegistry.ALL) {
            assertTrue("${tool.name}: description must be declared", tool.description.isNotBlank())
            assertTrue("${tool.name}: jsonSchema must be declared", tool.jsonSchema.isNotBlank())
            assertTrue("${tool.name}: declared flag", contract.single { it.name == tool.name }.declared)
        }
    }

    @Test
    fun registryReadyColumnMatchesToolDefAndStaysSingle() {
        for (row in contract) {
            assertEquals(
                "${row.name}: contract executionReady must match ToolDef",
                ToolRegistry.find(row.name)!!.executionReady,
                row.executionReady,
            )
            // A tool becoming registry-ready must be deliberately re-classified
            // from SCAFFOLD to SLICE — a silent flag flip cannot pass.
            if (row.executionReady) assertEquals(row.name, Impl.SLICE, row.impl)
        }
        assertEquals(listOf(SafeToolSlice.TOOL_NAME), contract.filter { it.executionReady }.map { it.name })
        assertEquals(42, contract.count { !it.executionReady })
    }

    @Test
    fun visibleToolsIsProjectionIntersectExecutionReadyAcrossThreeFlavors() {
        for (flavor in Flavor.values()) {
            val ctx = allOn(flavor)
            val projected = ToolRegistry.projectedTools(ctx).map { it.name }.toSet()
            val expected = contract.filter { it.executionReady && it.name in projected }.map { it.name }
            assertEquals("$flavor four-state projection ∩ ready", listOf(SafeToolSlice.TOOL_NAME), expected)
            assertEquals("$flavor registry visibleTools", expected, ToolRegistry.visibleTools(ctx).map { it.name })
            assertTrue(
                "$flavor default switches must offer no tool",
                ToolRegistry.visibleTools(ProjectionContext(flavor = flavor)).isEmpty(),
            )
        }
    }

    @Test
    fun fastLaneAndHighRiskToolsStayUnreadyUnwiredAndUnverified() {
        val names = listOf(
            WebSearchLocal.TOOL_NAME,   // web.search
            WebExtract.TOOL_NAME,       // web.extract
            "shell.exec",
            "shell.elevated",
            LspTools.DIAGNOSTICS_NAME,
            VoiceTools.OPENAI_SPEAK_NAME,
        )
        val visible = ToolRegistry.visibleTools(allOn(Flavor.GITHUB)).map { it.name }
        for (name in names) {
            val row = contract.single { it.name == name }
            assertFalse("$name registry ready", row.executionReady)
            assertFalse("$name wired", row.wired)
            assertFalse("$name device-verified", row.verified)
            assertFalse("$name leaked to model-visible list: $visible", name in visible)
        }
    }

    @Test
    fun noToolClaimsWiredOrDeviceVerifiedAndFastLaneHasNoProductionCaller() {
        assertTrue(contract.all { !it.wired && !it.verified })
        // Reuse the existing architecture pin instead of duplicating its scan:
        // if any production source gains a fast-lane caller, the wired=false
        // column of this contract is no longer trustworthy.
        FastDispatchSurfaceArchitectureTest().fastLaneExecutionSurfaceIsUnreachableFromProductionSources()
    }

    @Test
    fun capabilityMatrixFourStateTableMatchesContractRowForRow() {
        val text = readCapabilityMatrix()
        assertTrue(
            "matrix must state production/device work is still Proposed, not done",
            text.contains("production 接線與 device E2E 仍為 Proposed／未做"),
        )
        val begin = "<!-- FOUR_STATE_TABLE_BEGIN -->"
        val end = "<!-- FOUR_STATE_TABLE_END -->"
        val lines = text.lineSequence().map { it.trim() }.toList()
        assertEquals("should have one $begin in CAPABILITY_MATRIX.md", 1, lines.count { it == begin })
        assertEquals("should have one $end in CAPABILITY_MATRIX.md", 1, lines.count { it == end })
        val from = lines.indexOf(begin)
        val to = lines.indexOf(end)
        assertTrue("table must include header and separator", to >= from + 3)
        assertEquals("| 工具 | Declared | Implemented | Wired | Verified | executionReady |", lines[from + 1])
        assertEquals("|---|---|---|---|---|---|", lines[from + 2])
        // Check every line in the table's data section; a malformed or
        // unquoted tool row must fail rather than being silently skipped.
        val parsed = lines.subList(from + 3, to)
            .map { line ->
                val cells = line.split("|").map { it.trim() }
                assertEquals("unexpected table shape: $line", 8, cells.size)
                assertTrue(
                    "expected leading and trailing table separators: $line",
                    cells.first().isEmpty() && cells.last().isEmpty(),
                )
                // cells: ["", "`name`", declared, implemented, wired, verified, ready, ""]
                RenderedRow(
                    name = cells[1], // the expected rows require the same backtick-quoted names
                    declared = cells[2],
                    impl = cells[3],
                    wired = cells[4],
                    verified = cells[5],
                    executionReady = cells[6],
                )
            }
            .toList()
        val expected = contract.map {
            RenderedRow(
                name = "`${it.name}`",
                declared = if (it.declared) "是" else "否",
                impl = it.impl.token,
                wired = if (it.wired) "是" else "否",
                verified = if (it.verified) "是" else "否",
                executionReady = it.executionReady.toString(),
            )
        }
        assertEquals("matrix table drifted from ToolRegistry/JVM contract", expected, parsed)
    }

    private data class RenderedRow(
        val name: String,
        val declared: String,
        val impl: String,
        val wired: String,
        val verified: String,
        val executionReady: String,
    )

    private fun readCapabilityMatrix(): String {
        val file = listOf(
            "docs/CAPABILITY_MATRIX.md",
            "../docs/CAPABILITY_MATRIX.md",
            "../../docs/CAPABILITY_MATRIX.md",
        ).map { File(it) }.firstOrNull { it.isFile }
            ?: throw AssertionError("CAPABILITY_MATRIX.md not found from ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }
}
