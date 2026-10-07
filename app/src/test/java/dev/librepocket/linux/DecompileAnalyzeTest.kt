package dev.librepocket.linux

import dev.librepocket.linux.DecompileAnalyze.Stage
import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.RawOutput
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 `decompile.analyze` + `decompile.repack` 測試：
 * strings→smali→resources→java 漸進（不可跳階）、工具版本釘選
 * （apktool 3.0.1 / jadx 1.5.6）、階段-工具對應、APK 上限、
 * repack PRIVILEGED 每次確認。
 *
 * 純 JVM。
 */
class DecompileAnalyzeTest {

    @Test fun versions_pinned() {
        assertEquals("3.0.1", DecompileAnalyze.APKTOOL_VERSION)
        assertEquals("1.5.6", DecompileAnalyze.JADX_VERSION)
    }

    @Test fun progressive_noSkipping() {
        assertTrue(DecompileAnalyze.nextAllowed(emptySet(), Stage.STRINGS))
        assertTrue(!DecompileAnalyze.nextAllowed(emptySet(), Stage.SMALI))
        assertTrue(DecompileAnalyze.nextAllowed(setOf(Stage.STRINGS), Stage.SMALI))
        assertTrue(!DecompileAnalyze.nextAllowed(setOf(Stage.STRINGS), Stage.RESOURCES))
        assertTrue(
            DecompileAnalyze.nextAllowed(
                setOf(Stage.STRINGS, Stage.SMALI, Stage.RESOURCES), Stage.JAVA,
            ),
        )
        // 已完成階可重跑（全完成後 JAVA 重跑亦放行）。
        assertTrue(DecompileAnalyze.nextAllowed(setOf(Stage.STRINGS), Stage.STRINGS))
        assertTrue(DecompileAnalyze.nextAllowed(setOf(*Stage.entries.toTypedArray()), Stage.JAVA))
    }

    @Test fun stageToolMapping() {
        assertEquals(null, DecompileAnalyze.stageVeto(emptySet(), Stage.STRINGS, "strings"))
        assertEquals(null, DecompileAnalyze.stageVeto(setOf(Stage.STRINGS), Stage.SMALI, "baksmali"))
        assertEquals(null, DecompileAnalyze.stageVeto(setOf(Stage.STRINGS), Stage.SMALI, "apktool"))
        assertEquals(
            null,
            DecompileAnalyze.stageVeto(setOf(Stage.STRINGS, Stage.SMALI), Stage.RESOURCES, "aapt2"),
        )
        assertEquals(
            null,
            DecompileAnalyze.stageVeto(
                setOf(Stage.STRINGS, Stage.SMALI, Stage.RESOURCES), Stage.JAVA, "jadx",
            ),
        )
        // 工具-階段錯配。
        assertEquals(
            "TOOL_STAGE_MISMATCH:jadx",
            DecompileAnalyze.stageVeto(emptySet(), Stage.STRINGS, "jadx"),
        )
        // 跳階。
        assertEquals(
            "STAGE_SKIPPED:do STRINGS first",
            DecompileAnalyze.stageVeto(emptySet(), Stage.JAVA, "jadx"),
        )
    }

    @Test fun stageBinaries_inGuestUnion() {
        // 各階二進位必須同時在 exec 白名單聯集內（否則執行層仍拒）。
        for ((_, bins) in DecompileAnalyze.STAGE_BINARIES) {
            for (bin in bins) {
                assertTrue("$bin missing from union", bin in ProotExec.GUEST_BINARIES)
            }
        }
    }

    @Test fun apkInputCap_500M() {
        assertEquals(null, DecompileAnalyze.inputVeto(LinuxEnv.APK_MAX_BYTES))
        assertEquals("APK_TOO_LARGE", DecompileAnalyze.inputVeto(LinuxEnv.APK_MAX_BYTES + 1))
        assertEquals("NEGATIVE_SIZE", DecompileAnalyze.inputVeto(-1))
    }

    @Test fun repack_needsConfirmEveryTime() {
        val need = DecompileAnalyze.repack(confirmed = false)
        assertTrue(need is DecompileAnalyze.RepackOutcome.NeedConfirm)
        assertEquals(
            DecompileAnalyze.REPACK_NAME,
            (need as DecompileAnalyze.RepackOutcome.NeedConfirm).toolName,
        )
        val ok = DecompileAnalyze.repack(confirmed = true)
        assertTrue(ok is DecompileAnalyze.RepackOutcome.Ok)
        // 確認不可快取：下一次無確認仍回 NeedConfirm。
        assertTrue(DecompileAnalyze.repack(confirmed = false) is DecompileAnalyze.RepackOutcome.NeedConfirm)
    }

    @Test fun repack_gates_flavorSwitchContainer() {
        val filesDir = "/data/data/dev.librepocket.agent/files"
        val play = DecompileAnalyze.repack(true, Flavor.PLAY, true, filesDir, "alpine")
        assertTrue(play is DecompileAnalyze.RepackOutcome.Denied)
        assertEquals(DenyReason.FLAVOR_BLOCKED, (play as DecompileAnalyze.RepackOutcome.Denied).reason)
        val off = DecompileAnalyze.repack(true, Flavor.GITHUB, false, filesDir, "alpine")
        assertTrue(off is DecompileAnalyze.RepackOutcome.Denied)
        assertEquals(DenyReason.USER_DISABLED, (off as DecompileAnalyze.RepackOutcome.Denied).reason)
        val evil = DecompileAnalyze.repack(true, Flavor.GITHUB, true, filesDir, "../evil")
        assertTrue(evil is DecompileAnalyze.RepackOutcome.Denied)
        assertEquals(DenyReason.NO_PRIVILEGE, (evil as DecompileAnalyze.RepackOutcome.Denied).reason)
        // 門禁通過後仍需每次確認。
        val need = DecompileAnalyze.repack(false, Flavor.GITHUB, true, filesDir, "alpine")
        assertTrue(need is DecompileAnalyze.RepackOutcome.NeedConfirm)
        val ok = DecompileAnalyze.repack(true, Flavor.GITHUB, true, filesDir, "alpine")
        assertTrue(ok is DecompileAnalyze.RepackOutcome.Ok)
        // 降級訊息尾含 FALLBACK + PERF。
        assertTrue((play as DecompileAnalyze.RepackOutcome.Denied).message.contains(DecompileAnalyze.FALLBACK_HINT))
        assertTrue(play.message.contains(LinuxTools.PERF_NOTICE))
    }

    @Test fun nextAllowed_prefixClosed() {
        // 缺口集合 {SMALI} 不得跳去 RESOURCES，只能先補 STRINGS。
        assertTrue(!DecompileAnalyze.nextAllowed(setOf(Stage.SMALI), Stage.RESOURCES))
        assertTrue(DecompileAnalyze.nextAllowed(setOf(Stage.SMALI), Stage.STRINGS))
        // 缺口 {STRINGS, RESOURCES} 只能補 SMALI。
        assertTrue(!DecompileAnalyze.nextAllowed(setOf(Stage.STRINGS, Stage.RESOURCES), Stage.JAVA))
        assertTrue(DecompileAnalyze.nextAllowed(setOf(Stage.STRINGS, Stage.RESOURCES), Stage.SMALI))
    }

    private class OkRunner : ProcessRunner {
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>? = null): RawOutput =
            RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
    }

    @Test fun executeAnalyze_orchestration() {
        val filesDir = "/data/data/dev.librepocket.agent/files"
        // play/關開關/壞容器先拒。
        assertTrue(
            DecompileAnalyze.executeAnalyze(
                emptySet(), Stage.STRINGS, "strings", 1024, listOf("strings", "x.apk"),
                filesDir, "alpine", Flavor.PLAY, true, OkRunner(),
            ) is ShellResult.Denied,
        )
        assertTrue(
            DecompileAnalyze.executeAnalyze(
                emptySet(), Stage.STRINGS, "strings", 1024, listOf("strings", "x.apk"),
                filesDir, "alpine", Flavor.GITHUB, false, OkRunner(),
            ) is ShellResult.Denied,
        )
        val bad = DecompileAnalyze.executeAnalyze(
            emptySet(), Stage.STRINGS, "strings", 1024, listOf("strings", "x.apk"),
            filesDir, "../evil", Flavor.GITHUB, true, OkRunner(),
        )
        assertTrue(bad is ShellResult.Denied)
        // 跳階與超大 APK 拒絕。
        val skip = DecompileAnalyze.executeAnalyze(
            emptySet(), Stage.JAVA, "jadx", 1024, listOf("jadx", "x.apk"),
            filesDir, "alpine", Flavor.GITHUB, true, OkRunner(),
        )
        assertTrue(skip is ShellResult.Denied)
        val big = DecompileAnalyze.executeAnalyze(
            emptySet(), Stage.STRINGS, "strings", LinuxEnv.APK_MAX_BYTES + 1,
            listOf("strings", "x.apk"), filesDir, "alpine", Flavor.GITHUB, true, OkRunner(),
        )
        assertTrue(big is ShellResult.Denied)
        // 正常放行走 exec。
        val ok = DecompileAnalyze.executeAnalyze(
            emptySet(), Stage.STRINGS, "strings", 1024, listOf("strings", "x.apk"),
            filesDir, "alpine", Flavor.GITHUB, true, OkRunner(),
        )
        assertTrue("expected Ok, got $ok", ok is ShellResult.Ok)
        assertTrue((skip as ShellResult.Denied).message.contains(LinuxTools.PERF_NOTICE))
    }

    @Test fun executeRepack_gatesMerged() {
        val filesDir = "/data/data/dev.librepocket.agent/files"
        val play = DecompileAnalyze.executeRepack(
            true, listOf("apksigner", "sign"), filesDir, "alpine", Flavor.PLAY, true, OkRunner(),
        )
        assertTrue(play is ShellResult.Denied)
        val need = DecompileAnalyze.executeRepack(
            false, listOf("apksigner", "sign"), filesDir, "alpine", Flavor.GITHUB, true, OkRunner(),
        )
        assertTrue(need is ShellResult.Denied)
        assertTrue((need as ShellResult.Denied).message.contains("NEED_CONFIRM"))
        val ok = DecompileAnalyze.executeRepack(
            true, listOf("apksigner", "sign"), filesDir, "alpine", Flavor.GITHUB, true, OkRunner(),
        )
        assertTrue("expected Ok, got $ok", ok is ShellResult.Ok)
    }

    @Test fun switch_defaultsOff() {
        assertEquals("decompile", DecompileAnalyze.SWITCH)
        assertEquals(false, DecompileAnalyze.SWITCH_DEFAULT)
    }
}
