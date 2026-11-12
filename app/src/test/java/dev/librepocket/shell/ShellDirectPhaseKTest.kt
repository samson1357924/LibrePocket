package dev.librepocket.shell

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-4 Phase K pins（P1-4 freeze-once＋P2-4 bounded FS supervision，direct 通道）。
 *
 * - P1-4：入口只凍結一次（[ShellPolicy.copyArgvBounded] 上限）；同一 frozen snapshot
 *   驅動 validate／resolve／reverify／spawn。`MutableList`＋resolver 內竄改
 *   （驗證後把 `find -print`→`-delete`、把 `cat` 域內路徑換域外）→ spawn 仍是
 *   原本合法 argv（確定性負測，零繞道）。
 * - P2-4：freeze／gate／reverify 跑 [DirectSupervisor]（worker＋deadline＋bounded join）；
 *   阻塞 iterator／阻塞 resolver → typed `TimedOut`、零 spawn、有界返回、無無界活 thread。
 *   `timeoutMs` 為整體 request budget（剩餘才交 runner；`<=0` 即時 `TimedOut`）。
 * - Parity：Phase J multicall 有效形規則在 frozen 上同樣生效（dispatcher 形仍先走有效門）。
 *
 * 全 JVM（零 Android／零真機）：runner 皆為同步 fake（計數 spawn），阻塞只用可中斷
 * latch（門超界即 interrupt，worker 正常回收，無殘留執行緒）。
 */
class ShellDirectPhaseKTest {

    private class CapRunner : ProcessRunner {
        var calls: Int = 0
        var lastArgv: List<String>? = null
        var lastTimeoutMs: Long = -1L
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            lastArgv = argv.toList()
            lastTimeoutMs = timeoutMs
            return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
        }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun blockingArgv(latch: CountDownLatch): List<String> =
        object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): Iterator<String> {
                latch.await()
                return listOf("id").iterator()
            }
        }

    // ---- P1-4：find -print→-delete 竄改仍 spawn 原合法 argv ----

    @Test fun frozenFindPrintDeleteTamperStillSpawnsOriginal() {
        val scope = tempDir("k-scope")
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            val argv = mutableListOf("find", sub.absolutePath, "-print")
            val resolverCalls = AtomicInteger(0)
            // 驗證後竄改：gate resolve 與 reverify 兩次回呼都把原 list 第三項改為 -delete；
            // frozen snapshot 已在入口拷貝，故 spawn 不受影響（確定性，無 sleep 猜窗）。
            val resolver: (String) -> ShellExecutables.ResolvedExec? = {
                resolverCalls.incrementAndGet()
                argv[2] = "-delete"
                ShellExecutables.ResolvedExec("/trusted/find", null)
            }
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = resolver,
            )
            val r = shell.execute(argv, timeoutMs = 10_000L)
            assertTrue("$r", r is ShellResult.Ok)
            assertEquals(2, resolverCalls.get())
            assertEquals(1, runner.calls)
            assertEquals(listOf("/trusted/find", sub.absolutePath, "-print"), runner.lastArgv)
            // 原 list 已被竄改（證明竄改確實發生），但 spawn 用的是凍結前合法值。
            assertEquals("-delete", argv[2])
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- P1-4：cat 域內→域外換路徑仍 spawn 原合法 argv ----

    @Test fun frozenCatPathSwapStillSpawnsOriginal() {
        val scope = tempDir("k-cat-scope")
        try {
            val f = File(scope, "in.txt").apply { writeText("hi") }
            val argv = mutableListOf("cat", f.absolutePath)
            val resolver: (String) -> ShellExecutables.ResolvedExec? = {
                argv[1] = "/etc/passwd"
                ShellExecutables.ResolvedExec("/trusted/cat", null)
            }
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = resolver,
            )
            val r = shell.execute(argv, timeoutMs = 10_000L)
            assertTrue("$r", r is ShellResult.Ok)
            assertEquals(1, runner.calls)
            assertEquals(listOf("/trusted/cat", f.absolutePath), runner.lastArgv)
            assertEquals("/etc/passwd", argv[1])
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- P2-4：阻塞 resolver → TimedOut 零 spawn 有界 ----

    @Test fun blockingResolverTimedOutWithoutSpawn() {
        val latch = CountDownLatch(1) // 永不釋放：門超界靠 interrupt 回收 worker。
        val runner = CapRunner()
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = {
                latch.await()
                ShellExecutables.ResolvedExec("/trusted/echo", null)
            },
        )
        val start = System.currentTimeMillis()
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 300L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ShellResult.TimedOut)
        assertEquals(0, runner.calls)
        assertTrue("must return within request budget + grace, elapsed=$elapsed", elapsed < 5_000L)
    }

    // ---- P2-4：阻塞 iterator（freeze）→ TimedOut 零 spawn 有界 ----

    @Test fun blockingFreezeTimedOutWithoutSpawn() {
        val latch = CountDownLatch(1)
        val runner = CapRunner()
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        val start = System.currentTimeMillis()
        val r = shell.execute(blockingArgv(latch), timeoutMs = 300L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ShellResult.TimedOut)
        assertEquals(0, runner.calls)
        assertTrue("must return within request budget + grace, elapsed=$elapsed", elapsed < 5_000L)
    }

    // ---- P2-4：timeout<=0 即時 TimedOut，不耗配額不 spawn ----

    @Test fun zeroTimeoutImmediateTimedOutWithoutSpawnOrQuota() {
        val runner = CapRunner()
        val quota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
        val shell = RestrictedShell(
            quota = quota,
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 0L)
        assertTrue("$r", r is ShellResult.TimedOut)
        assertEquals(0, runner.calls)
        // 配額未被消耗：正常 budget 重試仍可執行。
        val r2 = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("$r2", r2 is ShellResult.Ok)
        assertEquals(1, runner.calls)
    }

    // ---- P2-4：runner 拿到的是剩餘 budget（<=請求值，>0） ----

    @Test fun runnerReceivesRemainingBudget() {
        val runner = CapRunner()
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 5_000L)
        assertTrue("$r", r is ShellResult.Ok)
        assertTrue("remaining=${runner.lastTimeoutMs}", runner.lastTimeoutMs in 1L..5_000L)
    }

    // ---- freeze schema：超限／孤立代理 → Denied(BLACKLISTED) 零 spawn ----

    @Test fun freezeSchemaViolationsDeniedWithoutSpawn() {
        for (argv in listOf(
            List(ShellPolicy.MAX_ELEVATED_ARGC + 1) { "a" },
            listOf("id", "x".repeat(ShellPolicy.MAX_ELEVATED_ARG_CHARS + 1)),
            listOf("id", "\uD800"),
        )) {
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = null,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val r = shell.execute(argv, timeoutMs = 10_000L)
            assertTrue("$argv -> $r", r is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertEquals(0, runner.calls)
        }
    }

    // ---- parity：Phase J 有效形規則在 frozen（MutableList）上同樣生效 ----

    @Test fun multicallEffectiveParityOnFrozenMutableList() {
        val scope = tempDir("k-parity-scope")
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            // 有效形 find -delete 經 dispatcher 仍 BLACKLISTED。
            for (argv in listOf(
                mutableListOf("toybox", "find", sub.absolutePath, "-delete"),
                mutableListOf("busybox", "find", sub.absolutePath, "-delete"),
            )) {
                val runner = CapRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val r = shell.execute(argv, timeoutMs = 10_000L)
                assertTrue("$argv -> $r", r is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
                assertEquals(0, runner.calls)
            }
            // 未知 applet 仍 NOT_WHITELISTED（B3 TODO，不 blanket allow）。
            run {
                val runner = CapRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val r = shell.execute(mutableListOf("toybox", "ps"), timeoutMs = 10_000L)
                assertTrue("$r", r is ShellResult.Denied)
                assertEquals(ShellDeny.NOT_WHITELISTED, (r as ShellResult.Denied).reason)
                assertEquals(0, runner.calls)
            }
            // 有效門全過的安全形仍以 dispatcher NOT_WHITELISTED 拒（非 BLACKLISTED）。
            run {
                val runner = CapRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val r = shell.execute(
                    mutableListOf("toybox", "find", sub.absolutePath, "-print"),
                    timeoutMs = 10_000L,
                )
                assertTrue("$r", r is ShellResult.Denied)
                assertEquals(ShellDeny.NOT_WHITELISTED, (r as ShellResult.Denied).reason)
                assertEquals(0, runner.calls)
            }
        } finally {
            scope.deleteRecursively()
        }
    }
}
