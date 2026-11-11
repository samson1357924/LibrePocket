package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage A（P1 dash bypass）提權跑道層迴歸（github 源集）：
 * `cat -- -secret`／`grep -f -patterns`／`grep -- pattern -secret`／
 * `toybox cat -- -secret` 經 [RootSuRunner]／[ShizukuShellRunner] 必須
 * `Denied(BLACKLISTED)` 且零 spawn（validate 先於 grant／activation／橋門／
 * su 解析／spawn，見兩 runner `runBinary` 順序）。
 *
 * scope 內預植 `-secret -> outside/secret.txt` symlink（cwd 釘死語義下
 * OS 打開短橫相對路徑即穿出；見主源集 `ShellDashScopeTest` 的 fixture
 * 有效性斷言）。政策層 parity（direct／validateElevated）由主源集覆蓋，
 * 此處只鎖跑道層零 spawn。
 */
class ElevatedDashScopeTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    @Before fun setUpBreakerIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollTo(0, 10_000L)
    }

    @After fun tearDownBreakerIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollTo(0, 10_000L)
    }

    private fun pollTo(expected: Int, boundMs: Long) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected ||
            ElevatedCleanupTracker.activeCalls() != expected
        ) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} " +
                        "active=${ElevatedCleanupTracker.activeCalls()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    private fun dashScope(): Triple<File, File, File> {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
        val scope = tempDir("dash-elev-scope")
        val outside = tempDir("dash-elev-outside")
        File(outside, "secret.txt").writeText("OUTSIDE")
        val link = File(scope, "-secret")
        Files.createSymbolicLink(link.toPath(), File(outside, "secret.txt").toPath())
        return Triple(scope, outside, link)
    }

    private fun makeRoot(scope: File, spawn: SpawnCounter): RootSuRunner = RootSuRunner(
        privateRoot = scope.absolutePath,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        flavor = Flavor.GITHUB,
        spawn = spawn::spawn,
        suBinary = "/system/bin/su",
        suExists = { true },
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun makeShizuku(scope: File, spawn: SpawnCounter): ShizukuShellRunner = ShizukuShellRunner(
        privateRoot = scope.absolutePath,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        flavor = Flavor.GITHUB,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = GrantFixtures.allowAlways,
    )

    private val vectors: List<List<String>> = listOf(
        listOf("cat", "--", "-secret"),
        listOf("grep", "-f", "-patterns", "allowed.txt"),
        listOf("grep", "--", "pattern", "-secret"),
        listOf("toybox", "cat", "--", "-secret"),
    )

    @Test fun rootRunner_zeroSpawnOnDashBypass() {
        val (scope, outside) = dashScope()
        try {
            for (argv in vectors) {
                val spawn = SpawnCounter()
                val runner = makeRoot(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "DASH-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in relative-path family, got ${denied.message}",
                    denied.message.contains("relative path denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun shizukuRunner_zeroSpawnOnDashBypass() {
        val (scope, outside) = dashScope()
        try {
            for (argv in vectors) {
                val spawn = SpawnCounter()
                val runner = makeShizuku(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "DASH-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in relative-path family, got ${denied.message}",
                    denied.message.contains("relative path denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private val eqVectors: List<List<String>> = listOf(
        listOf("cat", "--", "--color=auto"),
        listOf("grep", "-f", "--color=auto"),
        listOf("grep", "--", "pattern", "--color=auto"),
        listOf("cat", "--", "--file="),
        listOf("cat", "--", "--file=-"),
        listOf("cat", "--", "--file=/scope/work/known.txt"),
    )

    @Test fun rootRunner_zeroSpawnOnEqOperand() {
        val (scope, outside) = dashScope()
        try {
            for (argv in eqVectors) {
                val spawn = SpawnCounter()
                val runner = makeRoot(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "DASH-EQ-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in relative-path family, got ${denied.message}",
                    denied.message.contains("relative path denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun shizukuRunner_zeroSpawnOnEqOperand() {
        val (scope, outside) = dashScope()
        try {
            for (argv in eqVectors) {
                val spawn = SpawnCounter()
                val runner = makeShizuku(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "DASH-EQ-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in relative-path family, got ${denied.message}",
                    denied.message.contains("relative path denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    /**
     * Stage C（P1-1 attached file-options）提權跑道層迴歸：
     * `grep -f=/abs`／`--exclude-from -secret`／`--exclude-from=v`／
     * `toybox grep -f=…` 經 Root／Shizuku 必須 `Denied(BLACKLISTED)` 且零 spawn。
     * 尾參合法絕對（load-bearing：拒絕只能來自危險槽）。
     */
    private fun stageCVectors(scope: File): List<List<String>> {
        val patterns = File(scope, "patterns").apply { writeText("zzz-no-match\n") }.absolutePath
        val input = File(scope, "input").apply { writeText("hello world\n") }.absolutePath
        return listOf(
            listOf("grep", "-f=$patterns", input),
            listOf("grep", "-f=-secret", input),
            listOf("grep", "hello", "--exclude-from", "-secret", input),
            listOf("grep", "--exclude-from=-secret", input),
            listOf("grep", "--exclude-from=bare/relative", input),
            listOf("toybox", "grep", "-f=$patterns", input),
        )
    }

    @Test fun rootRunner_zeroSpawnOnStageC() {
        val (scope, outside) = dashScope()
        try {
            for (argv in stageCVectors(scope)) {
                val spawn = SpawnCounter()
                val runner = makeRoot(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "STAGEC-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in relative-path family, got ${denied.message}",
                    denied.message.contains("relative path denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun shizukuRunner_zeroSpawnOnStageC() {
        val (scope, outside) = dashScope()
        try {
            for (argv in stageCVectors(scope)) {
                val spawn = SpawnCounter()
                val runner = makeShizuku(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "STAGEC-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in relative-path family, got ${denied.message}",
                    denied.message.contains("relative path denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    /**
     * Stage D（P1-2 recursive symlink-follow）提權跑道層迴歸：
     * `grep -R`／`grep --dereference-recursive`／`find -L`／`find -follow`／
     * `du -L`／`ls -L` 經 Root／Shizuku 必須 `Denied(BLACKLISTED)` 且零 spawn
     *（validate 先於 grant／activation／橋門／su 解析／spawn，見兩 runner
     * `runBinary` 順序；訊息走 traversal 家族，與主源集
     * `ShellTraversalTest.directRunner_zeroSpawnOnTraversal` 同門）。
     *
     * fixture 與主源集同形：scope/subtree 內 `escape -> outside` dir symlink。
     */
    private fun traversalScope(): Triple<File, File, String> {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
        val scope = tempDir("trav-elev-scope")
        val outside = tempDir("trav-elev-outside")
        File(outside, "hidden.txt").writeText("OUTSIDE_MARK_xyz\n")
        val subtree = File(scope, "subtree").apply { mkdirs() }
        File(subtree, "inside.txt").writeText("INSIDE_MARK_abc\n")
        Files.createSymbolicLink(File(subtree, "escape").toPath(), outside.toPath())
        return Triple(scope, outside, subtree.absolutePath)
    }

    private fun traversalVectors(subtreeAbs: String): List<List<String>> = listOf(
        listOf("grep", "-R", "TOKEN", subtreeAbs),
        listOf("grep", "--dereference-recursive", "TOKEN", subtreeAbs),
        listOf("find", "-L", subtreeAbs, "-name", "hidden.txt"),
        listOf("find", subtreeAbs, "-follow", "-name", "hidden.txt"),
        listOf("du", "-L", "-a", subtreeAbs),
        listOf("ls", "-L", subtreeAbs),
    )

    @Test fun rootRunner_zeroSpawnOnTraversal() {
        val (scope, outside, subtreeAbs) = traversalScope()
        try {
            for (argv in traversalVectors(subtreeAbs)) {
                val spawn = SpawnCounter()
                val runner = makeRoot(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "TRAV-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in traversal family, got ${denied.message}",
                    denied.message.contains("traversal flag denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun shizukuRunner_zeroSpawnOnTraversal() {
        val (scope, outside, subtreeAbs) = traversalScope()
        try {
            for (argv in traversalVectors(subtreeAbs)) {
                val spawn = SpawnCounter()
                val runner = makeShizuku(scope, spawn)
                val result = runner.runBinary(ElevatedRequest(argv, "TRAV-TEST"), 5_000L)
                assertTrue("expected Denied for $argv, got $result", result is ElevatedBinaryResult.Denied)
                val denied = result as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, denied.reason)
                assertTrue(
                    "message must stay in traversal family, got ${denied.message}",
                    denied.message.contains("traversal flag denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
