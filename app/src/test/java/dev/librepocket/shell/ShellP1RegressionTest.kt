package dev.librepocket.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellP1RegressionTest {
    @Test fun wcBooleanFlagDoesNotSkipBarePath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("wc", "-c", "leak"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    @Test fun grepConsumedPatternDoesNotSkipFollow() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        val outside = java.io.File.createTempFile("out","").apply { delete(); mkdirs() }
        try {
            val sub = java.io.File(scope, "subtree").apply { mkdirs() }
            val v = ShellPolicy.validate(listOf("grep", "-e", "-e", "-R", scope.absolutePath + "/subtree"), privateRoot = scope.absolutePath)
            assertTrue("expected denied (traversal), got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally {
            scope.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test fun dfOptionValueNotScopedPath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("df", "-x", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    @Test fun wcOldBehaviorRegression_absoluteOutsideStillDenied() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        val outside = java.io.File.createTempFile("out","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("wc", "-c", outside.absolutePath), privateRoot = scope.absolutePath)
            // outside path: vetted against scope => denied
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively(); outside.deleteRecursively() }
    }

    // Elevated channel parity
    @Test fun elevated_wcBooleanFlagDoesNotSkipBarePath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validateElevated(listOf("wc", "-c", "leak"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    @Test fun elevated_grepConsumedPatternDoesNotSkipFollow() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validateElevated(listOf("grep", "-e", "-e", "-R", scope.absolutePath + "/subtree"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    @Test fun elevated_dfOptionValueNotScopedPath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validateElevated(listOf("df", "-x", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    // Multicall effective form (toybox)
    @Test fun multicall_wcBooleanFlagDoesNotSkipBarePath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("toybox", "wc", "-c", "leak"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    @Test fun multicall_grepConsumedPatternDoesNotSkipFollow() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("toybox", "grep", "-e", "-e", "-R", scope.absolutePath + "/subtree"), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    @Test fun multicall_dfOptionValueNotScopedPath() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("toybox", "df", "-x", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    // grep -e -R /scope/known: -R is pattern value, preserve legal semantics (not denied as traversal)
    @Test fun grepPatternValueR_notTraversal() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val known = java.io.File(scope, "known.txt").apply { writeText("needle") }
            val v = ShellPolicy.validate(listOf("grep", "-e", "-R", known.absolutePath), privateRoot = scope.absolutePath)
            // -R consumed as pattern value; known path vetted -> Allowed (no denial).
            assertTrue("expected Allowed, got $v", v is Validation.Allowed)
        } finally { scope.deleteRecursively() }
    }

    // Long flag --regexp dash-leading pattern value consumption (P1)
    @Test fun grepRegexpLongFlag_dashLeadingPattern() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        val outside = java.io.File.createTempFile("out","").apply { delete(); mkdirs() }
        try {
            val link = java.io.File(scope, "leak").apply { delete() }
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            val v = ShellPolicy.validate(listOf("grep", "--regexp", "-e", link.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun grepRegexpLongFlag_dashLeadingTraversal() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val sub = java.io.File(scope, "subtree").apply { mkdirs() }
            val v = ShellPolicy.validate(listOf("grep", "--regexp", "-e", "-R", scope.absolutePath + "/subtree"), privateRoot = scope.absolutePath)
            assertTrue("expected denied (traversal), got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            val msg = (v as Validation.Denied).message
            assertTrue("should be traversal denial, got $v", msg.contains("traversal"))
        } finally { scope.deleteRecursively() }
    }

    // df short cluster (P2)
    @Test fun dfShortCluster_typeValue() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("df", "-hx", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    // df long prefix (P2)
    @Test fun dfLongPrefix_excludeType() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("df", "--exclude-typ", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    // Elevated channel parity for new vectors
    @Test fun elevated_grepRegexp_dashLeadingPattern() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        val outside = java.io.File.createTempFile("out","").apply { delete(); mkdirs() }
        try {
            val link = java.io.File(scope, "leak").apply { delete() }
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            val v = ShellPolicy.validateElevated(listOf("grep", "--regexp", "-e", link.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun elevated_dfShortCluster_typeValue() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validateElevated(listOf("df", "-hx", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }

    // Multicall effective form (toybox) for new vectors
    @Test fun multicall_grepRegexp_dashLeadingPattern() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        val outside = java.io.File.createTempFile("out","").apply { delete(); mkdirs() }
        try {
            val link = java.io.File(scope, "leak").apply { delete() }
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            val v = ShellPolicy.validate(listOf("toybox", "grep", "--regexp", "-e", link.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun multicall_dfShortCluster_typeValue() {
        val scope = java.io.File.createTempFile("scp","").apply { delete(); mkdirs() }
        try {
            val v = ShellPolicy.validate(listOf("toybox", "df", "-hx", scope.absolutePath), privateRoot = scope.absolutePath)
            assertTrue("expected denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally { scope.deleteRecursively() }
    }
}
