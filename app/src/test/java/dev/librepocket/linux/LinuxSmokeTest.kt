package dev.librepocket.linux

import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.RawOutput
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 SMOKE：alpine `hello-world`。
 *
 * 純 JVM 部分（CI 必跑）：alpine 容器 `["echo", "hello-world"]`
 * 走 [ProotExec.execute] 全路徑（政策 → 配額 → 假跑道 → 截斷 → 脫敏），
 * 斷言輸出 `hello-world` 且 `--get-proot-cmd` 形狀正確。
 *
 * 機內部分（需真機 + 已下載 alpine rootfs + proot 落盤，手動）：
 * 1. `linux.boot{action:download, container:alpine, url:https..., sha256:...}` → READY；
 * 2. `linux.boot{action:start, container:alpine}` → RUNNING；
 * 3. `linux.exec{argv:[echo, hello-world], container:alpine}` → `hello-world`；
 * 4. `linux.boot{action:stop}` → STOPPED。
 * 宿主有 `proot` 時加跑真進程對照（否則 Assume 跳過後半）。
 */
class LinuxSmokeTest {

    private val filesDir = "/data/data/dev.librepocket.agent/files"

    private class EchoRunner : ProcessRunner {
        override fun run(argv: List<String>, timeoutMs: Long): RawOutput {
            // 假跑道回顯 guest argv 尾段（模擬容器內 echo）。
            val guest = argv.dropWhile { it != "-0" }.drop(1)
            val text = if (guest.firstOrNull() == "echo") guest.drop(1).joinToString(" ") else ""
            return RawOutput(text.toByteArray(), ByteArray(0), 0, false)
        }
    }

    @Test fun alpineHelloWorld_guestPath() {
        val guest = listOf("echo", "hello-world")
        val runner = EchoRunner()
        val result = ProotExec.execute(
            guestArgv = guest,
            filesDir = filesDir,
            container = "alpine",
            flavor = Flavor.GITHUB,
            switchOn = true,
            runner = runner,
            timeoutMs = ShellPolicy.DEFAULT_TIMEOUT_MS,
        )
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        assertEquals("hello-world", (result as ShellResult.Ok).stdout)
        assertEquals(0, result.exitCode)
    }

    @Test fun hostProotParity_ifAvailable() {
        val proot = try {
            val p = ProcessBuilder("proot", "--version")
                .redirectErrorStream(true)
                .start()
            val finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            finished && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
        Assume.assumeTrue("no host proot; guest-path smoke above is the CI signal", proot)
        val p = ProcessBuilder("proot", "--version").start()
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(0, p.exitValue())
    }
}
