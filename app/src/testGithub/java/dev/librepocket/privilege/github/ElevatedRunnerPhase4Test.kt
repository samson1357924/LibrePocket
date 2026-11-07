package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellExecutables
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR140 Phase 4 pins (P2-1/P2-4 + F1/F2/F8/F9, issue #140).
 *
 * - P2-1/F9: entry snapshot failure (concurrent mutation during copy) is
 *   Denied(BLACKLISTED) with zero spawn on both runners; audit-level freeze
 *   (tool/dispatch sharing one frozen object with spawn argv) is covered in
 *   main source-set tests (`ShellElevatedTest`, `ElevatedDispatchTest`).
 * - F1: customResolver results pass the same real-entity gates
 *   (containment + writability + entry); production always passes null.
 * - P2-4: PNG gate requires signature + first-chunk IHDR (len 13, "IHDR",
 *   width/height > 0) + tail IEND + min length; the 20-byte splice is rejected.
 * - F2: Shizuku newProcess env is the fixed CLEAN_ENV array, never null.
 * - F8: su non-zero exit is a single Failed form regardless of stderr.
 *
 * All hermetic (temp fixtures + injected fakes), no device, no su, no binder.
 */
class ElevatedRunnerPhase4Test {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }

    @Before fun setUpBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected",
                )
            }
            Thread.sleep(50L)
        }
    }

    private fun pollActiveTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.activeCalls() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "active=${ElevatedCleanupTracker.activeCalls()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    private fun throwingArgv(): List<String> = object : AbstractList<String>() {
        override val size: Int get() = 1
        override fun get(index: Int): String = "id"
        override fun iterator(): Iterator<String> =
            throw java.util.ConcurrentModificationException("injected")
    }

    // ---- F9: snapshot failure is Denied(BLACKLISTED) with zero spawn ----

    @Test fun rootSnapshotFailureDeniedWithoutSpawn() {
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray())
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.runBinary(ElevatedRequest(throwingArgv(), "P4-F9"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        r as ElevatedBinaryResult.Denied
        assertEquals(ShellDeny.BLACKLISTED, r.reason)
        assertTrue("message=${r.message}", r.message.contains("snapshot failed"))
        assertEquals(0, spawns)
    }

    @Test fun shizukuSnapshotFailureDeniedWithoutSpawn() {
        var spawns = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray())
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.runBinary(ElevatedRequest(throwingArgv(), "P4-F9"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        r as ElevatedBinaryResult.Denied
        assertEquals(ShellDeny.BLACKLISTED, r.reason)
        assertTrue("message=${r.message}", r.message.contains("snapshot failed"))
        assertEquals(0, spawns)
    }

    // ---- F1: customResolver results pass the same real-entity gates ----

    private fun executable(dir: File, name: String): File {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\necho hi\n")
        assertTrue("setExecutable failed for $f", f.setExecutable(true))
        return f
    }

    @Test fun customResolverTrustedPositiveKeepsRealPath() {
        val trusted = Files.createTempDirectory("p4-custom-pos").toFile()
        try {
            val tool = executable(trusted, "tool")
            val outcome = ElevatedExecPolicy.resolveForSnapshot(
                "tool",
                listOf(trusted.absolutePath),
                listOf(trusted.canonicalPath),
                systemOwned,
                customResolver = { ShellExecutables.ResolvedExec(tool.absolutePath, null) },
            )
            assertTrue("$outcome", outcome is ShellExecutables.ResolveOutcome.Ok)
            assertEquals(tool.canonicalPath, (outcome as ShellExecutables.ResolveOutcome.Ok).exec.path)
        } finally {
            trusted.deleteRecursively()
        }
    }

    @Test fun customResolverWritableTargetDenied() {
        val trusted = Files.createTempDirectory("p4-custom-w").toFile()
        try {
            val tool = executable(trusted, "tool")
            val outcome = ElevatedExecPolicy.resolveForSnapshot(
                "tool",
                listOf(trusted.absolutePath),
                listOf(trusted.canonicalPath),
                { _ -> true },
                customResolver = { ShellExecutables.ResolvedExec(tool.absolutePath, null) },
            )
            assertTrue("$outcome", outcome is ShellExecutables.ResolveOutcome.Denied)
            val reason = (outcome as ShellExecutables.ResolveOutcome.Denied).reason
            assertTrue("reason=$reason", reason == ShellExecutables.ResolveDeny.REAL_WRITABLE)
        } finally {
            trusted.deleteRecursively()
        }
    }

    @Test fun customResolverEscapeOutsideRootsDenied() {
        val trusted = Files.createTempDirectory("p4-custom-roots").toFile()
        val outside = Files.createTempDirectory("p4-custom-out").toFile()
        try {
            val evil = executable(outside, "tool")
            val outcome = ElevatedExecPolicy.resolveForSnapshot(
                "tool",
                listOf(trusted.absolutePath),
                listOf(trusted.canonicalPath),
                systemOwned,
                customResolver = { ShellExecutables.ResolvedExec(evil.absolutePath, null) },
            )
            assertTrue("$outcome", outcome is ShellExecutables.ResolveOutcome.Denied)
            assertEquals(
                ShellExecutables.ResolveDeny.OUTSIDE_ROOTS,
                (outcome as ShellExecutables.ResolveOutcome.Denied).reason,
            )
        } finally {
            trusted.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun customResolverRelativePathDenied() {
        val outcome = ElevatedExecPolicy.resolveForSnapshot(
            "tool",
            listOf("/system/bin"),
            listOf("/system"),
            systemOwned,
            customResolver = { ShellExecutables.ResolvedExec("tool", null) },
        )
        assertTrue("$outcome", outcome is ShellExecutables.ResolveOutcome.Denied)
        assertEquals(
            ShellExecutables.ResolveDeny.UNTRUSTED_PATH,
            (outcome as ShellExecutables.ResolveOutcome.Denied).reason,
        )
    }

    // ---- P2-4: IHDR gate ----

    private fun u32Be(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun ihdrPng(width: Int, height: Int, fill: ByteArray = "x".toByteArray()): ByteArray {
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        return RootFramebufferCapture.PNG_SIGNATURE +
            byteArrayOf(0x00, 0x00, 0x00, 0x0D) +
            "IHDR".toByteArray() +
            u32Be(width) + u32Be(height) +
            byteArrayOf(0x08, 0x02, 0x00, 0x00, 0x00) +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            fill + iend
    }

    @Test fun pngGateRequiresIhdrAndRejectsSplice() {
        assertEquals(45, RootFramebufferCapture.PNG_MIN_BYTES)
        assertTrue(RootFramebufferCapture.isPlausiblePng(ihdrPng(1, 1)))
        assertTrue(RootFramebufferCapture.isPlausiblePng(ihdrPng(1920, 1080)))
        // 20-byte splice (signature + IEND glued together) is rejected.
        val splice = RootFramebufferCapture.PNG_SIGNATURE + byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        assertEquals(20, splice.size)
        assertFalse(RootFramebufferCapture.isPlausiblePng(splice))
        // Zero width / zero height rejected.
        assertFalse(RootFramebufferCapture.isPlausiblePng(ihdrPng(0, 1)))
        assertFalse(RootFramebufferCapture.isPlausiblePng(ihdrPng(1, 0)))
        // Wrong first-chunk length rejected.
        val badLen = ihdrPng(1, 1).copyOf()
        badLen[11] = 0x0C.toByte()
        assertFalse(RootFramebufferCapture.isPlausiblePng(badLen))
        // Non-IHDR first chunk rejected.
        val badType = ihdrPng(1, 1).copyOf()
        "IDAT".toByteArray().copyInto(badType, 12)
        assertFalse(RootFramebufferCapture.isPlausiblePng(badType))
        // Capture level: splice falls back (null), valid frame digests as SCREENCAP.
        assertNull(RootFramebufferCapture({ splice }, { null }).capture())
        val digest = RootFramebufferCapture({ ihdrPng(4, 4) }, { null }).capture()
        assertNotNull(digest)
        assertEquals(FrameSource.SCREENCAP, digest!!.source)
    }

    // ---- F2: clean env ----

    @Test fun shizukuCleanEnvIsNonNullAndMinimal() {
        val env = ShizukuShellRunner.buildCleanEnv()
        assertNotNull(env)
        val asMap = env.map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
        assertEquals(ShellExecutables.CLEAN_ENV, asMap)
        assertTrue(env.none { it.startsWith("LD_PRELOAD=") || it.startsWith("LD_LIBRARY_PATH=") })
        assertTrue(env.any { it.startsWith("PATH=/system/bin") })
    }

    // ---- F8: single Failed form ----

    @Test fun suFailureMessageIsSingleFormRegardlessOfStderr() {
        for (stderr in listOf(
            "su: permission denied".toByteArray(),
            "boom".toByteArray(),
        )) {
            val runner = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { FakeProcess(ByteArray(0), stderr, 1) },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P4-F8"), 2_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            val message = (b as ElevatedBinaryResult.Failed).message
            assertTrue("message=$message", message.startsWith("su failed exit=1: "))
            assertFalse("message=$message", message.contains("possible denial"))
        }
    }
}
