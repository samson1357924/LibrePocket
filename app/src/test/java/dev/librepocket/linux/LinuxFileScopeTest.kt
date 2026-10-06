package dev.librepocket.linux

import dev.librepocket.files.FileScope
import dev.librepocket.files.FileZone
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 FileScope 跨域測試：
 * 容器 rootfs（私有域下）判 PRIVATE 直行；SAF 樹路徑在 guest 執行語義下
 * 不得直行（[ProotExec] 固定 `bridgeGranted=false`，跨域即拒）；
 * play 跨域維持 FLAVOR_BLOCKED。
 *
 * 純 JVM。
 */
class LinuxFileScopeTest {

    private val filesDir = "/data/data/dev.librepocket.agent/files"
    private val rootfs = "$filesDir/linux/containers/alpine/rootfs"
    private val safRoots = listOf("/sdcard/Download")

    @Test fun containerRootfs_isPrivate() {
        val d = FileScope.decide("$rootfs/etc/hosts", filesDir, safRoots, Flavor.GITHUB, true)
        assertTrue(d.allowed)
        assertEquals(FileZone.PRIVATE, d.zone)
        assertFalse(d.needsBridge)
    }

    @Test fun safTree_neverDirectForGuest() {
        // FileScope 層：未授權橋接的跨域（guest 語義固定 bridgeGranted=false）
        // 一律拒絕（foss/github 回 NEEDS_BRIDGE，呼叫方改走 inbox 複製）。
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val d = FileScope.decide("/sdcard/Download/x.apk", filesDir, emptyList(), flavor, false)
            assertFalse("$flavor", d.allowed)
            assertEquals("$flavor", FileScope.CODE_NEEDS_BRIDGE, d.code)
        }
        // 即使 SAF 樹已授權（SAF_GRANTED），guest 直接 exec 仍拒絕：
        // ProotExec 固定傳空授權表，禁 bind 直通，必須先複製進 inbox。
        val granted = FileScope.decide("/sdcard/Download/x.apk", filesDir, safRoots, Flavor.GITHUB, true)
        assertTrue(granted.allowed)
        val denied = ProotExec.execute(
            listOf("cat", "/sdcard/Download/x.apk"),
            filesDir, "alpine", Flavor.GITHUB, true,
            object : dev.librepocket.shell.ProcessRunner {
                override fun run(
                    argv: List<String>,
                    timeoutMs: Long,
                ): dev.librepocket.shell.RawOutput = throw AssertionError("must not spawn")
            },
        )
        assertTrue(denied is dev.librepocket.shell.ShellResult.Denied)
        // inbox（私有域下）直行。
        val inbox = FileScope.decide(
            "$filesDir/linux/tmp/inbox/x.apk", filesDir, safRoots, Flavor.GITHUB, false,
        )
        assertTrue(inbox.allowed)
        assertEquals(FileZone.PRIVATE, inbox.zone)
    }

    @Test fun playCrossDomain_flavorBlocked() {
        // 授權表之外的跨域：play 一律 FLAVOR_BLOCKED（即使橋接旗標誤傳 true）。
        val d = FileScope.decide("/sdcard/Other/x.apk", filesDir, safRoots, Flavor.PLAY, true)
        assertFalse(d.allowed)
        assertEquals(DenyReason.FLAVOR_BLOCKED, d.denyReason)
        assertEquals(FileScope.CODE_CROSS_DOMAIN, d.code)
    }

    @Test fun hostAbsoluteOutsideScope_denied() {
        val d = FileScope.decide("/etc/passwd", rootfs, emptyList(), Flavor.GITHUB, false)
        assertFalse(d.allowed)
    }
}
