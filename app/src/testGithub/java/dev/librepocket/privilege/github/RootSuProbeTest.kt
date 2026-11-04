package dev.librepocket.privilege.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 Root su 探測測試（Issue #136 B1）：
 * 傳統 su＋Magisk／KernelSU／APatch 標記，存在性檢查、零執行。
 * [exists] 全注入假映射，不碰真實檔案系統/su。
 */
class RootSuProbeTest {

    private fun probe(vararg present: String): RootSuProbe.Report =
        RootSuProbe.probe(exists = { it in present })

    @Test fun emptyEnvironmentReportsUnavailable() {
        val report = probe()
        assertFalse(report.anyAvailable)
        assertFalse(report.magisk)
        assertFalse(report.kernelSu)
        assertFalse(report.aPatch)
        assertTrue(report.suBinaries.isEmpty())
    }

    @Test fun classicSuBinaryDetected() {
        val report = probe("/system/xbin/su")
        assertTrue(report.anyAvailable)
        assertEquals(listOf("/system/xbin/su"), report.suBinaries)
        assertFalse(report.magisk)
    }

    @Test fun magiskMarkerDetectedWithoutSuBinary() {
        val report = probe("/sbin/.magisk")
        assertTrue(report.anyAvailable)
        assertTrue(report.magisk)
        assertTrue(report.suBinaries.isEmpty())
    }

    @Test fun kernelSuMarkerDetected() {
        val report = probe("/data/adb/ksu")
        assertTrue(report.anyAvailable)
        assertTrue(report.kernelSu)
        assertFalse(report.magisk)
        assertFalse(report.aPatch)
    }

    @Test fun aPatchMarkerDetected() {
        val report = probe("/data/adb/apd")
        assertTrue(report.anyAvailable)
        assertTrue(report.aPatch)
        assertFalse(report.kernelSu)
    }

    @Test fun probeExceptionFoldsToAbsent() {
        val report = RootSuProbe.probe(exists = { throw SecurityException("denied") })
        assertFalse(report.anyAvailable)
    }

    @Test fun multipleMarkersCombinedReport() {
        val report = probe("/system/bin/su", "/sbin/.magisk", "/data/adb/ksu", "/data/adb/ap")
        assertTrue(report.anyAvailable)
        assertEquals(listOf("/system/bin/su"), report.suBinaries)
        assertTrue(report.suExecutableAvailable)
        assertTrue(report.magisk)
        assertTrue(report.kernelSu)
        assertTrue(report.aPatch)
    }

    @Test fun secondaryMarkersDetected() {
        assertTrue(probe("/data/adb/magisk").magisk)
        assertTrue(probe("/data/adb/ksud").kernelSu)
        assertTrue(probe("/data/adb/ap").aPatch)
        val daemonsu = probe("/system/xbin/daemonsu")
        assertEquals(listOf("/system/xbin/daemonsu"), daemonsu.suBinaries)
        assertTrue(daemonsu.suExecutableAvailable)
        assertTrue(daemonsu.anyAvailable)
        // 第二標記單獨出現時不污染他道判定。
        val magiskOnly = probe("/data/adb/magisk")
        assertTrue(magiskOnly.anyAvailable)
        assertTrue(magiskOnly.magisk)
        assertFalse(magiskOnly.kernelSu)
        assertFalse(magiskOnly.aPatch)
        assertTrue(magiskOnly.suBinaries.isEmpty())
    }

    @Test fun partialExistsThrowStillReportsRest() {
        val report = RootSuProbe.probe(exists = {
            if (it == "/system/bin/su") throw SecurityException("denied")
            it == "/sbin/.magisk"
        })
        // 抛的折缺席，其餘照常報告，整體仍 available。
        assertTrue(report.suBinaries.isEmpty())
        assertFalse(report.suExecutableAvailable)
        assertTrue(report.magisk)
        assertTrue(report.anyAvailable)
    }

    @Test fun markerOnlyIsAvailableButNotExecutable() {
        val report = probe("/sbin/.magisk")
        assertTrue(report.anyAvailable)
        assertFalse(report.suExecutableAvailable)
    }
}
