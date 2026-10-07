package dev.librepocket.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 提權探測測試（BACKLOG D09）：三環境回落（無提權 / Shizuku / Root）
 * + 異常 fail-closed。探測函數全注入，不碰真實 binder/su。
 */
class PrivilegeProbeTest {

    @Test fun noPrivilegeNeitherAvailable() {
        val probe = PrivilegeProbe(shizukuProbe = { false }, suProbe = { false })
        val results = probe.probe()
        assertEquals(2, results.size)
        assertTrue(results.none { it.available })
        assertFalse(probe.anyAvailable())
    }

    @Test fun shizukuOnlyEnvironment() {
        val probe = PrivilegeProbe(shizukuProbe = { true }, suProbe = { false })
        val byPriv = probe.probe().associateBy { it.privilege }
        assertTrue(byPriv.getValue(Privilege.SHIZUKU).available)
        assertFalse(byPriv.getValue(Privilege.ROOT).available)
        assertTrue(probe.anyAvailable())
    }

    @Test fun rootOnlyEnvironment() {
        val probe = PrivilegeProbe(shizukuProbe = { false }, suProbe = { true })
        val byPriv = probe.probe().associateBy { it.privilege }
        assertFalse(byPriv.getValue(Privilege.SHIZUKU).available)
        assertTrue(byPriv.getValue(Privilege.ROOT).available)
        assertTrue(probe.anyAvailable())
    }

    @Test fun throwingProbesFailClosed() {
        val probe = PrivilegeProbe(
            shizukuProbe = { throw SecurityException("denied") },
            suProbe = { throw IllegalStateException("no su") },
        )
        assertFalse(probe.anyAvailable())
        assertTrue(probe.probe().none { it.available })
    }
}
