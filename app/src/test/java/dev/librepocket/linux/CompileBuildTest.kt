package dev.librepocket.linux

import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.RawOutput
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 `compile.build` 測試：make/cmake/gcc/clang/python recipe 放行，
 * Gradle 機內明確不支援（CI 指引話術逐字），其餘二進位走聯集否決。
 *
 * 純 JVM。
 */
class CompileBuildTest {

    @Test fun recipes_allowed() {
        for (argv in listOf(
            listOf("make", "-j4"),
            listOf("cmake", ".."),
            listOf("ninja", "-C", "build"),
            listOf("gcc", "-O2", "-o", "hello", "hello.c"),
            listOf("g++", "--version"),
            listOf("clang", "--version"),
            listOf("clang++", "--version"),
            listOf("pkg-config", "--cflags", "zlib"),
            listOf("python3", "build.py"),
        )) {
            assertEquals("veto for $argv", null, CompileBuild.recipeVeto(argv))
            // recipe 二進位必須同時在 exec 白名單聯集內（否則執行層仍拒）。
            val base = argv[0].substringAfterLast('/')
            assertTrue("$base missing from union", base in ProotExec.GUEST_BINARIES)
        }
    }

    @Test fun gradle_unsupportedWithCiGuidance() {
        for (argv in listOf(
            listOf("gradle", "build"),
            listOf("gradlew", "build"),
            listOf("./gradlew", "assembleDebug"),
            listOf("/x/gradlew", "build"),
        )) {
            assertEquals("veto for $argv", "GRADLE_UNSUPPORTED", CompileBuild.recipeVeto(argv))
        }
        // CI 指引逐字：機內不支援 + CI + inbox 取回。
        assertTrue(CompileBuild.CI_GUIDANCE.contains("Gradle 機內不支援"))
        assertTrue(CompileBuild.CI_GUIDANCE.contains("CI"))
        assertTrue(CompileBuild.CI_GUIDANCE.contains("inbox"))
    }

    @Test fun otherBinaries_denied() {
        assertEquals("RECIPE_DENIED:curl", CompileBuild.recipeVeto(listOf("curl", "https://x")))
        assertEquals("RECIPE_DENIED:apt", CompileBuild.recipeVeto(listOf("apt", "update")))
        assertEquals("EMPTY_COMMAND", CompileBuild.recipeVeto(emptyList()))
        assertEquals("EMPTY_COMMAND", CompileBuild.recipeVeto(listOf("  ")))
    }

    @Test fun switch_defaultsOff() {
        assertEquals("compile", CompileBuild.SWITCH)
        assertEquals(false, CompileBuild.SWITCH_DEFAULT)
    }

    private class OkRunner : ProcessRunner {
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>? = null): RawOutput =
            RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
    }

    @Test fun execute_orchestration_flavorSwitchContainerVetoExec() {
        val filesDir = "/data/data/dev.librepocket.agent/files"
        val play = CompileBuild.execute(listOf("make", "-j4"), filesDir, "alpine", Flavor.PLAY, true, OkRunner())
        assertTrue(play is ShellResult.Denied)
        assertTrue((play as ShellResult.Denied).message.contains(LinuxTools.PERF_NOTICE))
        val off = CompileBuild.execute(listOf("make"), filesDir, "alpine", Flavor.GITHUB, false, OkRunner())
        assertTrue(off is ShellResult.Denied)
        val evil = CompileBuild.execute(listOf("make"), filesDir, "../evil", Flavor.GITHUB, true, OkRunner())
        assertTrue(evil is ShellResult.Denied)
        // Gradle 自動附 CI 指引原文。
        val gradle = CompileBuild.execute(listOf("gradle", "build"), filesDir, "alpine", Flavor.GITHUB, true, OkRunner())
        assertTrue(gradle is ShellResult.Denied)
        assertTrue((gradle as ShellResult.Denied).message.contains(CompileBuild.CI_GUIDANCE))
        assertTrue(gradle.message.contains(CompileBuild.FALLBACK_HINT))
        assertTrue(gradle.message.contains(LinuxTools.PERF_NOTICE))
        // 非 recipe 拒絕。
        val curl = CompileBuild.execute(listOf("curl", "https://x"), filesDir, "alpine", Flavor.GITHUB, true, OkRunner())
        assertTrue(curl is ShellResult.Denied)
        // 落盤配額超限不建子進程。
        val quota = CompileBuild.execute(
            listOf("make"), filesDir, "alpine", Flavor.GITHUB, true, OkRunner(),
            usedContainerBytes = LinuxEnv.PER_CONTAINER_BYTES, estimatedWriteBytes = 1,
        )
        assertTrue(quota is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (quota as ShellResult.Denied).reason)
        // 正常放行。
        val ok = CompileBuild.execute(listOf("make", "-j4"), filesDir, "alpine", Flavor.GITHUB, true, OkRunner())
        assertTrue("expected Ok, got $ok", ok is ShellResult.Ok)
    }

    @Test fun ciGuidance_traditionalChinese() {
        assertTrue(CompileBuild.CI_GUIDANCE.contains("倉庫"))
        assertTrue(!CompileBuild.CI_GUIDANCE.contains("仓库"))
    }
}
