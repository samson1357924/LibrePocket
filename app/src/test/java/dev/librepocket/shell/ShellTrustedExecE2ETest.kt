package dev.librepocket.shell

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 可信 executable 端對端（真實二進位 fixture）：宿主為 ubuntu-26.04
 * （rust coreutils 0.10.0 / uutils）時，`/usr/bin/id` 為 symlink →
 * `../lib/cargo/bin/coreutils/id`，而該檔與 `/usr/bin/coreutils` 共用同一 inode
 * —— 115+ applet 共用一個以 argv[0] basename 派發的 multicall 分派器
 * （basename 派發：`./id` 出 uid=…，`./id-master` 回 `coreutils: unknown program`
 * exit 1）。spawn 不可插 applet（argv[1] 形只有 toybox/toolbox/busybox）。
 *
 * 這些測試釘住「resolve 後的實體路徑真的能 spawn 且 exit 0」的形狀事實，
 * 而非僅字串／前綴比對。於 ubuntu-26.04 宿主四個測試皆執行；其餘平台
 * 依 [assumeTrue] 略過的三個（bare／absolute／hardlink），
 * appWritableFakeIdIsRejected 在任何平台皆執行。
 *
 * - bare `id`：經受控搜尋解析，applet 必為 null（uutils 為 argv[0] basename
 *   派發的 multicall 分派器，不在 MULTICALL_BINARIES 的 argv[1] 形表內，
 *   resolve 不插 applet），spawn exit 0 且 stdout 含 `uid=`；
 * - 絕對 `/usr/bin/id` 與 `/bin/id`：同上；
 * - hardlink fixture（tempdir 內、允許根注入）：證明 containment 允許集可涵蓋
 *   非 ubuntu 佈局的實體落點，resolve 後仍能 spawn exit 0；
 * - app-writable fake `id`（tempdir 內 shell 腳本、真實 [ShellExecutables.defaultIsWritable]
 *   判定）：一律拒（fail-closed）；本 fixture 可寫的 tempdir 會先被 searchDir
 *   祖先守衛擋下（SEARCH_DIR_UNTRUSTED），real writability 閘為 backstop。
 */
class ShellTrustedExecE2ETest {

    private fun spawnAndWait(path: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf(path))
            .redirectErrorStream(true)
            .start()
        val finished = proc.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw AssertionError("process did not exit within 10s: $path")
        }
        val out = proc.inputStream.bufferedReader().readText()
        return proc.exitValue() to out
    }

    @Test fun bareIdResolvesAndActuallyRuns() {
        val cargoId = File("/usr/lib/cargo/bin/coreutils/id")
        assumeTrue("ubuntu-26.04 uutils coreutils id not executable on this host", cargoId.canExecute())
        assumeTrue("writability gate is only meaningful for non-root uid", !File("/").canWrite())

        // KDoc 釘住的形狀事實：cargo 落點在 SYSTEM_REAL_PREFIXES 內。
        assertTrue(
            "SYSTEM_REAL_PREFIXES must contain the uutils coreutils real prefix",
            ShellExecutables.SYSTEM_REAL_PREFIXES.contains("/usr/lib/cargo/bin/coreutils"),
        )

        val exec = ShellExecutables.resolve("id")
        assertNotNull("bare id must resolve on ubuntu-26.04", exec)
        assertNull("uutils id is an argv[0] basename-dispatched dispatcher, not the argv[1] MULTICALL_BINARIES form; applet must stay null", exec!!.applet)

        val (exit, out) = spawnAndWait(exec.path)
        assertEquals("spawn of resolved id must exit 0; out=$out", 0, exit)
        assertTrue("stdout must contain uid=; out=$out", out.contains("uid="))
    }

    @Test fun absoluteIdResolvesAndActuallyRuns() {
        assumeTrue("writability gate is only meaningful for non-root uid", !File("/").canWrite())
        val candidates = listOf("/usr/bin/id", "/bin/id")
        assumeTrue("at least one absolute id executable present for this test", candidates.any { File(it).canExecute() })
        for (candidate in candidates.filter { File(it).canExecute() }) {
            val exec = ShellExecutables.resolve(candidate)
            assertNotNull("$candidate must resolve", exec)
            assertNull("$candidate is dispatched via argv[0] basename (uutils), not the argv[1] MULTICALL_BINARIES form; applet must stay null", exec!!.applet)
            val (exit, out) = spawnAndWait(exec.path)
            assertEquals("spawn of resolved $candidate must exit 0; out=$out", 0, exit)
            assertTrue("stdout must contain uid=; out=$out", out.contains("uid="))
        }
    }

    @Test fun hardlinkFixtureResolvesOutsideUbuntuLayout() {
        val cargoId = File("/usr/lib/cargo/bin/coreutils/id")
        assumeTrue("ubuntu-26.04 uutils coreutils id not executable on this host", cargoId.canExecute())

        // fs.protected_hardlinks=1，且 /tmp 為 tmpfs、cargo 樹位於 ext4 —— 跨裝置
        // 無法 hardlink root-owned 的 cargo 實體，故改為複製一份位元組相同的自有
        // 副本（同一 tempdir 內，保證同 fs）。
        // fixture 依賴複製後檔案仍名為 `id`：uutils multicall 分派器以 argv[0]
        // basename 派發，改名會誤派發（回 unknown program）；勿重新命名。
        val dir = Files.createTempDirectory("trusted-id-fixture").toFile()
        try {
            val master = File(dir, "id-master")
            Files.copy(cargoId.toPath(), master.toPath())
            assertTrue("setExecutable failed for $master", master.setExecutable(true))
            val hard = File(dir, "id")
            Files.createLink(hard.toPath(), master.toPath())
            assertTrue("hardlink fixture must share the master inode",
                Files.isSameFile(hard.toPath(), master.toPath()))

            val exec = ShellExecutables.resolve(
                hard.absolutePath,
                searchDirs = listOf(dir.absolutePath),
                allowedRoots = listOf(dir.absolutePath),
                isWritable = { false },
            )
            assertNotNull("hardlink fixture inside an injected allowed root must resolve", exec)
            assertNull("hardlink to argv[0] basename-dispatched uutils id resolves applet=null (not the argv[1] MULTICALL_BINARIES form)", exec!!.applet)

            val (exit, out) = spawnAndWait(exec.path)
            assertEquals("spawn of resolved hardlink must exit 0; out=$out", 0, exit)
            assertTrue("stdout must contain uid=; out=$out", out.contains("uid="))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun appWritableFakeIdIsRejected() {
        val dir = Files.createTempDirectory("fake-id-fixture").toFile()
        try {
            val fake = File(dir, "id")
            fake.writeText("#!/bin/sh\necho owned\n")
            assertTrue("setExecutable failed for $fake", fake.setExecutable(true))

            // 真實 defaultIsWritable：宿主 tempdir 屬主可寫 → fail-closed 拒。
            // 本 fixture 的可寫 tempdir 先被 searchDir 祖先守衛擋下（SEARCH_DIR_UNTRUSTED）：
            // containment 允許集已含此 tempdir（allowedRoots 注入）故不會擋下；
            // real writability 閘為 backstop，本 fixture 不會走到。
            val detailed = ShellExecutables.resolveDetailed(
                fake.absolutePath,
                searchDirs = listOf(dir.absolutePath),
                allowedRoots = listOf(dir.absolutePath),
            )
            assertTrue(
                "app-writable fake id must be rejected via the searchDir ancestor guard (SEARCH_DIR_UNTRUSTED), got $detailed",
                detailed is ShellExecutables.ResolveOutcome.Denied &&
                    detailed.reason == ShellExecutables.ResolveDeny.SEARCH_DIR_UNTRUSTED,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
