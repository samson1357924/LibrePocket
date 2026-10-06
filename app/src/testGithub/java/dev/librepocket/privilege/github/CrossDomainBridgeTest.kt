package dev.librepocket.privilege.github

import dev.librepocket.files.FileScope
import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * S3 跨域橋測試（BACKLOG D09）：SAF 優先分派 + needsBridge 門禁。
 * 遠端 IO 全注入假實現，不碰真實 binder。
 */
class CrossDomainBridgeTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"
    private val safTree = "/tree/primary:Download/docs"

    private fun bridge(
        bridgeGranted: Boolean,
        safContent: Map<String, ByteArray> = emptyMap(),
        remoteContent: Map<String, ByteArray> = emptyMap(),
    ): CrossDomainFileBridge {
        val saf = object : dev.librepocket.files.SafFileBridge {
            override fun exists(absolutePath: String) = absolutePath in safContent
            override fun read(absolutePath: String) =
                safContent[absolutePath] ?: throw FileNotFoundException(absolutePath)
            override fun write(absolutePath: String, bytes: ByteArray) = Unit
            override fun list(absolutePrefix: String) = emptyList<String>()
        }
        val shizuku = ShizukuFileBridge(
            pingBinder = { true },
            remoteRead = { path -> remoteContent[path] ?: throw FileNotFoundException(path) },
            remoteWrite = { _, _ -> Unit },
            remoteExists = { path -> path in remoteContent },
            remoteList = { emptyList() },
        )
        return CrossDomainFileBridge(
            privateRoot = privateRoot,
            safRoots = listOf(safTree),
            flavor = Flavor.GITHUB,
            bridgeGranted = bridgeGranted,
            safBridge = saf,
            shizukuBridge = shizuku,
            privateRead = { path -> "private:$path".toByteArray() },
            privateWrite = { _, _ -> Unit },
            privateExists = { true },
        )
    }

    @Test fun privatePathGoesDirectWithoutBridge() {
        val b = bridge(bridgeGranted = false)
        val bytes = b.read("$privateRoot/chat/x.txt")
        assertEquals("private:$privateRoot/chat/x.txt", bytes.toString(Charsets.UTF_8))
    }

    @Test fun safGrantedPathPrefersSafOverBridge() {
        // SAF 優先：即使橋接已授權，已授權 SAF 仍走 SAF，不碰遠端。
        val b = bridge(
            bridgeGranted = true,
            safContent = mapOf("$safTree/report.pdf" to "saf-bytes".toByteArray()),
            remoteContent = mapOf("$safTree/report.pdf" to "remote-bytes".toByteArray()),
        )
        assertEquals("saf-bytes", b.read("$safTree/report.pdf").toString(Charsets.UTF_8))
    }

    @Test fun crossDomainWithoutGrantIsDenied() {
        val b = bridge(bridgeGranted = false)
        try {
            b.read("/sdcard/Download/other/x.txt")
            assertTrue("expected FileNotFoundException", false)
        } catch (e: FileNotFoundException) {
            assertTrue(e.message!!.contains("SAF優先"))
        }
        assertFalse(b.exists("/sdcard/Download/other/x.txt"))
    }

    @Test fun crossDomainWithGrantGoesOverBridge() {
        val b = bridge(
            bridgeGranted = true,
            remoteContent = mapOf("/sdcard/Download/other/x.txt" to "remote-x".toByteArray()),
        )
        // 先 FileScope.decide：needsBridge 且已授權才允許。
        val decision = FileScope.decide(
            "/sdcard/Download/other/x.txt",
            privateRoot,
            safRoots = listOf(safTree),
            flavor = Flavor.GITHUB,
            bridgeGranted = true,
        )
        assertTrue(decision.allowed)
        assertTrue(decision.needsBridge)
        assertEquals("remote-x", b.read("/sdcard/Download/other/x.txt").toString(Charsets.UTF_8))
        assertTrue(b.exists("/sdcard/Download/other/x.txt"))
    }

    @Test fun shizukuBridgeAloneRequiresNeedsBridge() {
        val shizuku = ShizukuFileBridge(pingBinder = { true }, remoteRead = { "x".toByteArray() })
        // 私有域直行路徑不可走橋（SAF 優先：直行不繞橋）。
        try {
            shizuku.read("$privateRoot/x.txt", privateRoot, listOf(safTree), Flavor.GITHUB, true)
            assertTrue("expected SecurityException", false)
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("SAF優先"))
        }
        // 未授權跨域同樣拒絕。
        try {
            shizuku.read("/sdcard/x.txt", privateRoot, listOf(safTree), Flavor.GITHUB, false)
            assertTrue("expected SecurityException", false)
        } catch (_: SecurityException) {
        }
    }

    @Test fun shizukuBridgeBinderDownFailsHonestly() {
        val shizuku = ShizukuFileBridge(
            pingBinder = { false },
            remoteRead = { "x".toByteArray() },
        )
        try {
            shizuku.read("/sdcard/x.txt", privateRoot, listOf(safTree), Flavor.GITHUB, true)
            assertTrue("expected FileNotFoundException", false)
        } catch (_: FileNotFoundException) {
        }
    }
}
