package dev.librepocket.linux

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 `linux.boot` + [LinuxEnv] 測試：
 * 路徑佈局、私有域存放、配額、HTTPS+SHA256、OCI digest pinning、
 * tarball 相容、`--get-proot-cmd` 可觀測、乾淨 guest env、三風味投影。
 *
 * 純 JVM。
 */
class LinuxBootTest {

    private val filesDir = "/data/data/dev.librepocket.agent/files"

    private class FakeDownloader(
        private val bytes: ByteArray,
        private val unpackFails: Boolean = false,
    ) : LinuxBoot.Downloader {
        var fetched = 0
        var unpackedTo: String? = null
        override fun fetch(spec: LinuxEnv.DownloadSpec): ByteArray {
            fetched++
            return bytes
        }
        override fun unpack(archive: ByteArray, destRootfs: String) {
            if (unpackFails) throw IllegalStateException("unpack boom")
            unpackedTo = destRootfs
        }
    }

    private fun shaOf(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    // ---- 佈局：filesDir/linux/{bin,image,cache,containers/<name>/rootfs,tmp} ----

    @Test fun layout_underPrivateFilesDir() {
        assertEquals("$filesDir/linux", LinuxEnv.root(filesDir))
        assertEquals("$filesDir/linux/bin", LinuxEnv.binDir(filesDir))
        assertEquals("$filesDir/linux/image", LinuxEnv.imageDir(filesDir))
        assertEquals("$filesDir/linux/cache", LinuxEnv.cacheDir(filesDir))
        assertEquals("$filesDir/linux/containers", LinuxEnv.containersDir(filesDir))
        assertEquals(
            "$filesDir/linux/containers/alpine/rootfs",
            LinuxEnv.containerRootfs(filesDir, "alpine"),
        )
        assertEquals("$filesDir/linux/tmp", LinuxEnv.tmpDir(filesDir))
        assertEquals("$filesDir/linux/tmp/inbox", LinuxEnv.inboxDir(filesDir))
        assertEquals("$filesDir/linux/bin/proot", LinuxEnv.prootBin(filesDir))
    }

    @Test fun containerName_rejectsTraversal() {
        assertTrue(LinuxEnv.isSafeContainerName("alpine"))
        assertTrue(LinuxEnv.isSafeContainerName("debian-12_x"))
        assertFalse(LinuxEnv.isSafeContainerName("../evil"))
        assertFalse(LinuxEnv.isSafeContainerName("a/b"))
        assertFalse(LinuxEnv.isSafeContainerName(""))
        assertEquals("BAD_CONTAINER_NAME", LinuxEnv.containerVeto(filesDir, "../x"))
        assertEquals("NO_SCOPE", LinuxEnv.containerVeto("", "alpine"))
    }

    // ---- 配額：單容器 2G / 總量 4G ----

    @Test fun quota_single2G_total4G() {
        val g = 1024L * 1024 * 1024
        assertEquals(null, LinuxEnv.quotaVeto(0, 0, 2 * g))
        assertEquals("CONTAINER_QUOTA_2G", LinuxEnv.quotaVeto(0, 0, 2 * g + 1))
        assertEquals("CONTAINER_QUOTA_2G", LinuxEnv.quotaVeto(0, 2 * g, 1))
        assertEquals("TOTAL_QUOTA_4G", LinuxEnv.quotaVeto(4 * g, 0, 1))
        assertEquals(null, LinuxEnv.quotaVeto(3 * g, g, g))
        assertEquals("NEGATIVE_SIZE", LinuxEnv.quotaVeto(0, 0, -5))
    }

    // ---- 下載式：HTTPS + SHA256 必備 ----

    @Test fun downloadVeto_requiresHttpsAndSha256() {
        val ok = LinuxEnv.DownloadSpec("https://example.com/alpine.tar.gz", "a".repeat(64))
        assertEquals(null, LinuxEnv.downloadVeto(ok))
        assertEquals(
            "NOT_HTTPS",
            LinuxEnv.downloadVeto(ok.copy(url = "http://example.com/a.tar.gz")),
        )
        assertEquals("SHA256_REQUIRED", LinuxEnv.downloadVeto(ok.copy(sha256Hex = "abc")))
        assertEquals("SHA256_REQUIRED", LinuxEnv.downloadVeto(ok.copy(sha256Hex = "")))
    }

    @Test fun download_happyPath_unpacksToRootfs() {
        val bytes = "fake-image".toByteArray()
        val spec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(bytes), bytes.size.toLong())
        val dl = FakeDownloader(bytes)
        val out = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, dl,
        )
        assertTrue(out is LinuxBoot.BootOutcome.Ok)
        assertEquals(LinuxBoot.BootState.READY, (out as LinuxBoot.BootOutcome.Ok).state)
        assertEquals("$filesDir/linux/containers/alpine/rootfs", dl.unpackedTo)
    }

    @Test fun download_shaMismatch_rejectsWithoutInstall() {
        val bytes = "fake-image".toByteArray()
        val spec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", "b".repeat(64))
        val dl = FakeDownloader(bytes)
        val out = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, dl,
        )
        assertTrue(out is LinuxBoot.BootOutcome.Failed)
        assertEquals(null, dl.unpackedTo)
    }

    @Test fun download_playBlocked_quotaExceeded() {
        val bytes = ByteArray(8)
        val spec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(bytes), bytes.size.toLong())
        val play = LinuxBoot.download(
            spec, Flavor.PLAY, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, FakeDownloader(bytes),
        )
        assertTrue(play is LinuxBoot.BootOutcome.Denied)
        assertEquals(DenyReason.FLAVOR_BLOCKED, (play as LinuxBoot.BootOutcome.Denied).reason)
        val over = LinuxBoot.download(
            spec.copy(sizeBytes = LinuxEnv.TOTAL_BYTES + 1), Flavor.GITHUB, true,
            LinuxBoot.BootState.NOT_INSTALLED, filesDir, "alpine", 0, 0, FakeDownloader(bytes),
        )
        assertTrue(over is LinuxBoot.BootOutcome.Failed)
    }

    @Test fun download_postFetchQuota_blocksInflatedActual() {
        // B1：宣告 4 位元組預檢放行，實際 8 位元組超限必須 Failed 且不 unpack。
        val actual = ByteArray(8) { 1 }
        val spec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(actual), 4L)
        val dl = FakeDownloader(actual)
        val usedContainer = LinuxEnv.PER_CONTAINER_BYTES - 4
        val out = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, usedContainer, dl,
        )
        assertTrue("expected Failed, got $out", out is LinuxBoot.BootOutcome.Failed)
        assertTrue((out as LinuxBoot.BootOutcome.Failed).detail.contains("quota veto (actual"))
        assertEquals(null, dl.unpackedTo)
    }

    @Test fun linuxBoot_descriptionMarksDownloadScaffold() {
        // PR#1 review 收斂：生產 Downloader 接線前 description 誠實標註 SCAFFOLD。
        val desc = ToolRegistry.find(LinuxBoot.NAME)!!.description
        assertTrue("desc=$desc", desc.contains("SCAFFOLD"))
    }

    @Test fun quota_countsCompressedBytesNotExpandedRootfs() {
        // PR#1 review 收斂（語義邊界鎖定）：現配額只看壓縮檔位元組。
        // 小 archive 兩次 veto 全過即 unpack（不做解包後佔用核算）；
        // 生產 unpacker 必須補 expanded-byte 二次裁決，接線時更新此測試。
        val bytes = ByteArray(8) { 3 }
        val spec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(bytes), bytes.size.toLong())
        val dl = FakeDownloader(bytes)
        val out = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, dl,
        )
        assertTrue("expected Ok, got $out", out is LinuxBoot.BootOutcome.Ok)
        assertEquals(LinuxEnv.containerRootfs(filesDir, "alpine"), dl.unpackedTo)
    }

    @Test fun download_unknownSize_notCountedAsZero() {       // 未知大小（-1）預檢跳過，fetch 後按實際裁決：小檔放行，大檔拒絕。
        val small = "tiny".toByteArray()
        val smallSpec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(small), -1L)
        val smallDl = FakeDownloader(small)
        val smallOut = LinuxBoot.download(
            smallSpec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, smallDl,
        )
        assertTrue("expected Ok, got $smallOut", smallOut is LinuxBoot.BootOutcome.Ok)
        val big = ByteArray(8) { 2 }
        val bigSpec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(big), -1L)
        val bigDl = FakeDownloader(big)
        val usedContainer = LinuxEnv.PER_CONTAINER_BYTES - 4
        val bigOut = LinuxBoot.download(
            bigSpec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, usedContainer, bigDl,
        )
        assertTrue("expected Failed, got $bigOut", bigOut is LinuxBoot.BootOutcome.Failed)
        assertEquals(null, bigDl.unpackedTo)
    }

    @Test fun download_sourceSplit_ociFirst_tarballFirst() {
        val bytes = "img".toByteArray()
        val spec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(bytes), bytes.size.toLong())
        // OCI :latest 未 pin 拒絕。
        val ociLatest = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, FakeDownloader(bytes), ociRef = "alpine:latest",
        )
        assertTrue("expected Failed, got $ociLatest", ociLatest is LinuxBoot.BootOutcome.Failed)
        assertTrue((ociLatest as LinuxBoot.BootOutcome.Failed).detail.contains("oci veto"))
        // OCI 已 pin 放行。
        val ociPinned = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, FakeDownloader(bytes),
            ociRef = "alpine@sha256:" + "c".repeat(64),
        )
        assertTrue("expected Ok, got $ociPinned", ociPinned is LinuxBoot.BootOutcome.Ok)
        // tarball 壞後綴拒絕。
        val badTar = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, FakeDownloader(bytes), tarballFileName = "a.zip",
        )
        assertTrue(badTar is LinuxBoot.BootOutcome.Failed)
        // tarball 好後綴放行。
        val goodTar = LinuxBoot.download(
            spec, Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, FakeDownloader(bytes), tarballFileName = "a.tar.gz",
        )
        assertTrue(goodTar is LinuxBoot.BootOutcome.Ok)
    }

    @Test fun denied_containsFallbackAndPerfNotice() {
        val bytes = ByteArray(4)
        val spec = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", shaOf(bytes), bytes.size.toLong())
        val denied = LinuxBoot.download(
            spec, Flavor.PLAY, true, LinuxBoot.BootState.NOT_INSTALLED,
            filesDir, "alpine", 0, 0, FakeDownloader(bytes),
        ) as LinuxBoot.BootOutcome.Denied
        assertTrue(denied.message.contains(LinuxBoot.FALLBACK_HINT))
        assertTrue(denied.message.contains(LinuxTools.PERF_NOTICE))
        val off = LinuxBoot.start(Flavor.GITHUB, false, LinuxBoot.BootState.READY, filesDir, "alpine")
        assertTrue(off is LinuxBoot.BootOutcome.Denied)
        assertTrue((off as LinuxBoot.BootOutcome.Denied).message.contains(LinuxTools.PERF_NOTICE))
    }

    // ---- 狀態機 ----

    @Test fun bootStates_startStop() {
        assertTrue(
            LinuxBoot.start(Flavor.GITHUB, true, LinuxBoot.BootState.NOT_INSTALLED, filesDir, "alpine")
                is LinuxBoot.BootOutcome.NeedDownload,
        )
        assertEquals(
            LinuxBoot.BootState.RUNNING,
            (LinuxBoot.start(Flavor.GITHUB, true, LinuxBoot.BootState.READY, filesDir, "alpine")
                as LinuxBoot.BootOutcome.Ok).state,
        )
        assertEquals(
            LinuxBoot.BootState.STOPPED,
            (LinuxBoot.stop(Flavor.GITHUB, true, LinuxBoot.BootState.RUNNING, filesDir, "alpine")
                as LinuxBoot.BootOutcome.Ok).state,
        )
        assertTrue(
            LinuxBoot.stop(Flavor.GITHUB, true, LinuxBoot.BootState.READY, filesDir, "alpine")
                is LinuxBoot.BootOutcome.Denied,
        )
    }

    // ---- OCI 優先 digest pinning / tarball 相容 ----

    @Test fun oci_requiresDigestPin() {
        val pinned = "alpine@sha256:" + "c".repeat(64)
        assertEquals(null, LinuxEnv.ociVeto(pinned))
        assertEquals("DIGEST_PIN_REQUIRED", LinuxEnv.ociVeto("alpine:latest"))
        assertEquals("DIGEST_PIN_REQUIRED", LinuxEnv.ociVeto("alpine@sha256:short"))
        assertEquals("EMPTY_REF", LinuxEnv.ociVeto(""))
    }

    @Test fun tarball_compatSuffixes() {
        assertEquals(null, LinuxEnv.tarballVeto("alpine-minirootfs-3.20.tar.gz"))
        assertEquals(null, LinuxEnv.tarballVeto("a.tgz"))
        assertEquals(null, LinuxEnv.tarballVeto("a.tar.xz"))
        assertEquals("TARBALL_SUFFIX_REQUIRED", LinuxEnv.tarballVeto("a.zip"))
        assertEquals("TARBALL_SUFFIX_REQUIRED", LinuxEnv.tarballVeto("a.tar"))
    }

    // ---- --get-proot-cmd 可觀測 + 乾淨 guest env ----

    @Test fun prootCmd_observableShape_noBind() {
        val cmd = LinuxEnv.prootCmd("/p/bin/proot", "/p/linux/containers/alpine/rootfs", listOf("echo", "hi"))
        assertEquals(
            listOf("/p/bin/proot", "-r", "/p/linux/containers/alpine/rootfs", "-w", "/", "-0", "echo", "hi"),
            cmd,
        )
        // 斷言只禁 proot 形 --bind（裸 -b 是 guest 普通旗標，如 ls -b，不得誤殺）。
        assertTrue(cmd.none { it == "--bind" || it.startsWith("--bind=") })
    }

    @Test fun guestEnv_cleanFixedSet() {
        assertEquals(
            mapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "HOME" to "/root",
            ),
            LinuxEnv.GUEST_ENV,
        )
        assertFalse(LinuxEnv.GUEST_ENV.keys.any { it.startsWith("PROOT_") || it == "LD_PRELOAD" })
    }

    @Test fun bindVeto_forbidsBindFlags() {
        // 裸 -b 是 guest 普通旗標（ls -b），只有 proot 形 --bind 才禁。
        assertEquals(null, LinuxEnv.bindVeto(listOf("ls", "-b")))
        assertEquals(null, LinuxEnv.bindVeto(listOf("echo", "hi")))
        assertEquals("BIND_FORBIDDEN:--bind", LinuxEnv.bindVeto(listOf("echo", "--bind", "/x")))
        assertTrue(
            (LinuxEnv.bindVeto(listOf("echo", "--bind=/sdcard:/mnt")) ?: "").startsWith("BIND_FORBIDDEN"),
        )
    }

    // ---- SAF 進出上限：inbox 10MiB / APK 500M 分段 ----

    @Test fun safLimits_inbox10M_apk500M() {
        assertEquals(10L * 1024 * 1024, LinuxEnv.INBOX_MAX_BYTES)
        assertEquals(500L * 1024 * 1024, LinuxEnv.APK_MAX_BYTES)
        assertEquals(0L, LinuxEnv.apkSegmentsFor(0))
        assertEquals(1L, LinuxEnv.apkSegmentsFor(1))
        assertEquals(1L, LinuxEnv.apkSegmentsFor(LinuxEnv.APK_MAX_BYTES))
        assertEquals(2L, LinuxEnv.apkSegmentsFor(LinuxEnv.APK_MAX_BYTES + 1))
    }

    @Test fun inboxVeto_10M_hardCap() {
        assertEquals(null, LinuxEnv.inboxVeto(0))
        assertEquals(null, LinuxEnv.inboxVeto(LinuxEnv.INBOX_MAX_BYTES))
        assertEquals("INBOX_TOO_LARGE", LinuxEnv.inboxVeto(LinuxEnv.INBOX_MAX_BYTES + 1))
        assertEquals("NEGATIVE_SIZE", LinuxEnv.inboxVeto(-1))
    }

    @Test fun apkSegments_sentinelForNegative() {
        assertEquals(-1L, LinuxEnv.apkSegmentsFor(-1))
        assertEquals(-1L, LinuxEnv.apkSegmentsFor(-5))
        assertEquals(0L, LinuxEnv.apkSegmentsFor(0))
    }

    @Test fun downloadVeto_rejectsUserinfoAndBadHost() {
        val good = LinuxEnv.DownloadSpec("https://example.com/a.tar.gz", "a".repeat(64))
        assertEquals(null, LinuxEnv.downloadVeto(good))
        assertEquals(
            "USERINFO_FORBIDDEN",
            LinuxEnv.downloadVeto(good.copy(url = "https://alice:s3cr3t@example.com/a.tar.gz")),
        )
        assertEquals(
            "USERINFO_FORBIDDEN",
            LinuxEnv.downloadVeto(good.copy(url = "https://alice@example.com/a.tar.gz")),
        )
        assertEquals("BAD_HOST", LinuxEnv.downloadVeto(good.copy(url = "https:///no-host/a.tar.gz")))
        assertEquals("BAD_HOST", LinuxEnv.downloadVeto(good.copy(url = "https:// /a.tar.gz")))
    }

    @Test fun prootBinMode_wired() {
        assertEquals("0700", LinuxEnv.PROOT_BIN_MODE)
        assertEquals(null, LinuxEnv.prootBinModeVeto("0700"))
        assertTrue((LinuxEnv.prootBinModeVeto("0755") ?: "").startsWith("PROOT_MODE_MISMATCH"))
    }

    // ---- 三風味投影：play 隱藏（FLAVOR_BLOCKED），foss/github NATIVE ----

    @Test fun projection_playHidden_fossGithubNative() {
        for (name in LinuxTools.ALL_NAMES) {
            val play = ToolRegistry.projectAll(ProjectionContext(flavor = Flavor.PLAY))[name]!!
            assertEquals("$name play", CapabilityLevel.UNAVAILABLE, play.level)
            assertEquals("$name play", DenyReason.FLAVOR_BLOCKED, play.reason)
        }
        val foss = ToolRegistry.projectAll(
            ProjectionContext(flavor = Flavor.FOSS, userSwitches = mapOf("linux" to true, "compile" to true, "decompile" to true)),
        )
        val github = ToolRegistry.projectAll(
            ProjectionContext(flavor = Flavor.GITHUB, userSwitches = mapOf("linux" to true, "compile" to true, "decompile" to true)),
        )
        for (name in LinuxTools.ALL_NAMES) {
            assertEquals("$name foss", CapabilityLevel.NATIVE, foss[name]!!.level)
            assertEquals("$name github", CapabilityLevel.NATIVE, github[name]!!.level)
        }
        val playVisible = ToolRegistry.visibleTools(ProjectionContext(flavor = Flavor.PLAY)).map { it.name }
        assertTrue(playVisible.none { it in LinuxTools.ALL_NAMES })
        // 開關預設關：不開即 USER_DISABLED（六工具全列）。
        val off = ToolRegistry.projectAll(ProjectionContext(flavor = Flavor.GITHUB))
        for (name in LinuxTools.ALL_NAMES) {
            assertEquals("$name off", DenyReason.USER_DISABLED, off[name]!!.reason)
        }
    }

    @Test fun registry_sideEffects_writePlusPrivilegedRepack() {
        val byName = ToolRegistry.ALL.associateBy { it.name }
        assertEquals(dev.librepocket.tool.SideEffect.WRITE, byName[LinuxBoot.NAME]!!.sideEffect)
        assertEquals(dev.librepocket.tool.SideEffect.WRITE, byName[ProotExec.NAME]!!.sideEffect)
        assertEquals(dev.librepocket.tool.SideEffect.WRITE, byName[LinuxPkg.NAME]!!.sideEffect)
        assertEquals(dev.librepocket.tool.SideEffect.WRITE, byName[CompileBuild.NAME]!!.sideEffect)
        assertEquals(dev.librepocket.tool.SideEffect.WRITE, byName[DecompileAnalyze.NAME]!!.sideEffect)
        assertEquals(dev.librepocket.tool.SideEffect.PRIVILEGED, byName[DecompileAnalyze.REPACK_NAME]!!.sideEffect)
    }

    @Test fun registry_repackSchema_hasApkPath() {
        val repack = ToolRegistry.ALL.first { it.name == DecompileAnalyze.REPACK_NAME }
        assertTrue("schema=${repack.jsonSchema}", "\"apkPath\"" in repack.jsonSchema)
        assertTrue("schema=${repack.jsonSchema}", "\"container\"" in repack.jsonSchema)
        assertTrue("schema=${repack.jsonSchema}", "\"confirmed\"" in repack.jsonSchema)
    }
}
